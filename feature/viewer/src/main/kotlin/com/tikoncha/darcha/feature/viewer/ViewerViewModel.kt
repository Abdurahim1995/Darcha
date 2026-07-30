package com.tikoncha.darcha.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tikoncha.darcha.feature.viewer.data.WorkbookLoad
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.mvi.FlingDecay
import com.tikoncha.darcha.feature.viewer.mvi.ParseEvent
import com.tikoncha.darcha.feature.viewer.mvi.RenderEvent
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.ViewerEvent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerReducer
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the viewer's state and orchestrates loading (TECH_SPEC §10).
 *
 * Flow is strictly unidirectional: the UI calls [dispatch] with a
 * [ViewerIntent], every state change goes through [ViewerReducer], and the UI
 * re-renders from [state]. The ViewModel itself holds no rendering logic — it
 * only decides which side effects an intent triggers.
 *
 * @param repository the parser behind an interface, so tests can substitute a fake.
 * @param scope coroutine scope for loads; defaults to `viewModelScope`. Tests
 *   pass their own so no main dispatcher is required.
 */
public class ViewerViewModel(
    private val repository: WorkbookRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Idle)

    /** The current UI state. */
    public val state: StateFlow<ViewerState> = _state.asStateFlow()

    /** The last source we tried to open, so [ViewerIntent.Retry] can re-run it. */
    private var lastSource: WorkbookSource? = null

    private var loadJob: Job? = null

    /** The glide currently running, if any. A new touch cancels it. */
    private var flingJob: Job? = null

    /** The single entry point for user intents. */
    public fun dispatch(intent: ViewerIntent) {
        when (intent) {
            is ViewerIntent.OpenFile -> {
                lastSource = intent.source
                apply(intent)
                startLoad(intent.source)
            }

            ViewerIntent.Retry -> {
                // Without a previous attempt there is nothing to retry, so the
                // error state stands rather than flipping to an endless Parsing.
                val source = lastSource ?: return
                apply(intent)
                startLoad(source)
            }

            is ViewerIntent.Fling -> startFling(intent.vx, intent.vy)

            is ViewerIntent.Scroll -> {
                // Any deliberate scroll — a finger going down mid-glide — wins.
                flingJob?.cancel()
                apply(intent)
            }

            else -> apply(intent)
        }
    }

    /**
     * The renderer reporting how far this sheet can scroll at the current
     * density. Separate from [dispatch] because it is not a user intent.
     */
    public fun onScrollBoundsChanged(bounds: ScrollBounds) {
        apply(RenderEvent.BoundsChanged(bounds))
    }

    /**
     * Run a fling to a stop, feeding it back as ordinary [ViewerIntent.Scroll]s.
     *
     * The decay lives here rather than in the UI so it survives recomposition,
     * can be cancelled centrally by the next touch, and is testable without
     * Compose. The reducer never learns that a fling happened — it only ever
     * sees scrolls, which keeps state a pure function of its events.
     *
     * Velocities are content px/s: the caller has already divided by zoom (§9.2).
     */
    private fun startFling(vx: Float, vy: Float) {
        flingJob?.cancel()
        if (!FlingDecay.isMoving(vx, vy)) return
        flingJob = workScope.launch {
            var velocityX = vx
            var velocityY = vy
            while (isActive && FlingDecay.isMoving(velocityX, velocityY)) {
                val before = state.value
                apply(ViewerIntent.Scroll(FlingDecay.step(velocityX), FlingDecay.step(velocityY)))
                // Hitting a bound leaves the viewport unmoved; stop rather than
                // spin out the remaining frames against the edge.
                if (state.value == before) return@launch
                velocityX = FlingDecay.decay(velocityX)
                velocityY = FlingDecay.decay(velocityY)
                delay(FlingDecay.FRAME_MILLIS)
            }
        }
    }

    private fun startLoad(source: WorkbookSource) {
        // A newer request wins: drop the in-flight one so its late callbacks
        // cannot overwrite the new parse.
        loadJob?.cancel()
        loadJob = workScope.launch {
            val result = repository.load(source) { progress ->
                apply(ParseEvent.Progress(progress))
            }
            when (result) {
                is WorkbookLoad.Success -> apply(ParseEvent.Loaded(result.meta, result.sheet))
                is WorkbookLoad.Failure -> apply(ParseEvent.Failed(result.kind))
            }
        }
    }

    /** Feed [event] through the reducer and publish the result. */
    private fun apply(event: ViewerEvent) {
        _state.value = ViewerReducer.reduce(_state.value, event)
    }

    override fun onCleared() {
        loadJob?.cancel()
        flingJob?.cancel()
        // The open document dies with the ViewModel: its temp copy is only useful
        // while this screen can still ask for another sheet. The repository is
        // process-scoped and stays usable — a later screen loads into it again.
        repository.closeDocument()
        super.onCleared()
    }
}

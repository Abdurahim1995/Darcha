package com.tikoncha.darcha.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tikoncha.darcha.feature.viewer.data.WorkbookLoad
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.mvi.ParseEvent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerEvent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerReducer
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

            else -> apply(intent)
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
                is WorkbookLoad.Success -> apply(ParseEvent.Loaded(result.meta))
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
        // The open document dies with the ViewModel: its temp copy is only useful
        // while this screen can still ask for another sheet.
        repository.close()
        super.onCleared()
    }
}

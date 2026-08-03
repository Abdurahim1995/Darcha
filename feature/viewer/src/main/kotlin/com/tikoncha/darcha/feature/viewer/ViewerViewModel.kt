package com.tikoncha.darcha.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tikoncha.darcha.feature.viewer.data.RecentDocument
import com.tikoncha.darcha.feature.viewer.data.RecentsRepository
import com.tikoncha.darcha.feature.viewer.data.WorkbookLoad
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.feature.viewer.mvi.FlingDecay
import com.tikoncha.darcha.feature.viewer.mvi.ParseEvent
import com.tikoncha.darcha.feature.viewer.mvi.RenderEvent
import com.tikoncha.darcha.feature.viewer.mvi.SearchEvent
import com.tikoncha.darcha.feature.viewer.search.SheetSearch
import com.tikoncha.darcha.model.DateNames
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.ViewerEvent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import com.tikoncha.darcha.feature.viewer.mvi.ZoomAnimation
import com.tikoncha.darcha.feature.viewer.mvi.ViewerReducer
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
 * @param onDiagnostic receives timing notes for the log. Injected rather than
 *   calling `android.util.Log` directly, which is a throwing stub in unit tests
 *   and would drag this class onto an instrumented runner.
 * @param recents the persisted recent-documents list (T22).
 * @param isRecentAvailable answers whether a stored document can still be opened
 *   right now. Injected because the answer is a platform question — is the grant
 *   still held, is the file still there — that this class must not know how to
 *   ask. Defaults to optimistic, which is what tests want.
 */
public class ViewerViewModel(
    private val repository: WorkbookRepository,
    scope: CoroutineScope? = null,
    private val onDiagnostic: (String) -> Unit = {},
    private val recents: RecentsRepository = RecentsRepository.NONE,
    private val isRecentAvailable: suspend (String) -> Boolean = { true },
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Idle)

    /** The current UI state. */
    public val state: StateFlow<ViewerState> = _state.asStateFlow()

    /**
     * Re-checked whenever the home screen comes back into view. Availability is
     * not a property of the stored record — a grant can be revoked and a file
     * deleted long after it was written — so it is recomputed rather than saved.
     */
    private val availabilityTick = MutableStateFlow(0)

    /** The recent documents, newest first, each marked available or not. */
    public val recentDocuments: StateFlow<List<RecentDocument>> =
        combine(recents.recents, availabilityTick) { documents, _ -> documents }
            .map { documents -> documents.map { it.copy(available = isRecentAvailable(it.id)) } }
            .stateIn(workScope, SharingStarted.Eagerly, emptyList())

    /** The last source we tried to open, so [ViewerIntent.Retry] can re-run it. */
    private var lastSource: WorkbookSource? = null

    private var loadJob: Job? = null

    /**
     * Which load is current. Partial results now paint into `Ready`, so the T10
     * rule — "parse events only apply while Parsing" — no longer rejects a stale
     * one on its own. Every callback carries the generation it was started with
     * and is dropped if a newer load has begun; cancellation is cooperative, so
     * a chunk can still be in flight when the next document opens.
     */
    private var loadGeneration: Int = 0

    /** The glide currently running, if any. A new touch cancels it. */
    private var flingJob: Job? = null
    private var zoomJob: Job? = null

    /** The on-demand sheet read in flight, if any. */
    private var sheetJob: Job? = null

    /** The scan in flight, if any. A new query or a new snapshot cancels it. */
    private var searchJob: Job? = null

    /** Month and weekday names for the current locale, injected by `:app` (T24). */
    private var searchNames: DateNames = DateNames.ENGLISH

    /**
     * Recently viewed sheets, newest last (`accessOrder = true`).
     *
     * Switching back to a tab should be instant, but a workbook can hold dozens
     * of large sheets, so only [MAX_CACHED_SHEETS] are kept — the parse is cheap
     * to repeat, the memory is not (TECH_SPEC §13).
     */
    private val sheetCache =
        object : LinkedHashMap<Int, Pair<DocumentMeta, SheetSnapshot>>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, Pair<DocumentMeta, SheetSnapshot>>,
            ): Boolean = size > MAX_CACHED_SHEETS
        }

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

            is ViewerIntent.SwitchSheet -> switchSheet(intent.id)

            is ViewerIntent.SetSearchOpen -> {
                if (!intent.open) searchJob?.cancel()
                apply(intent)
            }

            is ViewerIntent.SetSearchQuery -> {
                apply(intent)
                startSearch(intent.query)
            }

            is ViewerIntent.Fling -> startFling(intent.vx, intent.vy)

            is ViewerIntent.ResetZoom -> startZoomReset(intent.focalX, intent.focalY)

            is ViewerIntent.Scroll -> {
                // Any deliberate scroll — a finger going down mid-glide — wins.
                flingJob?.cancel()
                apply(intent)
            }

            is ViewerIntent.Zoom -> {
                // A pinch beats a glide and beats a running double-tap animation.
                flingJob?.cancel()
                zoomJob?.cancel()
                apply(intent)
            }

            else -> apply(intent)
        }
    }

    /**
     * Stop any running glide or zoom animation. Returns `true` if something was
     * actually stopped (T29).
     *
     * **Why the answer matters, not just the action.** A finger going down during
     * a fling means *stop*, not *select* — that is what every scrollable surface
     * on the platform does, and it is the case that feels broken when it is
     * missing: you stab at a moving sheet to halt it and something lights up
     * under your finger instead. The caller presses first, reads this, and only
     * treats the lift as a selection when nothing was in motion.
     *
     * A running double-tap zoom counts for the same reason: the touch was aimed
     * at the animation, not at a cell.
     */
    public fun stopMotion(): Boolean {
        val stopped = flingJob?.isActive == true || zoomJob?.isActive == true
        flingJob?.cancel()
        zoomJob?.cancel()
        return stopped
    }

    /**
     * Supply the month and weekday names searches format dates with (T24/T32).
     *
     * Locale lives in the UI layer, not in the model, so `:app` hands it down —
     * the same injection point the renderer uses. A search started before this is
     * set falls back to English rather than failing.
     */
    public fun setDateNames(names: DateNames) {
        if (names == searchNames) return
        searchNames = names
        // A query already on screen was matched with the old names; re-run so a
        // date search agrees with what the grid is showing.
        (state.value as? ViewerState.Ready)?.search?.query?.takeIf { it.isNotEmpty() }
            ?.let(::startSearch)
    }

    /**
     * Scan the active sheet, off the main thread and cancellably (T32/T33).
     *
     * Cancellation is the point. Someone typing `January` issues seven searches
     * and six are dead before they finish, so each new query cancels the last and
     * the engine polls `isActive` every 1,024 cells. A cancelled scan returns
     * `null` and dispatches nothing — a superseded result must never reach the
     * state, or the count would flicker through every prefix of the query.
     */
    private fun startSearch(query: String) {
        searchJob?.cancel()
        if (query.isEmpty()) return
        val ready = state.value as? ViewerState.Ready ?: return
        val sheet = ready.sheet
        val complete = ready.loadProgress == null
        val names = searchNames

        searchJob = workScope.launch {
            apply(SearchEvent.Started(query))
            val results = withContext(Dispatchers.Default) {
                SheetSearch.run(sheet, query, names, complete) { isActive }
            } ?: return@launch
            apply(SearchEvent.Completed(results))
        }
    }

    /**
     * Re-run the current search against a sheet that has just changed.
     *
     * Called only from the parse callbacks, which is the only place a new
     * snapshot can appear. Rows arrive in chunks, so the sheet grows under an
     * open search; the reducer has already dropped the old matches as stale by
     * the time this runs, and this puts a fresh count in their place.
     *
     * **Re-run quietly rather than refuse.** The alternative — no searching until
     * the parse completes — means 2.4 seconds of a dead search box on `big-50k`.
     * A count that moves while the sheet is still loading is honest: the progress
     * bar is up, and `SearchState.countIsFinal` is false so the UI says the number
     * is provisional rather than presenting it as the answer.
     */
    private fun rescanForNewSheet() {
        val query = (state.value as? ViewerState.Ready)?.search?.query ?: return
        if (query.isNotEmpty()) startSearch(query)
    }

    /** Re-check which recents can still be opened — call when the list appears. */
    public fun refreshRecents() {
        availabilityTick.value++
    }

    /** Remove a recent the user no longer wants, or can no longer open. */
    public fun forgetRecent(id: String) {
        workScope.launch { recents.forget(id) }
    }

    /**
     * Add [source] to the recents list, but only if it can actually be reopened.
     *
     * A source with no [WorkbookSource.recentId] came in through a route whose
     * permission dies with the task, so remembering it would put a row in the
     * list that fails on its first tap. Viewing it is fine; promising to have it
     * later is not (see [RecentDocument]).
     */
    private fun rememberIfReopenable(source: WorkbookSource, displayName: String) {
        val id = source.recentId ?: return
        workScope.launch {
            recents.remember(
                RecentDocument(
                    id = id,
                    displayName = displayName,
                    lastOpened = System.currentTimeMillis(),
                    sizeBytes = source.declaredSizeBytes,
                ),
            )
        }
    }

    /**
     * Show the sheet at [index], reading it on demand.
     *
     * The read goes through the repository's open **document session**
     * (TECH_SPEC §9.1) — the temp copy is alive precisely so later sheets can be
     * parsed without re-importing the file.
     */
    private fun switchSheet(index: Int) {
        val ready = state.value as? ViewerState.Ready ?: return
        if (index !in ready.docMeta.sheetNames.indices || index == ready.activeSheetId) return

        apply(ViewerIntent.SwitchSheet(index)) // marks the tab pending

        sheetCache[index]?.let { (meta, sheet) ->
            apply(ParseEvent.SheetLoaded(index, meta, sheet))
            rescanForNewSheet()
            return
        }

        sheetJob?.cancel()
        sheetJob = workScope.launch {
            when (val result = repository.readSheet(index)) {
                is WorkbookLoad.Success -> {
                    sheetCache[index] = result.meta to result.sheet
                    apply(ParseEvent.SheetLoaded(index, result.meta, result.sheet))
                    rescanForNewSheet()
                }
                is WorkbookLoad.Failure -> apply(ParseEvent.SheetFailed(result.kind))
            }
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

    /**
     * Animate the zoom back to 1 about ([focalX], [focalY]) — the double tap.
     *
     * Built like [startFling]: the animation lives here so it survives
     * recomposition and can be cancelled by the next touch, and it is fed back
     * as ordinary [ViewerIntent.Zoom]s so the reducer never learns that an
     * animation happened.
     *
     * Each step is a *ratio*, because zoom composes multiplicatively — stepping
     * by a constant difference would crawl at one end of the range and jump at
     * the other.
     */
    private fun startZoomReset(focalX: Float, focalY: Float) {
        zoomJob?.cancel()
        val start = (state.value as? ViewerState.Ready)?.viewport?.zoom ?: return
        if (kotlin.math.abs(start - ZoomAnimation.TARGET) < ZoomAnimation.EPSILON) return

        zoomJob = workScope.launch {
            for (frame in 1..ZoomAnimation.FRAMES) {
                if (!isActive) return@launch
                val progress = frame.toFloat() / ZoomAnimation.FRAMES
                val eased = ZoomAnimation.ease(progress)
                val want = ZoomAnimation.zoomAt(start, eased)
                val now = (state.value as? ViewerState.Ready)?.viewport?.zoom ?: return@launch
                if (now <= 0f) return@launch
                apply(ViewerIntent.Zoom(scale = want / now, focalX = focalX, focalY = focalY))
                delay(FlingDecay.FRAME_MILLIS)
            }
        }
    }

    private fun startLoad(source: WorkbookSource) {
        // A newer request wins: drop the in-flight one so its late callbacks
        // cannot overwrite the new parse.
        loadJob?.cancel()
        sheetJob?.cancel()
        sheetCache.clear() // a different document; nothing carries over
        val generation = ++loadGeneration
        loadJob = workScope.launch {
            val startedAt = System.currentTimeMillis()
            var firstPaintAt = 0L
            val result = repository.load(source) { partial ->
                if (generation != loadGeneration) return@load
                if (firstPaintAt == 0L) {
                    firstPaintAt = System.currentTimeMillis()
                    onDiagnostic(
                        "first cells of '${source.displayName}' in " +
                            "${firstPaintAt - startedAt} ms",
                    )
                }
                apply(
                    ParseEvent.PartialLoaded(
                        meta = partial.meta,
                        sheet = partial.sheet,
                        progress = partial.progress,
                    ),
                )
                // Each chunk supersedes the scan the last one started, so during
                // a long parse the bar honestly reads "searching" and the answer
                // lands once the sheet stops moving. Cheap: a cancelled scan
                // stops within 1,024 cells.
                rescanForNewSheet()
            }
            if (generation != loadGeneration) return@launch
            when (result) {
                is WorkbookLoad.Success -> {
                    sheetCache[0] = result.meta to result.sheet
                    rememberIfReopenable(source, result.meta.displayName)
                    // Time-to-first-cell, the §5 product metric; recorded in
                    // docs/PERF.md.
                    onDiagnostic(
                        "loaded '${result.meta.displayName}' " +
                            "(${result.meta.rowCount} rows) in " +
                            "${System.currentTimeMillis() - startedAt} ms",
                    )
                    apply(ParseEvent.Loaded(result.meta, result.sheet))
                    rescanForNewSheet()
                }
                is WorkbookLoad.Failure -> {
                    // Failures are worth timing too: a cap that trips only after
                    // a long parse is a slow error screen, and that is a real
                    // user-facing cost (T23).
                    onDiagnostic(
                        "failed '${source.displayName}' after " +
                            "${System.currentTimeMillis() - startedAt} ms: ${result.kind}",
                    )
                    apply(ParseEvent.Failed(result.kind))
                }
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
        sheetJob?.cancel()
        sheetCache.clear()
        // The open document dies with the ViewModel: its temp copy is only useful
        // while this screen can still ask for another sheet. The repository is
        // process-scoped and stays usable — a later screen loads into it again.
        repository.closeDocument()
        super.onCleared()
    }

    private companion object {
        /** Sheets kept parsed for instant tab switching. */
        const val MAX_CACHED_SHEETS = 3
    }
}

package com.tikoncha.darcha.feature.viewer.mvi

/**
 * The single place viewer state changes (TECH_SPEC §10): a pure function from
 * `(state, event)` to the next state.
 *
 * Being pure — no coroutines, no I/O, no Android — it is exhaustively unit
 * testable, and the ViewModel is left with only orchestration. Any event that
 * does not apply to the current state returns that state unchanged, so out-of-
 * order events (a late parse callback after the user opened another file, say)
 * can never corrupt the UI.
 */
public object ViewerReducer {

    /** Reduce [state] by [event], returning the next state. */
    public fun reduce(state: ViewerState, event: ViewerEvent): ViewerState = when (event) {
        is ViewerIntent -> reduceIntent(state, event)
        is ParseEvent -> reduceParse(state, event)
        is RenderEvent -> reduceRender(state, event)
    }

    private fun reduceIntent(state: ViewerState, intent: ViewerIntent): ViewerState =
        when (intent) {
            // Opening a document is valid from any state, including over an
            // already-open one.
            is ViewerIntent.OpenFile -> ViewerState.Parsing(progress = 0f)

            // Only meaningful after a failure; the ViewModel re-runs the load.
            ViewerIntent.Retry ->
                if (state is ViewerState.Error) ViewerState.Parsing(progress = 0f) else state

            is ViewerIntent.SwitchSheet -> state.mapReady { ready ->
                // Ignore an out-of-range index rather than showing a blank grid.
                if (intent.id !in ready.docMeta.sheetNames.indices) {
                    ready
                } else {
                    // A different sheet has its own geometry, so the viewport and
                    // selection start over.
                    ready.copy(
                        activeSheetId = intent.id,
                        viewport = Viewport.INITIAL,
                        selection = null,
                    )
                }
            }

            is ViewerIntent.Scroll -> state.mapReady { ready ->
                ready.copy(
                    viewport = ready.viewport.scrolledBy(intent.dx, intent.dy, ready.scrollBounds),
                )
            }

            is ViewerIntent.Zoom -> state.mapReady { ready ->
                // Focal-point compensation needs the geometry engine, so T20
                // revisits this; for now zoom is applied and clamped.
                ready.copy(viewport = ready.viewport.zoomedBy(intent.scale))
            }

            // A fling is a *request* to keep moving: the ViewModel owns the decay
            // and feeds it back as ordinary Scroll intents, so the reducer sees
            // nothing new and the state stays a pure function of the events.
            is ViewerIntent.Fling -> state

            // Cell hit-testing needs the renderer's geometry; T20 wires it up.
            is ViewerIntent.TapCell -> state
        }

    private fun reduceParse(state: ViewerState, event: ParseEvent): ViewerState =
        when (event) {
            // Progress only advances an in-flight parse — never resurrects a
            // finished or superseded one.
            is ParseEvent.Progress ->
                if (state is ViewerState.Parsing) {
                    ViewerState.Parsing(event.progress.coerceIn(0f, 1f))
                } else {
                    state
                }

            is ParseEvent.Loaded ->
                if (state is ViewerState.Parsing) {
                    ViewerState.Ready(
                        docMeta = event.meta,
                        sheet = event.sheet,
                        activeSheetId = 0,
                        viewport = Viewport.INITIAL,
                        selection = null,
                    )
                } else {
                    state
                }

            is ParseEvent.Failed ->
                if (state is ViewerState.Parsing) ViewerState.Error(event.kind) else state
        }

    private fun reduceRender(state: ViewerState, event: RenderEvent): ViewerState =
        when (event) {
            is RenderEvent.BoundsChanged -> state.mapReady { ready ->
                // Re-clamp at once: a smaller sheet can leave the viewport past
                // the new limit, which would otherwise show blank space until the
                // next scroll.
                ready.copy(
                    scrollBounds = event.bounds,
                    viewport = ready.viewport.scrolledBy(0f, 0f, event.bounds),
                )
            }
        }

    /** Apply [block] only when the viewer is [ViewerState.Ready]. */
    private inline fun ViewerState.mapReady(
        block: (ViewerState.Ready) -> ViewerState,
    ): ViewerState = if (this is ViewerState.Ready) block(this) else this
}

/**
 * Scroll by ([dx], [dy]) content pixels, clamped to `0..bounds` on each axis.
 *
 * Deltas arrive already converted from screen to content pixels — the caller
 * divides by zoom, per TECH_SPEC §9.2.
 */
internal fun Viewport.scrolledBy(dx: Float, dy: Float, bounds: ScrollBounds): Viewport = copy(
    scrollX = (scrollX + dx).coerceIn(0f, bounds.maxScrollX.coerceAtLeast(0f)),
    scrollY = (scrollY + dy).coerceIn(0f, bounds.maxScrollY.coerceAtLeast(0f)),
)

/** Multiply the zoom by [scale], clamped to the allowed range. */
internal fun Viewport.zoomedBy(scale: Float): Viewport = copy(
    zoom = (zoom * scale).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM),
)

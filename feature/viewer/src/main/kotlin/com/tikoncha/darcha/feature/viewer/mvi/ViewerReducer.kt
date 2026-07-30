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
                // Only mark the tab as pending here. The sheet is read on demand
                // (T15), and the grid keeps showing the current one until the
                // ParseEvent.SheetLoaded arrives — no blank frame in between.
                val valid = intent.id in ready.docMeta.sheetNames.indices
                if (!valid || intent.id == ready.activeSheetId) ready
                else ready.copy(loadingSheetId = intent.id)
            }

            is ViewerIntent.Scroll -> state.mapReady { ready ->
                ready.copy(
                    viewport = ready.viewport.scrolledBy(intent.dx, intent.dy, ready.scrollBounds),
                )
            }

            is ViewerIntent.Zoom -> state.mapReady { ready ->
                // The focal point keeps the cell under the fingers where it was
                // (TECH_SPEC §9.2); the same bounds that clamp a drag clamp the
                // compensation, so zooming cannot walk off the sheet.
                ready.copy(
                    viewport = ready.viewport.zoomedAt(
                        scale = intent.scale,
                        focusX = intent.focalX,
                        focusY = intent.focalY,
                        bounds = ready.scrollBounds,
                    ),
                )
            }

            // A request, like Fling: the ViewModel animates it back to 1 and
            // feeds the steps in as Zoom intents.
            is ViewerIntent.ResetZoom -> state

            // A fling is a *request* to keep moving: the ViewModel owns the decay
            // and feeds it back as ordinary Scroll intents, so the reducer sees
            // nothing new and the state stays a pure function of the events.
            is ViewerIntent.Fling -> state

            // Deliberately inert: selection is post-v1 (TECH_SPEC §14). See
            // ViewerIntent.TapCell.
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

            // First rows of a still-streaming sheet. From Parsing this is the
            // moment the grid appears; from Ready it just grows the sheet, so the
            // viewport must survive — the user may already be scrolling.
            is ParseEvent.PartialLoaded -> when (state) {
                is ViewerState.Parsing -> ViewerState.Ready(
                    docMeta = event.meta,
                    sheet = event.sheet,
                    activeSheetId = 0,
                    viewport = Viewport.INITIAL,
                    selection = null,
                    loadProgress = event.progress.coerceIn(0f, 1f),
                )
                is ViewerState.Ready -> state.copy(
                    docMeta = event.meta,
                    sheet = event.sheet,
                    loadProgress = event.progress.coerceIn(0f, 1f),
                )
                // Never resurrect a document the user has moved on from.
                else -> state
            }

            is ParseEvent.Loaded -> when (state) {
                is ViewerState.Parsing -> ViewerState.Ready(
                    docMeta = event.meta,
                    sheet = event.sheet,
                    activeSheetId = 0,
                    viewport = Viewport.INITIAL,
                    selection = null,
                )
                // The grid is already up from partials: swap in the complete
                // sheet and its real layout, keeping where the user is looking.
                is ViewerState.Ready -> state.copy(
                    docMeta = event.meta,
                    sheet = event.sheet,
                    loadProgress = null,
                )
                else -> state
            }

            // A failure can arrive after partials have already put the grid up.
            is ParseEvent.Failed -> when (state) {
                is ViewerState.Parsing -> ViewerState.Error(event.kind)
                is ViewerState.Ready ->
                    if (state.loadProgress != null) ViewerState.Error(event.kind) else state
                else -> state
            }

            is ParseEvent.SheetLoaded -> state.mapReady { ready ->
                // A different sheet has its own geometry, so the viewport, the
                // selection and the scroll limits all start over.
                ready.copy(
                    docMeta = event.meta,
                    sheet = event.sheet,
                    activeSheetId = event.index,
                    viewport = Viewport.INITIAL,
                    selection = null,
                    scrollBounds = ScrollBounds.UNKNOWN,
                    loadingSheetId = null,
                )
            }

            // The sheet we were reading failed; keep the one already on screen.
            is ParseEvent.SheetFailed -> state.mapReady { it.copy(loadingSheetId = null) }
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
 * Scroll by ([dx], [dy]) content pixels, clamped to [bounds] on each axis.
 *
 * The floor is zero on an ordinary sheet and the frozen extent on a frozen one,
 * so the scrolling region can never slide back over its own frozen strips (T19).
 *
 * Deltas arrive already converted from screen to content pixels — the caller
 * divides by zoom, per TECH_SPEC §9.2.
 */
internal fun Viewport.scrolledBy(dx: Float, dy: Float, bounds: ScrollBounds): Viewport = copy(
    scrollX = clampScroll(scrollX + dx, bounds.minScrollX, bounds.maxScrollX),
    scrollY = clampScroll(scrollY + dy, bounds.minScrollY, bounds.maxScrollY),
)

/**
 * Clamp one axis to `min..max`, tolerating a max below the min — which happens
 * on a sheet whose used range is entirely inside the frozen strips. The floor
 * wins there, because drawing the frozen columns twice is worse than not being
 * able to scroll a sheet that has nothing to scroll to.
 */
private fun clampScroll(value: Float, min: Float, max: Float): Float {
    val floor = min.coerceAtLeast(0f)
    return value.coerceAtLeast(floor).coerceAtMost(max.coerceAtLeast(floor))
}

/**
 * Zoom by [scale] about the point ([focusX], [focusY]), so the content under
 * that point stays under it (TECH_SPEC §9.2).
 *
 * The focus is in **screen pixels relative to the grid's content origin** — the
 * caller subtracts the header strips and, on a frozen sheet, the frozen extent,
 * because those are the coordinates the scroll actually addresses.
 *
 * The content point under the focus is `scroll + focus / zoom`. Holding it fixed
 * across a zoom change gives
 *
 * ```
 * scroll' = scroll + focus × (1 / zoom − 1 / zoom')
 * ```
 *
 * which is the whole of pinch-to-zoom. Note it is driven by the *clamped* new
 * zoom: at the 0.5 and 3.0 stops the scale is refused, and the compensation has
 * to be refused with it or the sheet would drift while the zoom stood still.
 */
internal fun Viewport.zoomedAt(
    scale: Float,
    focusX: Float,
    focusY: Float,
    bounds: ScrollBounds = ScrollBounds.UNKNOWN,
): Viewport {
    val target = (zoom * scale).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)
    if (target == zoom) return this
    val shiftX = focusX * (1f / zoom - 1f / target)
    val shiftY = focusY * (1f / zoom - 1f / target)
    return copy(zoom = target).scrolledBy(shiftX, shiftY, bounds)
}

/** Multiply the zoom by [scale], clamped to the allowed range. */
internal fun Viewport.zoomedBy(scale: Float): Viewport = copy(
    zoom = (zoom * scale).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM),
)

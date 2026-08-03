package com.tikoncha.darcha.feature.viewer.mvi

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.search.SearchResults
import com.tikoncha.darcha.model.ErrorKind

/**
 * Anything the reducer consumes. Two families implement it: [ViewerIntent] for
 * user-originated events, and [ParseEvent] for results coming back from the
 * parser. Keeping both under one type means there is a single `reduce` entry
 * point and one place where state changes (TECH_SPEC §10).
 */
public sealed interface ViewerEvent

/**
 * A user-originated event: a gesture, a menu action, a file being opened
 * (TECH_SPEC §10). The UI never mutates state directly — it dispatches these.
 */
public sealed interface ViewerIntent : ViewerEvent {

    /**
     * Open a document.
     *
     * Carries a [WorkbookSource] rather than an `android.net.Uri`: `Uri` is a
     * stub in plain JVM unit tests, and depending on it here would force the
     * whole MVI layer onto an instrumented test runner. The `:app` layer maps
     * its `Uri` to a [WorkbookSource].
     */
    public data class OpenFile(public val source: WorkbookSource) : ViewerIntent

    /** Show the sheet at index [id]; resets the viewport and selection. */
    public data class SwitchSheet(public val id: Int) : ViewerIntent

    /** Scroll the grid by ([dx], [dy]) pixels. */
    public data class Scroll(public val dx: Float, public val dy: Float) : ViewerIntent

    /** A fling gesture with velocity ([vx], [vy]) in pixels/second. */
    public data class Fling(public val vx: Float, public val vy: Float) : ViewerIntent

    /** Pinch zoom by [scale] around the focal point ([focalX], [focalY]). */
    public data class Zoom(
        public val scale: Float,
        public val focalX: Float,
        public val focalY: Float,
    ) : ViewerIntent

    /**
     * Double tap at the focal point ([focalX], [focalY]): return to zoom 1.
     *
     * Like [Fling], this is a *request*: the ViewModel owns the animation and
     * feeds it back as ordinary [Zoom] intents, so the reducer stays a pure
     * function of the events it already knows.
     */
    public data class ResetZoom(
        public val focalX: Float,
        public val focalY: Float,
    ) : ViewerIntent

    /**
     * Select [cell], or clear the selection when it is `null` (T29).
     *
     * **Why this carries a cell and not the tap's pixel.** Screen coordinates
     * mean nothing without the geometry that produced them — column widths, the
     * zoom, and which of the four frozen regions the point landed in, each with
     * its own origin. All of that lives in the renderer, so the renderer resolves
     * the tap and this intent carries the answer. The alternative would be a
     * reducer that reads geometry, and the reducer is a pure function with no
     * layout, no I/O and no Android in it (TECH_SPEC §10).
     *
     * The same split already exists for [Fling], which the ViewModel turns into
     * [Scroll]s. Resolution happens wherever the knowledge is; the reducer only
     * ever stores facts.
     *
     * Merged cells resolve to their **anchor** before reaching here, so the
     * selection is the cell that owns the value rather than one of the covered
     * cells that has none (`MergeIndex.anchorOf`, T18).
     */
    public data class SelectCell(public val cell: CellRef?) : ViewerIntent

    /** Open the search bar, or close it and drop its results (T33). */
    public data class SetSearchOpen(public val open: Boolean) : ViewerIntent

    /**
     * The query changed.
     *
     * A *request*, like [Fling]: the ViewModel owns the scan, cancels the one
     * this supersedes, and feeds the answer back as [SearchEvent.Completed]. The
     * reducer never scans — it is a pure function with no I/O and no threads.
     */
    public data class SetSearchQuery(public val query: String) : ViewerIntent

    /**
     * Step to the next match, or the previous one, wrapping at the ends.
     *
     * Wrapping is deliberate: a list that stops at the last match makes the
     * reader wonder whether it is broken or finished. The UI says which happened.
     */
    public data class StepMatch(public val forward: Boolean) : ViewerIntent

    /**
     * Move the viewport to one the renderer computed (T33).
     *
     * The same split as [SelectCell], for the same reason: bringing a cell into
     * view needs column widths, the zoom and the frozen-region origins, all of
     * which live in the renderer. It resolves the cell with T31 and sends the
     * answer; the reducer clamps and stores it.
     */
    public data class RevealViewport(public val viewport: Viewport) : ViewerIntent

    /** Retry the last failed load. */
    public data object Retry : ViewerIntent
}

/**
 * A parser-originated event, produced while loading a document. These are
 * internal to the viewer — the UI dispatches [ViewerIntent]s, and the ViewModel
 * turns parse results into these.
 */
public sealed interface ParseEvent : ViewerEvent {

    /** Progressive loading advanced to [progress] (`0f..1f`). */
    public data class Progress(public val progress: Float) : ParseEvent

    /**
     * Rows parsed so far, ready to draw while the rest still streams in (T15.5).
     * Arrives repeatedly during one load, each time with more rows.
     */
    public data class PartialLoaded(
        public val meta: DocumentMeta,
        public val sheet: SheetSnapshot,
        public val progress: Float,
    ) : ParseEvent

    /** The document loaded successfully, with its first sheet ready to draw. */
    public data class Loaded(
        public val meta: DocumentMeta,
        public val sheet: SheetSnapshot,
    ) : ParseEvent

    /** The document failed to load. */
    public data class Failed(public val kind: ErrorKind) : ParseEvent

    /** Another sheet of the open document finished reading (T15). */
    public data class SheetLoaded(
        public val index: Int,
        public val meta: DocumentMeta,
        public val sheet: SheetSnapshot,
    ) : ParseEvent

    /** Reading another sheet failed; the current one stays on screen. */
    public data class SheetFailed(public val kind: ErrorKind) : ParseEvent
}

/**
 * The outcome of a scan the ViewModel ran (T33).
 *
 * Search is a *request* the reducer cannot answer, exactly like a fling or a
 * parse: it needs a worker and a cancellation, and the reducer has neither. So
 * it comes back as an event, and the reducer only ever stores facts.
 */
public sealed interface SearchEvent : ViewerEvent {

    /** A scan started for [query]. */
    public data class Started(public val query: String) : SearchEvent

    /**
     * A scan finished. The results carry the identity of the sheet they scanned,
     * and the reducer checks it before storing them — a scan that finished after
     * the sheet moved on is dropped rather than shown.
     */
    public data class Completed(public val results: SearchResults) : SearchEvent
}

/**
 * An event from the renderer. The Canvas is the only place that knows the
 * display density and the sheet's measured extent, so it reports what the
 * reducer cannot work out on its own.
 */
public sealed interface RenderEvent : ViewerEvent {

    /** The scrollable extent changed — a new sheet, or a new density. */
    public data class BoundsChanged(public val bounds: ScrollBounds) : RenderEvent
}

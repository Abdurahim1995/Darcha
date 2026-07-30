package com.tikoncha.darcha.feature.viewer.mvi

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
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

    /** A tap at viewport pixel ([x], [y]), to be hit-tested to a cell. */
    public data class TapCell(public val x: Float, public val y: Float) : ViewerIntent

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

    /** The document loaded successfully, with its first sheet ready to draw. */
    public data class Loaded(
        public val meta: DocumentMeta,
        public val sheet: SheetSnapshot,
    ) : ParseEvent

    /** The document failed to load. */
    public data class Failed(public val kind: ErrorKind) : ParseEvent
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

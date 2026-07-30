package com.tikoncha.darcha.feature.viewer.mvi

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.model.ErrorKind

/**
 * The visible window onto the grid (TECH_SPEC §9, §10): scroll offsets in pixels
 * and a uniform zoom factor.
 *
 * @property scrollX horizontal scroll offset, in pixels; never negative.
 * @property scrollY vertical scroll offset, in pixels; never negative.
 * @property zoom uniform scale, clamped to [MIN_ZOOM]..[MAX_ZOOM].
 */
public data class Viewport(
    public val scrollX: Float = 0f,
    public val scrollY: Float = 0f,
    public val zoom: Float = 1f,
) {
    public companion object {
        /** Most zoomed-out level the UI allows. */
        public const val MIN_ZOOM: Float = 0.5f

        /** Most zoomed-in level the UI allows. */
        public const val MAX_ZOOM: Float = 3.0f

        /** The viewport a freshly opened sheet starts at. */
        public val INITIAL: Viewport = Viewport()
    }
}

/**
 * A selected cell, in 0-based grid coordinates.
 *
 * @property row 0-based row index.
 * @property col 0-based column index.
 */
public data class CellRef(
    public val row: Int,
    public val col: Int,
)

/**
 * What the UI needs to know about an open document without holding its cells.
 *
 * @property displayName the file name shown in the UI.
 * @property sheetNames worksheet names, in document order.
 * @property rowCount populated rows in the sheet that was read.
 */
public data class DocumentMeta(
    public val displayName: String,
    public val sheetNames: List<String>,
    public val rowCount: Int,
)

/**
 * The complete UI state of the viewer (TECH_SPEC §10). Exactly one of these is
 * live at a time; the Canvas renders whatever [Ready] holds.
 */
public sealed interface ViewerState {

    /** Nothing is open yet — the launch state, and where the home screen lives. */
    public data object Idle : ViewerState

    /**
     * A document is being parsed.
     *
     * @property progress fraction parsed so far, in `0f..1f`.
     */
    public data class Parsing(public val progress: Float) : ViewerState

    /**
     * A document is open and renderable.
     *
     * @property docMeta the open document's name and sheets.
     * @property sheet the active worksheet's cells and layout, ready to draw.
     * @property activeSheetId index into [DocumentMeta.sheetNames].
     * @property viewport current scroll/zoom.
     * @property selection the selected cell, or `null` if nothing is selected.
     * @property scrollBounds how far the viewport may scroll, published by the
     *   renderer once it knows the sheet's used range at this density (§9.2).
     */
    public data class Ready(
        public val docMeta: DocumentMeta,
        public val sheet: SheetSnapshot,
        public val activeSheetId: Int,
        public val viewport: Viewport,
        public val selection: CellRef?,
        public val scrollBounds: ScrollBounds = ScrollBounds.UNKNOWN,
    ) : ViewerState

    /**
     * Loading failed.
     *
     * @property kind why it failed, from the parser's taxonomy.
     */
    public data class Error(public val kind: ErrorKind) : ViewerState
}

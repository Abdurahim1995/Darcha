package com.tikoncha.darcha.feature.viewer.mvi

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.search.SearchResults
import com.tikoncha.darcha.model.CellRange
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
 * The search bar's state, or `null` when it is closed (T33).
 *
 * @property query what the user has typed. May be empty — the bar is open and
 *   waiting, which is a different state from "no matches".
 * @property results the matches, or `null` while a scan is in flight or the
 *   query is empty. **Never holds results for a different sheet:** the reducer
 *   drops them the moment a new snapshot arrives, which is what stops
 *   next/previous acting on an index into a sheet that has since grown.
 * @property currentIndex which match is current, or `-1` when there are none.
 * @property running whether a scan is in flight, so the UI can say "searching"
 *   rather than "no matches" while it waits.
 */
public data class SearchState(
    public val query: String = "",
    public val results: SearchResults? = null,
    public val currentIndex: Int = -1,
    public val running: Boolean = false,
) {
    /** The cell of the current match, or `null` if there is not one. */
    public val currentCell: CellRef?
        get() {
            val r = results ?: return null
            if (currentIndex !in 0 until r.size) return null
            return CellRef(r.rowAt(currentIndex), r.colAt(currentIndex))
        }

    /** Matches found so far. */
    public val matchCount: Int get() = results?.size ?: 0

    /**
     * These results, or a clean slate if they no longer describe [sheet].
     *
     * Called wherever a new snapshot lands. A progressive parse produces a new
     * `SheetData` per chunk, so results computed against the previous one are
     * stale — and an index into a stale list is exactly how next/previous ends up
     * scrolling to a cell that moved. Dropping them here means **the state can
     * never hold results for a sheet other than the one on screen**, so nothing
     * downstream has to remember to check.
     *
     * `running` is set so the UI says "searching" rather than "no matches" in the
     * gap before the re-run lands.
     */
    public fun invalidatedFor(sheet: SheetSnapshot): SearchState =
        if (results == null || results.isFor(sheet)) {
            this
        } else {
            copy(results = null, currentIndex = -1, running = query.isNotEmpty())
        }

    /**
     * Whether the count is final. `false` while a scan runs **or** while the
     * sheet is still parsing — in both cases more matches may appear, and the UI
     * must not present the number as complete.
     */
    public val countIsFinal: Boolean get() = !running && results?.complete == true
}

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
     * @property selection the selected cells, or `null` if nothing is selected
     *   (T29, extended to a rectangle in T34). A plain tap gives a 1x1 range, so
     *   single-cell selection is the same behaviour it always was — the type
     *   widened, not the semantics. A drag gives a rectangle already **expanded
     *   to whole merges** by the renderer, which is the only place that knows
     *   where merges are.
     * @property scrollBounds how far the viewport may scroll, published by the
     *   renderer once it knows the sheet's used range at this density (§9.2).
     * @property loadingSheetId the tab being read on demand, or `null` when
     *   nothing is pending. The grid keeps showing the current sheet meanwhile.
     * @property loadProgress fraction parsed while the sheet is still streaming
     *   in, or `null` once it is complete. The grid draws the rows it already
     *   has (TECH_SPEC §7); this only drives the progress bar.
     */
    public data class Ready(
        public val docMeta: DocumentMeta,
        public val sheet: SheetSnapshot,
        public val activeSheetId: Int,
        public val viewport: Viewport,
        public val selection: CellRange?,
        public val scrollBounds: ScrollBounds = ScrollBounds.UNKNOWN,
        public val loadingSheetId: Int? = null,
        public val loadProgress: Float? = null,
        public val search: SearchState? = null,
    ) : ViewerState

    /**
     * Loading failed.
     *
     * @property kind why it failed, from the parser's taxonomy.
     */
    public data class Error(public val kind: ErrorKind) : ViewerState
}

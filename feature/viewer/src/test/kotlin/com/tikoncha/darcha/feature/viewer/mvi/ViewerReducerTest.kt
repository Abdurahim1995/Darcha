package com.tikoncha.darcha.feature.viewer.mvi

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.search.SheetSearch
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transition tests for the pure reducer (TECH_SPEC §10). */
class ViewerReducerTest {

    private val meta = DocumentMeta(
        displayName = "report.xlsx",
        sheetNames = listOf("Jadval 1", "Narxlar", "Ҳисобот"),
        rowCount = 42,
    )

    private val source = object : WorkbookSource {
        override val displayName: String = "report.xlsx"
        override val declaredSizeBytes: Long? = null
        override fun openStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    }

    private fun reduce(state: ViewerState, event: ViewerEvent) = ViewerReducer.reduce(state, event)

    private fun ready(
        activeSheetId: Int = 0,
        viewport: Viewport = Viewport.INITIAL,
        selection: CellRange? = null,
        scrollBounds: ScrollBounds = ScrollBounds.UNKNOWN,
        loadingSheetId: Int? = null,
    ) = ViewerState.Ready(
        meta, SheetSnapshot.EMPTY, activeSheetId, viewport, selection, scrollBounds, loadingSheetId,
    )

    // --- the happy path: open -> parsing -> ready ---

    @Test
    fun openFile_fromIdle_startsParsingAtZero() {
        val next = reduce(ViewerState.Idle, ViewerIntent.OpenFile(source))
        assertEquals(ViewerState.Parsing(0f), next)
    }

    @Test
    fun progress_advancesParsing() {
        var state: ViewerState = reduce(ViewerState.Idle, ViewerIntent.OpenFile(source))
        state = reduce(state, ParseEvent.Progress(0.25f))
        assertEquals(ViewerState.Parsing(0.25f), state)
        state = reduce(state, ParseEvent.Progress(0.8f))
        assertEquals(ViewerState.Parsing(0.8f), state)
    }

    @Test
    fun progress_isClampedToUnitRange() {
        val parsing = ViewerState.Parsing(0f)
        assertEquals(ViewerState.Parsing(1f), reduce(parsing, ParseEvent.Progress(4f)))
        assertEquals(ViewerState.Parsing(0f), reduce(parsing, ParseEvent.Progress(-2f)))
    }

    @Test
    fun loaded_fromParsing_becomesReadyOnFirstSheet() {
        val next = reduce(ViewerState.Parsing(0.9f), ParseEvent.Loaded(meta, SheetSnapshot.EMPTY))
        assertEquals(ready(), next)
    }

    @Test
    fun openFile_whileReady_restartsParsing() {
        val next = reduce(ready(activeSheetId = 2), ViewerIntent.OpenFile(source))
        assertEquals(ViewerState.Parsing(0f), next)
    }

    // --- error paths ---

    @Test
    fun failed_fromParsing_becomesError() {
        val kind = ErrorKind.Encrypted("password protected")
        assertEquals(ViewerState.Error(kind), reduce(ViewerState.Parsing(0.4f), ParseEvent.Failed(kind)))
    }

    @Test
    fun retry_fromError_returnsToParsing() {
        val error = ViewerState.Error(ErrorKind.Corrupted("bad zip"))
        assertEquals(ViewerState.Parsing(0f), reduce(error, ViewerIntent.Retry))
    }

    @Test
    fun retry_outsideError_isIgnored() {
        assertSame(ViewerState.Idle, reduce(ViewerState.Idle, ViewerIntent.Retry))
        val readyState = ready()
        assertSame(readyState, reduce(readyState, ViewerIntent.Retry))
    }

    @Test
    fun lateParseEvents_afterFailure_cannotResurrectParsing() {
        val error = ViewerState.Error(ErrorKind.TooLarge("too many cells"))
        assertSame(error, reduce(error, ParseEvent.Progress(0.5f)))
        assertSame(error, reduce(error, ParseEvent.Loaded(meta, SheetSnapshot.EMPTY)))
    }

    @Test
    fun lateParseEvents_afterReady_areIgnored() {
        val readyState = ready(activeSheetId = 1)
        assertSame(readyState, reduce(readyState, ParseEvent.Progress(0.2f)))
        assertSame(readyState, reduce(readyState, ParseEvent.Failed(ErrorKind.Corrupted())))
    }

    // --- SwitchSheet resets the viewport ---

    @Test
    fun switchSheet_marksTheTabPendingWithoutSwapping() {
        // The sheet is read on demand, so the grid must keep showing the current
        // one until SheetLoaded arrives — no blank frame in between.
        val scrolled = ready(
            activeSheetId = 0,
            viewport = Viewport(scrollX = 500f, scrollY = 900f, zoom = 2f),
        )
        val next = reduce(scrolled, ViewerIntent.SwitchSheet(2)) as ViewerState.Ready

        assertEquals(2, next.loadingSheetId)
        assertEquals("still on the old sheet", 0, next.activeSheetId)
        assertEquals(scrolled.viewport, next.viewport)
    }

    @Test
    fun sheetLoaded_swapsTheSheetAndResetsEverythingViewport() {
        val pending = ready(
            activeSheetId = 0,
            viewport = Viewport(scrollX = 500f, scrollY = 900f, zoom = 2f),
            selection = CellRange(12, 3, 12, 3),
            scrollBounds = ScrollBounds(999f, 999f),
            loadingSheetId = 2,
        )
        val newMeta = meta.copy(rowCount = 5)

        val next = reduce(
            pending,
            ParseEvent.SheetLoaded(index = 2, meta = newMeta, sheet = SheetSnapshot.EMPTY),
        ) as ViewerState.Ready

        assertEquals(2, next.activeSheetId)
        assertEquals(newMeta, next.docMeta)
        assertEquals(Viewport.INITIAL, next.viewport)
        assertNull(next.selection)
        // Stale limits from the previous sheet must not clamp the new one.
        assertEquals(ScrollBounds.UNKNOWN, next.scrollBounds)
        assertNull(next.loadingSheetId)
    }

    @Test
    fun sheetFailed_keepsTheCurrentSheetAndClearsPending() {
        val pending = ready(activeSheetId = 1, loadingSheetId = 2)
        val next = reduce(pending, ParseEvent.SheetFailed(ErrorKind.Corrupted())) as ViewerState.Ready
        assertEquals(1, next.activeSheetId)
        assertNull(next.loadingSheetId)
    }

    @Test
    fun switchSheet_toTheActiveOne_isIgnored() {
        val readyState = ready(activeSheetId = 1)
        assertSame(readyState, reduce(readyState, ViewerIntent.SwitchSheet(1)))
    }

    @Test
    fun switchSheet_outOfRange_isIgnored() {
        val readyState = ready(activeSheetId = 1)
        assertSame(readyState, reduce(readyState, ViewerIntent.SwitchSheet(3)))
        assertSame(readyState, reduce(readyState, ViewerIntent.SwitchSheet(-1)))
    }

    @Test
    fun switchSheet_beforeReady_isIgnored() {
        assertSame(ViewerState.Idle, reduce(ViewerState.Idle, ViewerIntent.SwitchSheet(1)))
        val parsing = ViewerState.Parsing(0.3f)
        assertSame(parsing, reduce(parsing, ViewerIntent.SwitchSheet(1)))
    }

    // --- scrolling ---

    @Test
    fun scroll_accumulatesOffsets() {
        var state: ViewerState = ready()
        state = reduce(state, ViewerIntent.Scroll(dx = 120f, dy = 40f))
        state = reduce(state, ViewerIntent.Scroll(dx = 30f, dy = 10f))
        assertEquals(Viewport(scrollX = 150f, scrollY = 50f), (state as ViewerState.Ready).viewport)
    }

    @Test
    fun scroll_clampsAtOrigin() {
        val state = reduce(ready(), ViewerIntent.Scroll(dx = -500f, dy = -500f))
        assertEquals(Viewport.INITIAL, (state as ViewerState.Ready).viewport)
    }

    @Test
    fun scroll_beforeReady_isIgnored() {
        assertSame(ViewerState.Idle, reduce(ViewerState.Idle, ViewerIntent.Scroll(10f, 10f)))
    }

    // --- zooming ---

    @Test
    fun zoom_multipliesAndClamps() {
        val zoomedIn = reduce(ready(), ViewerIntent.Zoom(2f, 0f, 0f)) as ViewerState.Ready
        assertEquals(2f, zoomedIn.viewport.zoom, 0f)

        // Far past the ceiling clamps to MAX_ZOOM.
        val clampedIn = reduce(zoomedIn, ViewerIntent.Zoom(10f, 0f, 0f)) as ViewerState.Ready
        assertEquals(Viewport.MAX_ZOOM, clampedIn.viewport.zoom, 0f)

        // And past the floor clamps to MIN_ZOOM.
        val clampedOut = reduce(ready(), ViewerIntent.Zoom(0.01f, 0f, 0f)) as ViewerState.Ready
        assertEquals(Viewport.MIN_ZOOM, clampedOut.viewport.zoom, 0f)
    }

    /**
     * Before T20 this asserted that zooming left scroll alone, because the focal
     * point was ignored. Holding the focal cell under the fingers is the whole
     * of pinch zoom, so scroll now moves *with* it — the arithmetic lives in
     * `FocalZoomTest`; this pins that the reducer actually applies it.
     */
    @Test
    fun zoom_movesScrollToHoldTheFocalPoint() {
        val scrolled = ready(viewport = Viewport(scrollX = 100f, scrollY = 200f))
        val next = reduce(scrolled, ViewerIntent.Zoom(1.5f, 50f, 50f)) as ViewerState.Ready

        // 50 x (1/1 - 1/1.5) = 16.67 of compensation on each axis.
        assertEquals(116.67f, next.viewport.scrollX, 0.01f)
        assertEquals(216.67f, next.viewport.scrollY, 0.01f)
    }

    /** Zooming about the top-left corner is pure scaling, so scroll stands still. */
    @Test
    fun zoom_aboutTheOrigin_leavesScrollAlone() {
        val scrolled = ready(viewport = Viewport(scrollX = 100f, scrollY = 200f))
        val next = reduce(scrolled, ViewerIntent.Zoom(1.5f, 0f, 0f)) as ViewerState.Ready
        assertEquals(100f, next.viewport.scrollX, 0f)
        assertEquals(200f, next.viewport.scrollY, 0f)
    }

    /** A double tap is a request; the ViewModel animates it, the reducer ignores it. */
    @Test
    fun resetZoom_isInertInTheReducer() {
        val state = ready(viewport = Viewport(zoom = 2.5f))
        assertEquals(state, reduce(state, ViewerIntent.ResetZoom(100f, 100f)))
    }

    // --- progressive first paint (T15.5) ---

    @Test
    fun partialLoaded_fromParsing_putsTheGridUpImmediately() {
        val next = reduce(
            ViewerState.Parsing(0.1f),
            ParseEvent.PartialLoaded(meta, SheetSnapshot.EMPTY, progress = 0.1f),
        ) as ViewerState.Ready

        assertEquals(meta, next.docMeta)
        assertEquals(0.1f, next.loadProgress!!, 0f)
        assertEquals(Viewport.INITIAL, next.viewport)
    }

    @Test
    fun partialLoaded_whileReady_growsTheSheetWithoutMovingTheViewport() {
        // The user may already be scrolling through the first rows; later chunks
        // must not yank them back to the top.
        val scrolled = ready(
            viewport = Viewport(scrollX = 120f, scrollY = 4_000f, zoom = 1.5f),
        ).copy(loadProgress = 0.2f)

        val next = reduce(
            scrolled,
            ParseEvent.PartialLoaded(meta, SheetSnapshot.EMPTY, progress = 0.6f),
        ) as ViewerState.Ready

        assertEquals(scrolled.viewport, next.viewport)
        assertEquals(0.6f, next.loadProgress!!, 0f)
    }

    @Test
    fun loaded_afterPartials_clearsProgressAndKeepsTheViewport() {
        val streaming = ready(viewport = Viewport(scrollX = 50f, scrollY = 900f))
            .copy(loadProgress = 0.9f)

        val next = reduce(streaming, ParseEvent.Loaded(meta, SheetSnapshot.EMPTY)) as ViewerState.Ready

        assertNull("the bar must go away when the parse finishes", next.loadProgress)
        assertEquals(streaming.viewport, next.viewport)
    }

    @Test
    fun partialLoaded_afterTheUserMovedOn_isIgnored() {
        // Idle or Error means this document is history; a late chunk must not
        // resurrect it.
        val event = ParseEvent.PartialLoaded(meta, SheetSnapshot.EMPTY, progress = 0.5f)
        assertSame(ViewerState.Idle, reduce(ViewerState.Idle, event))
        val error = ViewerState.Error(ErrorKind.Corrupted())
        assertSame(error, reduce(error, event))
    }

    @Test
    fun failure_duringStreaming_replacesThePartialGrid() {
        // Half a sheet is worse than an honest error, so a mid-parse failure
        // takes over even though the grid is already up.
        val streaming = ready().copy(loadProgress = 0.4f)
        val kind = ErrorKind.TooLarge("cap")
        assertEquals(ViewerState.Error(kind), reduce(streaming, ParseEvent.Failed(kind)))
    }

    @Test
    fun failure_afterLoadingCompleted_leavesTheSheetAlone() {
        // loadProgress == null means this document finished; a stray failure from
        // somewhere else must not wipe it.
        val settled = ready()
        assertSame(settled, reduce(settled, ParseEvent.Failed(ErrorKind.Corrupted())))
    }

    // --- scroll bounds (T14) ---

    @Test
    fun scroll_clampsAtTheContentEdge() {
        val bounded = ready(scrollBounds = ScrollBounds(maxScrollX = 200f, maxScrollY = 500f))
        val next = reduce(bounded, ViewerIntent.Scroll(dx = 9_000f, dy = 9_000f)) as ViewerState.Ready
        assertEquals(200f, next.viewport.scrollX, 0f)
        assertEquals(500f, next.viewport.scrollY, 0f)
    }

    @Test
    fun scroll_withinBounds_isUntouched() {
        val bounded = ready(scrollBounds = ScrollBounds(maxScrollX = 200f, maxScrollY = 500f))
        val next = reduce(bounded, ViewerIntent.Scroll(dx = 50f, dy = 60f)) as ViewerState.Ready
        assertEquals(50f, next.viewport.scrollX, 0f)
        assertEquals(60f, next.viewport.scrollY, 0f)
    }

    @Test
    fun boundsChanged_isRecordedAndReClampsTheViewport() {
        // A viewport parked deep in a big sheet must not hang past the end when a
        // smaller sheet's bounds arrive.
        val scrolled = ready(viewport = Viewport(scrollX = 5_000f, scrollY = 8_000f))
        val next = reduce(
            scrolled,
            RenderEvent.BoundsChanged(ScrollBounds(maxScrollX = 100f, maxScrollY = 120f)),
        ) as ViewerState.Ready

        assertEquals(ScrollBounds(100f, 120f), next.scrollBounds)
        assertEquals(100f, next.viewport.scrollX, 0f)
        assertEquals(120f, next.viewport.scrollY, 0f)
    }

    @Test
    fun boundsChanged_beforeReady_isIgnored() {
        val event = RenderEvent.BoundsChanged(ScrollBounds(10f, 10f))
        assertSame(ViewerState.Idle, reduce(ViewerState.Idle, event))
    }

    @Test
    fun negativeBounds_collapseToTheOrigin() {
        // A sheet narrower than the viewport has nothing to scroll.
        val bounded = ready(scrollBounds = ScrollBounds(maxScrollX = -50f, maxScrollY = -50f))
        val next = reduce(bounded, ViewerIntent.Scroll(dx = 100f, dy = 100f)) as ViewerState.Ready
        assertEquals(Viewport.INITIAL, next.viewport)
    }

    // --- fling ---

    @Test
    fun fling_leavesStateToTheViewModel() {
        // The reducer sees only the Scroll intents the decay produces, so a Fling
        // itself must not move anything.
        val readyState = ready()
        assertSame(readyState, reduce(readyState, ViewerIntent.Fling(vx = 2_000f, vy = -800f)))
    }

    // --- selection (T29) ---
    //
    // Replaces tap_leavesStateUnchangedForNow, which pinned the v1.0 contract:
    // TapCell was declared and deliberately inert. T29 wires it, so the
    // assertion is inverted rather than loosened — and the intent now carries a
    // resolved cell, because screen pixels mean nothing without the geometry
    // that produced them. See ViewerIntent.SelectCell.

    @Test
    fun selectCell_storesTheCell() {
        val readyState = ready()
        val next = reduce(readyState, ViewerIntent.SelectCell(CellRef(row = 4, col = 2)))
        assertEquals(CellRange(4, 2, 4, 2), (next as ViewerState.Ready).selection)
    }

    @Test
    fun selectCell_null_clearsTheSelection() {
        // A tap on the header strips or past the last row lands nowhere, and
        // clearing is the honest answer — better than leaving a stale outline on
        // a cell the user is no longer pointing at.
        val selected = reduce(ready(), ViewerIntent.SelectCell(CellRef(1, 1)))
        val cleared = reduce(selected, ViewerIntent.SelectCell(null))
        assertNull((cleared as ViewerState.Ready).selection)
    }

    @Test
    fun selectCell_doesNotDisturbTheViewport() {
        // Selecting must not scroll: the cell the user tapped has to stay under
        // their finger.
        val scrolled = reduce(ready(), ViewerIntent.Scroll(dx = 120f, dy = 60f)) as ViewerState.Ready
        val selected = reduce(scrolled, ViewerIntent.SelectCell(CellRef(3, 3))) as ViewerState.Ready
        assertEquals(scrolled.viewport, selected.viewport)
    }

    @Test
    fun selectCell_isIgnoredWhenThereIsNoSheet() {
        // Nothing to select on the error or parsing screens, and mapReady must
        // not conjure a Ready state out of one.
        assertSame(ViewerState.Idle, reduce(ViewerState.Idle, ViewerIntent.SelectCell(CellRef(0, 0))))
    }

    @Test
    fun switchingSheet_clearsTheSelection() {
        // A1 of the next sheet is not the cell that was selected on this one, so
        // carrying the reference across would point at an unrelated value.
        val selected = reduce(ready(), ViewerIntent.SelectCell(CellRef(2, 2)))
        val switched = reduce(selected, ParseEvent.SheetLoaded(1, meta, SheetSnapshot.EMPTY))
        assertNull((switched as ViewerState.Ready).selection)
    }

    @Test
    fun selectionSurvivesScrollingAndZooming() {
        // It lives in state, so a configuration change — which rebuilds the UI
        // from this same state — cannot lose it either.
        var state: ViewerState = reduce(ready(), ViewerIntent.SelectCell(CellRef(7, 1)))
        state = reduce(state, ViewerIntent.Scroll(dx = 40f, dy = 40f))
        state = reduce(state, ViewerIntent.Zoom(scale = 1.5f, focalX = 100f, focalY = 100f))
        assertEquals(CellRange(7, 1, 7, 1), (state as ViewerState.Ready).selection)
    }

    // --- search (T33) ---
    //
    // The rule these exist for: the reducer must never hold results that do not
    // belong to the sheet on screen. An index into a stale match list is how
    // next/previous ends up scrolling to a cell that moved.

    private fun searching(query: String, sheetData: SheetSnapshot = SheetSnapshot.EMPTY): ViewerState.Ready {
        var s: ViewerState = reduce(ready().copy(sheet = sheetData), ViewerIntent.SetSearchOpen(true))
        s = reduce(s, ViewerIntent.SetSearchQuery(query))
        return s as ViewerState.Ready
    }

    private fun resultsFor(sheet: SheetSnapshot, query: String, vararg cells: Pair<Int, Int>) =
        SheetSearch.run(sheet, query)!!

    @Test
    fun openingSearch_givesAnEmptyBar_andClosingDropsEverything() {
        val opened = reduce(ready(), ViewerIntent.SetSearchOpen(true)) as ViewerState.Ready
        assertEquals(SearchState(), opened.search)

        val closed = reduce(opened, ViewerIntent.SetSearchOpen(false)) as ViewerState.Ready
        assertNull("closing must not leave a match list to be reopened", closed.search)
    }

    @Test
    fun typingAQuery_marksItRunningAndClearsAnyOldMatches() {
        val s = searching("Toshkent")
        assertEquals("Toshkent", s.search!!.query)
        assertNull(s.search!!.results)
        assertTrue(s.search!!.running)
        assertEquals(-1, s.search!!.currentIndex)
    }

    @Test
    fun clearingTheBox_stopsSearchingRatherThanLeavingTheLastMatchesLit() {
        var s: ViewerState = searching("Toshkent")
        s = reduce(s, ViewerIntent.SetSearchQuery(""))
        val search = (s as ViewerState.Ready).search!!
        assertFalse(search.running)
        assertNull(search.results)
    }

    /**
     * A scan that finishes after the sheet has moved on must be **dropped**, not
     * shown. This is the single rule that makes a stale index impossible.
     */
    @Test
    fun resultsForADifferentSheet_areRejected() {
        val other = SheetSnapshot.EMPTY.copy(data = com.tikoncha.darcha.model.SheetData(emptyMap()))
        val state = searching("x")
        val stale = SheetSearch.run(other, "x")!!

        val after = reduce(state, SearchEvent.Completed(stale)) as ViewerState.Ready
        assertNull("a scan of another sheet must not become the state", after.search!!.results)
    }

    @Test
    fun resultsForASupersededQuery_areRejected() {
        val state = searching("second")
        val old = SheetSearch.run(state.sheet, "first")!!

        val after = reduce(state, SearchEvent.Completed(old)) as ViewerState.Ready
        assertNull(after.search!!.results)
        assertEquals("second", after.search!!.query)
    }

    @Test
    fun aNewChunkInvalidatesTheMatches_soTheyCannotBeNavigated() {
        val state = searching("x")
        val results = SheetSearch.run(state.sheet, "x")!!
        var s: ViewerState = reduce(state, SearchEvent.Completed(results))
        assertNotNull((s as ViewerState.Ready).search!!.results)

        // The next chunk: a different SheetData, so the matches are stale.
        val grown = SheetSnapshot.EMPTY.copy(data = com.tikoncha.darcha.model.SheetData(emptyMap()))
        s = reduce(s, ParseEvent.PartialLoaded(meta, grown, 0.5f))

        val search = (s as ViewerState.Ready).search!!
        assertNull("stale matches must be dropped, not carried", search.results)
        assertEquals(-1, search.currentIndex)
        assertTrue("and the UI must say it is working, not that there are none", search.running)
    }

    @Test
    fun switchingSheet_dropsTheMatchesButKeepsTheQuery() {
        val state = searching("x")
        var s: ViewerState = reduce(state, SearchEvent.Completed(SheetSearch.run(state.sheet, "x")!!))
        val other = SheetSnapshot.EMPTY.copy(data = com.tikoncha.darcha.model.SheetData(emptyMap()))
        s = reduce(s, ParseEvent.SheetLoaded(1, meta, other))

        val search = (s as ViewerState.Ready).search!!
        assertEquals("the reader is still looking for the same thing", "x", search.query)
        assertNull(search.results)
    }

    @Test
    fun steppingWrapsAtBothEnds_andSelectsTheMatch() {
        val sheet = sheetWith("hit", cells = listOf(0 to 0, 2 to 1, 5 to 3))
        var s: ViewerState = searching("hit", sheet)
        s = reduce(s, SearchEvent.Completed(SheetSearch.run(sheet, "hit")!!))

        assertEquals(0, (s as ViewerState.Ready).search!!.currentIndex)

        s = reduce(s, ViewerIntent.StepMatch(forward = true))
        assertEquals(1, (s as ViewerState.Ready).search!!.currentIndex)
        assertEquals("the found cell becomes the selection (T29)", CellRange(2, 1, 2, 1), s.selection)

        s = reduce(s, ViewerIntent.StepMatch(forward = true))
        s = reduce(s, ViewerIntent.StepMatch(forward = true))
        assertEquals("wraps past the last", 0, (s as ViewerState.Ready).search!!.currentIndex)

        s = reduce(s, ViewerIntent.StepMatch(forward = false))
        assertEquals("wraps before the first", 2, (s as ViewerState.Ready).search!!.currentIndex)
    }

    @Test
    fun steppingWithNoMatches_doesNothing() {
        val s = searching("nothing")
        assertSame(s, reduce(s, ViewerIntent.StepMatch(forward = true)))
    }

    @Test
    fun aManualTapMovesTheSelection_butKeepsTheSearch() {
        val sheet = sheetWith("hit", cells = listOf(0 to 0, 4 to 2))
        var s: ViewerState = searching("hit", sheet)
        s = reduce(s, SearchEvent.Completed(SheetSearch.run(sheet, "hit")!!))
        s = reduce(s, ViewerIntent.SelectCell(CellRef(9, 9)))

        val ready = s as ViewerState.Ready
        assertEquals(CellRange(9, 9, 9, 9), ready.selection)
        assertEquals("tapping elsewhere must not throw away the matches", 2, ready.search!!.matchCount)
        assertEquals(0, ready.search!!.currentIndex)
    }

    @Test
    fun theCountIsNotFinalWhileAScanRunsOrTheSheetGrows() {
        val sheet = sheetWith("hit", cells = listOf(0 to 0))
        var s: ViewerState = searching("hit", sheet)
        assertFalse("a scan in flight", (s as ViewerState.Ready).search!!.countIsFinal)

        s = reduce(s, SearchEvent.Completed(SheetSearch.run(sheet, "hit", complete = false)!!))
        assertFalse("the sheet is still parsing", (s as ViewerState.Ready).search!!.countIsFinal)

        s = reduce(s, SearchEvent.Completed(SheetSearch.run(sheet, "hit", complete = true)!!))
        assertTrue((s as ViewerState.Ready).search!!.countIsFinal)
    }

    /** A sheet with the word "hit" at each of the given (row, col) positions. */
    private fun sheetWith(word: String, cells: List<Pair<Int, Int>>): SheetSnapshot {
        val rows = cells.groupBy { it.first }.mapValues { (_, cs) ->
            val sorted = cs.map { it.second }.sorted()
            com.tikoncha.darcha.model.Row(
                columns = sorted.toIntArray(),
                values = Array(sorted.size) { com.tikoncha.darcha.model.CellValue.InlineText(word) },
                styleIds = IntArray(sorted.size),
            )
        }
        return SheetSnapshot.EMPTY.copy(data = com.tikoncha.darcha.model.SheetData(rows))
    }

    // --- full sequence ---

    @Test
    fun fullSequence_openToReadyToSwitchSheet() {
        var state: ViewerState = ViewerState.Idle
        state = reduce(state, ViewerIntent.OpenFile(source))
        assertTrue(state is ViewerState.Parsing)
        state = reduce(state, ParseEvent.Progress(0.5f))
        state = reduce(state, ParseEvent.Loaded(meta, SheetSnapshot.EMPTY))
        state = reduce(state, ViewerIntent.Scroll(dx = 80f, dy = 20f))
        state = reduce(state, ViewerIntent.SwitchSheet(1))
        state = reduce(state, ParseEvent.SheetLoaded(1, meta, SheetSnapshot.EMPTY))

        val final = state as ViewerState.Ready
        assertEquals(1, final.activeSheetId)
        assertEquals(Viewport.INITIAL, final.viewport)
        assertEquals(listOf("Jadval 1", "Narxlar", "Ҳисобот"), final.docMeta.sheetNames)
    }
}

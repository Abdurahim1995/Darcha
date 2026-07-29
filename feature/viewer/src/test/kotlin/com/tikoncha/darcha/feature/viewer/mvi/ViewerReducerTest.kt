package com.tikoncha.darcha.feature.viewer.mvi

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.model.ErrorKind
import org.junit.Assert.assertEquals
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
        selection: CellRef? = null,
    ) = ViewerState.Ready(meta, SheetSnapshot.EMPTY, activeSheetId, viewport, selection)

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
    fun switchSheet_resetsViewportAndSelection() {
        val scrolled = ready(
            activeSheetId = 0,
            viewport = Viewport(scrollX = 500f, scrollY = 900f, zoom = 2f),
            selection = CellRef(row = 12, col = 3),
        )
        val next = reduce(scrolled, ViewerIntent.SwitchSheet(2)) as ViewerState.Ready

        assertEquals(2, next.activeSheetId)
        assertEquals(Viewport.INITIAL, next.viewport)
        assertNull(next.selection)
        assertEquals(meta, next.docMeta)
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

    @Test
    fun zoom_preservesScrollOffsets() {
        val scrolled = ready(viewport = Viewport(scrollX = 100f, scrollY = 200f))
        val next = reduce(scrolled, ViewerIntent.Zoom(1.5f, 50f, 50f)) as ViewerState.Ready
        assertEquals(100f, next.viewport.scrollX, 0f)
        assertEquals(200f, next.viewport.scrollY, 0f)
    }

    // --- intents deferred to later tasks ---

    @Test
    fun flingAndTap_leaveStateUnchangedForNow() {
        val readyState = ready()
        assertSame(readyState, reduce(readyState, ViewerIntent.Fling(vx = 800f, vy = 0f)))
        assertSame(readyState, reduce(readyState, ViewerIntent.TapCell(x = 10f, y = 20f)))
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

        val final = state as ViewerState.Ready
        assertEquals(1, final.activeSheetId)
        assertEquals(Viewport.INITIAL, final.viewport)
        assertEquals(listOf("Jadval 1", "Narxlar", "Ҳисобот"), final.docMeta.sheetNames)
    }
}

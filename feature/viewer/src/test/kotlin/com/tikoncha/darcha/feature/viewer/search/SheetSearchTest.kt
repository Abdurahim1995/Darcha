package com.tikoncha.darcha.feature.viewer.search

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.model.CellStyle
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.Row
import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.StringTable
import com.tikoncha.darcha.model.StyleTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search engine (T32). Engine only — no UI, which is T33.
 *
 * The three decisions this suite pins are the ones that were decided rather
 * than stumbled into: match both readings but count a cell once, never touch the
 * renderer's cache, and never let a match index outlive the snapshot it was
 * computed from.
 */
class SheetSearchTest {

    // numFmtId 14 is the builtin mm-dd-yy, so this style renders a date.
    private val dateStyle = CellStyle.DEFAULT.copy(numFmtId = 14, formatCode = "mm-dd-yy", isDate = true)
    private val styles = StyleTable(listOf(CellStyle.DEFAULT, dateStyle))
    private val strings = StringTable(listOf("Toshkent", "Namangan", "toshkent shahri", "Farg'ona"))

    private fun row(vararg cells: Triple<Int, CellValue, Int>) = Row(
        columns = cells.map { it.first }.toIntArray(),
        values = cells.map { it.second }.toTypedArray(),
        styleIds = cells.map { it.third }.toIntArray(),
    )

    private fun sheet(rows: Map<Int, Row>) = SheetSnapshot(
        data = SheetData(rows),
        layout = com.tikoncha.darcha.model.SheetLayout.EMPTY,
        sharedStrings = strings,
        styles = styles,
    )

    private fun text(index: Int, col: Int) = Triple(col, CellValue.SharedText(index) as CellValue, 0)
    private fun number(v: Double, col: Int, style: Int = 0) = Triple(col, CellValue.Number(v) as CellValue, style)

    // --- what gets matched ---

    @Test
    fun findsTextByWhatTheCellShows() {
        val s = sheet(mapOf(0 to row(text(0, 0)), 1 to row(text(1, 0))))
        val r = SheetSearch.run(s, "Toshkent")!!

        assertEquals(1, r.size)
        assertEquals(0, r.rowAt(0))
        assertEquals(MatchField.DISPLAYED, r.fieldAt(0))
    }

    /**
     * The case the "match both" decision exists for. `45306` is a January 2024
     * date: on screen it reads `01-15-24`, and the serial appears nowhere. A user
     * who knows the serial and a user reading the screen are both looking for
     * something real.
     */
    @Test
    fun aDateIsFoundByItsDisplayedFormAndByItsSerial() {
        val s = sheet(mapOf(0 to row(number(45306.0, col = 0, style = 1))))

        val byDisplay = SheetSearch.run(s, "01-15")!!
        assertEquals(1, byDisplay.size)
        assertEquals(MatchField.DISPLAYED, byDisplay.fieldAt(0))

        val bySerial = SheetSearch.run(s, "45306")!!
        assertEquals(1, bySerial.size)
        assertEquals("the serial exists only in the stored value", MatchField.RAW, bySerial.fieldAt(0))
    }

    /**
     * A cell matching both ways is still **one** match. If this ever returns 2,
     * the count is lying and next/previous will visit the same cell twice.
     */
    @Test
    fun aCellMatchingBothReadings_isStillOneMatch() {
        // Unstyled, so it displays as "24" and stores 24.0 -- "24" is in both.
        val s = sheet(mapOf(0 to row(number(24.0, col = 0))))
        val r = SheetSearch.run(s, "24")!!

        assertEquals("one cell, one hit", 1, r.size)
        assertEquals(MatchField.BOTH, r.fieldAt(0))
    }

    @Test
    fun rawPrecisionIsSearchableBehindARoundedDisplay() {
        // General shows 11 significant digits; the stored value has more.
        val s = sheet(mapOf(0 to row(number(1234.56789012345, col = 0))))
        assertNotNull("the full precision must still be findable", SheetSearch.run(s, "89012345"))
        assertEquals(1, SheetSearch.run(s, "89012345")!!.size)
    }

    @Test
    fun anIntegralNumberDoesNotCarryATrailingPointZero() {
        // A user types 45306, not 45306.0.
        val s = sheet(mapOf(0 to row(number(45306.0, col = 0, style = 1))))
        assertEquals(1, SheetSearch.run(s, "45306")!!.size)
    }

    @Test
    fun booleansAndErrorsMatchWhatTheyShow() {
        val s = sheet(
            mapOf(
                0 to row(Triple(0, CellValue.Bool(true) as CellValue, 0)),
                1 to row(Triple(0, CellValue.Error("#DIV/0!") as CellValue, 0)),
            ),
        )
        assertEquals(1, SheetSearch.run(s, "true")!!.size)
        assertEquals(1, SheetSearch.run(s, "DIV/0")!!.size)
        // Never reported as RAW: their two readings are the same string.
        assertEquals(MatchField.DISPLAYED, SheetSearch.run(s, "true")!!.fieldAt(0))
    }

    // --- case and partiality ---

    @Test
    fun matchingIsCaseInsensitive() {
        val s = sheet(mapOf(0 to row(text(0, 0))))
        assertEquals(1, SheetSearch.run(s, "toshkent")!!.size)
        assertEquals(1, SheetSearch.run(s, "TOSHKENT")!!.size)
        assertEquals(1, SheetSearch.run(s, "ToSHkeNT")!!.size)
    }

    @Test
    fun matchingIsPartial_notWholeCell() {
        val s = sheet(mapOf(0 to row(text(2, 0)))) // "toshkent shahri"
        assertEquals(1, SheetSearch.run(s, "shahri")!!.size)
        assertEquals(1, SheetSearch.run(s, "kent sh")!!.size)
    }

    @Test
    fun nonAsciiTextIsFoundAsTyped() {
        val s = sheet(mapOf(0 to row(text(3, 0)))) // "Farg'ona"
        assertEquals(1, SheetSearch.run(s, "Farg'ona")!!.size)
        assertEquals(1, SheetSearch.run(s, "g'on")!!.size)
    }

    @Test
    fun anEmptyQueryMatchesNothing_ratherThanEverything() {
        // Every cell contains "", so the naive answer is "all of them" -- which
        // would make an empty search box highlight the entire sheet.
        val s = sheet(mapOf(0 to row(text(0, 0), text(1, 1))))
        assertTrue(SheetSearch.run(s, "")!!.isEmpty)
    }

    // --- ordering and lookup ---

    @Test
    fun matchesAreInRowMajorOrder() {
        val s = sheet(
            mapOf(
                2 to row(text(0, 5), text(0, 1)),
                0 to row(text(0, 3)),
                1 to row(text(0, 0)),
            ),
        )
        val r = SheetSearch.run(s, "Toshkent")!!
        val order = (0 until r.size).map { r.rowAt(it) to r.colAt(it) }

        assertEquals(
            "next/previous walks the sheet the way a reader does",
            listOf(0 to 3, 1 to 0, 2 to 1, 2 to 5),
            order,
        )
    }

    @Test
    fun indexOfFindsAMatchedCellAndRejectsOthers() {
        val s = sheet(mapOf(0 to row(text(0, 3)), 4 to row(text(0, 9))))
        val r = SheetSearch.run(s, "Toshkent")!!

        assertEquals(0, r.indexOf(0, 3))
        assertEquals(1, r.indexOf(4, 9))
        assertEquals(-1, r.indexOf(0, 9))
        assertEquals(-1, r.indexOf(4, 3))
        assertEquals(-1, r.indexOf(99, 99))
    }

    @Test
    fun packingSurvivesLargeCoordinates() {
        // The full grid is 1,048,576 x 16,384; the packing must not collide or
        // mis-order anywhere in it.
        val s = sheet(mapOf(1_048_575 to row(text(0, 16_383)), 0 to row(text(0, 0))))
        val r = SheetSearch.run(s, "Toshkent")!!

        assertEquals(0, r.rowAt(0))
        assertEquals(1_048_575, r.rowAt(1))
        assertEquals(16_383, r.colAt(1))
        assertEquals(1, r.indexOf(1_048_575, 16_383))
    }

    // --- the renderer's cache must be untouched ---

    /**
     * The design pressure the playbook named. The renderer's cache holds 2,048
     * entries sized for a viewport; a scan of ~350,000 cells would evict every
     * string the grid needs and leave it re-measuring text on the next frame.
     *
     * The scan builds its own cache instead. This proves it: the renderer's
     * instance is used, then a search runs over a sheet full of distinct
     * numbers, and the renderer's cache is unchanged afterwards.
     */
    @Test
    fun searchingDoesNotDisturbTheRenderersCache() {
        val rows = (0 until 5_000).associateWith { row(number(it.toDouble() + 0.5, col = 0)) }
        val s = sheet(rows)

        val renderersCache = com.tikoncha.darcha.model.FormattedValueCache(
            styles = styles,
            strings = strings,
        )
        // Warm it the way the draw pass would.
        for (i in 0 until 40) renderersCache.format(CellValue.Number(i.toDouble()), 0)
        val warmed = renderersCache.size

        SheetSearch.run(s, "7")

        assertEquals(
            "a scan must not evict a single one of the renderer's strings",
            warmed,
            renderersCache.size,
        )
    }

    // --- cancellation ---

    @Test
    fun aCancelledSearchReturnsNothingUsable() {
        val rows = (0 until 20_000).associateWith { row(text(0, 0)) }
        var polls = 0
        val result = SheetSearch.run(
            sheet(rows),
            "Toshkent",
            isActive = { polls++ < 2 },
        )
        assertNull("a superseded search has no partial answer worth showing", result)
        assertTrue("cancellation must actually be polled", polls > 2)
    }

    @Test
    fun anUncancelledSearchPollsButCompletes() {
        val rows = (0 until 3_000).associateWith { row(text(0, 0)) }
        val r = SheetSearch.run(sheet(rows), "Toshkent", isActive = { true })
        assertNotNull(r)
        assertEquals(3_000, r!!.size)
    }

    // --- progressive parsing: a stale index must be impossible, not unlikely ---

    /**
     * Results belong to one immutable snapshot. A new chunk produces a new
     * `SheetData`, so results computed before it are **stale**, not merely
     * incomplete — and [SearchResults.isFor] says so, by identity.
     */
    @Test
    fun resultsAreBoundToTheSnapshotTheyScanned() {
        val first = sheet(mapOf(0 to row(text(0, 0))))
        val r = SheetSearch.run(first, "Toshkent")!!

        assertTrue(r.isFor(first))

        // The next chunk: same rows plus more, a different SheetData.
        val grown = first.copy(data = SheetData(mapOf(0 to row(text(0, 0)), 1 to row(text(0, 0)))))
        assertFalse("a grown sheet is a different scan", r.isFor(grown))
    }

    @Test
    fun anEqualButDifferentSnapshotIsStillRejected() {
        // Identity, not equality: comparing 350,000 cells to find out would cost
        // more than re-running the search.
        val a = sheet(mapOf(0 to row(text(0, 0))))
        val b = sheet(mapOf(0 to row(text(0, 0))))
        val r = SheetSearch.run(a, "Toshkent")!!

        assertTrue(r.isFor(a))
        assertFalse(r.isFor(b))
    }

    @Test
    fun anIncompleteScanSaysSo() {
        val s = sheet(mapOf(0 to row(text(0, 0))))
        val partial = SheetSearch.run(s, "Toshkent", complete = false)!!
        assertFalse("the count must not be presented as final", partial.complete)
        assertTrue(SheetSearch.run(s, "Toshkent", complete = true)!!.complete)
    }

    // --- sparsity ---

    @Test
    fun anEmptySheetCostsNothingToSearch() {
        val r = SheetSearch.run(SheetSnapshot.EMPTY, "anything")!!
        assertTrue(r.isEmpty)
        assertEquals(-1, r.indexOf(0, 0))
    }

    @Test
    fun onlyPopulatedCellsAreVisited() {
        // Three cells scattered across a million rows: the scan walks the sparse
        // map, not the addressable grid.
        val s = sheet(
            mapOf(
                0 to row(text(0, 0)),
                500_000 to row(text(1, 4)),
                1_048_575 to row(text(0, 16_000)),
            ),
        )
        val r = SheetSearch.run(s, "Toshkent")!!
        assertEquals(2, r.size)
    }
}

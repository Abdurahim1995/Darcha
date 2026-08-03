package com.tikoncha.darcha.feature.viewer.ui

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.geometry.MergeIndex
import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.FormattedValueCache
import com.tikoncha.darcha.model.Row
import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.SheetLayout
import com.tikoncha.darcha.model.StringTable
import com.tikoncha.darcha.model.StyleTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Range selection and its TSV (T34).
 *
 * The playbook said merges would break first, so they are most of what follows.
 * A rectangle that clips half a merged title is not something a spreadsheet can
 * express — half a merge has no value of its own and no outline that makes sense
 * — so the range widens until it contains every merge it touches.
 */
class RangeSelectionTest {

    private fun merges(vararg ranges: CellRange) = MergeIndex.of(ranges.toList())

    private fun expand(index: MergeIndex, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) =
        index.expandedRange(CellRef(fromRow, fromCol), CellRef(toRow, toCol))

    // --- the rectangle itself ---

    @Test
    fun aDragInAnyDirection_normalisesToTheSameRectangle() {
        val none = merges()
        val expected = CellRange(2, 1, 5, 4)
        assertEquals(expected, expand(none, 2, 1, 5, 4))
        assertEquals("dragged up-left", expected, expand(none, 5, 4, 2, 1))
        assertEquals("dragged down-left", expected, expand(none, 2, 4, 5, 1))
        assertEquals("dragged up-right", expected, expand(none, 5, 1, 2, 4))
    }

    @Test
    fun aRangeWithNoMerges_isLeftExactlyAsDragged() {
        assertEquals(CellRange(0, 0, 0, 0), expand(merges(), 0, 0, 0, 0))
        assertEquals(CellRange(10, 3, 10, 3), expand(merges(), 10, 3, 10, 3))
    }

    // --- merges: the case that breaks first ---

    /**
     * Touching one cell of a merge pulls in the whole merge. Anything else would
     * select a fragment that holds no value and cannot be outlined honestly.
     */
    @Test
    fun touchingAnyPartOfAMerge_swallowsTheWholeMerge() {
        val index = merges(CellRange(0, 0, 0, 2)) // A1:C1

        assertEquals("the anchor", CellRange(0, 0, 0, 2), expand(index, 0, 0, 0, 0))
        assertEquals("a covered cell", CellRange(0, 0, 0, 2), expand(index, 0, 1, 0, 1))
        assertEquals("the far end", CellRange(0, 0, 0, 2), expand(index, 0, 2, 0, 2))
    }

    @Test
    fun aRangeClippingAMerge_isWidenedNotCut() {
        val index = merges(CellRange(1, 1, 3, 3)) // B2:D4
        // A drag from A1 to B2 clips the top-left corner of the merge.
        assertEquals(CellRange(0, 0, 3, 3), expand(index, 0, 0, 1, 1))
        // ...and from D4 to F6 clips the bottom-right.
        assertEquals(CellRange(1, 1, 5, 5), expand(index, 3, 3, 5, 5))
    }

    /**
     * Widening runs to a fixed point, because swallowing one merge can bring the
     * rectangle into contact with another that reaches further still. A single
     * pass would leave the second one clipped — the exact bug this guards.
     */
    @Test
    fun swallowingOneMerge_pullsInTheNextItNowTouches() {
        val index = merges(
            CellRange(0, 2, 0, 4), // C1:E1
            CellRange(0, 4, 0, 9), // E1:J1 — reached only via the first
        )
        val result = expand(index, 0, 2, 0, 2)

        assertEquals("must not stop after one pass", CellRange(0, 2, 0, 9), result)
    }

    @Test
    fun chainedMergesTerminate_andDoNotGrowForever() {
        // A staircase, each step touching the last.
        val chain = (0 until 8).map { CellRange(it, it, it + 1, it + 1) }
        val result = MergeIndex.of(chain).expandedRange(CellRef(0, 0), CellRef(0, 0))

        assertEquals(CellRange(0, 0, 8, 8), result)
    }

    @Test
    fun aMergeTheRangeDoesNotTouch_isLeftAlone() {
        val index = merges(CellRange(10, 10, 12, 12))
        assertEquals(CellRange(0, 0, 2, 2), expand(index, 0, 0, 2, 2))
    }

    // --- TSV ---

    private val strings = StringTable(listOf("Nomi", "Olma", "line1\nline2", "has\ttab", "say \"hi\""))

    private fun row(vararg cells: Pair<Int, CellValue>) = Row(
        columns = cells.map { it.first }.toIntArray(),
        values = cells.map { it.second }.toTypedArray(),
        styleIds = IntArray(cells.size),
    )

    private fun sheet(rows: Map<Int, Row>) = SheetSnapshot(
        data = SheetData(rows),
        layout = SheetLayout.EMPTY,
        sharedStrings = strings,
        styles = StyleTable(listOf(com.tikoncha.darcha.model.CellStyle.DEFAULT)),
    )

    private fun tsv(s: SheetSnapshot, range: CellRange): String =
        s.tsvOf(range, FormattedValueCache(styles = s.styles, strings = s.sharedStrings))

    @Test
    fun aRectangleBecomesTabsAndNewlines() {
        val s = sheet(
            mapOf(
                0 to row(0 to CellValue.SharedText(0), 1 to CellValue.Number(10.0)),
                1 to row(0 to CellValue.SharedText(1), 1 to CellValue.Number(25.0)),
            ),
        )
        assertEquals("Nomi\t10\nOlma\t25", tsv(s, CellRange(0, 0, 1, 1)))
    }

    /**
     * An empty cell must produce an **empty field**, not nothing. Dropping it
     * shifts every value after it into the wrong column, which turns a paste from
     * an inconvenience into corrupt data.
     */
    @Test
    fun anEmptyCellIsAnEmptyField_notAMissingOne() {
        val s = sheet(mapOf(0 to row(0 to CellValue.Number(1.0), 2 to CellValue.Number(3.0))))
        assertEquals("1\t\t3", tsv(s, CellRange(0, 0, 0, 2)))
    }

    @Test
    fun anEntirelyEmptyRowStillHoldsItsPlace() {
        val s = sheet(mapOf(0 to row(0 to CellValue.Number(1.0)), 2 to row(0 to CellValue.Number(3.0))))
        assertEquals("1\n\n3", tsv(s, CellRange(0, 0, 2, 0)))
    }

    /**
     * A merged cell contributes its value once, at its anchor, with empty fields
     * for the cells it covers — which is exactly what the source file contains,
     * since the covered cells hold nothing at all.
     */
    @Test
    fun aMergedCellExportsAtItsAnchorWithEmptiesBeside() {
        // A1:C1 merged: only A1 carries a value in the file.
        val s = sheet(
            mapOf(
                0 to row(0 to CellValue.SharedText(0)),
                1 to row(0 to CellValue.Number(1.0), 1 to CellValue.Number(2.0), 2 to CellValue.Number(3.0)),
            ),
        )
        assertEquals("Nomi\t\t\n1\t2\t3", tsv(s, CellRange(0, 0, 1, 2)))
    }

    @Test
    fun theDisplayedTextIsExported_notTheRawValue() {
        val dateStyle = com.tikoncha.darcha.model.CellStyle.DEFAULT
            .copy(numFmtId = 14, formatCode = "mm-dd-yy", isDate = true)
        val s = SheetSnapshot(
            data = SheetData(mapOf(0 to Row(intArrayOf(0), arrayOf(CellValue.Number(45306.0)), intArrayOf(1)))),
            layout = SheetLayout.EMPTY,
            sharedStrings = strings,
            styles = StyleTable(listOf(com.tikoncha.darcha.model.CellStyle.DEFAULT, dateStyle)),
        )
        // The same decision as the single-cell copy (T29): what you see.
        assertEquals("01-15-24", tsv(s, CellRange(0, 0, 0, 0)))
    }

    // --- quoting: one cell must not rewrite the whole paste ---

    /**
     * A shared string with an embedded newline is common enough that the
     * `uzbek-text` fixture has several. Emitted bare, one such cell silently
     * turns the rest of the paste into extra rows.
     */
    @Test
    fun aCellContainingANewlineIsQuoted() {
        val s = sheet(mapOf(0 to row(0 to CellValue.SharedText(2), 1 to CellValue.Number(7.0))))
        assertEquals("\"line1\nline2\"\t7", tsv(s, CellRange(0, 0, 0, 1)))
    }

    @Test
    fun aCellContainingATabIsQuoted() {
        val s = sheet(mapOf(0 to row(0 to CellValue.SharedText(3), 1 to CellValue.Number(7.0))))
        assertEquals("\"has\ttab\"\t7", tsv(s, CellRange(0, 0, 0, 1)))
    }

    @Test
    fun quotesInsideAQuotedFieldAreDoubled() {
        val s = sheet(mapOf(0 to row(0 to CellValue.SharedText(4))))
        // Only quoted because it contains a quote; the doubling is the convention
        // every spreadsheet reads back.
        assertEquals("\"say \"\"hi\"\"\"", tsv(s, CellRange(0, 0, 0, 0)))
    }

    @Test
    fun ordinaryTextIsNotQuoted() {
        val s = sheet(mapOf(0 to row(0 to CellValue.SharedText(0))))
        assertEquals("the common case stays clean", "Nomi", tsv(s, CellRange(0, 0, 0, 0)))
    }

    @Test
    fun everyRowHasTheSameFieldCount() {
        // The invariant a paste depends on: a ragged TSV lands in the wrong
        // columns even when every individual value is right.
        val s = sheet(
            mapOf(
                0 to row(0 to CellValue.Number(1.0)),
                1 to row(0 to CellValue.Number(1.0), 2 to CellValue.Number(3.0)),
                3 to row(1 to CellValue.Number(2.0)),
            ),
        )
        val lines = tsv(s, CellRange(0, 0, 3, 2)).split("\n")
        assertEquals(4, lines.size)
        assertTrue("every row must have 3 fields", lines.all { it.count { c -> c == '\t' } == 2 })
    }
}

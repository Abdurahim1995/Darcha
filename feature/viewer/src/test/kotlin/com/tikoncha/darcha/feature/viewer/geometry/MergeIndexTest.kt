package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.model.CellRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the merged-range lookup the draw loop and hit-testing share (T18). */
class MergeIndexTest {

    /** The three merges of `synthetic/merged.xlsx`, read from the fixture. */
    private val syntheticMerges = listOf(
        CellRange(0, 0, 0, 2), // A1:C1 — a title across three columns
        CellRange(1, 0, 3, 0), // A2:A4 — a vertical side label
        CellRange(1, 1, 2, 2), // B2:C3 — a block
    )

    private fun index(vararg ranges: CellRange) = MergeIndex.of(ranges.toList())

    // --- lookup ---

    @Test
    fun aSheetWithNoMerges_isEmpty() {
        val index = MergeIndex.of(emptyList())
        assertTrue(index.isEmpty)
        assertEquals(MergeIndex.NONE, index.indexOf(0, 0))
        assertEquals(CellRef(3, 4), index.anchorOf(3, 4))
    }

    @Test
    fun everyCellOfEveryRange_isFound() {
        val index = MergeIndex.of(syntheticMerges)
        for ((n, range) in syntheticMerges.withIndex()) {
            for (row in range.startRow..range.endRow) {
                for (col in range.startCol..range.endCol) {
                    assertNotEquals(
                        "range $n should cover ($row, $col)",
                        MergeIndex.NONE,
                        index.indexOf(row, col),
                    )
                }
            }
        }
    }

    @Test
    fun cellsOutsideEveryRange_areNotFound() {
        val index = MergeIndex.of(syntheticMerges)
        // D1 is just past the title; A5 just below the side label; B4 beside it.
        assertEquals(MergeIndex.NONE, index.indexOf(0, 3))
        assertEquals(MergeIndex.NONE, index.indexOf(4, 0))
        assertEquals(MergeIndex.NONE, index.indexOf(3, 1))
        assertEquals(MergeIndex.NONE, index.indexOf(100, 100))
    }

    @Test
    fun onlyTheTopLeftCell_isTheAnchor() {
        val index = MergeIndex.of(syntheticMerges)
        val title = index.indexOf(0, 0)

        assertTrue(index.isAnchor(title, 0, 0))
        assertFalse("B1 is covered, not the anchor", index.isAnchor(title, 0, 1))
        assertFalse("C1 is covered, not the anchor", index.isAnchor(title, 0, 2))
    }

    // --- hit-testing ---

    /**
     * The point of the mapping: tapping anywhere on a merged title must select
     * the title, not the empty cell the finger happened to land on.
     */
    @Test
    fun aCoveredCell_mapsToItsAnchor() {
        val index = MergeIndex.of(syntheticMerges)

        assertEquals(CellRef(0, 0), index.anchorOf(0, 0))
        assertEquals(CellRef(0, 0), index.anchorOf(0, 1))
        assertEquals(CellRef(0, 0), index.anchorOf(0, 2))

        assertEquals(CellRef(1, 0), index.anchorOf(3, 0)) // bottom of the side label
        assertEquals(CellRef(1, 1), index.anchorOf(2, 2)) // bottom-right of the block
    }

    @Test
    fun anUnmergedCell_mapsToItself() {
        val index = MergeIndex.of(syntheticMerges)
        assertEquals(CellRef(9, 9), index.anchorOf(9, 9))
        assertEquals(CellRef(3, 1), index.anchorOf(3, 1))
    }

    // --- what the index refuses to store ---

    /**
     * A 1×1 "merge" is legal in a file and covers nothing. Dropping it keeps the
     * anchor logic honest — a single cell can never be "covered but not anchor".
     */
    @Test
    fun degenerateSingleCellMerges_areDropped() {
        val index = index(CellRange(5, 5, 5, 5), CellRange(0, 0, 0, 1))
        assertEquals(1, index.size)
        assertEquals(MergeIndex.NONE, index.indexOf(5, 5))
    }

    /**
     * The reason ranges are not expanded into a set of covered cells: this one
     * range covers over three million cells and costs four ints.
     */
    @Test
    fun aRangeSpanningTheWholeSheet_costsNothingToStore() {
        val index = index(CellRange(0, 0, 1_048_575, 2))
        assertEquals(1, index.size)

        assertNotEquals(MergeIndex.NONE, index.indexOf(0, 0))
        assertNotEquals(MergeIndex.NONE, index.indexOf(500_000, 1))
        assertNotEquals(MergeIndex.NONE, index.indexOf(1_048_575, 2))
        assertEquals(MergeIndex.NONE, index.indexOf(500_000, 3))
        assertEquals(CellRef(0, 0), index.anchorOf(1_048_575, 2))
    }

    // --- many ranges ---

    @Test
    fun lookupIsCorrectAcrossManyRanges() {
        // 2,000 two-column merges, one every third row, built out of order so the
        // index has to sort them itself.
        val ranges = (0 until 2_000).map { CellRange(it * 3, 0, it * 3, 1) }.reversed()
        val index = MergeIndex.of(ranges)
        assertEquals(2_000, index.size)

        for (n in intArrayOf(0, 1, 999, 1_998, 1_999)) {
            val row = n * 3
            assertNotEquals("row $row should be merged", MergeIndex.NONE, index.indexOf(row, 0))
            assertEquals(CellRef(row, 0), index.anchorOf(row, 1))
            // The two rows after each merge are ordinary.
            assertEquals(MergeIndex.NONE, index.indexOf(row + 1, 0))
            assertEquals(MergeIndex.NONE, index.indexOf(row + 2, 1))
        }
    }

    /**
     * A viewport asks about the merges it can see, not all of them. With one tall
     * range plus many short ones, only the intersecting handful come back.
     */
    @Test
    fun forEachIntersecting_visitsOnlyWhatTheViewportTouches() {
        val ranges = buildList {
            add(CellRange(0, 0, 5_000, 0)) // one very tall range
            for (n in 0 until 500) add(CellRange(n * 10 + 1, 2, n * 10 + 1, 4))
        }
        val index = MergeIndex.of(ranges)

        val visited = mutableListOf<Int>()
        index.forEachIntersecting(firstRow = 100, lastRow = 130, firstColumn = 0, lastColumn = 6) {
            visited.add(it)
        }

        // The tall range, plus the three short ones at rows 101, 111 and 121.
        assertEquals(4, visited.size)
        assertTrue("no duplicates", visited.toSet().size == visited.size)
    }

    @Test
    fun forEachIntersecting_skipsRangesOutsideTheColumnWindow() {
        val index = index(CellRange(0, 0, 0, 2), CellRange(0, 40, 0, 45))
        val visited = mutableListOf<Int>()
        index.forEachIntersecting(0, 10, 0, 10) { visited.add(it) }
        assertEquals("only the range inside columns 0..10", 1, visited.size)
    }

    /**
     * Overlaps are invalid OOXML. The index must not crash or loop on them, and
     * must answer the same way every time.
     */
    @Test
    fun overlappingRanges_resolveDeterministically() {
        val index = index(CellRange(0, 0, 2, 2), CellRange(1, 1, 3, 3))
        val first = index.indexOf(1, 1)
        assertNotEquals(MergeIndex.NONE, first)
        repeat(5) { assertEquals(first, index.indexOf(1, 1)) }
    }
}

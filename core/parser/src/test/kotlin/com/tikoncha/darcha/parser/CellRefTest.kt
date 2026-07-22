package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellRange
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for A1-reference → 0-based coordinate and range conversion. */
class CellRefTest {

    @Test
    fun columnIndex_singleAndMultiLetter() {
        assertEquals(0, CellRef.columnIndexOf("A1"))
        assertEquals(25, CellRef.columnIndexOf("Z1"))
        assertEquals(26, CellRef.columnIndexOf("AA1"))
        assertEquals(27, CellRef.columnIndexOf("AB1"))
        assertEquals(51, CellRef.columnIndexOf("AZ1"))
        assertEquals(52, CellRef.columnIndexOf("BA1"))
        assertEquals(702, CellRef.columnIndexOf("AAA1"))
        assertEquals(16383, CellRef.columnIndexOf("XFD1048576")) // Excel's last column
    }

    @Test
    fun columnIndex_withoutRowDigits() {
        assertEquals(26, CellRef.columnIndexOf("AA"))
    }

    @Test
    fun rowIndex_variousMagnitudes() {
        assertEquals(0, CellRef.rowIndexOf("A1"))
        assertEquals(4, CellRef.rowIndexOf("C5"))
        assertEquals(99, CellRef.rowIndexOf("AA100"))
        assertEquals(1_048_575, CellRef.rowIndexOf("XFD1048576")) // Excel's last row
    }

    @Test
    fun parseRange_normalizesToInclusiveBounds() {
        assertEquals(CellRange(0, 0, 0, 2), CellRef.parseRange("A1:C1"))
        assertEquals(CellRange(1, 0, 3, 0), CellRef.parseRange("A2:A4"))
        assertEquals(CellRange(1, 1, 2, 2), CellRef.parseRange("B2:C3"))
    }

    @Test
    fun parseRange_singleCellIsOneByOne() {
        assertEquals(CellRange(4, 2, 4, 2), CellRef.parseRange("C5"))
    }

    @Test
    fun parseRange_reversedBoundsAreOrdered() {
        assertEquals(CellRange(0, 0, 0, 2), CellRef.parseRange("C1:A1"))
    }
}

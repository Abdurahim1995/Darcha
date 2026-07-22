package com.tikoncha.darcha.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the central column-width → pixel conversion (TECH_SPEC §7). */
class DimensionsTest {

    @Test
    fun columnWidthToPixels_calibri11() {
        // ECMA-376 §18.3.1.13 with MDW = 7 (Calibri 11 @ 96 DPI).
        assertEquals(0, columnWidthToPixels(0.0))
        assertEquals(59, columnWidthToPixels(8.43)) // default column width
        assertEquals(84, columnWidthToPixels(12.0))
        assertEquals(143, columnWidthToPixels(20.5))
    }

    @Test
    fun columnWidthToPixels_scalesWithMaxDigitWidth() {
        assertEquals(76, columnWidthToPixels(8.43, maxDigitWidth = 9))
    }
}

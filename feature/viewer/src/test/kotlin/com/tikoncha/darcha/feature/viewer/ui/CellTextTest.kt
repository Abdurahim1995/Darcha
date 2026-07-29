package com.tikoncha.darcha.feature.viewer.ui

import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.StringTable
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the renderer's text helpers: labels, display text, and the cache key. */
class CellTextTest {

    @Test
    fun columnLabels_followA1Notation() {
        assertEquals("A", columnLabel(0))
        assertEquals("Z", columnLabel(25))
        assertEquals("AA", columnLabel(26))
        assertEquals("AB", columnLabel(27))
        assertEquals("AZ", columnLabel(51))
        assertEquals("BA", columnLabel(52))
        assertEquals("AAA", columnLabel(702))
        assertEquals("XFD", columnLabel(16_383)) // Excel's last column
    }

    @Test
    fun sharedText_resolvesThroughTheTable() {
        val strings = StringTable(listOf("apple", "banana"))
        assertEquals("apple", CellValue.SharedText(0).displayText(strings))
        assertEquals("banana", CellValue.SharedText(1).displayText(strings))
        // A dangling index renders blank rather than crashing the draw pass.
        assertEquals("", CellValue.SharedText(9).displayText(strings))
    }

    @Test
    fun otherValueKinds_renderRaw() {
        val none = StringTable.EMPTY
        assertEquals("hello", CellValue.InlineText("hello").displayText(none))
        assertEquals("TRUE", CellValue.Bool(true).displayText(none))
        assertEquals("FALSE", CellValue.Bool(false).displayText(none))
        assertEquals("#DIV/0!", CellValue.Error("#DIV/0!").displayText(none))
    }

    @Test
    fun wholeNumbers_dropTheDecimalTail() {
        val none = StringTable.EMPTY
        // A count should read "30", not "30.0".
        assertEquals("30", CellValue.Number(30.0).displayText(none))
        assertEquals("0", CellValue.Number(0.0).displayText(none))
        assertEquals("50000", CellValue.Number(50_000.0).displayText(none))
        // Fractions keep their precision until T16's format engine takes over.
        assertEquals("12.5", CellValue.Number(12.5).displayText(none))
        assertEquals("0.75", CellValue.Number(0.75).displayText(none))
    }

    @Test
    fun zoomBuckets_quantizeToTenths() {
        assertEquals(10, CellTextCache.zoomBucketOf(1f))
        assertEquals(10, CellTextCache.zoomBucketOf(1.02f)) // same bucket, no re-measure
        assertEquals(11, CellTextCache.zoomBucketOf(1.06f))
        assertEquals(20, CellTextCache.zoomBucketOf(2f))
        assertEquals(5, CellTextCache.zoomBucketOf(0.5f))
    }
}

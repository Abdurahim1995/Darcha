package com.tikoncha.darcha.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the display-string LRU (TECH_SPEC §8). */
class FormattedValueCacheTest {

    private val styles = StyleTable(
        listOf(
            CellStyle.DEFAULT, // 0: General
            CellStyle.DEFAULT.copy(numFmtId = 4, formatCode = "#,##0.00"), // 1
            CellStyle.DEFAULT.copy(numFmtId = 14, formatCode = "mm-dd-yy", isDate = true), // 2
            CellStyle.DEFAULT.copy(numFmtId = 9, formatCode = "0%"), // 3
        ),
    )

    private fun cache(maxEntries: Int = FormattedValueCache.DEFAULT_MAX_ENTRIES) =
        FormattedValueCache(
            styles = styles,
            strings = StringTable(listOf("apple", "banana")),
            date1904 = false,
            maxEntries = maxEntries,
        )

    @Test
    fun formatsThroughTheStyleTable() {
        val cache = cache()
        assertEquals("1234.5", cache.format(CellValue.Number(1234.5), styleId = 0))
        assertEquals("1,234.50", cache.format(CellValue.Number(1234.5), styleId = 1))
        assertEquals("01-15-24", cache.format(CellValue.Number(45306.0), styleId = 2))
        assertEquals("apple", cache.format(CellValue.SharedText(0), styleId = 0))
    }

    @Test
    fun anOutOfRangeStyleId_usesTheDefaultStyle() {
        assertEquals("1234.5", cache().format(CellValue.Number(1234.5), styleId = 99))
    }

    @Test
    fun repeatedLookups_reuseOneEntry() {
        val cache = cache()
        repeat(50) { cache.format(CellValue.Number(45306.0), styleId = 2) }
        assertEquals(1, cache.size)
    }

    /**
     * The key is `(value bits, style id)`, so the same number under two formats
     * must not collapse into one entry — that would show a date as a percentage.
     */
    @Test
    fun theSameValue_underTwoStyles_isTwoEntries() {
        val cache = cache()
        val value = CellValue.Number(0.5)
        assertEquals("0.5", cache.format(value, styleId = 0))
        assertEquals("50%", cache.format(value, styleId = 3))
        assertEquals(2, cache.size)
    }

    /**
     * `Number(0.0)` and `Bool(false)` both have the bit pattern 0. Without a
     * kind tag in the key they would share an entry and one would render as the
     * other.
     */
    @Test
    fun valuesOfDifferentKinds_doNotCollide() {
        val cache = cache()
        val number = cache.format(CellValue.Number(0.0), styleId = 0)
        val boolean = cache.format(CellValue.Bool(false), styleId = 0)
        val shared = cache.format(CellValue.SharedText(0), styleId = 0)

        assertEquals("0", number)
        assertEquals("FALSE", boolean)
        assertEquals("apple", shared)
        assertNotEquals(number, boolean)
        assertEquals(3, cache.size)
    }

    @Test
    fun textAndErrorValues_arePassedThroughUncached() {
        val cache = cache()
        assertEquals("Toshkent", cache.format(CellValue.InlineText("Toshkent"), styleId = 0))
        assertEquals("#DIV/0!", cache.format(CellValue.Error("#DIV/0!"), styleId = 0))
        assertEquals("their text is already the answer", 0, cache.size)
    }

    @Test
    fun theLeastRecentlyUsedEntryIsEvicted() {
        val cache = cache(maxEntries = 3)
        cache.format(CellValue.Number(1.0), styleId = 0)
        cache.format(CellValue.Number(2.0), styleId = 0)
        cache.format(CellValue.Number(3.0), styleId = 0)
        assertEquals(3, cache.size)

        // Touch 1 so that 2 becomes the least recently used, then overflow.
        cache.format(CellValue.Number(1.0), styleId = 0)
        cache.format(CellValue.Number(4.0), styleId = 0)

        assertEquals("capacity is respected", 3, cache.size)
        // Still correct whatever was evicted — eviction must never change output.
        assertEquals("2", cache.format(CellValue.Number(2.0), styleId = 0))
        assertEquals("1", cache.format(CellValue.Number(1.0), styleId = 0))
    }

    @Test
    fun clear_dropsEverythingButNotCorrectness() {
        val cache = cache()
        cache.format(CellValue.Number(45306.0), styleId = 2)
        assertTrue(cache.size > 0)

        cache.clear()
        assertEquals(0, cache.size)
        assertEquals("01-15-24", cache.format(CellValue.Number(45306.0), styleId = 2))
    }

    @Test
    fun theEpochFlagIsFixedPerWorkbook() {
        val mac = FormattedValueCache(styles = styles, date1904 = true)
        assertEquals("01-01-24", mac.format(CellValue.Number(43830.0), styleId = 2))
        // The same serial in a 1900-epoch workbook is a different day entirely —
        // four years and a day earlier, which is the whole point of the flag.
        assertEquals("12-31-19", cache().format(CellValue.Number(43830.0), styleId = 2))
    }
}

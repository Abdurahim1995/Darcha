package com.tikoncha.darcha.feature.viewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the renderer's text helpers: column labels and the measurement cache.
 *
 * What a cell *says* is no longer decided here — `ValueFormatter` in
 * `:core:model` owns that since T16, and `ValueFormatterTest` covers it. What is
 * left is what the renderer itself is responsible for: naming columns, and never
 * handing back a measurement taken under a different style or zoom.
 */
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
    fun zoomBuckets_quantizeToTenths() {
        assertEquals(10, CellTextCache.zoomBucketOf(1f))
        assertEquals(10, CellTextCache.zoomBucketOf(1.02f)) // same bucket, no re-measure
        assertEquals(11, CellTextCache.zoomBucketOf(1.06f))
        assertEquals(20, CellTextCache.zoomBucketOf(2f))
        assertEquals(5, CellTextCache.zoomBucketOf(0.5f))
    }

    // --- the cache key (T17 added the style id) ---

    /**
     * Why the style id is in the key: bold and normal are different glyphs, so
     * reusing one measurement for the other would give the wrong width and clip
     * the text in the wrong place.
     */
    @Test
    fun theSameText_underTwoStyles_isMeasuredTwice() {
        val cache = CellTextCache<String>()
        var measurements = 0
        fun measure(text: String, styleId: Int) =
            cache.get(text, styleId, zoom = 1f) { measurements++; "$text@$styleId" }

        assertEquals("Total@0", measure("Total", 0))
        assertEquals("Total@1", measure("Total", 1))
        assertEquals(2, measurements)
        assertEquals(2, cache.size)

        // ...and each is then served from the cache.
        assertEquals("Total@0", measure("Total", 0))
        assertEquals("Total@1", measure("Total", 1))
        assertEquals("no further measuring", 2, measurements)
        assertNotEquals(measure("Total", 0), measure("Total", 1))
    }

    @Test
    fun theSameTextAndStyle_atAnotherZoom_isMeasuredAgain() {
        val cache = CellTextCache<String>()
        var measurements = 0
        cache.get("42", styleId = 0, zoom = 1f) { measurements++; "small" }
        cache.get("42", styleId = 0, zoom = 2f) { measurements++; "large" }
        assertEquals(2, measurements)
        // A nudge inside the same bucket does not re-measure.
        cache.get("42", styleId = 0, zoom = 1.02f) { measurements++; "nope" }
        assertEquals(2, measurements)
    }

    @Test
    fun headerLabels_cannotCollideWithACellStyle() {
        val cache = CellTextCache<String>()
        // A column labelled "1" and a cell containing 1 both render the text "1".
        val header = cache.get("1", CellTextCache.HEADER_STYLE_ID, 1f) { "header" }
        val cell = cache.get("1", styleId = 0, zoom = 1f) { "cell" }
        assertEquals("header", header)
        assertEquals("cell", cell)
        assertTrue("header ids are negative", CellTextCache.HEADER_STYLE_ID < 0)
    }

    @Test
    fun theLeastRecentlyUsedEntryIsEvicted() {
        val cache = CellTextCache<String>(maxEntries = 2)
        cache.get("a", 0, 1f) { "a" }
        cache.get("b", 0, 1f) { "b" }
        cache.get("a", 0, 1f) { "re-measured" } // touch a, so b becomes the oldest
        cache.get("c", 0, 1f) { "c" }

        assertEquals(2, cache.size)
        assertEquals("a survived", "a", cache.get("a", 0, 1f) { "re-measured" })
    }

    @Test
    fun hitRate_tracksWhatWasReused() {
        val cache = CellTextCache<String>()
        assertEquals("nothing asked for yet", 0f, cache.hitRate, 0f)
        cache.get("x", 0, 1f) { "x" } // miss
        cache.get("x", 0, 1f) { "x" } // hit
        cache.get("x", 0, 1f) { "x" } // hit
        assertEquals(2, cache.hits)
        assertEquals(1, cache.misses)
        assertEquals(2f / 3f, cache.hitRate, 1e-6f)

        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0, cache.hits)
    }
}

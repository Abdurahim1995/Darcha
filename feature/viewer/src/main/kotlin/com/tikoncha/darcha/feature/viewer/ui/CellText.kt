package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.ui.text.TextLayoutResult
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.StringTable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The label of a column, in A1 notation: `0` → `A`, `25` → `Z`, `26` → `AA`.
 * The letters are a bijective base-26 numeral, so there is no "zero" digit.
 */
internal fun columnLabel(index: Int): String {
    var remaining = index + 1
    val letters = StringBuilder()
    while (remaining > 0) {
        val digit = (remaining - 1) % 26
        letters.append('A' + digit)
        remaining = (remaining - 1) / 26
    }
    return letters.reverse().toString()
}

/**
 * The text to draw for a cell.
 *
 * Deliberately raw (TECH_SPEC §8 keeps values unformatted): shared strings are
 * resolved through [strings], and a number is printed without an exponent when
 * it is whole, so `30` does not render as `30.0`. Real number and date
 * formatting is the format engine's job in T16.
 */
internal fun CellValue.displayText(strings: StringTable): String = when (this) {
    is CellValue.SharedText -> strings[index].orEmpty()
    is CellValue.InlineText -> text
    is CellValue.Bool -> if (value) "TRUE" else "FALSE"
    is CellValue.Error -> code
    is CellValue.Number -> {
        val rounded = value.roundToLong()
        if (abs(value - rounded) < WHOLE_NUMBER_EPSILON) rounded.toString() else value.toString()
    }
}

private const val WHOLE_NUMBER_EPSILON = 1e-9

/**
 * A bounded LRU cache of measured text (TECH_SPEC §9 — "measuring is expensive").
 *
 * Keyed by the text and the zoom bucket, since a layout is only reusable at the
 * size it was measured for. Zoom is bucketed rather than exact so that a pinch
 * does not invalidate every entry on every frame (T20 quantizes to 0.1).
 *
 * Not thread-safe: it is touched only from the draw pass.
 *
 * @param maxEntries how many layouts to keep; the least recently used is evicted.
 */
internal class CellTextCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Key(val text: String, val zoomBucket: Int)

    /** `accessOrder = true` makes this a genuine LRU rather than insertion-ordered. */
    private val entries = object : LinkedHashMap<Key, TextLayoutResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TextLayoutResult>) =
            size > maxEntries
    }

    /** Number of cached layouts — for tests and diagnostics. */
    val size: Int get() = entries.size

    /** The layout for [text] at [zoom], measuring with [measure] on a miss. */
    fun get(text: String, zoom: Float, measure: () -> TextLayoutResult): TextLayoutResult =
        entries.getOrPut(Key(text, zoomBucketOf(zoom))) { measure() }

    /** Drop everything — used when the sheet or text style changes. */
    fun clear(): Unit = entries.clear()

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 512

        /** Zoom quantized to 0.1 steps, so nearby zooms share measurements. */
        fun zoomBucketOf(zoom: Float): Int = (zoom * 10f).roundToLong().toInt()
    }
}

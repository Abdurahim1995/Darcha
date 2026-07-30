package com.tikoncha.darcha.feature.viewer.ui

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
 * A bounded LRU cache of measured text (TECH_SPEC §9 — "measuring is expensive").
 *
 * Keyed by the text, the **style id** and the zoom bucket. The style id joined
 * the key in T17: bold, italic and colour all change the glyphs, so the same
 * string measured under two styles is two different layouts. Zoom is bucketed
 * rather than exact so that a pinch does not invalidate every entry on every
 * frame (T20 quantizes to 0.1).
 *
 * Style ids only mean anything inside one workbook, so a cache must not outlive
 * the document it was filled for.
 *
 * Not thread-safe: it is touched only from the draw pass.
 *
 * Generic in the cached type only so the keying can be unit-tested without a
 * Compose runtime; production always caches a `TextLayoutResult`.
 *
 * @param maxEntries how many layouts to keep; the least recently used is evicted.
 */
internal class CellTextCache<V>(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Key(val text: String, val styleId: Int, val zoomBucket: Int)

    /** `accessOrder = true` makes this a genuine LRU rather than insertion-ordered. */
    private val entries = object : LinkedHashMap<Key, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, V>) = size > maxEntries
    }

    /** Number of cached layouts — for tests and diagnostics. */
    val size: Int get() = entries.size

    /**
     * The layout for [text] under [styleId] at [zoom], measuring with [measure]
     * on a miss.
     *
     * Header labels pass [HEADER_STYLE_ID], which no cell style can collide with.
     */
    fun get(
        text: String,
        styleId: Int,
        zoom: Float,
        measure: () -> V,
    ): V {
        val key = Key(text, styleId, zoomBucketOf(zoom))
        val cached = entries[key]
        if (cached != null) {
            hits++
            return cached
        }
        misses++
        return measure().also { entries[key] = it }
    }

    /** Layouts served from the cache, and layouts that had to be measured. */
    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    /** Hit rate in `0f..1f`, or `0f` before anything has been asked for. */
    val hitRate: Float
        get() = if (hits + misses == 0) 0f else hits.toFloat() / (hits + misses)

    /** Drop everything, counters included. */
    fun clear() {
        entries.clear()
        hits = 0
        misses = 0
    }

    companion object {
        /**
         * How many layouts to keep.
         *
         * A portrait viewport draws at most ~480 cells, and every one of them
         * could be a distinct `(text, styleId)` pair. 512 held barely one screen
         * — fine while the key ignored style, thrashing once it did not. This is
         * roughly four screens, so scrolling back over cells just left behind
         * still hits. Measured hit rates are in docs/PERF.md.
         */
        private const val DEFAULT_MAX_ENTRIES = 2_048

        /**
         * The style id used for the row-number and column-letter strips. Cell
         * style ids are non-negative, so this cannot collide with one.
         */
        const val HEADER_STYLE_ID: Int = -1

        /** Zoom quantized to 0.1 steps, so nearby zooms share measurements. */
        fun zoomBucketOf(zoom: Float): Int = (zoom * 10f).roundToLong().toInt()
    }
}

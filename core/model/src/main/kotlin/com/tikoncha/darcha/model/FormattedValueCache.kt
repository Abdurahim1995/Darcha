package com.tikoncha.darcha.model

/**
 * An LRU cache of display strings, keyed by the raw value and the style id
 * (TECH_SPEC §8 — "computed lazily at render time and LRU-cached").
 *
 * Formatting is not free: a date walks a format code and a `General` number goes
 * through `BigDecimal`. A grid redraws the same cells on every frame of a scroll,
 * and a column usually repeats one style down its whole length, so the hit rate
 * is high.
 *
 * **The key is `(value bits, style id)`, not the formatted text**, so a lookup
 * costs no allocation. Style *ids* are only meaningful inside one workbook, and
 * so is a shared-string index — which is why the workbook's tables are fixed at
 * construction: a cache belongs to a document, and the next document gets a new
 * one. `InlineText` and `Error` values are passed straight through, since their
 * text is already the answer and hashing it would cost more than the lookup
 * saves.
 *
 * Not thread-safe: like the renderer's text cache, it is touched only from the
 * draw pass.
 *
 * @param styles the workbook's style table; an out-of-range id uses
 *   [CellStyle.DEFAULT].
 * @param strings the workbook's shared strings.
 * @param date1904 the workbook's epoch flag.
 * @param maxEntries how many strings to keep before evicting the least recently
 *   used.
 */
public class FormattedValueCache(
    private val styles: StyleTable,
    private val strings: StringTable = StringTable.EMPTY,
    private val date1904: Boolean = false,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private data class Key(val kind: Int, val bits: Long, val styleId: Int)

    /** `accessOrder = true` makes this a true LRU rather than insertion-ordered. */
    private val entries = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>): Boolean =
            size > maxEntries
    }

    /** Number of cached strings — for tests and diagnostics. */
    public val size: Int get() = entries.size

    /** The display text for [value] under the style at [styleId]. */
    public fun format(value: CellValue, styleId: Int): String {
        val key = keyOf(value, styleId)
            ?: return ValueFormatter.format(value, styleOf(styleId), strings, date1904)
        return entries.getOrPut(key) {
            ValueFormatter.format(value, styleOf(styleId), strings, date1904)
        }
    }

    /** Drop every cached string. */
    public fun clear(): Unit = entries.clear()

    private fun styleOf(styleId: Int): CellStyle = styles[styleId] ?: CellStyle.DEFAULT

    /**
     * A collision-free key, or `null` for values that are cheaper to format than
     * to look up. The kind tag matters: `Number(0.0)` and `Bool(false)` would
     * otherwise share the bit pattern `0`.
     */
    private fun keyOf(value: CellValue, styleId: Int): Key? = when (value) {
        is CellValue.Number -> Key(KIND_NUMBER, value.value.toRawBits(), styleId)
        is CellValue.Bool -> Key(KIND_BOOL, if (value.value) 1L else 0L, styleId)
        is CellValue.SharedText -> Key(KIND_SHARED, value.index.toLong(), styleId)
        is CellValue.InlineText, is CellValue.Error -> null
    }

    public companion object {
        /**
         * Default capacity. A screen holds a few hundred cells; this leaves room
         * for several screens of scrolling before anything is evicted, at a few
         * tens of bytes per entry.
         */
        public const val DEFAULT_MAX_ENTRIES: Int = 2_048

        private const val KIND_NUMBER = 0
        private const val KIND_BOOL = 1
        private const val KIND_SHARED = 2
    }
}

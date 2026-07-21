package com.tikoncha.darcha.model

/**
 * The workbook's shared string table (TECH_SPEC §7 step 3).
 *
 * Excel de-duplicates cell text into `xl/sharedStrings.xml`; a shared-string
 * cell stores an index into this table instead of the text itself. Entries are
 * in file order, so the position in [entries] is the shared-string index.
 *
 * @property entries the shared strings, in table order.
 */
public data class StringTable(
    public val entries: List<String>,
) {
    /** Number of shared strings in the table. */
    public val size: Int get() = entries.size

    /** The string at [index], or `null` if [index] is out of range. */
    public operator fun get(index: Int): String? = entries.getOrNull(index)

    public companion object {
        /** An empty table — used when the workbook has no `sharedStrings.xml`. */
        public val EMPTY: StringTable = StringTable(emptyList())
    }
}

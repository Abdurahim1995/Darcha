package com.tikoncha.darcha.feature.viewer.search

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.DateNames
import com.tikoncha.darcha.model.FormattedValueCache
import com.tikoncha.darcha.model.SheetData

/**
 * Which of a cell's two readings the query was found in (T32).
 *
 * Only [CellValue.Number] has two readings at all. Text is itself, a boolean
 * renders as `TRUE`/`FALSE`, an error renders as its code — for all of those the
 * displayed string *is* the raw string, so they can only ever report
 * [DISPLAYED]. The distinction exists for the case that motivated it: a date
 * cell displaying `01-15-24` over a stored serial of `45306`.
 */
internal enum class MatchField {
    /** Found in what the cell shows on screen. */
    DISPLAYED,

    /** Found only in the stored value — a date serial, or unrounded precision. */
    RAW,

    /** Found in both. Still **one** match: a cell is a hit at most once. */
    BOTH,
}

/**
 * Matches for one query in one sheet, in row-major order (T32).
 *
 * ## Why the cells are one `LongArray`
 *
 * T33 will ask "is this cell a match?" for every visible cell, every frame.
 * Each match is packed into a single `Long` — row, column and which reading
 * matched — so ascending order *is* row-major, the lookup is a binary search
 * over a primitive array, and there is no second array to keep parallel. No
 * boxing, no iterator, nothing allocated per frame. The same reasoning as
 * `MergeIndex` (T18), for the same reason.
 *
 * ```
 * bits 63..34  row     (the grid allows 2^20)
 * bits 33..2   column  (2^14)
 * bits  1..0   MatchField ordinal
 * ```
 *
 * The field sits in the low bits so it cannot disturb the ordering, and the
 * array is **sorted here** rather than assumed sorted. `Row.columns` is a sorted
 * `IntArray` by the model's own invariant, but a class that promises row-major
 * order and binary-searches its own array should not depend on a caller keeping
 * a promise on its behalf — the cost is one sort of the *match* list, which is
 * far shorter than the sheet.
 *
 * ## Why it remembers what it scanned
 *
 * A match list is only meaningful against the sheet it was computed from. Rows
 * arrive in chunks during a progressive parse (T15.5), and switching sheets or
 * opening another document replaces the data outright — at which point an index
 * from the old list would scroll confidently to the wrong cell.
 *
 * So the results hold the identity of the [SheetData] they scanned and [isFor]
 * answers whether they may still be used. Within a progressive parse the answer
 * is deliberately strict: a new chunk means a new `SheetData`, so results are
 * *stale* rather than merely incomplete, and the caller re-runs. That keeps a
 * match list always internally consistent with exactly one immutable snapshot,
 * which is the property that makes a stale index impossible rather than
 * unlikely.
 *
 * @property complete whether the sheet had finished parsing when it was scanned.
 *   `false` means "these are all the matches *so far*" — the count must not be
 *   presented as final.
 */
internal class SearchResults(
    val query: String,
    val complete: Boolean,
    private val cells: LongArray,
    private val scanned: SheetData,
) {
    /** Number of matching cells. A cell matches at most once. */
    val size: Int get() = cells.size

    val isEmpty: Boolean get() = cells.isEmpty()

    /** 0-based row of the match at [index]. */
    fun rowAt(index: Int): Int = (cells[index] ushr ROW_SHIFT).toInt()

    /** 0-based column of the match at [index]. */
    fun colAt(index: Int): Int = ((cells[index] ushr FIELD_BITS) and COL_MASK).toInt()

    /** Which reading matched at [index]. */
    fun fieldAt(index: Int): MatchField = MatchField.entries[(cells[index] and FIELD_MASK).toInt()]

    /**
     * Index of the match at ([row], [col]), or `-1`. Binary search over the
     * packed array — allocation-free, so the renderer may call it per cell.
     */
    fun indexOf(row: Int, col: Int): Int {
        // Compared with the field bits shifted away, so a cell is found whatever
        // reading it matched on.
        val key = cellKey(row, col)
        var low = 0
        var high = cells.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = cells[mid] ushr FIELD_BITS
            when {
                value < key -> low = mid + 1
                value > key -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * Whether these results still describe [sheet].
     *
     * Identity, not equality: two snapshots with equal contents are still
     * different scans, and comparing 350,000 cells to find that out would cost
     * more than re-running the search.
     */
    fun isFor(sheet: SheetSnapshot): Boolean = sheet.data === scanned

    companion object {
        private const val FIELD_BITS = 2
        private const val FIELD_MASK = 0b11L
        private const val ROW_SHIFT = 34
        private const val COL_MASK = 0xFFFF_FFFFL

        /** Row and column with the field bits removed — the ordering key. */
        private fun cellKey(row: Int, col: Int): Long =
            (row.toLong() shl 32) or (col.toLong() and COL_MASK)

        fun pack(row: Int, col: Int, field: MatchField): Long =
            (row.toLong() shl ROW_SHIFT) or
                ((col.toLong() and COL_MASK) shl FIELD_BITS) or
                field.ordinal.toLong()

        fun empty(query: String, sheet: SheetSnapshot, complete: Boolean): SearchResults =
            SearchResults(query, complete, LongArray(0), sheet.data)
    }
}

/**
 * Finds every cell in one sheet whose text contains the query (T32).
 *
 * ## What is matched, and why both
 *
 * **Displayed text and raw value, with one hit per cell.** Someone typing
 * `Toshkent` is reading the screen; someone typing `45306` knows the serial
 * underneath a date. Refusing either is a worse failure than a slightly wider
 * match set, and the code makes the choice cheap: [ValueFormatter] renders text
 * as itself, a boolean as `TRUE`/`FALSE` and an error as its code, so for every
 * kind except [CellValue.Number] the two readings are *the same string*. The
 * second comparison therefore only ever happens on numeric cells — exactly where
 * the date-serial case lives — and a cell that matches both ways is still one
 * match, reported as [MatchField.BOTH].
 *
 * ## Case and partiality
 *
 * **Case-insensitive substring**, both deliberate. A viewer's user is looking for
 * something half-remembered — a fragment of a name, a few digits of a total —
 * and whole-cell matching would make search nearly useless for text. Case
 * sensitivity would make it useless for anything typed in a hurry. Kotlin's
 * `contains(ignoreCase = true)` compares by region without allocating, so the
 * choice costs no garbage.
 *
 * ## The cache, and why the scan brings its own
 *
 * This is the real design pressure. The renderer's [FormattedValueCache] holds
 * 2,048 entries and is sized for a viewport; a scan of `big-50k`'s ~350,000
 * cells would evict every string the grid needs and leave it re-measuring text
 * on the next frame — search would make scrolling stutter.
 *
 * So **the scan never touches the renderer's cache.** It builds its own
 * [FormattedValueCache] and drops it when it returns. That instance still earns
 * its keep: a sheet has far fewer distinct `(value, style)` pairs than cells, so
 * repeated values format once. And text cells skip the cache entirely — a shared
 * string is a lookup, not a format, and letting it occupy cache entries would
 * waste the very capacity the numbers need.
 *
 * ## Scope
 *
 * The **active sheet only**. Cross-sheet search would have to read sheets that
 * have not been parsed yet — they are read on demand (T15) — which turns a
 * search into a parse of the whole workbook. Deliberately deferred; not an
 * oversight.
 *
 * @param isActive polled every [CANCEL_CHECK_INTERVAL] cells. Returns `null` the
 *   moment it goes false: a superseded search has no usable partial answer, and
 *   returning one invites a caller to display it. Someone typing `January`
 *   issues seven searches and six of them are dead before they finish.
 */
internal object SheetSearch {

    /** How often the cancellation flag is polled, in cells. */
    const val CANCEL_CHECK_INTERVAL: Int = 1024

    fun run(
        sheet: SheetSnapshot,
        query: String,
        names: DateNames = DateNames.ENGLISH,
        complete: Boolean = true,
        isActive: () -> Boolean = { true },
    ): SearchResults? {
        if (query.isEmpty()) return SearchResults.empty(query, sheet, complete)

        // The scan's own cache. Never the renderer's — see the class KDoc.
        val formatter = FormattedValueCache(
            styles = sheet.styles,
            strings = sheet.sharedStrings,
            date1904 = sheet.date1904,
            names = names,
        )

        val found = LongBuilder()
        var sinceCheck = 0

        // Rows are a sparse map, so this visits populated cells only — an empty
        // sheet of a million rows costs nothing. Map order is not sorted and
        // does not need to be: the matches are sorted once at the end, which is
        // a far shorter list than the rows.
        for ((rowIndex, row) in sheet.data.rows) {
            for (i in row.columns.indices) {
                if (++sinceCheck >= CANCEL_CHECK_INTERVAL) {
                    sinceCheck = 0
                    if (!isActive()) return null
                }

                val value = row.values[i]
                val field = matchOf(value, row.styleIds[i], query, sheet, formatter) ?: continue
                found.add(SearchResults.pack(rowIndex, row.columns[i], field))
            }
        }

        return SearchResults(
            query = query,
            complete = complete,
            cells = found.sorted(),
            scanned = sheet.data,
        )
    }

    /**
     * Which reading of [value] contains [query], or `null` for no match.
     *
     * Text never reaches the formatter: its displayed string *is* its stored
     * string, so resolving it directly is both faster and kinder to the cache.
     */
    private fun matchOf(
        value: CellValue,
        styleId: Int,
        query: String,
        sheet: SheetSnapshot,
        formatter: FormattedValueCache,
    ): MatchField? = when (value) {
        is CellValue.SharedText ->
            if (sheet.sharedStrings[value.index].orEmpty().contains(query, ignoreCase = true)) {
                MatchField.DISPLAYED
            } else {
                null
            }

        is CellValue.InlineText ->
            if (value.text.contains(query, ignoreCase = true)) MatchField.DISPLAYED else null

        // The only kind with two readings. Everything else renders as itself.
        is CellValue.Number -> {
            val displayed = formatter.format(value, styleId).contains(query, ignoreCase = true)
            val raw = rawNumberText(value.value).contains(query, ignoreCase = true)
            when {
                displayed && raw -> MatchField.BOTH
                displayed -> MatchField.DISPLAYED
                raw -> MatchField.RAW
                else -> null
            }
        }

        is CellValue.Bool, is CellValue.Error ->
            if (formatter.format(value, styleId).contains(query, ignoreCase = true)) {
                MatchField.DISPLAYED
            } else {
                null
            }
    }

    /**
     * The stored number as a searchable string.
     *
     * An integral value is written without the `.0` a `Double` would carry, so
     * the serial behind a date reads as `45306` — the form a user would type.
     * Anything else keeps its full precision, which is the point of searching the
     * raw value at all: a cell displaying `1,234.57` still finds `1234.5678`.
     */
    private fun rawNumberText(value: Double): String =
        if (value % 1.0 == 0.0 && value.isFinite() && value in SAFE_INTEGRAL) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    /** Doubles outside this cannot round-trip through `Long` exactly. */
    private val SAFE_INTEGRAL = -9.007199254740992E15..9.007199254740992E15
}

/**
 * A growable `LongArray`.
 *
 * `ArrayList<Long>` would box every match, and a broad query over a large sheet
 * can match hundreds of thousands of cells — the one case where the result set
 * is as big as the problem. Fifteen lines to avoid that is the same trade
 * `MergeIndex` and the sparse model already make.
 */
private class LongBuilder(initialCapacity: Int = 16) {
    private var items = LongArray(initialCapacity)
    private var size = 0

    fun add(value: Long) {
        if (size == items.size) items = items.copyOf(items.size * 2)
        items[size++] = value
    }

    /** The contents, right-sized and ascending. */
    fun sorted(): LongArray = items.copyOf(size).also { it.sort() }
}

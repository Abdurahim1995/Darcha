package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.Worksheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden lock for the **Google Sheets** corpus (T30) — files exported by
 * `File → Download → Microsoft Excel (.xlsx)`, not written by this project.
 *
 * Every expected value here was read out of the committed files. None of it was
 * copied from the `excel/` equivalents, and that restraint is the entire point:
 * the two producers were given the same content on purpose, so anywhere they
 * disagree is variance the parser has to absorb. Assuming they agree would have
 * turned this suite into a very expensive way of testing nothing.
 *
 * The divergences these files actually exposed are tabulated in `FIXTURES.md`.
 * Two are worth knowing before reading the assertions:
 *
 * - Google Sheets stores `12,5` as **text**, not a number, because the sheet's
 *   locale uses `.` as the decimal separator. That is the file being honest
 *   about what was typed, and the parser must report it as typed.
 * - Every `<pane>` split is written as a **decimal** (`ySplit="2.0"`). Excel
 *   writes `ySplit="2"`. See [frozenRowsSurviveADecimalSplit].
 */
class GoogleSheetsFixturesTest {

    // --- values-basic.xlsx ---

    @Test
    fun valuesBasic_sheetNameAndEpoch() {
        open("values-basic.xlsx").use { wb ->
            // Google Sheets names the first sheet "Sheet1"; Excel Online, in the
            // Russian UI these fixtures were made with, named it "Лист1".
            assertEquals(listOf("Sheet1"), wb.sheets.map { it.name })
            assertFalse(wb.date1904)
        }
    }

    @Test
    fun valuesBasic_headerRowIsSharedText() {
        open("values-basic.xlsx").use { wb ->
            val sheet = read(wb, 0)
            assertEquals("Product", text(wb, sheet, 0, 0))
            assertEquals("Price", text(wb, sheet, 0, 1))
            assertEquals("InStock", text(wb, sheet, 0, 2))
        }
    }

    @Test
    fun valuesBasic_booleansAreRealBooleans() {
        open("values-basic.xlsx").use { wb ->
            val sheet = read(wb, 0)
            assertEquals(CellValue.Bool(true), sheet.data.cellAt(1, 2))
            assertEquals(CellValue.Bool(false), sheet.data.cellAt(2, 2))
            assertEquals(CellValue.Bool(true), sheet.data.cellAt(3, 2))
        }
    }

    /**
     * **A real producer divergence, and the reason this corpus exists.**
     *
     * `B2` and `B4` were typed as `12,5` and `0,75` — a comma decimal separator,
     * which is how prices are written across the region this app is for. The
     * sheet's locale uses `.`, so Google Sheets did **not** recognise them as
     * numbers and stored them as text. `B3`, typed as `300`, is a number.
     *
     * The parser's job is to report what the file says, not to guess what the
     * author meant. A viewer that "helpfully" reinterpreted `12,5` as `12.5`
     * would be inventing data, and would get it wrong in every locale where the
     * comma is a thousands separator.
     */
    @Test
    fun valuesBasic_commaDecimalsAreTextNotNumbers() {
        open("values-basic.xlsx").use { wb ->
            val sheet = read(wb, 0)

            assertTrue(
                "B2 is stored as text by the producer",
                sheet.data.cellAt(1, 1) is CellValue.SharedText,
            )
            assertEquals("12,5", text(wb, sheet, 1, 1))
            assertEquals("0,75", text(wb, sheet, 3, 1))

            // ...while a value the locale did accept is a genuine number.
            assertEquals(CellValue.Number(300.0), sheet.data.cellAt(2, 1))
        }
    }

    @Test
    fun valuesBasic_sharedStringTableIsExact() {
        open("values-basic.xlsx").use { wb ->
            assertEquals(8, wb.sharedStrings.size)
            assertEquals(
                listOf("Product", "Price", "InStock", "Olma", "12,5", "Anor", "Uzum", "0,75"),
                (0 until wb.sharedStrings.size).map { wb.sharedStrings[it] },
            )
        }
    }

    /**
     * Google Sheets always writes `<sheetFormatPr>` with its own defaults, where
     * Excel omits the element on an untouched sheet. `12.63` is not the OOXML
     * default of `8.43`, so a viewer that ignored it would lay every Google
     * Sheets file out too narrow.
     */
    @Test
    fun valuesBasic_producerDefaultsAreReadNotAssumed() {
        open("values-basic.xlsx").use { wb ->
            val layout = read(wb, 0).layout
            assertEquals(12.63, layout.defaultColWidth!!, 0.001)
            assertEquals(15.75, layout.defaultRowHeight!!, 0.001)
            // No column or row is individually sized — only the defaults differ.
            assertTrue(layout.columnWidths.isEmpty())
            assertTrue(layout.rowHeights.isEmpty())
        }
    }

    // --- merged-frozen.xlsx ---

    @Test
    fun mergedFrozen_mergeSpansTheTitleRow() {
        open("merged-frozen.xlsx").use { wb ->
            val sheet = read(wb, 0)
            assertEquals(
                listOf(CellRange(startRow = 0, startCol = 0, endRow = 0, endCol = 2)),
                sheet.layout.merges,
            )
        }
    }

    /**
     * The merged row carries **no cell at all** — `<sheetData>` starts at row 2.
     *
     * Google Sheets omits an empty row entirely rather than writing an empty
     * `<row>`, even when a merge references it. So a merge can point at a row the
     * sparse model has never heard of, and the renderer has to survive that: it
     * draws the span from the anchor's geometry, which exists whether or not a
     * value does.
     */
    @Test
    fun mergedFrozen_theMergedRowHasNoCells() {
        open("merged-frozen.xlsx").use { wb ->
            val sheet = read(wb, 0)
            assertNull("row 1 of the file is absent from <sheetData>", sheet.data.row(0))
            assertNull(sheet.data.cellAt(0, 0))
        }
    }

    /**
     * **Frozen rows must survive a decimal split value.**
     *
     * Google Sheets writes `<pane ySplit="2.0" .../>`; Excel writes `ySplit="2"`.
     * Both mean two frozen rows. Nothing in the file is ambiguous — this is
     * purely a difference in how the number is spelled.
     *
     * Asserted at the value the file states, not at what the parser currently
     * returns. If this fails, the frozen panes are being dropped on the floor for
     * every Google Sheets export, and the assertion is right while the code is
     * wrong.
     */
    @Test
    fun frozenRowsSurviveADecimalSplit() {
        open("merged-frozen.xlsx").use { wb ->
            assertEquals(
                FrozenPanes(frozenCols = 0, frozenRows = 2),
                read(wb, 0).layout.frozenPanes,
            )
        }
    }

    @Test
    fun mergedFrozen_dataRowsAndTypes() {
        open("merged-frozen.xlsx").use { wb ->
            val sheet = read(wb, 0)
            // File row 2 is the header; rows 3-5 are data. 0-based here.
            assertEquals("Nomi", text(wb, sheet, 1, 0))
            assertEquals("Soni", text(wb, sheet, 1, 1))
            assertEquals("Narxi", text(wb, sheet, 1, 2))

            assertEquals("Olma", text(wb, sheet, 2, 0))
            assertEquals(CellValue.Number(10.0), sheet.data.cellAt(2, 1))
            assertEquals(CellValue.Number(5000.0), sheet.data.cellAt(2, 2))

            assertEquals("Anor", text(wb, sheet, 3, 0))
            assertEquals(CellValue.Number(25.0), sheet.data.cellAt(3, 1))
            assertEquals(CellValue.Number(8000.0), sheet.data.cellAt(3, 2))

            assertEquals("Uzum", text(wb, sheet, 4, 0))
            assertEquals(CellValue.Number(7.0), sheet.data.cellAt(4, 1))
            assertEquals(CellValue.Number(3000.0), sheet.data.cellAt(4, 2))
        }
    }

    // --- uzbek-text.xlsx ---

    /**
     * The sheet name is Uzbek and is asserted as an exact literal. A producer
     * that mangled non-ASCII names, or a parser that normalised them, would show
     * up here rather than in a screenshot.
     */
    @Test
    fun uzbekText_sheetNameIsExact() {
        open("uzbek-text.xlsx").use { wb ->
            assertEquals(listOf("Jadval 1"), wb.sheets.map { it.name })
        }
    }

    /**
     * Uzbek Latin uses an apostrophe in `oʻ` and `gʻ`. These files carry the
     * plain ASCII `U+0027`, not the modifier letter `U+02BB` or the typographic
     * `U+2019` — checked byte by byte, and asserted as such, because a silent
     * substitution by either the producer or the parser is exactly the kind of
     * thing that only shows up in someone else's language.
     */
    @Test
    fun uzbekText_apostrophesAreAsciiAndUnchanged() {
        open("uzbek-text.xlsx").use { wb ->
            val sheet = read(wb, 0)
            assertEquals("O'zbekiston", text(wb, sheet, 0, 0))
            assertEquals("Farg'ona", text(wb, sheet, 3, 0))

            assertEquals(
                "the apostrophe must stay U+0027",
                0x27,
                text(wb, sheet, 0, 0)!![1].code,
            )
        }
    }

    @Test
    fun uzbekText_allFiveCitiesInOrder() {
        open("uzbek-text.xlsx").use { wb ->
            val sheet = read(wb, 0)
            assertEquals(
                listOf("O'zbekiston", "Toshkent", "Namangan", "Farg'ona", "Andijon"),
                (0..4).map { text(wb, sheet, it, 0) },
            )
            assertEquals(5, sheet.data.cellCount)
        }
    }

    // --- what every file in this corpus has in common ---

    /**
     * Google Sheets writes parts the synthetic and Excel corpora never produced:
     * a `xl/drawings/drawing1.xml` and a `<drawing>` reference on **every** sheet
     * even when nothing is drawn, plus `xl/persons/person.xml` and a full
     * `xl/theme/theme1.xml`.
     *
     * The parser reads none of them, and that is the assertion: an unknown part
     * must be ignored rather than treated as a reason to fail. Every one of these
     * files opening at all is that guarantee under test.
     */
    @Test
    fun everyFileOpensDespiteUnreadParts() {
        for (name in FIXTURES) {
            open(name).use { wb ->
                assertTrue("$name has no sheets", wb.sheets.isNotEmpty())
                assertTrue("$name sheet 0 unreadable", wb.readSheet(0) is ParseResult.Ok)
            }
        }
    }

    /**
     * Not one of these files uses an inline string: Google Sheets de-duplicates
     * into a shared table, like Excel and unlike openpyxl. Worth locking, because
     * it means the shared-string path is the one this corpus exercises.
     */
    @Test
    fun everyStringIsShared_neverInline() {
        for (name in FIXTURES) {
            open(name).use { wb ->
                val sheet = read(wb, 0)
                for ((_, row) in sheet.data.rows) {
                    for (value in row.values) {
                        assertFalse(
                            "$name should not contain an inline string",
                            value is CellValue.InlineText,
                        )
                    }
                }
            }
        }
    }

    // --- helpers ---

    private val FIXTURES = listOf("values-basic.xlsx", "merged-frozen.xlsx", "uzbek-text.xlsx")

    private fun open(name: String): Workbook =
        when (val result = XlsxParser.open(fixtureFile(name))) {
            is ParseResult.Ok -> result.value
            is ParseResult.Err -> error("open($name) failed: ${result.kind}")
        }

    private fun read(workbook: Workbook, index: Int): Worksheet =
        when (val result = workbook.readSheet(index)) {
            is ParseResult.Ok -> result.value
            is ParseResult.Err -> error("readSheet($index) failed: ${result.kind}")
        }

    /** Resolve a cell to its display text, following shared string indices. */
    private fun text(workbook: Workbook, sheet: Worksheet, row: Int, col: Int): String? =
        when (val value = sheet.data.cellAt(row, col)) {
            is CellValue.SharedText -> workbook.sharedStrings[value.index]
            is CellValue.InlineText -> value.text
            else -> null
        }

    private fun fixtureFile(name: String): File {
        val tmp = File.createTempFile("darcha-gsheets", ".xlsx")
        tmp.deleteOnExit()
        javaClass.getResourceAsStream("/fixtures/gsheets/$name")!!.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        return tmp
    }
}

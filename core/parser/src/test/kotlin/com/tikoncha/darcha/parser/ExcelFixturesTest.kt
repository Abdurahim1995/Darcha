package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.Color
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.HorizontalAlignment
import com.tikoncha.darcha.model.VerticalAlignment
import com.tikoncha.darcha.model.Worksheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden lock for the real-producer corpus: files written by **Microsoft Excel
 * Online** (see docs/FIXTURE_RECIPES.md), not by this project's tooling.
 *
 * Every expected value here was read out of the committed files, never typed
 * from memory. Assertions are deliberately exact — including sheet names, which
 * differ per file because of how each was created. A future regeneration that
 * changes any of this should fail loudly rather than silently drift.
 *
 * These files exercise producer variance the synthetic corpus cannot: Excel
 * de-duplicates text into a shared string table (openpyxl always writes
 * `inlineStr`), picks different builtin number-format ids, and emits its own
 * color palette.
 */
class ExcelFixturesTest {

    // --- values-basic.xlsx: text, numbers, real booleans ---

    @Test
    fun valuesBasic_textNumbersAndBooleans() {
        open("values-basic.xlsx").use { wb ->
            // This file was seeded from a workbook whose sheet was named "Sheet",
            // and Excel preserved that name on save.
            assertEquals(listOf("Sheet"), wb.sheets.map { it.name })
            assertFalse(wb.date1904)

            val sheet = read(wb, 0)
            assertEquals(12, sheet.data.cellCount)

            // Header row — Excel stores text as shared strings.
            assertEquals("Product", text(wb, sheet, 0, 0))
            assertEquals("Price", text(wb, sheet, 0, 1))
            assertEquals("InStock", text(wb, sheet, 0, 2))

            // Numbers keep full precision.
            assertEquals(CellValue.Number(12.5), sheet.data.cellAt(1, 1))
            assertEquals(CellValue.Number(300.0), sheet.data.cellAt(2, 1))
            assertEquals(CellValue.Number(0.75), sheet.data.cellAt(3, 1))

            // Real booleans (t="b"), not the strings "TRUE"/"FALSE".
            assertEquals(CellValue.Bool(true), sheet.data.cellAt(1, 2))
            assertEquals(CellValue.Bool(false), sheet.data.cellAt(2, 2))
            assertEquals(CellValue.Bool(true), sheet.data.cellAt(3, 2))

            assertEquals("Olma", text(wb, sheet, 1, 0))
            assertEquals("Uzum", text(wb, sheet, 3, 0))
        }
    }

    // --- strings.xlsx: the shared string table, de-duplicated by Excel ---

    @Test
    fun strings_sharedTableIsDeduplicated() {
        open("strings.xlsx").use { wb ->
            assertEquals(listOf(SHEET_RU), wb.sheets.map { it.name })

            // Six cells, three unique strings — Excel de-duplicates.
            assertEquals(listOf("apple", "banana", "cherry"), wb.sharedStrings.entries)

            val sheet = read(wb, 0)
            assertEquals(6, sheet.data.cellCount)

            // The repeated values reuse the same index.
            assertEquals(CellValue.SharedText(0), sheet.data.cellAt(0, 0))
            assertEquals(CellValue.SharedText(1), sheet.data.cellAt(1, 0))
            assertEquals(CellValue.SharedText(0), sheet.data.cellAt(2, 0))
            assertEquals(CellValue.SharedText(2), sheet.data.cellAt(3, 0))
            assertEquals(CellValue.SharedText(0), sheet.data.cellAt(5, 0))

            assertEquals(
                listOf("apple", "banana", "apple", "cherry", "banana", "apple"),
                (0..5).map { text(wb, sheet, it, 0) },
            )
        }
    }

    // --- styles-basic.xlsx: fonts, fill, alignment as Excel writes them ---

    @Test
    fun stylesBasic_fontsFillAndAlignment() {
        open("styles-basic.xlsx").use { wb ->
            assertEquals(listOf(SHEET_RU), wb.sheets.map { it.name })
            val sheet = read(wb, 0)

            val bold = styleOf(wb, sheet, 0, 0)
            assertTrue(bold.bold)
            assertFalse(bold.italic)

            val italic = styleOf(wb, sheet, 1, 0)
            assertTrue(italic.italic)
            assertFalse(italic.bold)

            // Excel picked its own red from the color picker, not pure FFFF0000.
            assertEquals(Color(0xFFFB0007.toInt()), styleOf(wb, sheet, 2, 0).fontColor)

            // Likewise its own yellow for the solid fill.
            assertEquals(Color(0xFFFFFF0B.toInt()), styleOf(wb, sheet, 3, 0).fillColor)

            // Excel writes an explicit vertical alignment alongside horizontal.
            val center = styleOf(wb, sheet, 4, 0)
            assertEquals(HorizontalAlignment.CENTER, center.horizontalAlignment)
            assertEquals(VerticalAlignment.CENTER, center.verticalAlignment)

            val right = styleOf(wb, sheet, 5, 0)
            assertEquals(HorizontalAlignment.RIGHT, right.horizontalAlignment)
            assertEquals(VerticalAlignment.CENTER, right.verticalAlignment)

            assertEquals("Bold", text(wb, sheet, 0, 0))
            assertEquals("Right", text(wb, sheet, 5, 0))
        }
    }

    // --- merged.xlsx: a merged title row ---

    @Test
    fun merged_titleRowSpansThreeColumns() {
        open("merged.xlsx").use { wb ->
            assertEquals(listOf(SHEET_RU), wb.sheets.map { it.name })
            val sheet = read(wb, 0)

            assertEquals(listOf(CellRange(0, 0, 0, 2)), sheet.layout.merges) // A1:C1
            assertEquals(FrozenPanes.NONE, sheet.layout.frozenPanes)

            // Only the anchor cell carries the value; the covered cells are empty.
            assertEquals("Hisobot", text(wb, sheet, 0, 0))
            assertEquals(null, sheet.data.cellAt(0, 1))
            assertEquals(null, sheet.data.cellAt(0, 2))

            assertEquals(10, sheet.data.cellCount)
            assertEquals("Narxi", text(wb, sheet, 1, 2))
            assertEquals(CellValue.Number(5000.0), sheet.data.cellAt(2, 2))
        }
    }

    // --- frozen.xlsx: Excel Online froze the column only ---

    @Test
    fun frozen_columnOnlyPane() {
        open("frozen.xlsx").use { wb ->
            assertEquals(listOf(SHEET_RU), wb.sheets.map { it.name })
            val sheet = read(wb, 0)

            // Excel emitted <pane xSplit="1" state="frozen" topLeftCell="B1"/> —
            // note there is NO ySplit attribute, so only the first column is
            // frozen even though the recipe aimed at row+column. Locked as-is:
            // a missing ySplit must parse as zero, not crash.
            assertEquals(FrozenPanes(frozenCols = 1, frozenRows = 0), sheet.layout.frozenPanes)
            assertEquals(emptyList<CellRange>(), sheet.layout.merges)

            assertEquals(12, sheet.data.cellCount)
            assertEquals("Nomi", text(wb, sheet, 0, 0))
            assertEquals(CellValue.Number(30.0), sheet.data.cellAt(3, 1))
            assertEquals(CellValue.Number(3000.0), sheet.data.cellAt(3, 2))
        }
    }

    // --- dates.xlsx: builtin date formats chosen by Excel ---

    @Test
    fun dates_builtinNumberFormats() {
        open("dates.xlsx").use { wb ->
            assertEquals(listOf(SHEET_RU), wb.sheets.map { it.name })
            assertFalse(wb.date1904) // 1900 epoch
            val sheet = read(wb, 0)

            assertEquals(4, sheet.data.cellCount)

            // Dates are plain numbers; date-ness comes from the style.
            assertEquals(CellValue.Number(45306.0), sheet.data.cellAt(0, 0))
            assertEquals(CellValue.Number(0.5625), sheet.data.cellAt(1, 0))
            assertEquals(CellValue.Number(45306.5625), sheet.data.cellAt(2, 0))
            assertEquals(CellValue.Number(45657.0), sheet.data.cellAt(3, 0))

            // Excel chose builtin 14 / 20 / 22. Producer variance: for the same
            // "13:30" the synthetic (openpyxl) fixture uses 21 (h:mm:ss) while
            // Excel uses 20 (h:mm).
            assertEquals(14, styleOf(wb, sheet, 0, 0).numFmtId)
            assertEquals(20, styleOf(wb, sheet, 1, 0).numFmtId)
            assertEquals(22, styleOf(wb, sheet, 2, 0).numFmtId)
            assertEquals(14, styleOf(wb, sheet, 3, 0).numFmtId)

            // All four must be detected as dates/times.
            for (row in 0..3) {
                assertTrue("row $row should be a date", styleOf(wb, sheet, row, 0).isDate)
            }
            assertEquals("h:mm", styleOf(wb, sheet, 1, 0).formatCode)
        }
    }

    // --- uzbek-text.xlsx: UTF-8 content and a non-ASCII sheet name ---

    @Test
    fun uzbekText_utf8ContentAndSheetName() {
        open("uzbek-text.xlsx").use { wb ->
            // The one file whose tab was renamed away from Excel's default.
            assertEquals(listOf("Jadval 1"), wb.sheets.map { it.name })

            val sheet = read(wb, 0)
            assertEquals(
                listOf("O'zbekiston", "Toshkent", "Farg'ona", "Namangan", "Andijon"),
                (0..4).map { text(wb, sheet, it, 0) },
            )

            // Locked reality: the paste left a block of cells (A1:E10) holding a
            // lone newline string, which Excel de-duplicated into index 1. Junk
            // like this is exactly what real files contain, so it is asserted
            // rather than cleaned away.
            assertEquals("\n", wb.sharedStrings[1])
            assertEquals(50, sheet.data.cellCount)
            assertEquals(CellValue.SharedText(1), sheet.data.cellAt(0, 1))
        }
    }

    // --- corpus-wide invariants ---

    @Test
    fun everyExcelFixture_opensAndReadsEverySheet() {
        for (name in FIXTURES) {
            open(name).use { wb ->
                assertTrue("$name should declare a sheet", wb.sheets.isNotEmpty())
                for (index in wb.sheets.indices) {
                    val sheet = read(wb, index)
                    assertTrue(
                        "$name sheet $index should resolve under xl/worksheets/",
                        wb.sheets[index].partPath.startsWith("xl/worksheets/"),
                    )
                    assertTrue("$name sheet $index should have cells", sheet.data.cellCount > 0)
                }
            }
        }
    }

    @Test
    fun excelAlwaysUsesSharedStrings_unlikeTheSyntheticCorpus() {
        // Producer variance in one assertion: every Excel file with text uses a
        // shared string table, whereas openpyxl emits inlineStr and no table.
        for (name in FIXTURES - "dates.xlsx") {
            open(name).use { wb ->
                assertTrue(
                    "$name should carry a shared string table",
                    wb.sharedStrings.size > 0,
                )
            }
        }
        // dates.xlsx has no text at all, hence no shared strings.
        open("dates.xlsx").use { wb -> assertEquals(0, wb.sharedStrings.size) }
    }

    // --- helpers ---

    /** Excel Online's default sheet name, in the Russian UI the files were made with. */
    private val SHEET_RU = "Лист1"

    private val FIXTURES = listOf(
        "values-basic.xlsx", "strings.xlsx", "styles-basic.xlsx",
        "merged.xlsx", "frozen.xlsx", "dates.xlsx", "uzbek-text.xlsx",
    )

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

    private fun styleOf(workbook: Workbook, sheet: Worksheet, row: Int, col: Int) =
        workbook.styles[sheet.data.styleIdAt(row, col)!!]!!

    private fun fixtureFile(name: String): File {
        val tmp = File.createTempFile("darcha-excel", ".xlsx")
        tmp.deleteOnExit()
        javaClass.getResourceAsStream("/fixtures/excel/$name")!!.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        return tmp
    }
}

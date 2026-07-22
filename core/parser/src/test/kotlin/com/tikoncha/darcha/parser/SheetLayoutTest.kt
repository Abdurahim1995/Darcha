package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.SheetLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/** Golden + inline-XML tests for worksheet layout parsing (TECH_SPEC §7, §9). */
class SheetLayoutTest {

    // --- golden: merged.xlsx ---

    @Test
    fun merged_threeRangesAndNoFreeze() {
        val layout = parseFixture("merged.xlsx", "xl/worksheets/sheet1.xml")
        assertEquals(
            listOf(
                CellRange(0, 0, 0, 2), // A1:C1
                CellRange(1, 0, 3, 0), // A2:A4
                CellRange(1, 1, 2, 2), // B2:C3
            ),
            layout.merges,
        )
        assertEquals(FrozenPanes.NONE, layout.frozenPanes)
    }

    // --- golden: frozen.xlsx ---

    @Test
    fun frozen_firstRowAndColumn() {
        val layout = parseFixture("frozen.xlsx", "xl/worksheets/sheet1.xml")
        assertEquals(FrozenPanes(frozenCols = 1, frozenRows = 1), layout.frozenPanes)
        assertEquals(emptyList<CellRange>(), layout.merges)
    }

    // --- inline-XML: cols, row heights, sheet defaults ---

    @Test
    fun cols_customWidthsExpandedAcrossRange() {
        val xml = """
            <worksheet xmlns="$MAIN_NS">
              <sheetFormatPr defaultColWidth="10" defaultRowHeight="18"/>
              <cols>
                <col min="2" max="3" width="15.5" customWidth="1"/>
                <col min="5" max="5" width="40" customWidth="1"/>
                <col min="7" max="7" width="99"/>
              </cols>
              <sheetData>
                <row r="1" ht="30" customHeight="1"><c r="A1"><v>1</v></c></row>
                <row r="2"><c r="A2"><v>2</v></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val layout = SheetParser.parseSheet(bytes(xml)).layout

        assertEquals(10.0, layout.defaultColWidth, 0.0)
        assertEquals(18.0, layout.defaultRowHeight, 0.0)
        // min/max are 1-based; stored 0-based. col 7 has no customWidth → skipped.
        assertEquals(
            mapOf(1 to 15.5, 2 to 15.5, 4 to 40.0),
            layout.columnWidths,
        )
        // Only the row with an explicit ht is recorded (row index 0).
        assertEquals(mapOf(0 to 30.0), layout.rowHeights)
    }

    @Test
    fun defaults_whenSheetFormatPrAbsent() {
        val xml = """<worksheet xmlns="$MAIN_NS"><sheetData/></worksheet>"""
        val layout = SheetParser.parseSheet(bytes(xml)).layout
        assertEquals(SheetLayout.DEFAULT_COL_WIDTH, layout.defaultColWidth, 0.0)
        assertEquals(SheetLayout.DEFAULT_ROW_HEIGHT, layout.defaultRowHeight, 0.0)
        assertEquals(FrozenPanes.NONE, layout.frozenPanes)
    }

    @Test
    fun pane_splitStateIsNotFrozen() {
        val xml = """
            <worksheet xmlns="$MAIN_NS">
              <sheetViews><sheetView><pane xSplit="2" ySplit="0" state="split"/></sheetView></sheetViews>
              <sheetData/>
            </worksheet>
        """.trimIndent()
        val layout = SheetParser.parseSheet(bytes(xml)).layout
        assertEquals(FrozenPanes.NONE, layout.frozenPanes)
    }

    // --- helpers ---

    private fun parseFixture(name: String, part: String): SheetLayout =
        fixtureZip(name).use { zip ->
            when (val result = SheetParser.parse(zip, part)) {
                is ParseResult.Ok -> result.value.layout
                is ParseResult.Err -> error("expected Ok for $name but got Err(${result.kind})")
            }
        }

    private fun fixtureZip(name: String): ZipFile {
        val tmp = File.createTempFile("darcha-fixture", ".xlsx")
        tmp.deleteOnExit()
        javaClass.getResourceAsStream("/fixtures/synthetic/$name")!!.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        return ZipFile(tmp)
    }

    private fun bytes(xml: String) = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))

    private companion object {
        const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    }
}

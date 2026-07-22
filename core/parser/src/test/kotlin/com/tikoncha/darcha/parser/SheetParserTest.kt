package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.SheetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/** Golden + inline-XML tests for sheet parsing into the sparse model. */
class SheetParserTest {

    // --- golden: values-basic (numbers, inline text, booleans) ---

    @Test
    fun valuesBasic_typesAndCoordinates() {
        val sheet = parseFixture("values-basic.xlsx", "xl/worksheets/sheet1.xml")
        assertEquals(12, sheet.cellCount)
        assertEquals(CellValue.InlineText("Name"), sheet.cellAt(0, 0))
        assertEquals(CellValue.InlineText("Alice"), sheet.cellAt(1, 0))
        assertEquals(CellValue.Number(30.0), sheet.cellAt(1, 1))
        assertEquals(CellValue.Bool(true), sheet.cellAt(1, 2))
        assertEquals(CellValue.Number(25.5), sheet.cellAt(2, 1))
        assertEquals(CellValue.Bool(false), sheet.cellAt(2, 2))
        assertEquals(CellValue.Number(0.0), sheet.cellAt(3, 1))
    }

    // --- golden: sparse-gaps (exactly three cells, exact coordinates) ---

    @Test
    fun sparseGaps_exactlyThreeCellsAtExactCoordinates() {
        val sheet = parseFixture("sparse-gaps.xlsx", "xl/worksheets/sheet1.xml")
        assertEquals(3, sheet.cellCount)
        assertEquals(CellValue.InlineText("start"), sheet.cellAt(0, 0))   // A1
        assertEquals(CellValue.Number(42.0), sheet.cellAt(4, 2))          // C5
        assertEquals(CellValue.InlineText("end"), sheet.cellAt(99, 26))   // AA100
        // Gaps are truly empty — no allocation.
        assertNull(sheet.cellAt(0, 1))
        assertNull(sheet.cellAt(50, 10))
        assertNull(sheet.row(1))
    }

    // --- golden: strings-shared (shared string indices, not resolved text) ---

    @Test
    fun stringsShared_sharedStringIndices() {
        val sheet = parseFixture("strings-shared.xlsx", "xl/worksheets/sheet1.xml")
        assertEquals(7, sheet.cellCount)
        assertEquals(CellValue.SharedText(0), sheet.cellAt(0, 0)) // fruit
        assertEquals(CellValue.SharedText(1), sheet.cellAt(1, 0)) // apple
        assertEquals(CellValue.SharedText(1), sheet.cellAt(3, 0)) // apple again
        assertEquals(CellValue.SharedText(3), sheet.cellAt(4, 0)) // cherry
    }

    // --- inline-XML unit tests ---

    @Test
    fun errorAndFormulaStringAndRichInline() {
        val xml = """
            <worksheet xmlns="$MAIN_NS"><sheetData>
              <row r="1">
                <c r="A1" t="e"><v>#DIV/0!</v></c>
                <c r="B1" t="str"><f>A1&amp;""</f><v>result</v></c>
                <c r="C1" t="inlineStr"><is><r><t>Hello </t></r><r><t>World</t></r></is></c>
              </row>
            </sheetData></worksheet>
        """.trimIndent()
        val sheet = SheetParser.parseSheet(bytes(xml)).data
        assertEquals(CellValue.Error("#DIV/0!"), sheet.cellAt(0, 0))
        assertEquals(CellValue.InlineText("result"), sheet.cellAt(0, 1)) // <f> skipped, cached <v> read
        assertEquals(CellValue.InlineText("Hello World"), sheet.cellAt(0, 2))
    }

    @Test
    fun formulaNumber_readsCachedValueNotFormula() {
        val xml = """
            <worksheet xmlns="$MAIN_NS"><sheetData>
              <row r="1"><c r="A1"><f>1+1</f><v>2</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        val sheet = SheetParser.parseSheet(bytes(xml)).data
        assertEquals(CellValue.Number(2.0), sheet.cellAt(0, 0))
    }

    @Test
    fun styledEmptyCell_isNotStored() {
        val xml = """
            <worksheet xmlns="$MAIN_NS"><sheetData>
              <row r="1"><c r="A1" s="3"/><c r="B1"><v>5</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        val sheet = SheetParser.parseSheet(bytes(xml)).data
        assertEquals(1, sheet.cellCount)
        assertNull(sheet.cellAt(0, 0))
        assertEquals(CellValue.Number(5.0), sheet.cellAt(0, 1))
    }

    @Test
    fun cellsOutOfColumnOrder_areSorted() {
        val xml = """
            <worksheet xmlns="$MAIN_NS"><sheetData>
              <row r="1"><c r="C1"><v>3</v></c><c r="A1"><v>1</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        val sheet = SheetParser.parseSheet(bytes(xml)).data
        assertEquals(intArrayOf(0, 2).toList(), sheet.row(0)!!.columns.toList())
        assertEquals(CellValue.Number(1.0), sheet.cellAt(0, 0))
        assertEquals(CellValue.Number(3.0), sheet.cellAt(0, 2))
    }

    // --- helpers ---

    private fun parseFixture(name: String, part: String): SheetData =
        fixtureZip(name).use { zip ->
            when (val result = SheetParser.parse(zip, part)) {
                is ParseResult.Ok -> result.value.data
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

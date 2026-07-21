package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.SheetRef
import com.tikoncha.darcha.model.WorkbookMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Golden tests for workbook + relationship parsing (TECH_SPEC §7 step 2), plus
 * inline-XML unit tests for the streaming helpers and path resolution.
 */
class WorkbookParserTest {

    // --- golden tests on real fixtures ---

    @Test
    fun multisheet_threeSheetsInOrderIncludingNonAscii() {
        val meta = parseFixture("multisheet.xlsx")
        assertFalse(meta.date1904)
        assertEquals(
            listOf(
                SheetRef("Jadval 1", 1, "rId1", "xl/worksheets/sheet1.xml"),
                SheetRef("Narxlar", 2, "rId2", "xl/worksheets/sheet2.xml"),
                SheetRef("Ҳисобот", 3, "rId3", "xl/worksheets/sheet3.xml"),
            ),
            meta.sheets,
        )
    }

    @Test
    fun valuesBasic_singleSheet_absoluteTargetNormalized() {
        // openpyxl emits an ABSOLUTE target ("/xl/worksheets/sheet1.xml").
        val meta = parseFixture("values-basic.xlsx")
        assertFalse(meta.date1904)
        assertEquals(
            listOf(SheetRef("Sheet1", 1, "rId1", "xl/worksheets/sheet1.xml")),
            meta.sheets,
        )
    }

    @Test
    fun stringsShared_singleSheet_relativeTargetNormalized() {
        // Hand-crafted fixture uses a RELATIVE target ("worksheets/sheet1.xml").
        val meta = parseFixture("strings-shared.xlsx")
        assertEquals(
            listOf(SheetRef("Sheet1", 1, "rId1", "xl/worksheets/sheet1.xml")),
            meta.sheets,
        )
    }

    @Test
    fun everySyntheticFixture_hasAtLeastOneWorksheet() {
        val fixtures = listOf(
            "values-basic", "strings-shared", "styles-basic", "merged",
            "frozen", "dates", "multisheet", "sparse-gaps",
        )
        for (name in fixtures) {
            val meta = parseFixture("$name.xlsx")
            assertTrue("$name should declare >= 1 sheet", meta.sheets.isNotEmpty())
            assertTrue(
                "$name sheet paths should resolve under xl/worksheets/",
                meta.sheets.all { it.partPath.startsWith("xl/worksheets/") },
            )
        }
    }

    // --- inline-XML unit tests: workbook.xml streaming ---

    @Test
    fun workbookPr_date1904_asOne_isTrue() {
        val xml = """
            <workbook xmlns="$MAIN_NS" xmlns:r="$R_NS">
              <workbookPr date1904="1"/>
              <sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
        """.trimIndent()
        val wb = WorkbookParser.parseWorkbookXml(bytes(xml))
        assertTrue(wb.date1904)
        assertEquals(WorkbookParser.RawSheet("S", 1, "rId1"), wb.sheets.single())
    }

    @Test
    fun workbookPr_date1904_asTrueKeyword_isTrue() {
        val xml = """<workbook xmlns="$MAIN_NS" xmlns:r="$R_NS"><workbookPr date1904="true"/><sheets/></workbook>"""
        assertTrue(WorkbookParser.parseWorkbookXml(bytes(xml)).date1904)
    }

    @Test
    fun workbookPr_absent_isFalse() {
        val xml = """<workbook xmlns="$MAIN_NS" xmlns:r="$R_NS"><sheets><sheet name="S" sheetId="7" r:id="rId9"/></sheets></workbook>"""
        val wb = WorkbookParser.parseWorkbookXml(bytes(xml))
        assertFalse(wb.date1904)
        assertEquals(WorkbookParser.RawSheet("S", 7, "rId9"), wb.sheets.single())
    }

    // --- inline-XML unit tests: rels streaming ---

    @Test
    fun rels_parsedToIdTargetMapInOrder() {
        val xml = """
            <Relationships xmlns="$PKG_REL_NS">
              <Relationship Id="rId1" Type="t" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Type="t" Target="styles.xml"/>
            </Relationships>
        """.trimIndent()
        val map = WorkbookParser.parseRels(bytes(xml))
        assertEquals("worksheets/sheet1.xml", map["rId1"])
        assertEquals("styles.xml", map["rId2"])
        assertEquals(2, map.size)
    }

    // --- path resolution unit tests ---

    @Test
    fun resolvePartPath_relativeTarget() {
        assertEquals("xl/worksheets/sheet1.xml", WorkbookParser.resolvePartPath("xl", "worksheets/sheet1.xml"))
    }

    @Test
    fun resolvePartPath_absoluteTargetStripsLeadingSlash() {
        assertEquals("xl/worksheets/sheet1.xml", WorkbookParser.resolvePartPath("xl", "/xl/worksheets/sheet1.xml"))
    }

    @Test
    fun resolvePartPath_dotAndParentSegments() {
        assertEquals("xl/other.xml", WorkbookParser.resolvePartPath("xl/sub", "../other.xml"))
        assertEquals("xl/worksheets/sheet1.xml", WorkbookParser.resolvePartPath("xl", "./worksheets/sheet1.xml"))
    }

    // --- helpers ---

    private fun parseFixture(name: String): WorkbookMeta =
        fixtureZip(name).use { zip ->
            when (val result = WorkbookParser.parse(zip)) {
                is ParseResult.Ok -> result.value
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
        const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    }
}

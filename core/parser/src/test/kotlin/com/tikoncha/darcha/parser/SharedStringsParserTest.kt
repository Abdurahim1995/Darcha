package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.StringTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Golden + inline-XML tests for shared string parsing (TECH_SPEC §7 step 3).
 */
class SharedStringsParserTest {

    // --- golden tests ---

    @Test
    fun stringsShared_fourUniqueInOrder() {
        val table = parseFixture("strings-shared.xlsx")
        assertEquals(listOf("fruit", "apple", "banana", "cherry"), table.entries)
        assertEquals("apple", table[1])
        assertEquals(4, table.size)
    }

    @Test
    fun valuesBasic_noSharedStringsPart_isEmpty() {
        val table = parseFixture("values-basic.xlsx")
        assertEquals(StringTable.EMPTY, table)
        assertEquals(0, table.size)
    }

    // --- inline-XML unit tests ---

    @Test
    fun plainAndRichText_flattenUniformly() {
        val xml = """
            <sst xmlns="$MAIN_NS" count="2" uniqueCount="2">
              <si><t>fruit</t></si>
              <si><r><t>Hello </t></r><r><t>World</t></r></si>
            </sst>
        """.trimIndent()
        val table = SharedStringsParser.parseSharedStrings(bytes(xml))
        assertEquals(listOf("fruit", "Hello World"), table.entries)
    }

    @Test
    fun richTextRunFormatting_isIgnored() {
        // <rPr> carries run formatting we deliberately drop in v1.
        val xml = """
            <sst xmlns="$MAIN_NS">
              <si><r><rPr><b/><color rgb="FFFF0000"/></rPr><t>Bold</t></r><r><t> normal</t></r></si>
            </sst>
        """.trimIndent()
        val table = SharedStringsParser.parseSharedStrings(bytes(xml))
        assertEquals(listOf("Bold normal"), table.entries)
    }

    @Test
    fun xmlSpacePreserve_keepsWhitespace() {
        val xml = """<sst xmlns="$MAIN_NS"><si><t xml:space="preserve">  a  b  </t></si></sst>"""
        val table = SharedStringsParser.parseSharedStrings(bytes(xml))
        assertEquals(listOf("  a  b  "), table.entries)
    }

    @Test
    fun emptyEntry_isEmptyString() {
        val xml = """<sst xmlns="$MAIN_NS"><si><t></t></si><si><t>x</t></si></sst>"""
        val table = SharedStringsParser.parseSharedStrings(bytes(xml))
        assertEquals(listOf("", "x"), table.entries)
    }

    @Test
    fun get_outOfRange_isNull() {
        val table = SharedStringsParser.parseSharedStrings(
            bytes("""<sst xmlns="$MAIN_NS"><si><t>only</t></si></sst>"""),
        )
        assertEquals("only", table[0])
        assertNull(table[5])
        assertNull(table[-1])
    }

    // --- helpers ---

    private fun parseFixture(name: String): StringTable =
        fixtureZip(name).use { zip ->
            when (val result = SharedStringsParser.parse(zip)) {
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
    }
}

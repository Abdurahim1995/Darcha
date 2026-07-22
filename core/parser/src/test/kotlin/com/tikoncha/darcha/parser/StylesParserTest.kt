package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.Color
import com.tikoncha.darcha.model.HorizontalAlignment
import com.tikoncha.darcha.model.StyleTable
import com.tikoncha.darcha.model.VerticalAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Golden tests for style resolution (TECH_SPEC §7 step 4). */
class StylesParserTest {

    // --- styles-basic.xlsx: fonts, fills, alignments ---

    @Test
    fun stylesBasic_resolvesEachCellXf() {
        val table = parseFixture("styles-basic.xlsx")
        assertEquals(7, table.size)

        // xf 0: default font (theme-1 color → black fallback), no formatting.
        val default = table[0]!!
        assertFalse(default.bold)
        assertFalse(default.italic)
        assertNull(default.fillColor)
        assertEquals(HorizontalAlignment.GENERAL, default.horizontalAlignment)
        assertEquals(0, default.numFmtId)
        assertFalse(default.isDate)
        assertEquals(Color.BLACK, default.fontColor) // theme="1" fallback

        // xf 1: bold.
        assertTrue(table[1]!!.bold)
        assertFalse(table[1]!!.italic)

        // xf 2: solid yellow fill.
        assertEquals(Color(0xFFFFFF00.toInt()), table[2]!!.fillColor)

        // xf 3: centered horizontally and vertically.
        assertEquals(HorizontalAlignment.CENTER, table[3]!!.horizontalAlignment)
        assertEquals(VerticalAlignment.CENTER, table[3]!!.verticalAlignment)

        // xf 4: italic.
        assertTrue(table[4]!!.italic)
        assertFalse(table[4]!!.bold)

        // xf 5: right-aligned (vertical defaults to bottom).
        assertEquals(HorizontalAlignment.RIGHT, table[5]!!.horizontalAlignment)
        assertEquals(VerticalAlignment.BOTTOM, table[5]!!.verticalAlignment)

        // xf 6: red font color from explicit rgb.
        assertEquals(Color(0xFFFF0000.toInt()), table[6]!!.fontColor)
    }

    // --- dates.xlsx: number formats + date detection ---

    @Test
    fun dates_numberFormatsAndDateFlags() {
        val table = parseFixture("dates.xlsx")
        assertEquals(5, table.size)

        assertEquals(listOf(0, 14, 21, 22, 164), table.styles.map { it.numFmtId })
        assertEquals(
            listOf(false, true, true, true, true),
            table.styles.map { it.isDate },
        )

        assertEquals("mm-dd-yy", table[1]!!.formatCode) // builtin 14
        assertEquals("h:mm:ss", table[2]!!.formatCode)  // builtin 21
        assertEquals("yyyy-mm-dd", table[4]!!.formatCode) // custom 164
    }

    // --- missing styles.xml ---

    @Test
    fun sparseGaps_hasStylesButNoDates() {
        // Every openpyxl file ships a styles.xml; just prove it parses cleanly.
        val table = parseFixture("sparse-gaps.xlsx")
        assertTrue(table.size >= 1)
        assertTrue(table.styles.none { it.isDate })
    }

    // --- helpers ---

    private fun parseFixture(name: String): StyleTable =
        fixtureZip(name).use { zip ->
            when (val result = StylesParser.parse(zip)) {
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
}

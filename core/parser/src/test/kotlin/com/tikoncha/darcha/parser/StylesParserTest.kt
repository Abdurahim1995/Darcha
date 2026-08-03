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

        // xf 0: default font, no formatting.
        val default = table[0]!!
        assertFalse(default.bold)
        assertFalse(default.italic)
        assertNull(default.fillColor)
        assertEquals(HorizontalAlignment.GENERAL, default.horizontalAlignment)
        assertEquals(0, default.numFmtId)
        assertFalse(default.isDate)
        // INVERTED IN T28, because the contract changed rather than the code
        // getting worse: this font is <color theme="1"/>, which is a reference to
        // the system's window-text colour, not a colour. It used to resolve to
        // black; it now resolves to "the document chose nothing". See
        // theme1IsNoChoiceAtAll_notBlack for the full reasoning.
        assertNull("theme=\"1\" is not a choice of colour", default.fontColor)

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

    // --- theme colours vs explicit colours (T28) ---
    //
    // Golden values read out of text-contrast.xlsx, not typed from memory:
    //   font 0/1 = <color theme="1"/>   font 2 = rgb FF000000
    //   font 3   = rgb FFFFFFFF         font 4 = rgb FF999999
    //   fill 2   = solid FFFFFF00       fill 3 = solid FF1F3864
    //   A1..A6   = s=1..6

    /**
     * The distinction the whole task exists to create.
     *
     * `<color theme="1"/>` is `dk1`, written in every Office theme as
     * `<a:sysClr val="windowText"/>` — a reference to the system's text colour,
     * not a colour. Recording it as black asserts a choice the author never
     * made, and a viewer that then honours that "choice" on a dark background
     * hides almost every cell of almost every real file.
     */
    @Test
    fun theme1IsNoChoiceAtAll_notBlack() {
        val table = parseFixture("text-contrast.xlsx")
        assertNull("A1 is <color theme=\"1\"/>", table[1]!!.fontColor)
    }

    /**
     * ...and the other half: an author who really wants black still gets black.
     * If this ever equals `null`, the distinction has collapsed and the renderer
     * can no longer tell a deliberate colour from an absent one.
     */
    @Test
    fun explicitBlackSurvivesAsAChoice() {
        val table = parseFixture("text-contrast.xlsx")
        assertEquals(Color.BLACK, table[2]!!.fontColor)
        assertNull("...and it is on no fill", table[2]!!.fillColor)
    }

    @Test
    fun explicitWhiteSurvivesAsAChoice() {
        val table = parseFixture("text-contrast.xlsx")
        assertEquals(Color.WHITE, table[3]!!.fontColor)
        assertNull(table[3]!!.fillColor)
    }

    @Test
    fun documentChosePairings_areCarriedWhole() {
        val table = parseFixture("text-contrast.xlsx")
        assertEquals(Color.BLACK, table[4]!!.fontColor)
        assertEquals(Color(0xFFFFFF00.toInt()), table[4]!!.fillColor)
        assertEquals(Color.WHITE, table[5]!!.fontColor)
        assertEquals(Color(0xFF1F3864.toInt()), table[5]!!.fillColor)
    }

    /**
     * The combination the corpus only had by accident, in `styles-basic.xlsx`
     * B1, until it shipped a regression in v1.1.0: **no font colour chosen, but
     * a fill chosen.**
     *
     * The parser's job here is only to keep the two facts apart — `fontColor` is
     * `null` while `fillColor` is not — so the renderer can see that the
     * background is the author's and the foreground is its own. Both polarities
     * are pinned, because getting one right by luck is exactly how this was
     * missed the first time.
     */
    @Test
    fun themeColourOnAFill_leavesTheFontUnchosenAndTheFillChosen() {
        val table = parseFixture("text-contrast.xlsx")

        assertNull("A7 chose no font colour", table[7]!!.fontColor)
        assertEquals(Color(0xFFFFFF00.toInt()), table[7]!!.fillColor)

        assertNull("A8 chose no font colour", table[8]!!.fontColor)
        assertEquals(Color(0xFF1F3864.toInt()), table[8]!!.fillColor)
    }

    @Test
    fun aQuietGreyIsStillAChoice() {
        val table = parseFixture("text-contrast.xlsx")
        assertEquals(Color(0xFF999999.toInt()), table[6]!!.fontColor)
    }

    /**
     * Theme 0 is `lt1` — `<a:sysClr val="window"/>` — and stays white on
     * purpose. As a *font* colour an author reaches for it to reverse text out
     * of a dark fill, so it is a real decision about contrast against that fill.
     * Dropping it to `null` like theme 1 would break the one case it exists for.
     */
    @Test
    fun theme0StaysWhite_becauseItIsUsedAsAFontColourOnDarkFills() {
        assertEquals(Color.WHITE, parseInlineFontColor("""<color theme="0"/>"""))
    }

    /**
     * Themes 2-11 are fixed RGB in `xl/theme/theme1.xml` (dk2 is a navy, the
     * accents are real colours), and black is wrong for all of them. That is a
     * fidelity gap, not a legibility one, and reading the theme part is out of
     * scope for T28 — TECH_SPEC §7 says so. This test pins the *known-wrong*
     * behaviour so that fixing it later is a deliberate act, not an accident.
     */
    @Test
    fun themes2To11_areStillFlattenedToBlack_aKnownGap() {
        for (theme in 2..11) {
            assertEquals(
                "theme=$theme still has no real resolution",
                Color.BLACK,
                parseInlineFontColor("""<color theme="$theme"/>"""),
            )
        }
    }

    // --- helpers ---

    /** Resolve one `<color .../>` element through the real parser. */
    private fun parseInlineFontColor(colorElement: String): Color? {
        val xml = """
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <fonts count="1"><font>$colorElement</font></fonts>
              <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
              <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0"/></cellXfs>
            </styleSheet>
        """.trimIndent()
        return StylesParser.parseStyles(xml.byteInputStream())[0]!!.fontColor
    }

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

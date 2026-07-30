package com.tikoncha.darcha.feature.viewer

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.data.WorkbookLoad
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.data.XlsxWorkbookRepository
import com.tikoncha.darcha.model.CellStyle
import com.tikoncha.darcha.model.DateNames
import com.tikoncha.darcha.model.FormattedValueCache
import com.tikoncha.darcha.model.HorizontalAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream

/**
 * The whole chain, on real files: **fixture → parser → snapshot → formatter →
 * the string the grid draws.**
 *
 * The pieces are each unit-tested in their own module, but nothing until now
 * proved they line up: `:core:model` cannot open an `.xlsx`, and `:core:parser`
 * knows nothing about formatting. `:feature:viewer` is the one module that has
 * both on its classpath, so the seam gets tested here (T17).
 *
 * It runs the *production* path — `XlsxWorkbookRepository`, not a hand-built
 * workbook — so it also covers the plumbing that carries the style table and the
 * epoch flag from the parser onto the [SheetSnapshot].
 *
 * The golden corpus is mounted from `:core:parser`'s test resources; see this
 * module's `build.gradle.kts`.
 */
class DisplayTextEndToEndTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    // --- values-basic.xlsx: numbers, text and booleans ---

    @Test
    fun valuesBasic_rendersEachValueKind() {
        val sheet = load("synthetic/values-basic.xlsx")
        val text = displayTexts(sheet)

        assertEquals("Name", text[0 to 0])
        assertEquals("Age", text[0 to 1])
        assertEquals("Alice", text[1 to 0])
        // 30 is stored as the double 30.0 and must not read "30.0".
        assertEquals("30", text[1 to 1])
        assertEquals("TRUE", text[1 to 2])
        assertEquals("25.5", text[2 to 1])
        assertEquals("FALSE", text[2 to 2])
        assertEquals("0", text[3 to 1])
    }

    // --- dates.xlsx: the formatter's whole point ---

    @Test
    fun excelDates_renderThroughTheBuiltinCodesExcelChose() {
        val sheet = load("excel/dates.xlsx")
        val text = displayTexts(sheet)

        assertFalse("this workbook uses the 1900 epoch", sheet.date1904)
        // Raw serials 45306 / 0.5625 / 45306.5625 / 45657 under builtin 14/20/22.
        assertEquals("01-15-24", text[0 to 0])
        assertEquals("13:30", text[1 to 0])
        assertEquals("1/15/24 13:30", text[2 to 0])
        assertEquals("12-31-24", text[3 to 0])
    }

    @Test
    fun syntheticDates_includingACustomCode() {
        val sheet = load("synthetic/dates.xlsx")
        val text = displayTexts(sheet)

        assertEquals("01-15-24", text[0 to 0])
        // openpyxl picked builtin 21 (h:mm:ss) where Excel picked 20 (h:mm).
        assertEquals("13:30:00", text[1 to 0])
        assertEquals("1/15/24 13:30", text[2 to 0])
        // A custom numFmt (id >= 164) read out of <numFmts>.
        assertEquals("2024-12-31", text[3 to 0])
    }

    /** Without the style table on the snapshot, every one of these is a number. */
    @Test
    fun theSnapshotCarriesTheStylesThatMakeADateADate() {
        val sheet = load("excel/dates.xlsx")
        assertTrue("styles must reach the renderer", sheet.styles.size > 0)

        val styleId = sheet.data.row(0)!!.styleIds[0]
        val style = sheet.styles[styleId]!!
        assertEquals(14, style.numFmtId)
        assertEquals("mm-dd-yy", style.formatCode)
        assertTrue(style.isDate)
    }

    // --- styles-basic.xlsx: what T17 draws ---

    @Test
    fun stylesBasic_carriesTheVisualStyleToTheRenderer() {
        val sheet = load("synthetic/styles-basic.xlsx")

        fun styleAt(row: Int, column: Int): CellStyle =
            sheet.styles[sheet.data.row(row)!!.styleIdAt(column)!!]!!

        assertTrue("A1 is bold", styleAt(0, 0).bold)
        assertTrue("A2 is italic", styleAt(1, 0).italic)
        assertEquals("A3 is red", RED_ARGB, styleAt(2, 0).fontColor?.argb)
        assertEquals("B1 is filled yellow", YELLOW_ARGB, styleAt(0, 1).fillColor?.argb)
        assertEquals(HorizontalAlignment.CENTER, styleAt(0, 2).horizontalAlignment)
        assertEquals(HorizontalAlignment.RIGHT, styleAt(1, 2).horizontalAlignment)

        // And the text itself still comes through the formatter unchanged.
        val text = displayTexts(sheet)
        assertEquals("Bold", text[0 to 0])
        assertEquals("Red", text[2 to 0])
    }

    // --- the name table really is injectable, end to end ---

    @Test
    fun uzbekNames_reachARealFilesDates() {
        val sheet = load("excel/dates.xlsx")
        val uzbek = DateNames(
            months = listOf(
                "yanvar", "fevral", "mart", "aprel", "may", "iyun",
                "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr",
            ),
            days = listOf(
                "yakshanba", "dushanba", "seshanba", "chorshanba",
                "payshanba", "juma", "shanba",
            ),
        )
        val cache = FormattedValueCache(
            styles = sheet.styles,
            strings = sheet.sharedStrings,
            date1904 = sheet.date1904,
            names = uzbek,
        )
        // Re-style A1's serial with a code that spells the month out.
        val serial = sheet.data.cellAt(0, 0)!!
        val spelled = com.tikoncha.darcha.model.ValueFormatter.format(
            value = serial,
            style = CellStyle.DEFAULT.copy(numFmtId = 164, formatCode = "d mmmm yyyy", isDate = true),
            date1904 = sheet.date1904,
            names = uzbek,
        )
        assertEquals("15 yanvar 2024", spelled)
        // The workbook's own format is numeric, so the names never come into it.
        assertEquals("01-15-24", cache.format(serial, sheet.data.row(0)!!.styleIds[0]))
    }

    // --- helpers ---

    /** Load a fixture through the real repository, exactly as the app does. */
    private fun load(path: String): SheetSnapshot = runBlocking {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/$path")) {
            "fixture '$path' is not on the test classpath — check the mounted resource dir"
        }.use(InputStream::readBytes)

        val repository = XlsxWorkbookRepository(cacheDir = temp.newFolder(), io = Dispatchers.Unconfined)
        val source = object : WorkbookSource {
            override val displayName = path.substringAfterLast('/')
            override val declaredSizeBytes = bytes.size.toLong()
            override fun openStream(): InputStream = bytes.inputStream()
        }
        when (val result = repository.load(source) {}) {
            is WorkbookLoad.Success -> result.sheet
            is WorkbookLoad.Failure -> error("loading $path failed: ${result.kind}")
        }
    }

    /** Every populated cell as `(row, column) -> display string`. */
    private fun displayTexts(sheet: SheetSnapshot): Map<Pair<Int, Int>, String> {
        val cache = FormattedValueCache(
            styles = sheet.styles,
            strings = sheet.sharedStrings,
            date1904 = sheet.date1904,
        )
        val out = LinkedHashMap<Pair<Int, Int>, String>()
        for ((rowIndex, row) in sheet.data.rows) {
            for (i in row.columns.indices) {
                out[rowIndex to row.columns[i]] = cache.format(row.values[i], row.styleIds[i])
            }
        }
        return out
    }

    private companion object {
        const val RED_ARGB = 0xFFFF0000.toInt()
        const val YELLOW_ARGB = 0xFFFFFF00.toInt()
    }
}

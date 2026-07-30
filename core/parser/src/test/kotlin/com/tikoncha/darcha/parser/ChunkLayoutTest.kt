package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.SheetLayout
import com.tikoncha.darcha.model.Worksheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * The layout carried by each [RowsChunk] (T15.6) — what makes progressive
 * rendering place rows correctly on first paint instead of laying the sheet out
 * again when the parse ends.
 *
 * Golden values come from `synthetic/column-widths.xlsx`, whose `<cols>` block
 * precedes `<sheetData>` in the file itself — the ordering the whole design
 * rests on is therefore asserted against real producer output, not assumed from
 * the schema.
 */
class ChunkLayoutTest {

    // --- golden: the complete layout of column-widths.xlsx ---

    @Test
    fun columnWidths_fixture_hasCustomWidthsAndHeights() {
        val layout = parseFixture("column-widths.xlsx").layout

        // A, C, D custom; B (index 1) left at the sheet default.
        assertEquals(mapOf(0 to 30.0, 2 to 4.5, 3 to 18.0), layout.columnWidths)
        // Rows 1 and 7 in the file, 0-based here.
        assertEquals(mapOf(0 to 40.0, 6 to 28.0), layout.rowHeights)
        // <sheetFormatPr> declares defaultRowHeight="15" and baseColWidth="8" —
        // and baseColWidth is *not* defaultColWidth, so the column default stays
        // the OOXML one.
        assertEquals(15.0, layout.defaultRowHeight, 0.0)
        assertEquals(SheetLayout.DEFAULT_COL_WIDTH, layout.defaultColWidth, 0.0)
        assertEquals(emptyList<CellRange>(), layout.merges)
        assertEquals(FrozenPanes.NONE, layout.frozenPanes)
    }

    // --- the column axis is complete from the first chunk ---

    @Test
    fun everyChunk_carriesTheWholeColumnAxis() {
        val chunks = chunksOf("column-widths.xlsx", chunkSize = 3)
        assertEquals(4, chunks.size) // 10 rows in batches of 3

        for ((index, chunk) in chunks.withIndex()) {
            assertEquals(
                "chunk $index column widths",
                mapOf(0 to 30.0, 2 to 4.5, 3 to 18.0),
                chunk.layout.columnWidths,
            )
            assertEquals("chunk $index", 15.0, chunk.layout.defaultRowHeight, 0.0)
            assertEquals(
                "chunk $index",
                SheetLayout.DEFAULT_COL_WIDTH,
                chunk.layout.defaultColWidth,
                0.0,
            )
        }
    }

    /** The first chunk is the one that matters: it is what the user first sees. */
    @Test
    fun theFirstChunk_alreadyKnowsTheColumnWidths() {
        val first = chunksOf("column-widths.xlsx", chunkSize = 3).first()
        assertEquals(mapOf(0 to 30.0, 2 to 4.5, 3 to 18.0), first.layout.columnWidths)
    }

    // --- row heights stream with their rows ---

    @Test
    fun rowHeights_arriveWithTheirOwnRows() {
        val chunks = chunksOf("column-widths.xlsx", chunkSize = 3)

        // Rows 0..2, 3..5, 6..8, 9. The custom heights sit on rows 0 and 6.
        assertEquals(mapOf(0 to 40.0), chunks[0].layout.rowHeights)
        assertEquals(emptyMap<Int, Double>(), chunks[1].layout.rowHeights)
        assertEquals(mapOf(6 to 28.0), chunks[2].layout.rowHeights)
        assertEquals(emptyMap<Int, Double>(), chunks[3].layout.rowHeights)
    }

    /**
     * The no-reflow property, stated directly: accumulating the chunk layouts the
     * way a progressive renderer does reproduces the final layout exactly, so
     * nothing the user is already looking at moves when the parse completes.
     */
    @Test
    fun accumulatedChunkLayouts_matchTheFinalLayout() {
        val chunks = ArrayList<RowsChunk>()
        val worksheet = parseFixture("column-widths.xlsx", chunkSize = 3) { chunks.add(it) }

        val accumulated = LinkedHashMap<Int, Double>()
        for (chunk in chunks) accumulated.putAll(chunk.layout.rowHeights)

        assertEquals(worksheet.layout.rowHeights, accumulated)
        assertEquals(worksheet.layout.columnWidths, chunks.last().layout.columnWidths)
        assertEquals(worksheet.layout.defaultColWidth, chunks.last().layout.defaultColWidth, 0.0)
        assertEquals(worksheet.layout.defaultRowHeight, chunks.last().layout.defaultRowHeight, 0.0)
    }

    // --- panes travel, merges do not: the asymmetry, locked ---

    /**
     * `<mergeCells>` genuinely follows `<sheetData>`, so a chunk cannot know the
     * merges however much a renderer might want them. This guarantee stays.
     */
    @Test
    fun chunkLayout_neverClaimsMerges() {
        val chunks = ArrayList<RowsChunk>()
        val worksheet = parseFixture("merged.xlsx", chunkSize = 1) { chunks.add(it) }

        assertTrue("expected several chunks", chunks.size > 1)
        for (chunk in chunks) assertEquals(emptyList<CellRange>(), chunk.layout.merges)
        // <mergeCells> follows <sheetData>: only the finished worksheet has them.
        assertTrue(worksheet.layout.merges.isNotEmpty())
    }

    /**
     * Frozen panes, unlike merges, ride with the very first chunk (T19).
     *
     * `<sheetViews>` precedes `<sheetData>`, so they are knowable early — and
     * they have to be. Panes split the grid into four scrolling regions; a
     * renderer that learned about them halfway through a parse would re-lay the
     * sheet and move the scroll position under the reader. A late merge only
     * repaints one range (T18), which is why the two are treated differently.
     */
    @Test
    fun chunkLayout_carriesFrozenPanesFromTheFirstChunk() {
        val chunks = ArrayList<RowsChunk>()
        val worksheet = parseFixture("frozen.xlsx", chunkSize = 1) { chunks.add(it) }
        val expected = FrozenPanes(frozenCols = 1, frozenRows = 1)

        assertTrue("expected several chunks", chunks.size > 1)
        assertEquals("the first paint already knows", expected, chunks.first().layout.frozenPanes)
        for (chunk in chunks) assertEquals(expected, chunk.layout.frozenPanes)
        // And the finished worksheet agrees, so nothing changes at completion.
        assertEquals(expected, worksheet.layout.frozenPanes)
    }

    /** A sheet with a split (not frozen) pane still reports no freezing. */
    @Test
    fun chunkLayout_ofAnUnfrozenSheet_hasNoPanes() {
        val chunks = ArrayList<RowsChunk>()
        parseFixture("merged.xlsx", chunkSize = 1) { chunks.add(it) }
        for (chunk in chunks) assertEquals(FrozenPanes.NONE, chunk.layout.frozenPanes)
    }

    @Test
    fun sheetWithoutCols_yieldsDefaultChunkLayout() {
        val xml = """
            <worksheet xmlns="$MAIN_NS">
              <sheetData>
                <row r="1"><c r="A1"><v>1</v></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val chunks = ArrayList<RowsChunk>()
        SheetParser.parseSheet(bytes(xml), chunkSize = 1) { chunks.add(it) }

        assertEquals(1, chunks.size)
        assertEquals(SheetLayout.EMPTY, chunks.single().layout)
    }

    /**
     * A height on a row with no cells still travels — it shifts every row below
     * it, so a renderer that ignored it would misplace rows it has already drawn.
     */
    @Test
    fun heightOfACellLessRow_travelsWithTheNextChunk() {
        val xml = """
            <worksheet xmlns="$MAIN_NS">
              <sheetData>
                <row r="1" ht="50" customHeight="1"/>
                <row r="2"><c r="A2"><v>1</v></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val chunks = ArrayList<RowsChunk>()
        SheetParser.parseSheet(bytes(xml), chunkSize = 1) { chunks.add(it) }

        assertEquals(1, chunks.size)
        assertEquals(mapOf(0 to 50.0), chunks.single().layout.rowHeights)
        assertEquals(setOf(1), chunks.single().rows.keys) // the empty row holds no cells
    }

    // --- helpers ---

    private fun parseFixture(
        name: String,
        chunkSize: Int = 200,
        onChunk: (RowsChunk) -> Unit = {},
    ): Worksheet = fixtureZip(name).use { zip ->
        when (val result = SheetParser.parse(zip, "xl/worksheets/sheet1.xml", chunkSize, onChunk)) {
            is ParseResult.Ok -> result.value
            is ParseResult.Err -> error("expected Ok for $name but got Err(${result.kind})")
        }
    }

    private fun chunksOf(name: String, chunkSize: Int): List<RowsChunk> {
        val chunks = ArrayList<RowsChunk>()
        parseFixture(name, chunkSize) { chunks.add(it) }
        return chunks
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

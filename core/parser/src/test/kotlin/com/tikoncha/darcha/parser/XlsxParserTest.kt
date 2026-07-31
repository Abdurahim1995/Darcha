package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.Row
import com.tikoncha.darcha.model.StringTable
import com.tikoncha.darcha.model.Worksheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** End-to-end tests for the public parser facade (TECH_SPEC §7, §10). */
class XlsxParserTest {

    private val fixtures = listOf(
        "values-basic.xlsx", "strings-shared.xlsx", "styles-basic.xlsx", "merged.xlsx",
        "frozen.xlsx", "dates.xlsx", "multisheet.xlsx", "sparse-gaps.xlsx",
    )

    @Test
    fun opensEveryFixture_andChunksAccumulateToFinalModel() {
        for (name in fixtures) {
            open(name).use { workbook ->
                assertTrue("$name should have sheets", workbook.sheets.isNotEmpty())
                for (index in workbook.sheets.indices) {
                    val collected = LinkedHashMap<Int, Row>()
                    var previousRowsSoFar = 0
                    val worksheet = readOk(workbook, index, chunkSize = 2) { chunk ->
                        chunk.rows.forEach { (row, cells) -> collected[row] = cells }
                        assertTrue("$name rowsSoFar must be monotonic", chunk.rowsSoFar >= previousRowsSoFar)
                        previousRowsSoFar = chunk.rowsSoFar
                    }
                    // The accumulated chunks reproduce the full sheet.
                    assertEquals("$name sheet $index rows", worksheet.data.rows.keys, collected.keys)
                    assertEquals(
                        "$name sheet $index cell count",
                        worksheet.data.cellCount,
                        collected.values.sumOf { it.size },
                    )
                }
            }
        }
    }

    @Test
    fun multisheet_metaAndSheetOrder() {
        open("multisheet.xlsx").use { workbook ->
            assertFalse(workbook.date1904)
            assertEquals(
                listOf("Jadval 1", "Narxlar", "Ҳисобот"),
                workbook.sheets.map { it.name },
            )
        }
    }

    @Test
    fun valuesBasic_eagerStringsAndStyles_andCells() {
        open("values-basic.xlsx").use { workbook ->
            assertEquals(StringTable.EMPTY, workbook.sharedStrings) // all inlineStr
            assertTrue(workbook.styles.size >= 1)
            val sheet = readOk(workbook, 0)
            assertEquals(12, sheet.data.cellCount)
            assertEquals(CellValue.Number(30.0), sheet.data.cellAt(1, 1))
        }
    }

    @Test
    fun stringsShared_indicesResolveThroughSharedTable() {
        open("strings-shared.xlsx").use { workbook ->
            val sheet = readOk(workbook, 0)
            val cell = sheet.data.cellAt(1, 0) as CellValue.SharedText
            assertEquals("apple", workbook.sharedStrings[cell.index])
        }
    }

    @Test
    fun chunks_fireInOrderWithExpectedBatches() {
        open("values-basic.xlsx").use { workbook ->
            val chunks = ArrayList<RowsChunk>()
            readOk(workbook, 0, chunkSize = 2) { chunks.add(it) }
            // 4 populated rows, chunkSize 2 → two chunks of two rows.
            assertEquals(2, chunks.size)
            assertEquals(2, chunks[0].rows.size)
            assertEquals(2, chunks[1].rows.size)
            assertEquals(4, chunks.last().rowsSoFar)
        }
    }

    @Test
    fun readSheet_outOfRange_isError() {
        open("values-basic.xlsx").use { workbook ->
            assertTrue(workbook.readSheet(99) is ParseResult.Err)
        }
    }

    @Test
    fun open_garbage_isCorrupted() {
        val result = XlsxParser.open(tempWith(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)))
        assertTrue(result is ParseResult.Err)
        assertTrue((result as ParseResult.Err).kind is com.tikoncha.darcha.model.ErrorKind.Corrupted)
    }

    @Test
    fun open_oleMagic_isEncrypted() {
        val ole = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        val result = XlsxParser.open(tempWith(ole))
        assertTrue(result is ParseResult.Err)
        assertTrue((result as ParseResult.Err).kind is com.tikoncha.darcha.model.ErrorKind.Encrypted)
    }

    /**
     * The T27 end-to-end: a renamed `.ods` reaching the public entry point comes
     * back as [ErrorKind.Unsupported], and — just as importantly — the archive is
     * never opened to find that out.
     *
     * This is also the test that keeps the release build honest. `Unsupported`
     * was declared but never constructed in v1.0.0, so R8 proved the
     * `ErrorScreen` branch unreachable and resource shrinking dropped its two
     * strings from the APK. As long as a reachable path produces the kind, that
     * chain stays alive. Do not delete this test to "simplify" the suite without
     * checking `aapt2 dump resources` on a release build first.
     */
    @Test
    fun open_renamedOds_isUnsupported_notCorrupted() {
        val result = XlsxParser.open(fixtureFile("ods-renamed.xlsx"))
        assertTrue(result is ParseResult.Err)
        assertTrue(
            "expected Unsupported, was ${(result as ParseResult.Err).kind}",
            result.kind is com.tikoncha.darcha.model.ErrorKind.Unsupported,
        )
    }

    // --- helpers ---

    private fun open(name: String): Workbook =
        when (val result = XlsxParser.open(fixtureFile(name))) {
            is ParseResult.Ok -> result.value
            is ParseResult.Err -> error("open($name) failed: ${result.kind}")
        }

    private fun readOk(
        workbook: Workbook,
        index: Int,
        chunkSize: Int = 200,
        onChunk: (RowsChunk) -> Unit = {},
    ): Worksheet =
        when (val result = workbook.readSheet(index, chunkSize, onChunk)) {
            is ParseResult.Ok -> result.value
            is ParseResult.Err -> error("readSheet($index) failed: ${result.kind}")
        }

    private fun fixtureFile(name: String): File {
        val tmp = File.createTempFile("darcha-fixture", ".xlsx")
        tmp.deleteOnExit()
        javaClass.getResourceAsStream("/fixtures/synthetic/$name")!!.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        return tmp
    }

    private fun tempWith(bytes: ByteArray): File {
        val tmp = File.createTempFile("darcha-blob", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)
        return tmp
    }
}

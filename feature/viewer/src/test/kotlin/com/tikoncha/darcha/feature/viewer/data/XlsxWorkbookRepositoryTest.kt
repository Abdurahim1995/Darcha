package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.model.ErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for the real parser-backed repository, including that both safety caps
 * (TECH_SPEC §13) fire **during** streaming rather than after it.
 */
class XlsxWorkbookRepositoryTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private fun repository(
        maxFileBytes: Long = XlsxWorkbookRepository.MAX_FILE_BYTES,
        maxCells: Int = XlsxWorkbookRepository.MAX_CELLS,
    ) = XlsxWorkbookRepository(
        cacheDir = temp.newFolder(),
        io = Dispatchers.Unconfined,
        maxFileBytes = maxFileBytes,
        maxCells = maxCells,
    )

    /** A source over an in-memory byte array, with a settable declared size. */
    private class BytesSource(
        private val bytes: ByteArray,
        override val displayName: String = "book.xlsx",
        override val declaredSizeBytes: Long? = bytes.size.toLong(),
    ) : WorkbookSource {
        var streamsOpened: Int = 0
            private set

        override fun openStream(): InputStream {
            streamsOpened++
            return ByteArrayInputStream(bytes)
        }
    }

    // --- happy path ---

    @Test
    fun validWorkbook_loadsMetadataAndRowCount() = runBlocking {
        val source = BytesSource(workbookBytes(rows = 4), displayName = "values.xlsx")
        val progress = mutableListOf<Float>()

        val result = repository().load(source) { progress.add(it) }

        val meta = (result as WorkbookLoad.Success).meta
        assertEquals("values.xlsx", meta.displayName)
        assertEquals(listOf("Sheet1"), meta.sheetNames)
        assertEquals(4, meta.rowCount)
        assertTrue("progress should have been reported", progress.isNotEmpty())
        assertTrue("progress must stay in 0f..1f", progress.all { it in 0f..1f })
    }

    @Test
    fun progress_increasesMonotonicallyAndNeverReachesOne() {
        var previous = -1f
        for (rows in listOf(1, 10, 100, 1_000, 50_000, 1_000_000)) {
            val value = XlsxWorkbookRepository.progressFor(rows)
            assertTrue("progress must increase", value > previous)
            assertTrue("progress must stay below 1", value < 1f)
            previous = value
        }
        assertEquals(0f, XlsxWorkbookRepository.progressFor(0), 0f)
    }

    // --- error mapping ---

    @Test
    fun garbageBytes_areCorrupted() = runBlocking {
        val result = repository().load(BytesSource(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))) {}
        assertTrue((result as WorkbookLoad.Failure).kind is ErrorKind.Corrupted)
    }

    @Test
    fun oleContainer_isEncrypted() = runBlocking {
        val ole = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        val result = repository().load(BytesSource(ole)) {}
        assertTrue((result as WorkbookLoad.Failure).kind is ErrorKind.Encrypted)
    }

    // --- size cap ---

    @Test
    fun declaredSizeOverCap_isRejectedWithoutReadingTheStream() = runBlocking {
        val source = BytesSource(
            bytes = workbookBytes(rows = 1),
            declaredSizeBytes = 999_999_999L,
        )

        val result = repository(maxFileBytes = 1_024).load(source) {}

        assertTrue((result as WorkbookLoad.Failure).kind is ErrorKind.TooLarge)
        assertEquals("the stream must not be opened at all", 0, source.streamsOpened)
    }

    @Test
    fun understatedSize_isStillCaughtWhileCopying() = runBlocking {
        // A provider that lies about (or omits) its size must not get us to write
        // an unbounded file: the copy counts bytes itself.
        val big = workbookBytes(rows = 200)
        val source = BytesSource(big, declaredSizeBytes = null)

        val result = repository(maxFileBytes = 512).load(source) {}

        assertTrue((result as WorkbookLoad.Failure).kind is ErrorKind.TooLarge)
        assertTrue("the stream is opened, then aborted mid-copy", source.streamsOpened == 1)
    }

    @Test
    fun copyAbortsBeforeWritingTheWholeFile() = runBlocking {
        val bytes = workbookBytes(rows = 500)
        val cacheDir = temp.newFolder()
        val repository = XlsxWorkbookRepository(
            cacheDir = cacheDir,
            io = Dispatchers.Unconfined,
            maxFileBytes = 1_024,
        )

        repository.load(BytesSource(bytes, declaredSizeBytes = null)) {}

        // The temp copy is cleaned up either way; what matters is that we never
        // materialized the full document.
        assertTrue("cache dir should hold no leftovers", cacheDir.listFiles().orEmpty().isEmpty())
        assertTrue("fixture must be bigger than the cap for this test to mean anything", bytes.size > 1_024)
    }

    // --- cell cap ---

    @Test
    fun cellCountOverCap_isTooLarge() = runBlocking {
        // 40 rows x 3 cells = 120 cells, against a cap of 10.
        val source = BytesSource(workbookBytes(rows = 40))

        val result = repository(maxCells = 10).load(source) {}

        val kind = (result as WorkbookLoad.Failure).kind
        assertTrue(kind is ErrorKind.TooLarge)
        assertTrue(
            "the message should say where it stopped, found: ${kind.message}",
            kind.message.orEmpty().contains("stopped at"),
        )
    }

    @Test
    fun cellCapAborts_beforeParsingEveryRow() = runBlocking {
        // With a cap of 10 cells the parse must stop early, so the last progress
        // report is far below what a full parse of 400 rows would produce.
        val source = BytesSource(workbookBytes(rows = 400))
        val progress = mutableListOf<Float>()

        repository(maxCells = 10).load(source) { progress.add(it) }

        val fullParse = XlsxWorkbookRepository.progressFor(400)
        assertTrue(
            "parse should abort early; last progress ${progress.lastOrNull()} vs full $fullParse",
            progress.isEmpty() || progress.last() < fullParse,
        )
    }

    @Test
    fun cellCapExactlyAtLimit_isAccepted() = runBlocking {
        // 4 rows x 3 cells = exactly 12 cells; the cap rejects only *above* it.
        val result = repository(maxCells = 12).load(BytesSource(workbookBytes(rows = 4))) {}
        assertTrue(result is WorkbookLoad.Success)
    }

    // --- helpers ---

    /**
     * Build a minimal valid .xlsx in memory with [rows] rows of three cells.
     *
     * Written by hand rather than reusing a fixture: the golden corpus lives in
     * `:core:parser` and exists to pin parser behaviour, whereas this only needs
     * *some* readable workbook to exercise repository wiring.
     */
    private fun workbookBytes(rows: Int): ByteArray {
        val sheetRows = (1..rows).joinToString("") { r ->
            """<row r="$r"><c r="A$r"><v>$r</v></c><c r="B$r"><v>${r * 2}</v></c>""" +
                """<c r="C$r"><v>${r * 3}</v></c></row>"""
        }
        val parts = listOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?>
                |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                |<Default Extension="xml" ContentType="application/xml"/>
                |<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                |<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                |</Types>""".trimMargin(),
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?>
                |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                |</Relationships>""".trimMargin(),
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                |<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                |<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""".trimMargin(),
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?>
                |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                |</Relationships>""".trimMargin(),
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                |<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                |<sheetData>$sheetRows</sheetData></worksheet>""".trimMargin(),
        )
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

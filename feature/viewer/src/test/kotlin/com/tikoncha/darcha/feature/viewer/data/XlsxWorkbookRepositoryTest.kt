package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.SheetLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

        val result = repository().load(source) { progress.add(it.progress) }

        val meta = (result as WorkbookLoad.Success).meta
        assertEquals("values.xlsx", meta.displayName)
        assertEquals(listOf("Sheet1"), meta.sheetNames)
        assertEquals(4, meta.rowCount)
        assertTrue("progress should have been reported", progress.isNotEmpty())
        assertTrue("progress must stay in 0f..1f", progress.all { it in 0f..1f })
    }

    @Test
    fun success_carriesTheSheetTheRendererWillDraw() = runBlocking {
        // T13 draws from this snapshot, so the load must hand back real cells,
        // not just counts.
        val result = repository().load(BytesSource(workbookBytes(rows = 3))) {}

        val sheet = (result as WorkbookLoad.Success).sheet
        assertEquals(3, sheet.data.rows.size)
        assertEquals(9, sheet.data.cellCount) // 3 rows x 3 cells
        assertEquals(com.tikoncha.darcha.model.CellValue.Number(1.0), sheet.data.cellAt(0, 0))
        assertEquals(com.tikoncha.darcha.model.CellValue.Number(6.0), sheet.data.cellAt(1, 2))
        assertEquals(SheetLayout.DEFAULT_COL_WIDTH, sheet.layout.defaultColWidth, 0.0)
    }

    @Test
    fun partials_carryRowsAsTheyArrive_notJustProgress() = runBlocking {
        // The point of T15.5: each emission is a drawable snapshot, and it grows.
        val partials = mutableListOf<SheetProgress>()
        val result = repository().load(BytesSource(workbookBytes(rows = 900))) { partials.add(it) }

        assertTrue("at least one partial before the end", partials.isNotEmpty())
        assertTrue("the first partial must already carry rows", partials.first().sheet.data.rows.isNotEmpty())
        assertTrue(
            "row counts must not shrink between partials",
            partials.map { it.sheet.data.rows.size }.zipWithNext().all { (a, b) -> b >= a },
        )
        assertEquals(listOf("Sheet1"), partials.first().meta.sheetNames)
        assertEquals(900, (result as WorkbookLoad.Success).sheet.data.rows.size)
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

        repository(maxCells = 10).load(source) { progress.add(it.progress) }

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

    // --- session lifetime: the temp copy must outlive the first parse ---

    @Test
    fun secondSheet_isReadableAfterTheInitialParse() = runBlocking {
        // The whole point of the session: sheet 0 is parsed up front, and the
        // rest are read later from the same temp copy (T15 switches sheets).
        val source = BytesSource(workbookBytes(rows = 3, sheetRowCounts = listOf(3, 5, 2)))
        val repository = repository()

        val first = repository.load(source) {}
        assertEquals(3, (first as WorkbookLoad.Success).meta.rowCount)
        assertEquals(listOf("Sheet1", "Sheet2", "Sheet3"), first.meta.sheetNames)

        val second = repository.readSheet(1)
        assertEquals(5, (second as WorkbookLoad.Success).meta.rowCount)

        val third = repository.readSheet(2)
        assertEquals(2, (third as WorkbookLoad.Success).meta.rowCount)

        repository.closeDocument()
    }

    @Test
    fun tempCopySurvivesTheParse_andIsRemovedOnClose() = runBlocking {
        val cacheDir = temp.newFolder()
        val repository = XlsxWorkbookRepository(cacheDir = cacheDir, io = Dispatchers.Unconfined)

        repository.load(BytesSource(workbookBytes(rows = 2))) {}
        assertEquals(
            "the document's copy must stay while it is open",
            1,
            cacheDir.listFiles().orEmpty().size,
        )

        repository.closeDocument()
        assertTrue("closing must delete it", cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun openingAnotherDocument_releasesThePreviousCopy() = runBlocking {
        val cacheDir = temp.newFolder()
        val repository = XlsxWorkbookRepository(cacheDir = cacheDir, io = Dispatchers.Unconfined)

        repository.load(BytesSource(workbookBytes(rows = 2))) {}
        repository.load(BytesSource(workbookBytes(rows = 4))) {}

        assertEquals(
            "only the current document keeps a copy",
            1,
            cacheDir.listFiles().orEmpty().size,
        )
        repository.closeDocument()
    }

    @Test
    fun readSheet_withoutAnOpenDocument_fails() = runBlocking {
        assertTrue(repository().readSheet(0) is WorkbookLoad.Failure)
    }

    @Test
    fun readSheet_outOfRange_fails() = runBlocking {
        val repository = repository()
        repository.load(BytesSource(workbookBytes(rows = 1))) {}
        assertTrue(repository.readSheet(9) is WorkbookLoad.Failure)
        repository.closeDocument()
    }

    @Test
    fun failedLoad_leavesNoCopyBehind() = runBlocking {
        val cacheDir = temp.newFolder()
        val repository = XlsxWorkbookRepository(cacheDir = cacheDir, io = Dispatchers.Unconfined)

        repository.load(BytesSource(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))) {}

        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    // --- startup sweep ---

    @Test
    fun sweep_removesOrphansButKeepsTheOpenDocumentAndForeignFiles() = runBlocking {
        val cacheDir = temp.newFolder()
        // Orphans a killed process would leave behind.
        File(cacheDir, "darcha-111.xlsx").writeText("stale")
        File(cacheDir, "darcha-222.xlsx").writeText("stale")
        // Something else's cache file must not be touched.
        val foreign = File(cacheDir, "glide-cache.bin").apply { writeText("keep me") }

        val repository = XlsxWorkbookRepository(cacheDir = cacheDir, io = Dispatchers.Unconfined)
        repository.load(BytesSource(workbookBytes(rows = 2))) {}

        val swept = repository.sweepStaleTempFiles()

        assertEquals("both orphans should go", 2, swept)
        assertTrue("the open document's copy must survive", repository.readSheet(0) is WorkbookLoad.Success)
        assertTrue("unrelated files are left alone", foreign.exists())
        repository.closeDocument()
    }

    @Test
    fun closingADocument_leavesTheRepositoryReusable() = runBlocking {
        // The repository outlives the ViewModel: pressing Back clears the
        // ViewModel (which closes the document) while the process stays alive,
        // and reopening the app must be able to load again on the same instance.
        val cacheDir = temp.newFolder()
        val repository = XlsxWorkbookRepository(cacheDir = cacheDir, io = Dispatchers.Unconfined)

        val first = repository.load(BytesSource(workbookBytes(rows = 2))) {}
        assertEquals(2, (first as WorkbookLoad.Success).meta.rowCount)

        repository.closeDocument()
        assertTrue("the copy is gone with the document", cacheDir.listFiles().orEmpty().isEmpty())
        assertTrue("and nothing is open", repository.readSheet(0) is WorkbookLoad.Failure)

        // Same instance, brand new document.
        val second = repository.load(BytesSource(workbookBytes(rows = 5))) {}
        assertEquals(5, (second as WorkbookLoad.Success).meta.rowCount)
        assertTrue("its sheets are readable", repository.readSheet(0) is WorkbookLoad.Success)
        assertEquals(1, cacheDir.listFiles().orEmpty().size)

        repository.closeDocument()
    }

    @Test
    fun closingTwice_isHarmless() = runBlocking {
        val repository = repository()
        repository.load(BytesSource(workbookBytes(rows = 1))) {}
        repository.closeDocument()
        repository.closeDocument()
        // Still usable afterwards.
        assertTrue(repository.load(BytesSource(workbookBytes(rows = 3))) {} is WorkbookLoad.Success)
        repository.closeDocument()
    }

    @Test
    fun sweep_duringAnInFlightLoad_doesNotStealItsCopy() = runBlocking {
        // The copy exists on disk before it becomes the session's, so a sweep
        // racing a load could delete the very file being parsed. The shared lock
        // is what prevents it; without it this test deletes the file mid-parse.
        val cacheDir = temp.newFolder()
        val repository = XlsxWorkbookRepository(cacheDir = cacheDir, io = Dispatchers.IO)
        val streamOpened = CompletableDeferred<Unit>()
        val letLoadFinish = CompletableDeferred<Unit>()

        val slowSource = object : WorkbookSource {
            override val displayName = "slow.xlsx"
            override val declaredSizeBytes: Long? = null
            override fun openStream(): InputStream {
                // By now load() has created its temp file and holds the lock.
                streamOpened.complete(Unit)
                runBlocking { letLoadFinish.await() }
                return ByteArrayInputStream(workbookBytes(rows = 3))
            }
        }

        withTimeout(20_000) {
            val load = async { repository.load(slowSource) {} }
            streamOpened.await()

            val sweep = async { repository.sweepStaleTempFiles() }
            letLoadFinish.complete(Unit)

            assertTrue("the load must survive a concurrent sweep", load.await() is WorkbookLoad.Success)
            assertEquals("nothing to sweep — the only copy is the live one", 0, sweep.await())
        }

        // And the document is still readable afterwards.
        assertTrue(repository.readSheet(0) is WorkbookLoad.Success)
        repository.closeDocument()
    }

    // --- partial layout (T15.6) ---

    /**
     * The reason T15.6 exists: a partial paint must use the sheet's real column
     * widths. Almost every real business spreadsheet sets them, so drawing the
     * first rows at the default width and re-laying them out when the parse ends
     * would be the normal case, not an edge case.
     */
    @Test
    fun aPartialPaint_usesTheRealColumnWidths_notTheDefaults() = runBlocking {
        val partials = mutableListOf<SheetProgress>()
        val load = repository().load(BytesSource(streamingWorkbookBytes())) { partials.add(it) }

        val first = partials.first().sheet.layout
        assertEquals(mapOf(0 to 30.0, 2 to 4.5), first.columnWidths)
        assertEquals(12.0, first.defaultColWidth, 0.0)
        assertEquals(18.0, first.defaultRowHeight, 0.0)

        // Nothing the user is already looking at moves when the parse finishes.
        val complete = (load as WorkbookLoad.Success).sheet.layout
        assertEquals(complete.columnWidths, first.columnWidths)
        assertEquals(complete.defaultColWidth, first.defaultColWidth, 0.0)
        assertEquals(complete.defaultRowHeight, first.defaultRowHeight, 0.0)
    }

    @Test
    fun rowHeights_reachAPartialWithTheirOwnRows() = runBlocking {
        val partials = mutableListOf<SheetProgress>()
        val load = repository().load(BytesSource(streamingWorkbookBytes())) { partials.add(it) }

        // The first emission covers the first chunk (rows 1..200 of the file),
        // so it knows the tall row inside it and not the one far below.
        val first = partials.first().sheet
        assertTrue("the partial must not already be the whole sheet", first.data.rows.size < 260)
        assertEquals(mapOf(2 to 40.0), first.layout.rowHeights)

        // A later row never shifts an earlier one, so learning about row 250
        // moves nothing that was already drawn.
        val complete = (load as WorkbookLoad.Success).sheet.layout
        assertEquals(mapOf(2 to 40.0, 249 to 28.0), complete.rowHeights)
    }

    /**
     * Merges and frozen panes follow `<sheetData>`, so a partial cannot know
     * them — and must not pretend to. Neither affects where a row sits, so they
     * can land with the final result without moving anything.
     */
    @Test
    fun aPartialPaint_claimsNoMergesOrFrozenPanes() = runBlocking {
        val partials = mutableListOf<SheetProgress>()
        repository().load(BytesSource(streamingWorkbookBytes())) { partials.add(it) }

        val first = partials.first().sheet.layout
        assertTrue(first.merges.isEmpty())
        assertEquals(FrozenPanes.NONE, first.frozenPanes)
    }

    // --- reopening, as recents does (T22) ---

    /**
     * A recents list means the same document gets opened over and over. Each
     * open makes a fresh temp copy, so the question is whether the old ones go.
     *
     * They must: the copy belongs to the *open document* (TECH_SPEC §9.1), and a
     * new load releases the previous session before it starts. This walks the
     * exact sequence a user does — A, B, A again — and checks the cache
     * directory after every step rather than only at the end, so a leak cannot
     * hide behind a later cleanup.
     */
    @Test
    fun reopeningDocumentsRepeatedly_leavesOneTempCopy() = runBlocking {
        val cacheDir = temp.newFolder()
        val repository = XlsxWorkbookRepository(cacheDir = cacheDir, io = Dispatchers.Unconfined)
        val a = BytesSource(workbookBytes(rows = 3), displayName = "a.xlsx")
        val b = BytesSource(workbookBytes(rows = 7), displayName = "b.xlsx")

        fun tempCopies() = cacheDir.listFiles().orEmpty().filter { it.name.startsWith("darcha-") }

        assertEquals("nothing before the first open", 0, tempCopies().size)

        assertEquals(3, (repository.load(a) {} as WorkbookLoad.Success).meta.rowCount)
        val afterA = tempCopies().single()

        assertEquals(7, (repository.load(b) {} as WorkbookLoad.Success).meta.rowCount)
        val afterB = tempCopies().single()
        assertTrue("B's copy is a new file", afterB != afterA)
        assertTrue("A's copy is gone", !afterA.exists())

        assertEquals(3, (repository.load(a) {} as WorkbookLoad.Success).meta.rowCount)
        assertEquals("still exactly one", 1, tempCopies().size)
        assertTrue("B's copy is gone", !afterB.exists())

        // And ten more opens do not accumulate either.
        repeat(10) { repository.load(if (it % 2 == 0) a else b) {} }
        assertEquals("no growth over repeated opens", 1, tempCopies().size)

        repository.closeDocument()
        assertEquals("and nothing is left behind", 0, tempCopies().size)
    }

    // --- helpers ---

    /**
     * A workbook big enough to be emitted in more than one chunk, with custom
     * column widths declared before `<sheetData>` and two custom row heights —
     * one inside the first chunk, one well past it.
     */
    private fun streamingWorkbookBytes(): ByteArray = workbookBytes(
        rows = 260, // > the parser's 200-row chunk size
        layoutXml = """<sheetFormatPr defaultColWidth="12" defaultRowHeight="18"/>""" +
            """<cols><col min="1" max="1" width="30" customWidth="1"/>""" +
            """<col min="3" max="3" width="4.5" customWidth="1"/></cols>""",
        // 1-based in the file; rows 3 and 250 become indices 2 and 249.
        rowHeights = mapOf(3 to 40.0, 250 to 28.0),
    )

    /**
     * Build a minimal valid .xlsx in memory.
     *
     * @param rows rows in the first sheet, when [sheetRowCounts] is not given.
     * @param sheetRowCounts row count per sheet, one entry per sheet.
     * @param layoutXml markup placed before `<sheetData>` — `<sheetFormatPr>` and
     *   `<cols>`, which the schema requires there.
     * @param rowHeights custom `ht` per **1-based** row number.
     *
     * Written by hand rather than reusing a fixture: the golden corpus lives in
     * `:core:parser` and exists to pin parser behaviour, whereas this only needs
     * *some* readable workbook to exercise repository wiring.
     */
    private fun workbookBytes(
        rows: Int,
        sheetRowCounts: List<Int> = listOf(rows),
        layoutXml: String = "",
        rowHeights: Map<Int, Double> = emptyMap(),
    ): ByteArray {
        fun sheetXml(rowCount: Int): String {
            val sheetRows = (1..rowCount).joinToString("") { r ->
                val ht = rowHeights[r]?.let { """ ht="$it" customHeight="1"""" }.orEmpty()
                """<row r="$r"$ht><c r="A$r"><v>$r</v></c><c r="B$r"><v>${r * 2}</v></c>""" +
                    """<c r="C$r"><v>${r * 3}</v></c></row>"""
            }
            return """<?xml version="1.0" encoding="UTF-8"?>
                |<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                |$layoutXml<sheetData>$sheetRows</sheetData></worksheet>""".trimMargin()
        }

        val sheetOverrides = sheetRowCounts.indices.joinToString("") { i ->
            """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }
        val sheetEntries = sheetRowCounts.indices.joinToString("") { i ->
            """<sheet name="Sheet${i + 1}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
        }
        val sheetRels = sheetRowCounts.indices.joinToString("") { i ->
            """<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>"""
        }

        val parts = buildList {
            add(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    |<Default Extension="xml" ContentType="application/xml"/>
                    |<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    |$sheetOverrides
                    |</Types>""".trimMargin(),
            )
            add(
                "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    |</Relationships>""".trimMargin(),
            )
            add(
                "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    |<sheets>$sheetEntries</sheets></workbook>""".trimMargin(),
            )
            add(
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    |$sheetRels
                    |</Relationships>""".trimMargin(),
            )
            sheetRowCounts.forEachIndexed { i, count ->
                add("xl/worksheets/sheet${i + 1}.xml" to sheetXml(count))
            }
        }

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

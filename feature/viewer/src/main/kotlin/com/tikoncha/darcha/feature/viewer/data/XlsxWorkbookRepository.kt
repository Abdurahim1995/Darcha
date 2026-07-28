package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.parser.ParseResult
import com.tikoncha.darcha.parser.Workbook
import com.tikoncha.darcha.parser.XlsxParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Loads documents with `:core:parser`, off the main thread (TECH_SPEC §7).
 *
 * The pipeline is: copy the source's bytes into [cacheDir] (`ZipFile` needs a
 * real file), open the workbook, then stream a sheet's rows, reporting progress
 * as they arrive.
 *
 * **Session lifetime.** The temp copy belongs to the open *document*, not to the
 * initial parse: sheets are read lazily, so the file must outlive the first one.
 * It is released when another document is opened or [close] is called. (Deleting
 * it earlier appears to work on Linux, where an open descriptor survives unlink,
 * but that is an accident of the platform — not a contract.)
 *
 * Two safety caps guard against OOM (TECH_SPEC §13), both enforced **while**
 * data flows rather than after the fact — checking afterwards would be useless,
 * since by then the memory is already committed:
 * - [maxFileBytes] — bytes are counted during the copy and it aborts mid-stream.
 * - [maxCells] — cells are counted per chunk and the parse aborts mid-sheet.
 *
 * @param cacheDir directory for the temporary copy.
 * @param io dispatcher for the blocking copy and parse work.
 * @param maxFileBytes largest accepted document, in bytes.
 * @param maxCells largest accepted populated-cell count.
 */
public class XlsxWorkbookRepository(
    private val cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxCells: Int = MAX_CELLS,
) : WorkbookRepository {

    /** An open document: the parsed workbook plus the file backing it. */
    private class Session(
        val workbook: Workbook,
        val file: File,
        val displayName: String,
    ) {
        fun release() {
            try {
                workbook.close()
            } catch (_: IOException) {
                // Closing a ZipFile can fail on a vanished file; the delete below
                // is what actually matters.
            }
            file.delete()
        }
    }

    /** Serializes session changes: a new load can arrive while one is in flight. */
    private val mutex = Mutex()

    private var session: Session? = null

    override suspend fun load(
        source: WorkbookSource,
        onProgress: (Float) -> Unit,
    ): WorkbookLoad = withContext(io) {
        mutex.withLock {
            // The previous document is done the moment another is opened.
            releaseSession()

            // Cheap pre-check: reject an obviously oversized document before
            // copying a single byte. The value is only a hint, so the copy
            // re-checks as it goes.
            val declared = source.declaredSizeBytes
            if (declared != null && declared > maxFileBytes) {
                return@withLock tooLarge(declared)
            }

            val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, cacheDir)
            var opened: Workbook? = null
            var keepFile = false
            try {
                when (val copied = copyCapped(source, temp)) {
                    is CopyOutcome.Failed -> return@withLock WorkbookLoad.Failure(copied.kind)
                    CopyOutcome.Copied -> Unit
                }

                val workbook = when (val result = XlsxParser.open(temp)) {
                    is ParseResult.Ok -> result.value
                    is ParseResult.Err -> return@withLock WorkbookLoad.Failure(result.kind)
                }
                opened = workbook

                when (val read = readSheetFrom(workbook, source.displayName, 0, onProgress)) {
                    is WorkbookLoad.Success -> {
                        // Only a successful open starts a session — a failure keeps
                        // nothing alive and leaves no file behind.
                        session = Session(workbook, temp, source.displayName)
                        opened = null
                        keepFile = true
                        read
                    }
                    is WorkbookLoad.Failure -> read
                }
            } catch (e: IOException) {
                WorkbookLoad.Failure(
                    ErrorKind.Corrupted("could not read '${source.displayName}': ${e.message}"),
                )
            } finally {
                opened?.close()
                if (!keepFile) temp.delete()
            }
        }
    }

    override suspend fun readSheet(
        index: Int,
        onProgress: (Float) -> Unit,
    ): WorkbookLoad = withContext(io) {
        mutex.withLock {
            val open = session
                ?: return@withLock WorkbookLoad.Failure(ErrorKind.Corrupted("no document is open"))
            readSheetFrom(open.workbook, open.displayName, index, onProgress)
        }
    }

    override fun close() {
        releaseSession()
    }

    private fun releaseSession() {
        session?.release()
        session = null
    }

    /**
     * Delete temp copies left behind by a previous process. A crash or a kill
     * skips [close], so orphans accumulate in the cache until something sweeps
     * them; the app does this at startup.
     *
     * @return how many files were removed.
     */
    public suspend fun sweepStaleTempFiles(): Int = withContext(io) {
        val live = session?.file
        cacheDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(TEMP_PREFIX) && it.name.endsWith(TEMP_SUFFIX) }
            .filter { it != live }
            .count { it.delete() }
    }

    // --- copy ---

    private sealed interface CopyOutcome {
        data object Copied : CopyOutcome
        data class Failed(val kind: ErrorKind) : CopyOutcome
    }

    /**
     * Stream [source] into [target], counting bytes as they go and stopping the
     * moment [maxFileBytes] is passed — a provider that under-reports (or omits)
     * its size cannot make us write an unbounded file.
     */
    private suspend fun copyCapped(source: WorkbookSource, target: File): CopyOutcome {
        var total = 0L
        source.openStream().use { input: InputStream ->
            target.outputStream().use { output: OutputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive() // a newer OpenFile cancels this copy
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxFileBytes) return CopyOutcome.Failed(tooLargeKind(total))
                    output.write(buffer, 0, read)
                }
            }
        }
        return CopyOutcome.Copied
    }

    // --- parse ---

    /** Signals that [maxCells] was passed; thrown from the chunk callback. */
    private class CellCapExceeded(val cells: Int) : RuntimeException(null, null, false, false)

    private suspend fun readSheetFrom(
        workbook: Workbook,
        displayName: String,
        index: Int,
        onProgress: (Float) -> Unit,
    ): WorkbookLoad {
        if (index !in workbook.sheets.indices) {
            return WorkbookLoad.Failure(ErrorKind.Corrupted("no sheet at index $index"))
        }

        var cells = 0
        val job = coroutineContext
        val result = try {
            workbook.readSheet(index) { chunk ->
                job.ensureActive() // cooperative cancellation between chunks
                cells += chunk.rows.values.sumOf { it.size }
                // Abort the moment the cap is passed, rather than finishing the
                // parse and rejecting a model we already paid the memory for.
                if (cells > maxCells) throw CellCapExceeded(cells)
                onProgress(progressFor(chunk.rowsSoFar))
            }
        } catch (e: CellCapExceeded) {
            return WorkbookLoad.Failure(
                ErrorKind.TooLarge("sheet exceeds the $maxCells-cell limit (stopped at ${e.cells})"),
            )
        }

        return when (result) {
            is ParseResult.Ok -> WorkbookLoad.Success(
                DocumentMeta(
                    displayName = displayName,
                    sheetNames = workbook.sheets.map { it.name },
                    rowCount = result.value.data.rows.size,
                ),
            )
            is ParseResult.Err -> WorkbookLoad.Failure(result.kind)
        }
    }

    private fun tooLarge(bytes: Long) = WorkbookLoad.Failure(tooLargeKind(bytes))

    private fun tooLargeKind(bytes: Long) =
        ErrorKind.TooLarge("file is $bytes bytes, over the $maxFileBytes-byte limit")

    public companion object {

        /** Prefix of the temp copies, so stale ones can be recognized and swept. */
        private const val TEMP_PREFIX = "darcha-"
        private const val TEMP_SUFFIX = ".xlsx"

        /**
         * Largest document accepted, in bytes (50 MB).
         *
         * TECH_SPEC §5 targets typical files under 5 MB; this leaves an order of
         * magnitude of headroom while keeping the cache copy bounded.
         */
        public const val MAX_FILE_BYTES: Long = 50L * 1024 * 1024

        /**
         * Largest populated-cell count accepted (1,000,000).
         *
         * In the sparse model a cell costs roughly 32–40 bytes — 4 for its column
         * index, 4 for its style id, 8 for the reference, and 16–24 for the
         * `CellValue` object — so a million cells is about 35–40 MB. Twice that
         * would approach 80 MB and crowd the heap of a mid-range device once
         * rendering starts (TECH_SPEC §13).
         */
        public const val MAX_CELLS: Int = 1_000_000

        /**
         * Rows at which reported progress reaches one half. Progress approaches
         * 1 without arriving; [WorkbookLoad.Success] is what ends the wait.
         */
        private const val PROGRESS_HALFWAY_ROWS = 2_000f

        /**
         * Map rows parsed so far onto `0f..1f`.
         *
         * The parser cannot know the row total in advance, so this is an
         * asymptotic activity indicator rather than a true fraction. Deriving a
         * real fraction from the worksheet's `<dimension>` was considered and
         * rejected: our own `big-50k-rows.xlsx` fixture has no `<dimension>` at
         * all (openpyxl's write-only mode omits it), so a dimension-based hint
         * would fail on exactly the largest files, where progress matters most.
         */
        internal fun progressFor(rowsSoFar: Int): Float =
            rowsSoFar / (rowsSoFar + PROGRESS_HALFWAY_ROWS)
    }
}

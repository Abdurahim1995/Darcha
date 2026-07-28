package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.parser.ParseResult
import com.tikoncha.darcha.parser.Workbook
import com.tikoncha.darcha.parser.XlsxParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
 * real file), open the workbook, then stream the first sheet's rows, reporting
 * progress as they arrive.
 *
 * Two safety caps guard against OOM (TECH_SPEC §13), both enforced **while**
 * data flows rather than after the fact — checking afterwards would be useless,
 * since by then the memory is already committed:
 * - [maxFileBytes] — bytes are counted during the copy and it aborts mid-stream.
 * - [maxCells] — cells are counted per chunk and the parse aborts mid-sheet.
 *
 * @param cacheDir directory for the temporary copy; the file is deleted after loading.
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

    override suspend fun load(
        source: WorkbookSource,
        onProgress: (Float) -> Unit,
    ): WorkbookLoad = withContext(io) {
        // Cheap pre-check: reject an obviously oversized document before copying
        // a single byte. The value is only a hint, so the copy re-checks.
        val declared = source.declaredSizeBytes
        if (declared != null && declared > maxFileBytes) {
            return@withContext tooLarge(declared)
        }

        val temp = File.createTempFile("darcha-", ".xlsx", cacheDir)
        try {
            when (val copied = copyCapped(source, temp)) {
                is CopyOutcome.Failed -> return@withContext WorkbookLoad.Failure(copied.kind)
                CopyOutcome.Copied -> Unit
            }
            readWorkbook(temp, source.displayName, onProgress)
        } catch (e: IOException) {
            WorkbookLoad.Failure(ErrorKind.Corrupted("could not read '${source.displayName}': ${e.message}"))
        } finally {
            temp.delete()
        }
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

    private suspend fun readWorkbook(
        file: File,
        displayName: String,
        onProgress: (Float) -> Unit,
    ): WorkbookLoad {
        val workbook = when (val opened = XlsxParser.open(file)) {
            is ParseResult.Ok -> opened.value
            is ParseResult.Err -> return WorkbookLoad.Failure(opened.kind)
        }
        return workbook.use { readFirstSheet(it, displayName, onProgress) }
    }

    private suspend fun readFirstSheet(
        workbook: Workbook,
        displayName: String,
        onProgress: (Float) -> Unit,
    ): WorkbookLoad {
        if (workbook.sheets.isEmpty()) {
            return WorkbookLoad.Failure(ErrorKind.Corrupted("workbook declares no sheets"))
        }

        var cells = 0
        val job = coroutineContext
        val result = try {
            workbook.readSheet(index = 0) { chunk ->
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

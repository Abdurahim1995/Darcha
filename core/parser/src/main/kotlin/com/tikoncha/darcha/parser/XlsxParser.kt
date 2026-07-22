package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.model.SheetRef
import com.tikoncha.darcha.model.StringTable
import com.tikoncha.darcha.model.StyleTable
import com.tikoncha.darcha.model.WorkbookMeta
import com.tikoncha.darcha.model.Worksheet
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The public entry point of the XLSX parser (TECH_SPEC §7, §10).
 *
 * [open] eagerly validates the container and parses workbook metadata, shared
 * strings, and styles; individual worksheets are then read lazily and
 * progressively via [Workbook.readSheet]. Every failure is an [ErrorKind]; no
 * raw exception escapes. The parser is thread-agnostic — it uses no coroutines
 * or dispatchers, and the caller controls threading.
 */
public object XlsxParser {

    /**
     * Open [file] as an XLSX workbook, eagerly parsing metadata, shared strings,
     * and styles. On success the returned [Workbook] owns an open file handle and
     * MUST be closed (it is [Closeable]).
     */
    public fun open(file: File): ParseResult<Workbook> {
        val detected = ContainerDetector.detect(file)
        if (detected is ParseResult.Err) return detected

        val zip = try {
            ZipFile(file)
        } catch (e: IOException) {
            return ParseResult.Err(ErrorKind.Corrupted("could not open '${file.name}': ${e.message}"))
        }

        return when (val eager = readEager(zip)) {
            is ParseResult.Ok -> eager
            is ParseResult.Err -> {
                zip.closeQuietly()
                eager
            }
        }
    }

    private fun readEager(zip: ZipFile): ParseResult<Workbook> {
        val meta = when (val r = WorkbookParser.parse(zip)) {
            is ParseResult.Ok -> r.value
            is ParseResult.Err -> return r
        }
        val sharedStrings = when (val r = SharedStringsParser.parse(zip)) {
            is ParseResult.Ok -> r.value
            is ParseResult.Err -> return r
        }
        val styles = when (val r = StylesParser.parse(zip)) {
            is ParseResult.Ok -> r.value
            is ParseResult.Err -> return r
        }
        return ParseResult.Ok(Workbook(zip, meta, sharedStrings, styles))
    }

    private fun ZipFile.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // Best effort — we are already returning an error.
        }
    }
}

/**
 * An open workbook: eagerly-parsed metadata plus lazy, progressive worksheet
 * reading (TECH_SPEC §7). Holds an open file handle, so call [close] when done
 * (or use it as a [Closeable] resource).
 *
 * Instances are created only by [XlsxParser.open].
 *
 * @property sharedStrings the workbook's shared string table.
 * @property styles the workbook's resolved cell-style table.
 */
public class Workbook internal constructor(
    private val zip: ZipFile,
    private val meta: WorkbookMeta,
    public val sharedStrings: StringTable,
    public val styles: StyleTable,
) : Closeable {

    /** Whether serial dates use the 1904 epoch (default `false`, the 1900 epoch). */
    public val date1904: Boolean get() = meta.date1904

    /** The worksheets in document order. */
    public val sheets: List<SheetRef> get() = meta.sheets

    /**
     * Read the worksheet at [index] into a [Worksheet].
     *
     * Rows are streamed: [onChunk] is invoked with each batch of up to
     * [chunkSize] rows as they accumulate, and the complete worksheet (cells +
     * layout) is returned. Runs on the calling thread — there is no internal
     * threading. Returns [ErrorKind.Corrupted] for an out-of-range [index] or a
     * missing/malformed worksheet part.
     */
    public fun readSheet(
        index: Int,
        chunkSize: Int = 200,
        onChunk: (RowsChunk) -> Unit = {},
    ): ParseResult<Worksheet> {
        val ref = meta.sheets.getOrNull(index)
            ?: return ParseResult.Err(ErrorKind.Corrupted("no sheet at index $index"))
        return SheetParser.parse(zip, ref.partPath, chunkSize, onChunk)
    }

    /** Release the underlying file handle. */
    override fun close() {
        zip.close()
    }
}

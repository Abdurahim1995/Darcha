package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.ErrorKind
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Recognized binary container of a candidate spreadsheet file (TECH_SPEC §7
 * step 1). Only [ZIP] is a readable OOXML `.xlsx`; [OLE] is reported to callers
 * as [ErrorKind.Encrypted].
 */
internal enum class Container {
    /** ZIP / OOXML package. Magic `50 4B 03 04`. */
    ZIP,

    /** OLE2 / CFB compound document. Magic `D0 CF 11 E0 A1 B1 1A E1`. */
    OLE,
}

/**
 * Classifies a file by its leading magic bytes before any ZIP or XML parsing is
 * attempted (TECH_SPEC §7 step 1). This is the first gate of the pipeline: it
 * turns an unknown blob into either "proceed" or a precise [ErrorKind].
 *
 * Mapping:
 * - ZIP magic  → [ParseResult.Ok] of [Container.ZIP] (safe to proceed)
 * - OLE magic  → [ParseResult.Err] of [ErrorKind.Encrypted]
 * - otherwise  → [ParseResult.Err] of [ErrorKind.Corrupted] (garbage, empty, too short)
 *
 * Never throws: I/O failures on the [File]/[InputStream] overloads are mapped to
 * [ErrorKind.Corrupted], preserving the underlying exception message.
 */
internal object ContainerDetector {

    /** ZIP local-file-header magic: `PK\x03\x04`. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** OLE2 / CFB magic: `D0 CF 11 E0 A1 B1 1A E1`. */
    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    /** Leading bytes needed to distinguish every recognized container. */
    private const val HEADER_LEN: Int = 8

    /**
     * Detect from an in-memory prefix. Only the first [HEADER_LEN] bytes are
     * inspected; a shorter array is handled safely (too short → [ErrorKind.Corrupted]).
     */
    fun detect(bytes: ByteArray): ParseResult<Container> =
        when (classify(bytes)) {
            Container.ZIP -> ParseResult.Ok(Container.ZIP)
            Container.OLE -> ParseResult.Err(
                ErrorKind.Encrypted("OLE/CFB container: password-protected .xlsx or legacy .xls"),
            )
            null -> ParseResult.Err(
                ErrorKind.Corrupted("Unrecognized container: not a ZIP (OOXML) or OLE file"),
            )
        }

    /**
     * Detect from a stream.
     *
     * Reads (and therefore **advances**) up to [HEADER_LEN] bytes from [input],
     * and does **not** close it — the caller owns the stream and must close it,
     * and must reopen or reset it before any further reading, since these bytes
     * have already been consumed.
     */
    fun detect(input: InputStream): ParseResult<Container> =
        try {
            detect(input.readHeader(HEADER_LEN))
        } catch (e: IOException) {
            ParseResult.Err(ErrorKind.Corrupted("Could not read stream: ${e.message}"))
        }

    /**
     * Detect from a file, reading only its leading bytes. Opens and closes its
     * own stream, and never throws: any [IOException] (missing/unreadable file)
     * becomes [ErrorKind.Corrupted] with the exception message preserved.
     */
    fun detect(file: File): ParseResult<Container> =
        try {
            file.inputStream().use { detect(it.readHeader(HEADER_LEN)) }
        } catch (e: IOException) {
            ParseResult.Err(ErrorKind.Corrupted("Could not read file '${file.name}': ${e.message}"))
        }

    /** Classify [bytes] by magic prefix, or `null` if it matches no known container. */
    private fun classify(bytes: ByteArray): Container? = when {
        bytes.startsWith(ZIP_MAGIC) -> Container.ZIP
        bytes.startsWith(OLE_MAGIC) -> Container.OLE
        else -> null
    }
}

/** True iff this array is at least as long as [magic] and shares its prefix. */
private fun ByteArray.startsWith(magic: ByteArray): Boolean {
    if (size < magic.size) return false
    for (i in magic.indices) {
        if (this[i] != magic[i]) return false
    }
    return true
}

/**
 * Read up to [n] bytes from the front of the stream into a right-sized array
 * (shorter than [n] only if the stream ends early). Handles partial reads.
 */
private fun InputStream.readHeader(n: Int): ByteArray {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(buf, read, n - read)
        if (r < 0) break
        read += r
    }
    return if (read == n) buf else buf.copyOf(read)
}

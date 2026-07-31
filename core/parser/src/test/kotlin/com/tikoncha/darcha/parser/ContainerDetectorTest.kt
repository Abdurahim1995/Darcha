package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Golden tests for container detection (TECH_SPEC §7 step 1). Cases are pure
 * byte arrays, plus one real fixture from the corpus to prove the stream path
 * on genuine XLSX bytes.
 */
class ContainerDetectorTest {

    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private val ODS_RENAMED = "/fixtures/synthetic/ods-renamed.xlsx"
    private val ODS_MEDIA_TYPE = "application/vnd.oasis.opendocument.spreadsheet"

    private val oleMagic = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    // --- the T2 byte-array cases ---

    @Test
    fun zipBytes_detectedAsZip() {
        val bytes = zipMagic + byteArrayOf(0x14, 0x00, 0x00, 0x00) // header + filler
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(bytes))
    }

    @Test
    fun oleBytes_detectedAsEncrypted() {
        assertErr<ErrorKind.Encrypted>(ContainerDetector.detect(oleMagic))
    }

    @Test
    fun garbageBytes_detectedAsCorrupted() {
        val garbage = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
        assertErr<ErrorKind.Corrupted>(ContainerDetector.detect(garbage))
    }

    @Test
    fun emptyBytes_detectedAsCorrupted() {
        assertErr<ErrorKind.Corrupted>(ContainerDetector.detect(ByteArray(0)))
    }

    @Test
    fun threeByteFile_detectedAsCorrupted() {
        // The first 3 bytes of the ZIP magic must not be mistaken for a ZIP.
        assertErr<ErrorKind.Corrupted>(ContainerDetector.detect(byteArrayOf(0x50, 0x4B, 0x03)))
    }

    // --- boundary cases ---

    @Test
    fun exactlyFourZipBytes_detectedAsZip() {
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(zipMagic))
    }

    @Test
    fun oleMagicTruncatedToSeven_detectedAsCorrupted() {
        // OLE needs all 8 magic bytes; 7 is not enough to claim Encrypted.
        assertErr<ErrorKind.Corrupted>(ContainerDetector.detect(oleMagic.copyOf(7)))
    }

    // --- stream + real fixture ---

    @Test
    fun stream_zip_detectedAsZip() {
        val stream = ByteArrayInputStream(zipMagic + ByteArray(64))
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(stream))
    }

    @Test
    fun realFixture_valuesBasic_detectedAsZip() {
        val stream = javaClass.getResourceAsStream("/fixtures/synthetic/values-basic.xlsx")
            ?: error("fixture not found on test classpath")
        stream.use {
            assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(it))
        }
    }

    // --- OpenDocument detection (T27) ---

    /**
     * The fixture must really have the layout the detector relies on.
     *
     * The detector and the fixture generator were written by the same author, so
     * "the detector accepts the fixture" on its own proves nothing — it would
     * pass just as happily if both agreed on a layout that no real ODF file uses.
     * This test asserts the three rules straight out of OpenDocument v1.2 §3.3,
     * independently of what the detector believes.
     */
    @Test
    fun odsFixture_hasTheLayoutTheSpecRequires() {
        val bytes = readFixture(ODS_RENAMED)

        assertEquals("local file header", "PK", String(bytes, 0, 4, Charsets.ISO_8859_1))
        assertEquals("compression method must be STORED", 0, le16(bytes, 8))
        assertEquals("extra field length must be 0", 0, le16(bytes, 28))
        assertEquals("file name length", 8, le16(bytes, 26))
        assertEquals("first entry name", "mimetype", String(bytes, 30, 8, Charsets.US_ASCII))

        val declared = le32(bytes, 22)
        assertEquals(
            "media type at the fixed offset",
            "application/vnd.oasis.opendocument.spreadsheet",
            String(bytes, 38, declared, Charsets.US_ASCII),
        )
    }

    @Test
    fun renamedOds_detectedAsUnsupported_notCorrupted() {
        // The whole point of T27: an intact spreadsheet of the wrong kind is
        // "not supported", not "damaged". Corrupted would be a lie about a file
        // that is perfectly fine.
        assertErr<ErrorKind.Unsupported>(ContainerDetector.detect(readFixture(ODS_RENAMED)))
    }

    @Test
    fun renamedOds_viaStream_detectedAsUnsupported() {
        javaClass.getResourceAsStream(ODS_RENAMED)!!.use {
            assertErr<ErrorKind.Unsupported>(ContainerDetector.detect(it))
        }
    }

    @Test
    fun theMessageNamesTheMediaTypeItFound() {
        val result = ContainerDetector.detect(readFixture(ODS_RENAMED)) as ParseResult.Err
        assertTrue(
            "diagnostic should name what was detected, was '${result.kind.message}'",
            result.kind.message?.contains("opendocument.spreadsheet") == true,
        )
    }

    @Test
    fun everyOdfFlavour_isUnsupported() {
        // Reading the media type costs the same whatever it says, so a .odt or
        // .odp renamed to .xlsx gets the honest answer too rather than "damaged".
        for (flavour in listOf("text", "presentation", "graphics", "spreadsheet-template")) {
            val mediaType = "application/vnd.oasis.opendocument.$flavour"
            assertErr<ErrorKind.Unsupported>(ContainerDetector.detect(odfHeader(mediaType)))
        }
    }

    // --- the strictness rules: a deviation must MISS, never guess wrong ---

    @Test
    fun deflatedMimetype_isNotClaimedAsOdf() {
        // Compression method 8 breaks §3.3, so the media type is not where we
        // would read it. Fall through to ZIP rather than decode whatever is there.
        val bytes = odfHeader(ODS_MEDIA_TYPE).also { it.putLe16(8, 8) }
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(bytes))
    }

    @Test
    fun mimetypeWithExtraField_isNotClaimedAsOdf() {
        val bytes = odfHeader(ODS_MEDIA_TYPE).also { it.putLe16(28, 4) }
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(bytes))
    }

    @Test
    fun aZipWhoseFirstEntryIsNotMimetype_isNotClaimedAsOdf() {
        val bytes = odfHeader(ODS_MEDIA_TYPE)
        "mimetypX".toByteArray(Charsets.US_ASCII).copyInto(bytes, 30)
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(bytes))
    }

    @Test
    fun aStoredFirstEntryThatIsNotOdf_isStillAPlainZip() {
        // An OOXML package may legitimately store its first entry uncompressed.
        // Only the ODF media-type prefix may trigger Unsupported.
        val bytes = odfHeader("application/vnd.openxmlformats-officedocument.x")
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(bytes))
    }

    @Test
    fun aTruncatedOdfHeader_isNotClaimedAsOdf() {
        // Cut mid-media-type: the declared length runs past the buffer.
        val bytes = odfHeader(ODS_MEDIA_TYPE).copyOf(60)
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(bytes))
    }

    @Test
    fun anAbsurdDeclaredLength_doesNotOverrun() {
        // 0xFFFFFFFF is the ZIP64 sentinel and would be a negative Int if read
        // carelessly — a negative length must not sneak past the range check.
        val bytes = odfHeader(ODS_MEDIA_TYPE)
        for (i in 22..25) bytes[i] = 0xFF.toByte()
        assertEquals(ParseResult.Ok(Container.ZIP), ContainerDetector.detect(bytes))
    }

    @Test
    fun realXlsxFixtures_areStillPlainZips() {
        // The regression that matters most: T27 must not start calling ordinary
        // workbooks unsupported.
        for (name in listOf(
            "/fixtures/synthetic/values-basic.xlsx",
            "/fixtures/synthetic/strings-shared.xlsx",
            "/fixtures/excel/styles-basic.xlsx",
            "/fixtures/excel/merged.xlsx",
        )) {
            assertEquals(name, ParseResult.Ok(Container.ZIP), ContainerDetector.detect(readFixture(name)))
        }
    }

    // --- helpers ---

    private fun readFixture(path: String): ByteArray =
        javaClass.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("fixture not found on test classpath: $path")

    /** A synthetic ODF-shaped local file header carrying [mediaType]. */
    private fun odfHeader(mediaType: String): ByteArray {
        val media = mediaType.toByteArray(Charsets.US_ASCII)
        val bytes = ByteArray(38 + media.size + 16)
        zipMagic.copyInto(bytes, 0)
        bytes.putLe16(8, 0) // STORED
        bytes.putLe32(18, media.size) // compressed size
        bytes.putLe32(22, media.size) // uncompressed size
        bytes.putLe16(26, 8) // "mimetype".length
        bytes.putLe16(28, 0) // no extra field
        "mimetype".toByteArray(Charsets.US_ASCII).copyInto(bytes, 30)
        media.copyInto(bytes, 38)
        return bytes
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putLe32(offset: Int, value: Int) {
        for (i in 0 until 4) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    /** Assert [result] is an [ParseResult.Err] whose kind is exactly [E]. */
    private inline fun <reified E : ErrorKind> assertErr(result: ParseResult<Container>) {
        assertTrue(
            "expected Err(${E::class.simpleName}) but was $result",
            result is ParseResult.Err && result.kind is E,
        )
    }
}

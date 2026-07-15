package com.tikoncha.katak.parser

import com.tikoncha.katak.model.ErrorKind
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

    /** Assert [result] is an [ParseResult.Err] whose kind is exactly [E]. */
    private inline fun <reified E : ErrorKind> assertErr(result: ParseResult<Container>) {
        assertTrue(
            "expected Err(${E::class.simpleName}) but was $result",
            result is ParseResult.Err && result.kind is E,
        )
    }
}

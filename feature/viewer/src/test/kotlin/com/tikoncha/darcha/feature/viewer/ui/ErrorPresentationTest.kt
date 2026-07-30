package com.tikoncha.darcha.feature.viewer.ui

import com.tikoncha.darcha.model.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How failures are presented (T23).
 *
 * The copy itself is in `strings.xml` and is checked by a separate test that
 * reads the file; this pins the decisions that are code — which kind gets which
 * screen, and which offers a Retry that could actually work.
 */
class ErrorPresentationTest {

    private val allKinds = listOf(
        ErrorKind.Encrypted(),
        ErrorKind.Corrupted(),
        ErrorKind.Unsupported(),
        ErrorKind.TooLarge(),
        ErrorKind.Unreadable(),
    )

    @Test
    fun everyKindHasItsOwnWords() {
        val titles = allKinds.map { it.presentation().titleRes }
        assertEquals("no two kinds may share a title", titles.size, titles.toSet().size)
        val bodies = allKinds.map { it.presentation().bodyRes }
        assertEquals("no two kinds may share a body", bodies.size, bodies.toSet().size)
    }

    /**
     * The distinction T23 exists to draw: a file we could not read is not a file
     * that is damaged, and the two must not look the same.
     */
    @Test
    fun unreadableIsNotPresentedAsDamaged() {
        val unreadable = ErrorKind.Unreadable().presentation()
        val corrupted = ErrorKind.Corrupted().presentation()

        assertNotEquals(corrupted.titleRes, unreadable.titleRes)
        assertNotEquals(corrupted.bodyRes, unreadable.bodyRes)
        assertNotEquals(corrupted.icon, unreadable.icon)
    }

    /**
     * Retry is only offered where it could work. A password, a damaged file, an
     * unsupported format and a file too big all fail identically on a second
     * attempt; a permission or a missing file might not.
     */
    @Test
    fun onlyUnreadableOffersRetry() {
        assertTrue(ErrorKind.Unreadable().presentation().retryable)
        for (kind in allKinds - ErrorKind.Unreadable()) {
            assertFalse("$kind should not offer Retry", kind.presentation().retryable)
        }
    }

    @Test
    fun theMessagePayloadNeverReachesThePresentation() {
        // Diagnostics are for the log. Two failures of the same kind must present
        // identically however different their internal detail.
        val terse = ErrorKind.Corrupted().presentation()
        val chatty = ErrorKind.Corrupted("EOCD not found at offset 0x4f2b").presentation()
        assertEquals(terse, chatty)
    }

    @Test
    fun encryptedIsTheOnlyLockedOne() {
        val encrypted = ErrorKind.Encrypted().presentation()
        for (kind in allKinds - ErrorKind.Encrypted()) {
            assertNotEquals("only a locked file gets the lock", encrypted.icon, kind.presentation().icon)
        }
    }
}

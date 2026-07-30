package com.tikoncha.darcha.feature.viewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recents encoding (T22).
 *
 * Two things matter here and neither is obvious from the format: a file name can
 * contain anything, and stored data outlives the code that wrote it. So the
 * tests are round-trips over nasty names, plus a set of malformed inputs that
 * must degrade rather than throw.
 */
class RecentsCodecTest {

    private fun doc(
        id: String = "content://x/1",
        name: String = "book.xlsx",
        lastOpened: Long = 1_700_000_000_000,
        size: Long? = 4096,
    ) = RecentDocument(id = id, displayName = name, lastOpened = lastOpened, sizeBytes = size)

    private fun roundTrip(vararg documents: RecentDocument) =
        RecentsCodec.decode(RecentsCodec.encode(documents.toList()))

    @Test
    fun anEmptyListRoundTrips() {
        assertEquals(emptyList<RecentDocument>(), roundTrip().toList())
        assertEquals(emptyList<RecentDocument>(), RecentsCodec.decode(""))
    }

    @Test
    fun documentsRoundTripInOrder() {
        val a = doc(id = "content://a", name = "first.xlsx", lastOpened = 3)
        val b = doc(id = "content://b", name = "second.xlsx", lastOpened = 2)
        val c = doc(id = "content://c", name = "third.xlsx", lastOpened = 1)
        assertEquals(listOf(a, b, c), roundTrip(a, b, c))
    }

    @Test
    fun aMissingSizeSurvives() {
        val document = doc(size = null)
        assertEquals(listOf(document), roundTrip(document))
    }

    /** File names are user data: tabs, newlines and backslashes are all legal. */
    @Test
    fun namesWithSeparatorsRoundTrip() {
        val nasty = listOf(
            "a\tb.xlsx",
            "line\nbreak.xlsx",
            "back\\slash.xlsx",
            "escaped\\tnot-a-tab.xlsx",
            "\\\\.xlsx",
            "Ҳисобот 2024 — копия.xlsx",
            "",
        )
        for (name in nasty) {
            val document = doc(name = name)
            assertEquals("name $name", listOf(document), roundTrip(document))
        }
    }

    @Test
    fun aNameFullOfSeparatorsCannotSplitTheRecord() {
        val a = doc(id = "content://a", name = "x\ty\nz")
        val b = doc(id = "content://b", name = "plain.xlsx")
        assertEquals(listOf(a, b), roundTrip(a, b))
    }

    // --- decoding what we did not write ---

    /**
     * Stored data outlives its writer. One unreadable record must cost that
     * record, not the whole list.
     */
    @Test
    fun malformedRecordsAreSkippedAndTheRestSurvives() {
        val good = doc(id = "content://good", name = "keep.xlsx")
        val encoded = listOf(
            "not-enough-fields",
            "content://bad\tname\tnot-a-number\t100",
            "\tname\t1\t100", // no id
            RecentsCodec.encode(listOf(good)),
            "too\tmany\t1\t2\t3\t4",
        ).joinToString("\n")

        assertEquals(listOf(good), RecentsCodec.decode(encoded))
    }

    @Test
    fun garbageDecodesToNothingRatherThanThrowing() {
        for (garbage in listOf("\n\n\n", "\t\t\t", "💥", "\\", "a\tb\tc\td\te")) {
            val decoded = RecentsCodec.decode(garbage)
            assertTrue("input $garbage", decoded.isEmpty() || decoded.all { it.id.isNotEmpty() })
        }
    }

    @Test
    fun anUnrecognisedEscapeKeepsBothCharacters() {
        // "\q" is not an escape we emit; decoding must not swallow the q.
        val decoded = RecentsCodec.decode("content://a\tweird\\qname\t1\t")
        assertEquals("weird\\qname", decoded.single().displayName)
    }

    /** `available` describes the world now, so it is never written or read back. */
    @Test
    fun availabilityIsNotPersisted() {
        val unavailable = doc().copy(available = false)
        assertTrue(RecentsCodec.decode(RecentsCodec.encode(listOf(unavailable))).single().available)
    }
}

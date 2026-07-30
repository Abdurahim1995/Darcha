package com.tikoncha.darcha.feature.viewer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule that user-facing copy must not name internals (T23).
 *
 * Read straight out of `strings.xml` rather than through resources, so it runs
 * on a plain JVM and fails the moment someone adds a string that leaks a term
 * the reader cannot act on. It is a lint rule that happens to be a test.
 */
class ErrorCopyTest {

    private val strings: String = File("src/main/res/values/strings.xml").readText()

    private val values: List<String> = Regex("<string name=\"[^\"]+\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        .findAll(strings)
        .map { it.groupValues[1] }
        .toList()

    /**
     * Words that mean something to us and nothing to the person reading them.
     * Anyone tempted to add one should write what the reader should *do* instead.
     */
    private val forbidden = listOf(
        "OOXML", "ZIP", "ErrorKind", "XML", "URI", "content://", "SAF",
        "parser", "exception", "null", "stream", "buffer", "DataStore",
        "container", "CFB", "OLE", "sharedStrings", "worksheet part",
    )

    @Test
    fun thereIsCopyToCheck() {
        assertTrue("expected to find strings", values.size > 15)
    }

    /**
     * Matched on **word boundaries**, not as substrings. A plain `contains`
     * flagged "safely" for containing "SAF" — a false positive that would push
     * whoever hit it into rewording perfectly good copy to satisfy the test.
     * Terms carrying punctuation are matched literally, since `\b` means nothing
     * beside a colon.
     */
    private fun mentions(value: String, term: String): Boolean =
        if (term.any { !it.isLetterOrDigit() }) {
            value.contains(term, ignoreCase = true)
        } else {
            Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
        }

    @Test
    fun noCopyNamesAnInternal() {
        for (value in values) {
            for (word in forbidden) {
                assertTrue(
                    "user-facing copy must not mention \"$word\": \"$value\"",
                    !mentions(value, word),
                )
            }
        }
    }

    /** The matcher itself, so a false positive cannot quietly come back. */
    @Test
    fun theMatcherLooksForWordsNotFragments() {
        assertTrue("plain word", mentions("we could not open the ZIP", "ZIP"))
        assertTrue("case-insensitive", mentions("a zip file", "ZIP"))
        assertTrue("punctuated terms match literally", mentions("open content://x", "content://"))
        assertTrue("substrings must not match", !mentions("this phone can safely open it", "SAF"))
        assertTrue("nor inside longer words", !mentions("a nullable value", "null"))
        assertTrue("nor mid-word", !mentions("unzipped already", "ZIP"))
    }

    /** Every error body should tell the reader what to do, not just what broke. */
    @Test
    fun everyErrorBodyOffersAWayForward() {
        val bodies = Regex("<string name=\"(error_[a-z_]+_body)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(strings)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        assertTrue("expected several error bodies", bodies.size >= 5)
        for ((name, body) in bodies) {
            assertTrue(
                "$name should suggest an action: \"$body\"",
                listOf("Try", "Open", "Save", "Remove", "Split").any { body.contains(it) },
            )
            assertTrue("$name reads too long for one screen", body.length < 200)
        }
    }

    /** Titles are a glance, not a paragraph. */
    @Test
    fun errorTitlesAreShort() {
        val titles = Regex("<string name=\"error_[a-z_]+_title\">(.*?)</string>")
            .findAll(strings)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(titles.size >= 5)
        for (title in titles) {
            assertTrue("too long for a title: \"$title\"", title.length <= 45)
            assertTrue("a title should not end in a full stop: \"$title\"", !title.endsWith("."))
        }
    }
}

package com.tikoncha.darcha.feature.viewer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rules user-facing copy must follow (T23), applied to **every** language
 * Darcha ships (T24).
 *
 * Read straight out of the `strings.xml` files rather than through resources, so
 * it runs on a plain JVM and fails the moment someone adds a string that leaks a
 * term the reader cannot act on. It is a lint rule that happens to be a test.
 *
 * Uzbek is checked by the same rules as English rather than being trusted
 * because it is a translation: a leaked internal is a leaked internal in any
 * language, and Uzbek is the primary audience, not a secondary one.
 */
class ErrorCopyTest {

    private data class Locale(val name: String, val path: String)

    private val locales = listOf(
        Locale("default (en)", "src/main/res/values/strings.xml"),
        Locale("uz", "src/main/res/values-uz/strings.xml"),
    )

    private fun source(locale: Locale): String = File(locale.path).readText()

    private fun stringsIn(locale: Locale): Map<String, String> =
        Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(source(locale))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val strings: String = source(locales.first())

    private val values: List<String> = stringsIn(locales.first()).values.toList()

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
        for (locale in locales) {
            for ((name, value) in stringsIn(locale)) {
                for (word in forbidden) {
                    assertTrue(
                        "[${locale.name}] $name must not mention \"$word\": \"$value\"",
                        !mentions(value, word),
                    )
                }
            }
        }
    }

    /** Every language carries every string, or the UI falls back mid-screen. */
    @Test
    fun everyLocaleTranslatesEveryString() {
        val reference = stringsIn(locales.first()).keys
        for (locale in locales.drop(1)) {
            val translated = stringsIn(locale).keys
            assertTrue("[${locale.name}] missing: ${reference - translated}", (reference - translated).isEmpty())
            assertTrue("[${locale.name}] unknown: ${translated - reference}", (translated - reference).isEmpty())
        }
    }

    /**
     * A "translation" that is still the English string is worse than none: it
     * looks finished and is not. Names and a few genuinely shared words are
     * allowed to match.
     */
    @Test
    fun uzbekCopyIsActuallyUzbek() {
        val allowedToMatch = setOf("app_title", "home_recent_generic_subtitle")
        val english = stringsIn(locales.first())
        val uzbek = stringsIn(locales.last())
        val untouched = english.keys
            .filterNot { it in allowedToMatch }
            .filter { english[it] == uzbek[it] }
        assertTrue("still in English in values-uz: $untouched", untouched.isEmpty())
    }

    /** The date names the formatter spells out are localized too (T16/T24). */
    @Test
    fun everyLocaleNamesItsMonthsAndDays() {
        for (locale in locales) {
            val text = source(locale)
            val months = Regex("<string-array name=\"month_names\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1).orEmpty()
            val days = Regex("<string-array name=\"day_names\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1).orEmpty()
            assertTrue("[${locale.name}] needs 12 months", Regex("<item>").findAll(months).count() == 12)
            assertTrue("[${locale.name}] needs 7 days", Regex("<item>").findAll(days).count() == 7)
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
        // Verbs that mean "do this next", per language.
        val actionWords = mapOf(
            "default (en)" to listOf("Try", "Open", "Save", "Remove", "Split"),
            "uz" to listOf("oching", "saqlang", "yuklab", "sinab", "ajrating", "olib tashlang"),
        )
        for (locale in locales) {
            val bodies = stringsIn(locale).filterKeys { it.startsWith("error_") && it.endsWith("_body") }
            assertTrue("[${locale.name}] expected several error bodies", bodies.size >= 5)
            for ((name, body) in bodies) {
                assertTrue(
                    "[${locale.name}] $name should suggest an action: \"$body\"",
                    actionWords.getValue(locale.name).any { body.contains(it, ignoreCase = true) },
                )
                assertTrue("[${locale.name}] $name reads too long for one screen", body.length < 220)
            }
        }
    }

    /** Titles are a glance, not a paragraph. */
    @Test
    fun errorTitlesAreShort() {
        for (locale in locales) {
            val titles = stringsIn(locale).filterKeys { it.startsWith("error_") && it.endsWith("_title") }
            assertTrue("[${locale.name}] expected several titles", titles.size >= 5)
            for ((name, title) in titles) {
                assertTrue("[${locale.name}] $name too long: \"$title\"", title.length <= 50)
                assertTrue("[${locale.name}] $name should not end in a full stop", !title.endsWith("."))
            }
        }
    }
}

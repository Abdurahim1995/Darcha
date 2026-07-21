package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.model.StringTable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Parses `xl/sharedStrings.xml` into a [StringTable] (TECH_SPEC §7 step 3).
 * Streaming only — no DOM.
 *
 * Each `<si>` is one shared string. Both plain (`<si><t>…</t></si>`) and rich
 * text (`<si><r><t>…</t></r>…</si>`) entries are flattened by concatenating all
 * `<t>` text inside the `<si>` — run formatting (`<rPr>`) is out of scope for
 * v1. `xml:space="preserve"` whitespace is kept verbatim (text is never
 * trimmed).
 *
 * A missing `sharedStrings.xml` is valid and yields [StringTable.EMPTY].
 */
internal object SharedStringsParser {

    private const val SHARED_STRINGS_PART = "xl/sharedStrings.xml"

    /** Read [zip]'s shared string part, or [StringTable.EMPTY] if it is absent. */
    fun parse(zip: ZipFile): ParseResult<StringTable> =
        try {
            val entry = zip.getEntry(SHARED_STRINGS_PART)
                ?: return ParseResult.Ok(StringTable.EMPTY)
            zip.getInputStream(entry).use { ParseResult.Ok(parseSharedStrings(it)) }
        } catch (e: XmlPullParserException) {
            ParseResult.Err(ErrorKind.Corrupted("malformed $SHARED_STRINGS_PART: ${e.message}"))
        } catch (e: IOException) {
            ParseResult.Err(ErrorKind.Corrupted("could not read $SHARED_STRINGS_PART: ${e.message}"))
        }

    /**
     * Stream-parse a `sharedStrings.xml` document. Accumulates every `<t>` text
     * node inside each `<si>`, so plain and rich-text entries flatten the same
     * way. (v1 limitation: phonetic runs `<rPh><t>` are not excluded — no
     * fixture exercises them yet; revisit per the fixture rule if one does.)
     */
    internal fun parseSharedStrings(input: InputStream): StringTable {
        val parser = Xml.newPullParser(input)
        val entries = ArrayList<String>()
        var current: StringBuilder? = null   // non-null while inside an <si>
        var inText = false                    // true while inside a <t>
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText) current?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> {
                        entries.add(current?.toString().orEmpty())
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return StringTable(entries)
    }
}

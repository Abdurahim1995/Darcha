package com.tikoncha.darcha.feature.viewer.data

/**
 * Encodes the recents list to a single string and back (T22).
 *
 * DataStore Preferences stores primitives, so a list needs a representation.
 * This is a hand-written one rather than JSON because `org.json` is a stub in
 * Android unit tests — it throws "not mocked" — and a codec that cannot be
 * tested on a plain JVM is exactly the wrong thing to hand-write. The format is
 * deliberately dull: one record per line, tab-separated fields.
 *
 * Only [RecentDocument.displayName] can contain anything the user likes, so it
 * is the only field escaped. `RecentDocument.available` is **not** encoded: it
 * describes the world right now, not what was saved.
 *
 * Decoding never throws. Stored data outlives the code that wrote it — a
 * malformed or truncated line is dropped and the rest of the list survives,
 * which beats losing every recent to one bad record.
 */
internal object RecentsCodec {

    private const val RECORD_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'
    private const val FIELD_COUNT = 4
    private const val NO_SIZE = ""

    /** Encode [documents] in the order given. */
    fun encode(documents: List<RecentDocument>): String =
        documents.joinToString(RECORD_SEPARATOR.toString()) { document ->
            listOf(
                document.id,
                escape(document.displayName),
                document.lastOpened.toString(),
                document.sizeBytes?.toString() ?: NO_SIZE,
            ).joinToString(FIELD_SEPARATOR.toString())
        }

    /** Decode what [encode] wrote, skipping any record that does not parse. */
    fun decode(encoded: String): List<RecentDocument> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split(RECORD_SEPARATOR).mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val fields = line.split(FIELD_SEPARATOR)
            if (fields.size != FIELD_COUNT) return@mapNotNull null
            val id = fields[0]
            if (id.isEmpty()) return@mapNotNull null
            val lastOpened = fields[2].toLongOrNull() ?: return@mapNotNull null
            RecentDocument(
                id = id,
                displayName = unescape(fields[1]),
                lastOpened = lastOpened,
                sizeBytes = fields[3].takeIf { it.isNotEmpty() }?.toLongOrNull(),
            )
        }
    }

    /** Protect the separators, and the escape character itself. */
    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                FIELD_SEPARATOR -> append("\\t")
                RECORD_SEPARATOR -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                append(c)
                i++
                continue
            }
            when (value[i + 1]) {
                '\\' -> append('\\')
                't' -> append(FIELD_SEPARATOR)
                'n' -> append(RECORD_SEPARATOR)
                // An escape we do not know: keep both characters rather than
                // silently eating one.
                else -> {
                    append(c)
                    append(value[i + 1])
                }
            }
            i += 2
        }
    }
}

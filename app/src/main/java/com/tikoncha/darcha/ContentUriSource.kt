package com.tikoncha.darcha

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import java.io.IOException
import java.io.InputStream

/**
 * A [WorkbookSource] over a SAF `content://` URI.
 *
 * This is the only place `android.net.Uri` appears in the loading path: the
 * viewer module works against [WorkbookSource], which keeps its ViewModel and
 * repository testable on a plain JVM.
 */
internal class ContentUriSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String,
    override val declaredSizeBytes: Long?,
    override val recentId: String?,
) : WorkbookSource {

    /**
     * Open the document's bytes.
     *
     * A provider may refuse — an `ACTION_VIEW` grant dies with the task that
     * received it, so a URI re-read after process death throws `SecurityException`
     * rather than returning null. That is mapped to [IOException] because the
     * loading pipeline's contract is that a source fails with one, and a
     * `SecurityException` escaping onto a background dispatcher would take the
     * process down instead of reaching the error screen.
     */
    override fun openStream(): InputStream = try {
        resolver.openInputStream(uri) ?: throw IOException("provider returned no stream for $uri")
    } catch (e: SecurityException) {
        throw IOException("no permission to read $uri", e)
    } catch (e: IllegalArgumentException) {
        // Some providers throw this for an unknown document id.
        throw IOException("provider rejected $uri", e)
    }

    internal companion object {

        /**
         * Build a source for [uri], querying the provider for its name and size.
         *
         * Both are best-effort: a provider may return neither. The size is only a
         * hint for an early rejection — the repository counts bytes as it copies,
         * so a missing or wrong value cannot defeat the cap.
         *
         * **Never throws.** This runs on the main thread the moment an
         * `ACTION_VIEW` arrives (T21), and the query is a call into another app's
         * provider: it can refuse, or reject the document id outright. A failure
         * here only costs the name and the size hint — the load then fails
         * properly, on its own dispatcher, and lands on the error screen.
         *
         * @param reopenable whether this document may be added to recents —
         *   `true` only when a persistable read permission was taken for [uri].
         *   See [WorkbookSource.recentId].
         */
        fun from(
            resolver: ContentResolver,
            uri: Uri,
            reopenable: Boolean = false,
        ): ContentUriSource {
            var name: String? = null
            var size: Long? = null
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            runCatching {
                resolver.query(uri, columns, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
            }
            return ContentUriSource(
                resolver = resolver,
                uri = uri,
                displayName = name ?: uri.lastPathSegment ?: "document.xlsx",
                declaredSizeBytes = size,
                recentId = uri.toString().takeIf { reopenable },
            )
        }
    }
}

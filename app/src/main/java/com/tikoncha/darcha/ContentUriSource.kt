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
) : WorkbookSource {

    override fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("provider returned no stream for $uri")

    internal companion object {

        /**
         * Build a source for [uri], querying the provider for its name and size.
         *
         * Both are best-effort: a provider may return neither. The size is only a
         * hint for an early rejection — the repository counts bytes as it copies,
         * so a missing or wrong value cannot defeat the cap.
         */
        fun from(resolver: ContentResolver, uri: Uri): ContentUriSource {
            var name: String? = null
            var size: Long? = null
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            return ContentUriSource(
                resolver = resolver,
                uri = uri,
                displayName = name ?: uri.lastPathSegment ?: "document.xlsx",
                declaredSizeBytes = size,
            )
        }
    }
}

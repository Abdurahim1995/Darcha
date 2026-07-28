package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.model.ErrorKind
import java.io.InputStream

/**
 * A document the viewer can open, as a stream of bytes plus a name.
 *
 * The MVI layer never sees an `android.net.Uri`: `:app` implements this over
 * whatever the platform hands it (a SAF `content://` URI in T11), which keeps
 * the ViewModel and the repository unit-testable on a plain JVM.
 */
public interface WorkbookSource {

    /** File name to show in the UI. */
    public val displayName: String

    /**
     * Size in bytes as reported by the provider, or `null` when unknown.
     *
     * Treated only as a cheap early hint — providers may omit it or report it
     * wrongly, so the repository counts bytes while copying regardless.
     */
    public val declaredSizeBytes: Long?

    /**
     * Open the document's bytes. Called on an I/O dispatcher; the caller closes
     * the stream.
     */
    public fun openStream(): InputStream
}

/** The outcome of loading a document. */
public sealed interface WorkbookLoad {

    /** Loading succeeded, yielding [meta]. */
    public data class Success(public val meta: DocumentMeta) : WorkbookLoad

    /** Loading failed with [kind] from the parser's error taxonomy. */
    public data class Failure(public val kind: ErrorKind) : WorkbookLoad
}

/**
 * The parser as the viewer sees it (TECH_SPEC §10). Putting an interface here
 * lets the ViewModel be tested with a fake, and keeps `:core:parser` details —
 * `ZipFile`, threading, chunk callbacks — out of the MVI layer.
 *
 * Implementations decide their own threading; the ViewModel calls [load] from a
 * coroutine and expects it to suspend rather than block the caller.
 */
public interface WorkbookRepository {

    /**
     * Load the document behind [source], reporting progress in `0f..1f` through
     * [onProgress] as rows stream in.
     *
     * Never throws for a bad document: failures come back as
     * [WorkbookLoad.Failure].
     */
    public suspend fun load(
        source: WorkbookSource,
        onProgress: (Float) -> Unit,
    ): WorkbookLoad
}

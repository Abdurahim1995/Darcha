package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.model.ErrorKind

/**
 * An opaque handle to a document the viewer can open.
 *
 * The MVI layer never sees an `android.net.Uri`: `:app` implements this over
 * whatever the platform hands it (a SAF `content://` URI in T11), which keeps
 * the ViewModel unit-testable on a plain JVM.
 *
 * @property displayName file name to show in the UI.
 */
public interface WorkbookSource {
    public val displayName: String
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

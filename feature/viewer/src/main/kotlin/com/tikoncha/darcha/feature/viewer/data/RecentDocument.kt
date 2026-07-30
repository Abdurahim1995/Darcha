package com.tikoncha.darcha.feature.viewer.data

/**
 * A document the user opened before, offered on the home screen (T22).
 *
 * ## Only things that will actually open
 *
 * A recent is a promise: tap it and the document appears. An entry that cannot
 * be reopened is worse than no entry at all, so **the list only ever holds
 * documents whose read permission was successfully persisted**. That rules out
 * everything arriving by `ACTION_VIEW`: those grants are one-shot and die with
 * the task (TECH_SPEC §9.1), so a file manager's file is viewed but never
 * remembered.
 *
 * Two alternatives were considered and rejected:
 * - **Copy the bytes into app storage** so any document can be reopened. That
 *   silently duplicates the user's data inside Darcha, which contradicts the
 *   whole privacy stance (§2): the app is meant to read your file, not keep it.
 * - **Store the entry anyway and fail on tap.** A list that lies is worse than a
 *   short list.
 *
 * @property id the `content://` URI, as a string. `:feature:viewer` deliberately
 *   does not know about `android.net.Uri` — this is an opaque handle that `:app`
 *   resolves (the same split as [WorkbookSource]).
 * @property displayName the file name to show.
 * @property lastOpened epoch millis of the most recent open; the list is sorted
 *   by it, newest first.
 * @property sizeBytes the provider's reported size, or `null` if it gave none.
 * @property available whether the document can still be opened right now.
 *   **Not persisted** — a grant can be revoked and a file deleted long after the
 *   entry was written, so this is recomputed each time the list is shown.
 */
public data class RecentDocument(
    public val id: String,
    public val displayName: String,
    public val lastOpened: Long,
    public val sizeBytes: Long? = null,
    public val available: Boolean = true,
)

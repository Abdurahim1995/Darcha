package com.tikoncha.katak.model

/**
 * The closed taxonomy of user-facing failure reasons the parser can surface
 * (TECH_SPEC §10).
 *
 * Parser code never throws raw exceptions across the `:core:parser` module
 * boundary (CLAUDE.md); every failure is mapped to one of these kinds and
 * carried out in a result type. The optional [message] is a short, non-localized
 * diagnostic intended for logs and developer context — user-facing copy is
 * chosen by the UI layer based on the [ErrorKind] subtype, not this string.
 */
public sealed interface ErrorKind {

    /** Optional short diagnostic detail; `null` when there is nothing to add. */
    public val message: String?

    /**
     * The bytes are not a readable XLSX container: wrong magic, a truncated or
     * malformed ZIP, or malformed OOXML inside an otherwise valid ZIP.
     */
    public data class Corrupted(override val message: String? = null) : ErrorKind

    /**
     * The file is an OLE/CFB compound document — a password-protected `.xlsx`
     * or a legacy `.xls`. Katak cannot decrypt or read these (TECH_SPEC §4).
     */
    public data class Encrypted(override val message: String? = null) : ErrorKind

    /**
     * A recognizable container that Katak deliberately does not support
     * (e.g. `.ods`, `.xlsb`). Distinct from [Corrupted]: the file is intact,
     * just out of scope (TECH_SPEC §4 non-goals).
     */
    public data class Unsupported(override val message: String? = null) : ErrorKind

    /**
     * The file exceeds a safety cap (byte size or cell count) enforced by the
     * loader to guard against OOM (TECH_SPEC §13). Produced from T11 onward.
     */
    public data class TooLarge(override val message: String? = null) : ErrorKind
}

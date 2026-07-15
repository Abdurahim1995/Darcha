package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.ErrorKind

/**
 * The outcome of a parser operation.
 *
 * The parser never lets raw exceptions cross the `:core:parser` module boundary
 * (CLAUDE.md). Instead every operation returns a [ParseResult]: either an [Ok]
 * holding a value, or an [Err] carrying an [ErrorKind] from the TECH_SPEC §10
 * taxonomy. Callers (e.g. the viewer ViewModel) map [Err.kind] to UI state.
 *
 * @param T the success value type.
 */
public sealed interface ParseResult<out T> {

    /** A successful result holding [value]. */
    public data class Ok<out T>(public val value: T) : ParseResult<T>

    /** A failed result described by [kind]. */
    public data class Err(public val kind: ErrorKind) : ParseResult<Nothing>
}

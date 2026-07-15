# CLAUDE.md — Darcha (XLSX Viewer for Android)

## What this project is

A fast, private, ad-free Android viewer for `.xlsx` files. Portfolio project.

**`docs/TECH_SPEC.md` is the single source of truth** for scope, architecture and terminology. Read the relevant section before starting any task. Never implement anything listed in its "Non-goals" section.

## Hard rules — never break these

1. **No third-party _runtime_ dependencies** in `:core:model` and `:core:parser` — nothing these modules use is packaged into the APK. Allowed: JUnit (test-only); and `kxml2` in `:core:parser` declared `compileOnly` (it supplies the `org.xmlpull.v1` XmlPullParser API, which the JDK lacks — Android provides the implementation at runtime, so it is never shipped) plus `testImplementation` (a real parser implementation for pure-JVM tests).
2. **No `INTERNET` permission**, ever. Privacy is a product feature.
3. **No Android imports** (`android.*`, `androidx.*`) inside `:core:*` modules — they are pure JVM Kotlin.
4. Module dependency direction is one-way:
   `:app` → `:feature:viewer` → `:core:parser` → `:core:model`. Never reversed, never skipped sideways.
5. **Fixture rule:** every parser feature or bugfix ships together with a fixture file + golden test. No fixture → not done.
6. **One task at a time.** Implement exactly what the current task prompt asks. Do not refactor, rename, or touch files outside the task's scope.
7. **Ask before adding any dependency** anywhere in the project, including test and build dependencies not already approved.

## Stack

- Kotlin 2.x, Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- `minSdk 26`, latest stable `targetSdk`, JVM target 17
- UI: Jetpack Compose — only in `:feature:viewer` and `:app`
- Architecture: MVI, strictly unidirectional: `Intent → reduce → State → render`
- Data model: immutable, sparse (see TECH_SPEC §8) — prefer primitive arrays (`IntArray`, `DoubleArray`) in hot paths

## Commands

```bash
./gradlew :core:model:test :core:parser:test   # fast, pure-JVM parser tests
./gradlew build                                # full build
```

## Workflow

- Work strictly from the task prompt and its acceptance criteria.
- After implementing: run the tests, fix until green, then STOP. Summarize what changed and why in a few bullets.
- Do not start the next task on your own.
- Commits follow Conventional Commits: `feat(parser): …`, `test(parser): …`, `build: …`, `ci: …`.
- If a decision is not covered by TECH_SPEC.md, list 2–3 options with trade-offs and ask — do not silently choose.

## Code style

- Explicit visibility and KDoc for all public API in `:core:*`.
- No wildcard imports.
- Errors surface as the `ErrorKind` taxonomy from TECH_SPEC §10 — parser code never throws raw exceptions across the module boundary.

## Testing

- Fixtures live in `core/parser/src/test/resources/fixtures/<producer>/`
  where `<producer>` ∈ `excel | libreoffice | gsheets | wps | synthetic`.
- Each fixture is described in `core/parser/src/test/resources/fixtures/FIXTURES.md` and covered by golden-value assertions.
- When a real-world file breaks the parser: first add it (or a minimized copy) as a fixture with a failing test, then fix.

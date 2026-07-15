# Darcha — XLSX Viewer for Android

A fast, private, ad-free Android viewer for `.xlsx` files. Opens spreadsheets
instantly, works fully offline, and ships without the `INTERNET` permission.

📄 **Full technical specification: [docs/TECH_SPEC.md](docs/TECH_SPEC.md)**

## Modules

| Module | Platform | Responsibility |
|---|---|---|
| `:core:model` | Pure Kotlin (JVM) | Immutable, sparse document model |
| `:core:parser` | Pure Kotlin (JVM) | Streaming XLSX parser (depends only on `:core:model`) |
| `:feature:viewer` | Android | Compose UI + Canvas grid renderer (MVI) |
| `:app` | Android | Entry point, intent filters |

Dependency direction is one-way: `:app → :feature:viewer → :core:parser → :core:model`.

## Build

Requires **JDK 17** and the Android SDK.

```bash
./gradlew build                                 # full build
./gradlew :core:model:test :core:parser:test    # fast pure-JVM parser tests
```

## How this was built

The engineering *method* is part of this portfolio, not just the result. The
process is deliberate and auditable:

- **Spec-first.** Scope and architecture are fixed in
  [docs/TECH_SPEC.md](docs/TECH_SPEC.md) before any code is written. It is the
  single source of truth — changing scope means editing the spec first.
- **Task-by-task playbook.** Work follows [docs/PLAYBOOK.md](docs/PLAYBOOK.md),
  a fixed sequence of small tasks (T0 → T26). **One task = one commit**, so the
  git history reads as a step-by-step build log.
- **Claude Code as the implementation agent.** Claude Code writes the diffs;
  **every diff is human-reviewed** before it lands. All architectural decisions
  are made and documented by the owner (in the spec and the task prompts) — the
  agent implements within those constraints, it does not set direction.
- **Transparency as a feature.** `Co-Authored-By` trailers are kept
  intentionally on every commit rather than stripped — the process is meant to
  be legible, not hidden.
- **Fixture-driven parser.** Every parser feature ships with a fixture file and
  a golden-value test. The corpus and its expected values are documented in
  [FIXTURES.md](core/parser/src/test/resources/fixtures/FIXTURES.md).

# Katak — XLSX Viewer for Android

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

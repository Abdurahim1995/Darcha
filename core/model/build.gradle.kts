plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module — NO Android Gradle plugin, NO android/androidx deps
// (CLAUDE.md hard rule 3, TECH_SPEC §6). Holds the immutable, sparse document
// model shared by parser and UI.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

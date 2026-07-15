plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module — NO Android Gradle plugin, NO android/androidx deps
// (CLAUDE.md hard rule 3, TECH_SPEC §6/§7). Streaming XLSX parser; depends only
// on :core:model. kxml2 supplies an XmlPullParser implementation for unit tests
// (Android provides one at runtime), so it stays test-only.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
}

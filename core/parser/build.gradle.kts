plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module — NO Android Gradle plugin, NO android/androidx deps
// (CLAUDE.md hard rule 3, TECH_SPEC §6/§7). Streaming XLSX parser; depends only
// on :core:model at runtime.
//
// kxml2 provides the `org.xmlpull.v1` XmlPullParser API, which is NOT part of
// the JDK. It is `compileOnly` here: the API is needed to compile the parser,
// but Android supplies the implementation at runtime, so kxml2 is never packaged
// into the APK (no third-party runtime dependency). It is ALSO
// `testImplementation` so pure-JVM unit tests get a real implementation
// (KXmlParser), which the plain JVM does not provide.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))

    // XmlPullParser API for main compilation only — not shipped (Android
    // provides the runtime implementation).
    compileOnly(libs.kxml2)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
}

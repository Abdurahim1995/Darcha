import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.tikoncha.darcha.feature.viewer"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // The end-to-end test (T17) runs a real fixture through parser →
        // formatter, so it needs the golden corpus on its test classpath. It is
        // referenced where it lives rather than copied: the corpus has exactly
        // one home (CLAUDE.md rule 5), and a second copy would drift. This adds
        // no file to :core:parser and changes nothing about how it builds.
        getByName("test") {
            resources.srcDir("../../core/parser/src/test/resources")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// ErrorCopyTest reads res/values*/strings.xml straight off disk, so Gradle
// cannot see it as an input and would report the task UP-TO-DATE after a
// strings-only edit — the copy lint would silently stop running at exactly the
// moment someone changed the copy. Declaring the directory fixes that.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("stringResources")
}

dependencies {
    // One-way module graph (CLAUDE.md rule 4). :core:model is `api` because
    // viewer types expose model types (ViewerState.Error carries ErrorKind), so
    // :app must be able to resolve them.
    implementation(project(":core:parser"))
    api(project(":core:model"))

    // ViewModel + viewModelScope for the MVI layer (T10). Third-party runtime
    // deps are barred in :core:* only; the UI layer may use AndroidX. `api`
    // because ViewerViewModel is a ViewModel that :app constructs itself (T11).
    api(libs.androidx.lifecycle.viewmodel.ktx)

    // Recent files are persisted here (T22). Approved by the owner: the Flow API
    // feeds the existing StateFlow wiring without a hand-rolled change listener,
    // and it is what Google recommends for new code. SharedPreferences would do
    // the job with less weight; the glue is what tipped it.
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)

    // The parser needs an XmlPullParser implementation at test time. In an
    // Android module the stubbed android.jar supplies `org.xmlpull.v1`, and its
    // XmlPullParserFactory.newInstance() throws "not mocked" — so unit tests
    // that exercise :core:parser for real would fail here even though a device
    // works fine. kxml2 on the test classpath shadows the stub; it is test-only
    // and never packaged (same arrangement as :core:parser, CLAUDE.md rule 1).
    testImplementation(libs.kxml2)
}

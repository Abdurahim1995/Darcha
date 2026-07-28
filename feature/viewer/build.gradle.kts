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
}

kotlin {
    jvmToolchain(17)
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

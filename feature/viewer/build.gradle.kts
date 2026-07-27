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
    // deps are barred in :core:* only; the UI layer may use AndroidX.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
}

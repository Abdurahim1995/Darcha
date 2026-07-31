import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release signing credentials, kept out of the repository (see docs/RELEASE.md).
// `keystore.properties` and `*.jks` are both gitignored.
//
// Absent is a valid state, not an error: CI has no keystore and `./gradlew
// build` has to keep working there, so a missing file leaves the release build
// unsigned rather than failing. A file that is present but incomplete *is* an
// error — that is a typo in a credential, and failing quietly would produce an
// unsigned APK that looks like a signed one.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigning: Properties? = when {
    keystoreProperties.isEmpty -> null
    signingKeys.all { keystoreProperties.getProperty(it)?.isNotBlank() == true } -> keystoreProperties
    else -> error(
        "keystore.properties is missing or has blank values for: " +
            signingKeys.filter { keystoreProperties.getProperty(it).isNullOrBlank() } +
            ". See docs/RELEASE.md.",
    )
}

android {
    namespace = "com.tikoncha.darcha"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tikoncha.darcha"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                // Resolved against the project root, so keystore.properties can
                // name the file either relatively or by absolute path.
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 and resource shrinking (T26). No keep rules of our own are
            // needed; app/proguard-rules.pro records why, and how that was
            // checked rather than assumed.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation(project(":feature:viewer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}

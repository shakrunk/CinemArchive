import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Mirrors the web app's .env.local pattern (see .env.example) — android/local.properties
// is already gitignored (it holds sdk.dir), so SUPABASE_URL/SUPABASE_ANON_KEY live there
// too rather than in a second secrets file. See android/local.properties.example.
//
// Read via providers.fileContents rather than a plain File.inputStream() — this project has
// the configuration cache enabled (gradle.properties), which only invalidates on file reads
// it can track; a raw java.io read isn't one, so editing local.properties wouldn't bust a
// stale cached config and BuildConfig would silently keep serving old values.
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { load(it.reader()) }
}

android {
    namespace = "work.kumarfamilynet.cinemarchive"
    compileSdk = 36

    defaultConfig {
        applicationId = "work.kumarfamilynet.cinemarchive"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${localProperties.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    implementation(project(":data"))
    implementation(project(":feature:library"))
    implementation(project(":feature:ledger"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:discover"))
    implementation(project(":feature:upnext"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "work.kumarfamilynet.cinemarchive.data"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    // Only for SupabaseRestClient's PATCH support — java.net.HttpURLConnection cannot
    // reliably send PATCH (not in its method allow-list; the JDK-only reflection workaround
    // proved unreliable against a real HTTPS endpoint), and java.net.http.HttpClient isn't
    // present in Android's SDK at all. OkHttp is the industry-standard, well-audited answer
    // to exactly this gap — not a broad new networking-stack decision for the app.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android's org.json is a compile-only stub that throws at runtime on the JVM unit
    // test classpath; the real implementation takes priority as an explicit dependency.
    testImplementation(libs.json)
}

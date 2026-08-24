import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Short commit the APK was built from, plus a `-dirty` marker when the working
 * tree had uncommitted changes.
 *
 * This is the field that actually matters for the tester round. `versionName`
 * is human-facing and we will forget to bump it; the SHA maps a tester's
 * exported log back to an exact commit without anyone having to remember
 * anything. `-dirty` is the honest half of that: if it appears, the log came
 * from a build that matches no commit at all, and the SHA next to it is a lie
 * by omission without it.
 *
 * Falls back to "nogit" rather than failing, so a fresh clone with no `.git`
 * (or a machine with no git on PATH) still builds. Read at configure time.
 */
fun gitStamp(): String = try {
    fun git(vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        return if (proc.waitFor() == 0) out else ""
    }
    val sha = git("rev-parse", "--short", "HEAD")
    if (sha.isEmpty()) "nogit" else sha + if (git("status", "--porcelain").isNotEmpty()) "-dirty" else ""
} catch (e: Exception) {
    "nogit"
}

android {
    namespace = "com.example.handsfree"
    compileSdk = 35

    defaultConfig {
        // NOT the same thing as `namespace` above. This is the identity Android
        // installs the app under; `namespace` is the package R and BuildConfig
        // are generated into, and it still matches the Kotlin source tree.
        // Changing this one makes the app a DIFFERENT app to Android — the old
        // com.example.handsfree build must be uninstalled, not upgraded.
        applicationId = "ph.edu.hau.handsfree"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.5.1-tester"

        // Printed in the exported tester log. See MainActivity.buildReport().
        buildConfigField("String", "BUILD_STAMP", "\"${gitStamp()}\"")
        buildConfigField(
            "String",
            "BUILD_DATE",
            "\"${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Off by default since AGP 8. Without it BuildConfig is never generated and
    // the two fields above resolve to nothing.
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    // VOSK offline speech recognition (verify latest at https://alphacephei.com/vosk/android)
    implementation("com.alphacephei:vosk-android:0.3.47")
    // Coroutines for background audio/VOSK pipeline work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // ONNX Runtime for the fine-tuned Whisper-tiny model (see CLAUDE.md 5)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

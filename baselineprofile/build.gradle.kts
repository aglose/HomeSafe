import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Macrobenchmark + Baseline Profile generator for :androidApp.
 *
 *   ./gradlew :androidApp:generateBaselineProfile      regenerate androidApp/src/release/generated/baselineProfiles/
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
 *                                                       run the startup + scroll benchmarks
 *
 * Both need a connected device (a physical one for numbers that mean anything; the emulator is
 * accepted for smoke runs, see suppressErrors below) with the debug/benchmark sign-in credentials
 * in local.credentials.properties and the Frigate server reachable from it.
 */
plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "com.meticulouscreations.homesafe.baselineprofile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Numbers from an emulator are not representative and Macrobenchmark refuses to run
        // there by default; allow it so the harness can be smoke-tested without a phone.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY,UNLOCKED"
    }

    targetProjectPath = ":androidApp"
}

baselineProfile {
    // Use whatever is plugged in rather than a Gradle-managed device: the journeys have to reach
    // the real Frigate server to get past sign-in, which a fresh managed AVD may not.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.testExt.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.espresso.core)
}

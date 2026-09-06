import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.metro)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom)
    alias(libs.plugins.kotlinxSerialization)
    // Compile-time stability baseline + `stabilityCheck` CI gate (see composeStabilityAnalyzer below).
    alias(libs.plugins.composeStabilityAnalyzer)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm()
    
    js {
        browser()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    android {
       namespace = "com.meticulouscreations.homesafe.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiBackhandler)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // `api`, not `implementation`: AppGraph extends MetroX's ViewModelGraph, and the app
            // modules hold an AppGraph, so they must be able to see that supertype.
            api(libs.metrox.viewmodel.compose)
            implementation(libs.navigation3.ui)
            implementation(libs.room.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.sqlite.bundled)
            implementation(libs.androidx.activity.compose) // ReportDrawnWhen, for time-to-fully-drawn
            implementation(libs.ktor.client.okhttp)
            // The push token is this device's identity to the relay (PushTokenProvider.android.kt).
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.fragment)
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.bundled)
            implementation(libs.ktor.client.cio)
        }
        // Compose UI tests cannot live in commonTest: commonTest feeds androidHostTest, and
        // runComposeUiTest needs a real Android instrumentation host, so those tests fail there
        // with a NullPointerException. src/uiTest/kotlin is compiled into the targets that can
        // render headlessly instead — JVM (skiko) and the iOS simulator. Shared by srcDir rather
        // than a dependsOn edge, because adding one by hand switches off KGP's default hierarchy
        // and iosMain stops seeing its actuals.
        jvmTest.get().kotlin.srcDir("src/uiTest/kotlin")
        getByName("iosSimulatorArm64Test").kotlin.srcDir("src/uiTest/kotlin")

        jvmTest.dependencies {
            // ui-test-desktop declares the skiko API but not its host-specific native runtime,
            // so Compose UI tests fail with skiko's LibraryLoadException without this. Resolved
            // per host, which is what CI (linux-x64) and this Mac (macos-arm64) each need.
            implementation(compose.desktop.currentOs)
        }
        iosMain.dependencies {
            implementation(libs.sqlite.bundled)
            implementation(libs.ktor.client.darwin)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.client.js)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

// Compose UI tests rasterise through skiko, whose runtime jar links AWT. CI runners have no
// display, so make the intent explicit rather than depending on the default.
tasks.named<Test>("jvmTest") {
    systemProperty("java.awt.headless", "true")
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

composeStabilityAnalyzer {
    stabilityValidation {
        enabled.set(true)
        // The committed baseline lives next to the sources, not under build/, so CI can diff it.
        outputDir.set(layout.projectDirectory.dir("stability"))
        // `./gradlew :shared:stabilityCheck` fails when a composable or class becomes less
        // stable than the committed baseline; refresh the baseline deliberately with
        // `./gradlew :shared:stabilityDump` (and say why in the PR). Local opt-out:
        // -PcomposeStabilityStrict=false.
        failOnStabilityChange.set(providers.gradleProperty("composeStabilityStrict").orNull?.toBoolean() ?: true)
    }
}

// The analyzer's dump/check tasks read what the Android compilation wrote to build/stability but
// (in this KMP + AGP 9 layout) don't wire the dependency themselves; the Android variant is the
// one that ships, so it is the one the baseline is taken from.
tasks.matching { it.name == "stabilityDump" || it.name == "stabilityCheck" }.configureEach {
    dependsOn("compileAndroidMain")
    mustRunAfter(tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>())
}

composeCompiler {
    // Third-party types the Compose compiler can't see into but that are immutable in practice
    // (kotlinx.datetime values, kotlin.time.Instant) are declared stable here rather than by
    // wrapping every parameter. Review the file before adding to it.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
    // See androidApp/build.gradle.kts: reports land in shared/build/compose_compiler/ and are
    // read from the *release* android variant.
    if (providers.gradleProperty("composeCompilerReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)

    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspJs", libs.room.compiler)
    add("kspWasmJs", libs.room.compiler)
}

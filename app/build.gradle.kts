import com.skydoves.compose.stability.gradle.StabilityCheckTask
import com.skydoves.compose.stability.gradle.StabilityDumpTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The composition root: the app graph (it alone sees every module), App(), the drawer's apps,
// and the iOS entry points. It builds the iOS framework, so Xcode runs
// :app:embedAndSignAppleFrameworkForXcode. Most of what follows matches
// shared/build.gradle.kts, where each piece is explained.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.composeStabilityAnalyzer)
    alias(libs.plugins.ktlint)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            // "Shared" still: it is the name the Swift code imports.
            baseName = "Shared"
            isStatic = true
            // Swift reaches :shared's types too (the WebRTC bridge it implements), under the names
            // they have always had.
            export(project(":shared"))
        }
    }

    // The Mac app (:desktopApp): the same Compose UI on the JVM, drawn with Skia as on iOS.
    jvm("desktop")

    android {
        namespace = "com.meticulouscreations.homesafe.app"
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
        commonMain.dependencies {
            api(project(":shared"))
            api(project(":finance"))
            api(project(":weather"))
            api(project(":fitness"))
            api(project(":atlas"))
            api(project(":charging"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.navigationevent.compose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
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
            implementation(libs.compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            // The Tracer the graph's binding spans are written with (see TracingBindings.android.kt).
            implementation(libs.androidx.tracing)
        }
        listOf("iosArm64Main", "iosSimulatorArm64Main", "desktopMain").forEach { name ->
            getByName(name).kotlin.srcDir("src/skiaMain/kotlin")
        }

        // Compose UI tests cannot live in commonTest: commonTest feeds androidHostTest, and
        // runComposeUiTest needs a real Android instrumentation host. src/uiTest/kotlin is compiled
        // into the targets that can host them: the iOS simulator and a real Android runtime. Here,
        // in the module that sees every screen. Shared by srcDir rather than a dependsOn edge,
        // because adding one by hand switches off KGP's default hierarchy.
        getByName("iosSimulatorArm64Test").kotlin.srcDir("src/uiTest/kotlin")
        getByName("androidDeviceTest").kotlin.srcDir("src/uiTest/kotlin")
        getByName("desktopTest").kotlin.srcDir("src/uiTest/kotlin")

        // Whole-app journeys: App() on a real AppGraph, signed in over HTTP to the fake Frigate in
        // :fake-frigate (a JVM server, hence Android and the desktop, no iOS).
        getByName("androidDeviceTest").kotlin.srcDir("src/integrationTest/kotlin")
        getByName("desktopTest").kotlin.srcDir("src/integrationTest/kotlin")
        getByName("desktopTest").dependencies {
            implementation(project(":fake-frigate"))
            // FFmpeg's natives, which the players load (and the fake has no video for, so they only try).
            runtimeOnly(libs.ffmpeg.get().toString() + ":" + bytedecoHostPlatform())
            runtimeOnly(libs.javacpp.get().toString() + ":" + bytedecoHostPlatform())
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.kotlin.testJunit)
            // Skia for the machine the tests run on, which the library modules leave to the app.
            implementation(
                "org.jetbrains.compose.desktop:desktop-jvm-" + System.getProperty("os.name").lowercase().let { os ->
                    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
                    when {
                        "mac" in os -> if (arm) "macos-arm64" else "macos-x64"
                        "win" in os -> "windows-x64"
                        else -> if (arm) "linux-arm64" else "linux-x64"
                    }
                } + ":" + libs.versions.composeMultiplatform.get(),
            )
        }

        getByName("androidDeviceTest").dependencies {
            // Merges an androidx.activity.ComponentActivity entry into the test APK's manifest.
            // Without it runComposeUiTest has no activity to host the composition and dies at
            // launch rather than at compile time.
            implementation(libs.compose.uiTestManifest)
            implementation(project(":fake-frigate"))
            // InstrumentationRegistry, for the context the journeys build the app graph with.
            implementation(libs.androidx.testExt.junit)
        }
    }
}

/** FFmpeg's name for the machine the build runs on: the desktop tests load its natives for this one. */
fun bytedecoHostPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    return when {
        "mac" in os -> if (arm) "macosx-arm64" else "macosx-x86_64"
        "win" in os -> "windows-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

// The desktop's tests keep their database and preferences apart from a real install's, fresh each run.
tasks.matching { it.name == "desktopTest" }.configureEach {
    (this as Test).apply {
        val scratch = layout.buildDirectory.dir("desktop-test-data").get().asFile
        doFirst { scratch.deleteRecursively() }
        systemProperty("homesafe.dataDir", scratch.absolutePath)
        systemProperty("homesafe.preferencesNode", "com/meticulouscreations/homesafe-tests")
        // Where the journeys put what was on screen at each step (saveJourneyScreen).
        systemProperty("homesafe.journeyScreens", layout.buildDirectory.dir("journeys").get().asFile.absolutePath)
        // A screen, real or virtual, isn't needed: the tests draw offscreen.
        systemProperty("java.awt.headless", "true")
    }
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// -Phomesafe.tracing=true: Metro wraps every binding the graph creates in a trace section and marks
// every accessor, written by the app's Tracing 2.0 driver (HomeSafeApplication) to a Perfetto file
// beside the app's data. Android only; the iOS graph is unchanged. docs/perf-tracing.md says how to read it.
metro {
    @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
    enableRuntimeTracing.set(providers.gradleProperty("homesafe.tracing").map(String::toBoolean).orElse(false))
}

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        exclude { entry -> entry.file.path.contains("/build/") }
    }
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

kotlin.sourceSets.all {
    val sourceSet = this
    val buildDir = layout.buildDirectory.get().asFile
    val handWritten = provider { sourceSet.kotlin.srcDirs.filterNot { it.startsWith(buildDir) } }
    listOf("Check", "Format").forEach { kind ->
        tasks.named<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>(
            "runKtlint${kind}Over${name.replaceFirstChar(Char::uppercase)}SourceSet",
        ) {
            setSource(handWritten)
        }
    }
}

dependencies {
    ktlintRuleset(libs.composeRules.ktlint)
}

composeStabilityAnalyzer {
    stabilityValidation {
        enabled.set(true)
        outputDir.set(layout.projectDirectory.dir("stability"))
        failOnStabilityChange.set(providers.gradleProperty("composeStabilityStrict").orNull?.toBoolean() ?: true)
    }
}

val androidStabilityInfo = files(layout.buildDirectory.file("stability/compileAndroidMain/stability-info.json")) {
    builtBy("compileAndroidMain")
}
afterEvaluate {
    tasks.withType<StabilityDumpTask>().configureEach {
        setDependsOn(emptyList<Any>())
        stabilityInputFiles.setFrom(androidStabilityInfo)
    }
    tasks.withType<StabilityCheckTask>().configureEach {
        setDependsOn(emptyList<Any>())
        stabilityInputFiles.setFrom(androidStabilityInfo)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(isolated.rootProject.projectDirectory.file("compose_stability_config.conf"))
    if (providers.gradleProperty("composeCompilerReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

// One emulator runs one instrumented suite at a time (see androidApp/build.gradle.kts), so the
// modules' device tests queue up behind each other rather than run side by side.
tasks.matching { it.name == "connectedAndroidDeviceTest" }.configureEach {
    mustRunAfter(":charging:connectedAndroidDeviceTest")
}

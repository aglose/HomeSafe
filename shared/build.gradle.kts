import com.skydoves.compose.stability.gradle.StabilityCheckTask
import com.skydoves.compose.stability.gradle.StabilityDumpTask
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
    // Kotlin formatting, plus the Compose rule set (see ktlint {} below).
    alias(libs.plugins.ktlint)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // The iOS framework is :app's, which builds on this module and the features.
    iosArm64()
    iosSimulatorArm64()

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
            implementation(libs.reorderable)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.navigationevent.compose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // `api`, not `implementation`: AppGraph (in :app) extends MetroX's ViewModelGraph, and
            // the feature modules' view models are contributed to it.
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
            // The household key vault's PBKDF2, ECDH, HKDF and AES-GCM: the JDK's on Android,
            // CryptoKit's (and CommonCrypto's for PBKDF2) on iOS. See docs/key-vault.md.
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
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
            // traceSection's Tracing 2.0 sections (Tracing.android.kt); written only by a -Phomesafe.tracing=true build.
            api(libs.androidx.tracing)
            implementation(libs.ktor.client.okhttp)
            // The push token is this device's identity to the relay (PushTokenProvider.android.kt).
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            // The home geofence and the one-shot fix that sets it (GeofenceMonitor.android.kt).
            implementation(libs.play.services.location)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            // Live view over WebRTC (WebRtcPeer.android.kt); Media3 stays for HLS and recordings.
            implementation(libs.stream.webrtc.android)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.fragment)
            // The weather app's periodic background check (WeatherNotifier.android.kt).
            implementation(libs.androidx.work.runtime)
            // Steps, sleep and runs from the band, by way of Google Health (HealthSource.android.kt).
            implementation(libs.androidx.health.connect)
        }
        // Code that draws with Skia directly (the timeline's liquid-glass lens) is iOS's, since
        // Android has AGSL instead. It is compiled into each iOS target rather than into iosMain,
        // whose metadata compilation doesn't see the Skia API.
        listOf("iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
            getByName(name).kotlin.srcDir("src/skiaMain/kotlin")
        }

        // Compose UI tests cannot live in commonTest: commonTest feeds androidHostTest, and
        // runComposeUiTest needs a real Android instrumentation host. src/uiTest/kotlin is compiled
        // into the targets that can host them: the iOS simulator and a real Android runtime
        // (androidDeviceTest is in the "test" source set tree, see withDeviceTestBuilder, so it
        // already inherits commonTest's dependencies including compose ui-test). The ones that
        // need a feature module or the whole app are in :app's uiTest. Shared by srcDir rather
        // than a dependsOn edge, because adding one by hand switches off KGP's default hierarchy.
        getByName("iosSimulatorArm64Test").kotlin.srcDir("src/uiTest/kotlin")
        getByName("androidDeviceTest").kotlin.srcDir("src/uiTest/kotlin")

        // The Room schemas the migration tests rebuild old databases from, packed into the test APK
        // as Java resources (androidDeviceTest's data/ExportedSchemas.kt reads them).
        getByName("androidDeviceTest").resources.srcDir("schemas")

        getByName("androidDeviceTest").dependencies {
            // Merges an androidx.activity.ComponentActivity entry into the test APK's manifest.
            // Without it runComposeUiTest has no activity to host the composition and dies at
            // launch rather than at compile time.
            implementation(libs.compose.uiTestManifest)
            // InstrumentationRegistry, for the context the device tests build with.
            implementation(libs.androidx.testExt.junit)
        }

        iosMain.dependencies {
            implementation(libs.sqlite.bundled)
            implementation(libs.ktor.client.darwin)
        }
    }
}

// A CI run's console is often the only evidence of why a test failed (and the integration
// journeys put the screen's semantics tree in their failure message), so print it in full.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

ktlint {
    // The plugin defaults to ktlint 1.5.0; pin it so everyone and CI agree on the rules.
    version.set(libs.versions.ktlint)
    // KMP source sets include the generated code that KSP (Room) and Compose Resources write
    // under build/, which is ~3,300 violations of nobody's business — nothing here is
    // hand-edited and none of it ships through review.
    filter {
        exclude { entry -> entry.file.path.contains("/build/") }
    }
    // The style itself lives in .editorconfig at the repo root.
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

// The plugin hands each of its tasks a source set's directories, and with them the tasks that
// write the generated ones under build/: KSP for Room, the Compose resource accessors. KSP for a
// test source set needs the main one compiled, so checking the style compiled every target
// for files the filter above then throws away: minutes on CI for under a minute of linting. Give the tasks only the hand-written directories instead,
// which no task has to produce. Inside sourceSets.all so it runs after the plugin's own callback
// has registered the tasks, whenever the source set turns up.
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
    // mrmans0n/compose-rules: Compose-specific lint that plain ktlint has no notion of —
    // missing Modifier parameters, effects capturing lambda parameters, state autoboxing.
    // Worth more here than a general-purpose static analyser, since most of this module is UI.
    ktlintRuleset(libs.composeRules.ktlint)
}

// The app's strings and pictures stay here, and the feature modules use them through this
// module's Res, so it has to be public. (One Res keeps one place to translate.)
compose.resources {
    publicResClass = true
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

// The analyzer's dump and check tasks read one report: the first stability-info.json they find
// under build/stability, which is the Android compilation's. That is the variant that ships, so
// it is the one the baseline is taken from. The plugin doesn't know that. It makes both tasks
// depend on every Kotlin compilation in the module (both iOS targets, and each one's KSP run),
// whose reports are never read, and not on the Android one, whose task name it doesn't
// recognise in this KMP + AGP 9 layout. So the tasks are handed the one file they read, built
// by the task that writes it, and nothing else.
// In afterEvaluate because that is where the plugin adds its dependencies: anything set before
// then is added to, not replaced.
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
    // Third-party types the Compose compiler can't see into but that are immutable in practice
    // (kotlinx.datetime values, kotlin.time.Instant) are declared stable here rather than by
    // wrapping every parameter. Review the file before adding to it.
    stabilityConfigurationFiles.add(isolated.rootProject.projectDirectory.file("compose_stability_config.conf"))
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
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

// Lint's model and analysis tasks for the Android host-test source set read KSP's generated
// sources for that source set (build/generated/ksp/android/androidHostTest) but, in this
// KMP + AGP 9 layout, nothing wires an edge to the task that writes them. Gradle only notices
// when both ends are in the same graph, i.e. when the host tests and lint are requested in one
// invocation, and then rejects it with "Property has implicit dependency". CI splits those
// across jobs, so only a local all-in-one run hits it.
tasks.matching { it.name == "generateAndroidHostTestLintModel" || it.name == "lintAnalyzeAndroidHostTest" }
    .configureEach {
        dependsOn("kspAndroidHostTest")
    }

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
        }
        listOf("iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
            getByName(name).kotlin.srcDir("src/skiaMain/kotlin")
        }

        // Compose UI tests cannot live in commonTest: commonTest feeds androidHostTest, and
        // runComposeUiTest needs a real Android instrumentation host. src/uiTest/kotlin is compiled
        // into the targets that can host them: the iOS simulator and a real Android runtime. Here,
        // in the module that sees every screen. Shared by srcDir rather than a dependsOn edge,
        // because adding one by hand switches off KGP's default hierarchy.
        getByName("iosSimulatorArm64Test").kotlin.srcDir("src/uiTest/kotlin")
        getByName("androidDeviceTest").kotlin.srcDir("src/uiTest/kotlin")

        // Whole-app journeys: App() on a real AppGraph, signed in over HTTP to the fake Frigate in
        // :fake-frigate (a JVM server, hence Android only, no iOS).
        getByName("androidDeviceTest").kotlin.srcDir("src/integrationTest/kotlin")

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

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
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
    mustRunAfter(":atlas:connectedAndroidDeviceTest")
}

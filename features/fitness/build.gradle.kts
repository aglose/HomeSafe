import com.skydoves.compose.stability.gradle.StabilityCheckTask
import com.skydoves.compose.stability.gradle.StabilityDumpTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Fitness app in the drawer: the training log, heart rate, steps and sleep. Split out of :shared so an edit here recompiles this module and the app
// shell, not the whole app. Most of what follows matches shared/build.gradle.kts, where each
// piece is explained.
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

    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.meticulouscreations.homesafe.fitness"
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
            // :finance for its charts (LineChart and the chart styles).
            implementation(project(":shared"))
            implementation(project(":finance"))
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
            implementation(libs.metrox.viewmodel.compose)
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
            implementation(project(":testing"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiTooling)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.fragment)
            // Steps, sleep and runs from the band, by way of Google Health (HealthSource.android.kt).
            implementation(libs.androidx.health.connect)
            // The phone's GPS for a run recorded in the app (RunLocation.android.kt).
            implementation(libs.play.services.location)
        }
        listOf("iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
            getByName(name).kotlin.srcDir("src/skiaMain/kotlin")
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testExt.junit)
            // FitnessLogCopyDatabaseTest opens a real database file.
            implementation(libs.sqlite.bundled)
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
    mustRunAfter(":weather:connectedAndroidDeviceTest")
}

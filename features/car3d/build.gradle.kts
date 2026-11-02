import com.skydoves.compose.stability.gradle.StabilityCheckTask
import com.skydoves.compose.stability.gradle.StabilityDumpTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The household's cars in 3D: the meshes tools/car-model makes, drawn with OpenGL on each platform.
// A module of its own so an edit here recompiles this module and the app shell, not the whole app. Most of what follows matches shared/build.gradle.kts, where each
// piece is explained.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeStabilityAnalyzer)
    alias(libs.plugins.ktlint)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    iosArm64()
    iosSimulatorArm64()
    // The Mac app (:desktopApp): the same Compose UI on the JVM, drawn with Skia as on iOS.
    jvm("desktop")

    android {
        namespace = "com.meticulouscreations.homesafe.car3d"
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
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.navigationevent.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiTooling)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.lwjgl.core)
            implementation(libs.lwjgl.opengl)
            // Only the Mac draws the car live (CGL); other desktops show its picture.
            if ("mac" in System.getProperty("os.name").lowercase()) {
                val natives = if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "natives-macos-arm64" else "natives-macos"
                runtimeOnly(libs.lwjgl.core.get().toString() + ":" + natives)
                runtimeOnly(libs.lwjgl.opengl.get().toString() + ":" + natives)
            }
        }
        getByName("desktopTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(compose.desktop.currentOs)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testExt.junit)
        }
    }
}

tasks.withType<Test>().configureEach {
    // Lets the render dump write pictures: see docs/car-model.md.
    System.getenv("CAR_DUMP_DIR")?.let { environment("CAR_DUMP_DIR", it) }
    System.getenv("CAR_PACK_DIR")?.let { environment("CAR_PACK_DIR", it) }
    System.getenv("CAR_TIER")?.let { environment("CAR_TIER", it) }
    System.getenv("CAR_PICTURES")?.let { environment("CAR_PICTURES", it) }
    outputs.upToDateWhen { System.getenv("CAR_DUMP_DIR") == null }
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
    mustRunAfter(":atlas:connectedAndroidDeviceTest", ":charging:connectedAndroidDeviceTest")
}

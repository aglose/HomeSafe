import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fakes the tests of more than one module use (a fake web for the weather and atlas tests, a
// fake server connection for the finance and atlas tests, a clock moved by hand). Test sources
// aren't shared between modules, so they live here as main code, depended on only from
// commonTest. Nothing in the app depends on this module.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ktlint)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    // The Mac app (:desktopApp): the same Compose UI on the JVM, drawn with Skia as on iOS.
    jvm("desktop")

    android {
        namespace = "com.meticulouscreations.homesafe.testing"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.compose.components.resources)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

dependencies {
    ktlintRuleset(libs.composeRules.ktlint)
}

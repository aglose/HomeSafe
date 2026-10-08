rootProject.name = "HomeSafe"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Kotlin/JS refuses to link a kotlin-test klib from a different compiler release, and some
// dependency's JS variant still asks for the one from the previous Kotlin version. Nothing
// else requests kotlin-test on that classpath, so Gradle would keep the stale one; pin every
// kotlin-test artifact to the compiler's version instead.
// Here rather than in a `subprojects {}` block of the root build script: with Isolated Projects
// one project may not configure another, and this callback runs inside each project instead.
gradle.lifecycle.beforeProject {
    // Lazy: the version catalog isn't attached to the project yet when this callback runs, only
    // by the time its build script creates the first configuration.
    val kotlinVersion by lazy {
        extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("kotlin").get().requiredVersion
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-test")) {
                useVersion(kotlinVersion)
                because("kotlin-test must match the Kotlin compiler version")
            }
        }
    }
}

include(":androidApp")
include(":baselineprofile")
include(":desktopApp")
include(":fake-frigate")
include(":shared")
include(":webApp")
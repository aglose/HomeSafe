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

include(":androidApp")
include(":app")
include(":baselineprofile")
include(":fake-frigate")
include(":shared")
include(":testing")

// The drawer's apps, each a module of its own under features/ so an edit to one recompiles it
// and :app, not everything. Flat names (":finance", not ":features:finance"), so Gradle doesn't
// make an empty ":features" project to hold them.
listOf("finance", "weather", "fitness", "atlas").forEach { feature ->
    include(":$feature")
    project(":$feature").projectDir = file("features/$feature")
}
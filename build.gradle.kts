plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidxRoom) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.composeStabilityAnalyzer) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.screenshot) apply false
}

// Kotlin/JS refuses to link a kotlin-test klib from a different compiler release, and some
// dependency's JS variant still asks for the one from the previous Kotlin version. Nothing
// else requests kotlin-test on that classpath, so Gradle would keep the stale one; pin every
// kotlin-test artifact to the compiler's version instead.
// asProvider(): "kotlin" is also the prefix of "kotlin-wrappers", so the bare accessor is a group.
val kotlinVersion = libs.versions.kotlin.asProvider().get()
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-test")) {
                useVersion(kotlinVersion)
                because("kotlin-test must match the Kotlin compiler version")
            }
        }
    }
}

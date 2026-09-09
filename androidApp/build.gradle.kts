import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // Reads androidApp/google-services.json (gitignored) for Firebase Cloud Messaging.
    // Applied conditionally below, because that file is absent on a fresh clone and in CI.
    alias(libs.plugins.googleServices) apply false
    // Adds the nonMinifiedRelease / benchmarkRelease build types and `generateBaselineProfile`;
    // the :baselineprofile module drives both.
    alias(libs.plugins.baselineprofile)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// google-services.json holds this Firebase project's client ids and can only come from the
// Firebase console, so it is gitignored. The plugin fails the build outright when the file is
// missing, which would break a fresh clone and any CI run that gets no secrets (a fork's pull
// request). Apply it only when the file is actually there: without it FirebaseApp never
// initialises, FirebaseMessaging throws, and PushRegistrar logs "push registration failed".
if (project.file("google-services.json").exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
} else {
    logger.warn(
        "androidApp: google-services.json not found \u2014 building without Firebase. " +
            "Push notifications will not work in this build."
    )
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    // MainActivity's own Compose surface (the testTagsAsResourceId wrapper around App()).
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // Push notifications from the HomeSafe relay on the Frigate box, via Firebase Cloud Messaging.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Installs the shipped Baseline Profile into ART on first run (see androidApp/src/release/generated/baselineProfiles/).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}

// Local, gitignored test credentials for the debug-only "Autofill test credentials" button
// on SecureConnectionScreen. File may be absent (fresh checkout); missing values just leave
// autofill blank. Never populated for release builds — see buildTypes below.
val localCredentials = Properties().apply {
    val file = rootProject.file("local.credentials.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Release signing. keystore.properties at the repo root (gitignored) holds storeFile (relative to
// the repo root), storePassword, keyAlias and keyPassword for androidApp/keystore/homesafe-release.jks
// (also gitignored). Back both up: the key is the app's identity for updates and for Play.
// When the file is absent (CI, another machine) release falls back to the debug key with a warning
// so the build still succeeds — that APK is installable but is not the real release identity.
// Anything headed for Play passes -PrequireReleaseSigning=true, which makes that fallback fail
// the build instead of shipping the wrong identity.
//
// CI has no keystore.properties: the publish job decodes the key from a secret and passes the
// three passwords as the environment variables below, so they never land in a file inside the
// workspace. The properties file wins wherever it exists, so this machine is unaffected.
val keystoreProperties: Map<String, String> = run {
    val fromFile = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }
    listOf(
        "storeFile" to "ANDROID_KEYSTORE_FILE",
        "storePassword" to "ANDROID_KEYSTORE_PASSWORD",
        "keyAlias" to "ANDROID_KEY_ALIAS",
        "keyPassword" to "ANDROID_KEY_PASSWORD"
    ).mapNotNull { (key, environmentVariable) ->
        val value = fromFile.getProperty(key)
            ?: providers.environmentVariable(environmentVariable).orNull
        value?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()
}

android {
    namespace = "com.meticulouscreations.homesafe"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.meticulouscreations.homesafe"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Play refuses any upload whose versionCode is not strictly greater than everything already
        // on the track, so CI passes the GitHub Actions run number (-PversionCode=..., see the
        // publish-internal job in .github/workflows/ci.yml). A local build keeps 1; nothing built
        // locally is uploaded.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        // All four or none: a half-configured signing config fails deep inside the packaging task
        // with a much worse message than the debug-key warning below.
        if (keystoreProperties.keys.containsAll(listOf("storeFile", "storePassword", "keyAlias", "keyPassword"))) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getValue("storeFile"))
                storePassword = keystoreProperties.getValue("storePassword")
                keyAlias = keystoreProperties.getValue("keyAlias")
                keyPassword = keystoreProperties.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // Installs beside the release build on the same phone: different package, "-debug"
            // version, "HomeSafe Debug" label and an amber icon (see androidApp/src/debug/res/).
            // Firebase needs its own Android app entry for this package in the console, otherwise
            // push registration is skipped in debug (see the googleServices notes below).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "TEST_SERVER_URL", "\"${localCredentials.getProperty("test.serverUrl", "")}\"")
            buildConfigField("String", "TEST_USERNAME", "\"${localCredentials.getProperty("test.username", "")}\"")
            buildConfigField("String", "TEST_PASSWORD", "\"${localCredentials.getProperty("test.password", "")}\"")
        }
        release {
            // R8 full mode (the AGP 8+ default) with the optimize rule set: lambda grouping,
            // sourceInformation stripping and ComposerImpl devirtualization are where most of the
            // debug→release Compose speed-up comes from. Compose, Ktor, Room, Media3, Coil and
            // kotlinx.serialization all ship consumer keep rules, so proguard-rules.pro stays minimal.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // -PrequireReleaseSigning=true turns that fallback into a hard failure. The publish job
            // sets it: a bundle signed with the debug key is a different app identity, which Play
            // rejects — and a silent fallback would be worse than a red build.
            signingConfig = signingConfigs.findByName("release")
                ?: if (providers.gradleProperty("requireReleaseSigning").orNull == "true") {
                    throw GradleException(
                        "androidApp: no release signing config — ${rootProject.file("keystore.properties")} is absent " +
                            "and ANDROID_KEYSTORE_FILE/_PASSWORD, ANDROID_KEY_ALIAS/_PASSWORD are not all set. " +
                            "-PrequireReleaseSigning=true, so refusing to sign the release build with the debug key."
                    )
                } else {
                    signingConfigs.getByName("debug").also {
                        logger.warn(
                            "androidApp: keystore.properties not found at ${rootProject.file("keystore.properties")} — " +
                                "signing the release build with the debug key. See androidApp/build.gradle.kts."
                        )
                    }
                }
            buildConfigField("String", "TEST_SERVER_URL", "\"\"")
            buildConfigField("String", "TEST_USERNAME", "\"\"")
            buildConfigField("String", "TEST_PASSWORD", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    finalizeDsl { extension ->
        // benchmarkRelease is the plugin-made "release bytecode, debug signing, profileable"
        // variant that Macrobenchmark runs against. The journeys have
        // to get past the sign-in screen unattended, so this variant alone also gets the local
        // test credentials (same gitignored file as debug). Release keeps them empty.
        // nonMinifiedRelease is what the Baseline Profile *generator* drives, so it needs them too.
        listOf("benchmarkRelease", "nonMinifiedRelease").forEach { name ->
            extension.buildTypes.getByName(name) {
                buildConfigField("String", "TEST_SERVER_URL", "\"${localCredentials.getProperty("test.serverUrl", "")}\"")
                buildConfigField("String", "TEST_USERNAME", "\"${localCredentials.getProperty("test.username", "")}\"")
                buildConfigField("String", "TEST_PASSWORD", "\"${localCredentials.getProperty("test.password", "")}\"")
            }
        }
    }
}

// Firebase and the ".debug" applicationId. The google-services plugin fails the build for any
// variant whose applicationId has no "client" entry in google-services.json, and that file can
// only come from the Firebase console (project homesafe-percysafe): add an Android app with
// package com.meticulouscreations.homesafe.debug there and re-download androidApp/google-services.json.
// Until then, skip the plugin's task for such a variant so it still builds: FirebaseApp simply
// does not initialise, FirebaseMessaging throws, and PushRegistrar logs "push registration failed"
// instead of registering with the relay. Once the client exists this block does nothing.
val googleServicesPackages: Set<String> = run {
    val file = project.file("google-services.json")
    if (!file.exists()) return@run emptySet()
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
    val clients = json["client"] as? List<Map<String, Any?>> ?: emptyList()
    clients.mapNotNull { (it["client_info"] as? Map<String, Any?>)?.get("android_client_info") as? Map<String, Any?> }
        .mapNotNull { it["package_name"] as? String }
        .toSet()
}
androidComponents {
    onVariants { variant ->
        val appId = variant.applicationId.get()
        if (googleServicesPackages.isNotEmpty() && appId !in googleServicesPackages) {
            logger.warn(
                "androidApp: google-services.json has no client for '$appId' — skipping Firebase setup " +
                    "for the ${variant.name} variant, so push notifications will not work in it. " +
                    "Add that package as an Android app in the Firebase console and re-download the file."
            )
            val taskName = "process${variant.name.replaceFirstChar { it.uppercase() }}GoogleServices"
            tasks.matching { it.name == taskName }.configureEach { enabled = false }
        }
    }
}

baselineProfile {
    // Regenerate with `./gradlew :androidApp:generateBaselineProfile`; the result is committed.
    // Not merged into the build automatically — generation needs a device and a reachable Frigate server.
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

// `./gradlew assembleRelease -PcomposeCompilerReports=true` writes the Compose compiler's
// stability reports to build/compose_compiler/. Release only: debug's Live Literals make every
// constant look dynamic and skew the numbers.
composeCompiler {
    if (providers.gradleProperty("composeCompilerReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}
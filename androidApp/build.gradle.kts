import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // Reads androidApp/google-services.json (gitignored) for Firebase Cloud Messaging.
    alias(libs.plugins.googleServices)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // Push notifications from the HomeSafe relay on the Frigate box, via Firebase Cloud Messaging.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
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

android {
    namespace = "com.meticulouscreations.homesafe"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.meticulouscreations.homesafe"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "TEST_SERVER_URL", "\"${localCredentials.getProperty("test.serverUrl", "")}\"")
            buildConfigField("String", "TEST_USERNAME", "\"${localCredentials.getProperty("test.username", "")}\"")
            buildConfigField("String", "TEST_PASSWORD", "\"${localCredentials.getProperty("test.password", "")}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
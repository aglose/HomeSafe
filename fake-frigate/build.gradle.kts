import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * A stand-in Frigate NVR over real HTTP (see FakeFrigateServer). Plain JVM so that every test
 * source set that needs it can take it as an ordinary dependency: the shared module's JVM and
 * on-device integration tests, and the Android app's end-to-end tests. `./gradlew
 * :fake-frigate:run` serves it on its own for the Android CLI journeys (androidApp/src/journeysTest).
 */
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// Bytecode an Android test APK can dex, like the rest of the project.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.meticulouscreations.homesafe.fakefrigate.MainKt")
}

tasks.test {
    useJUnit()
}

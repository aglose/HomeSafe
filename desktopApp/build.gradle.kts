import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The Mac app: :app's Compose UI in a desktop window, packaged as a .dmg by
// `./gradlew :desktopApp:packageDmg` on a Mac (CI's "Desktop app" workflow builds one). On any OS,
// `./gradlew :desktopApp:run` opens it as it is. See docs/desktop.md.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
}

// The machine the app is built for is the one it is built on: jpackage packages the JDK it runs
// on, so Skia and FFmpeg come for that OS and CPU too.
val hostOs: String = System.getProperty("os.name").lowercase()
val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
val isMac = "mac" in hostOs
val skikoTarget = when {
    isMac -> if (arm) "macos-arm64" else "macos-x64"
    "win" in hostOs -> "windows-x64"
    else -> if (arm) "linux-arm64" else "linux-x64"
}
val bytedecoPlatform = when {
    isMac -> if (arm) "macosx-arm64" else "macosx-x86_64"
    "win" in hostOs -> "windows-x86_64"
    else -> if (arm) "linux-arm64" else "linux-x86_64"
}

dependencies {
    implementation(project(":app"))
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$skikoTarget:${libs.versions.composeMultiplatform.get()}")
    implementation(libs.compose.components.resources)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.navigationevent.compose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.jna)
    // FFmpeg's and JavaCPP's native libraries for this OS (the Java halves come with :shared).
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(bytedecoPlatform) })
    runtimeOnly(variantOf(libs.javacpp) { classifier(bytedecoPlatform) })
    ktlintRuleset(libs.composeRules.ktlint)
}

// The Touch ID helper (macos/OwnerCheck.swift), built with the Xcode command-line tools into the
// app's resources for this Mac's CPU, where MacOwnerCheck.kt finds it. Not on other OSes: the
// app then has no saved sign-in or Touch ID unlock, and says nothing of either.
val appResources = layout.buildDirectory.dir("appResources")
val buildOwnerCheck by tasks.registering(Exec::class) {
    // Plain values, not the script's own: the configuration cache can't keep a task that refers back to the script.
    val onMac = isMac
    val source = layout.projectDirectory.file("macos/OwnerCheck.swift")
    val output = appResources.get().file("$skikoTarget/HomeSafe").asFile
    onlyIf { onMac }
    inputs.file(source)
    outputs.file(output)
    commandLine("xcrun", "swiftc", "-O", "-target", "${if (arm) "arm64" else "x86_64"}-apple-macos12", source.asFile.path, "-o", output.path)
    doFirst { output.parentFile.mkdirs() }
}
// The Mac's own notifications (macos/Notifications.swift), a library MacNotifications.kt loads into
// the app; without it the menu-bar icon shows them, and a click can't say which one it was.
val buildNotifications by tasks.registering(Exec::class) {
    val onMac = isMac
    val source = layout.projectDirectory.file("macos/Notifications.swift")
    val output = appResources.get().file("$skikoTarget/libHomeSafeNotifications.dylib").asFile
    onlyIf { onMac }
    inputs.file(source)
    outputs.file(output)
    commandLine(
        "xcrun", "swiftc", "-O", "-emit-library", "-module-name", "HomeSafeNotifications",
        "-target", "${if (arm) "arm64" else "x86_64"}-apple-macos12", source.asFile.path, "-o", output.path,
    )
    doFirst { output.parentFile.mkdirs() }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildOwnerCheck, buildNotifications) }
// jpackage copies the app's resources without their executable bit, so the helper is marked
// runnable again in the app image (which packageDmg packs as it is).
tasks.matching { it.name == "createDistributable" }.configureEach {
    val images = layout.buildDirectory.dir("compose/binaries/main/app")
    doLast {
        images.get().asFile.walk()
            .filter { it.isFile && it.name == "HomeSafe" && it.parentFile.name == "resources" }
            .forEach { it.setExecutable(true, false) }
    }
}

// "1.0.<build>": macOS wants three numbers, the first above zero. CI passes the run number.
val desktopVersion = "${providers.gradleProperty("appVersionBase").get()}.${providers.gradleProperty("desktopBuild").getOrElse("0")}"

compose.desktop {
    application {
        mainClass = "com.meticulouscreations.homesafe.desktop.MainKt"
        jvmArgs += listOf(
            "-Xmx2g",
            "-Dhomesafe.version=$desktopVersion",
            // The app is dark whatever the Mac's appearance, so its title bar, menus and dialogs are too.
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
            "-Dapple.awt.application.name=HomeSafe",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "HomeSafe"
            packageVersion = desktopVersion
            description = "Your home's cameras, and the apps beside them"
            vendor = "Meticulous Creations"
            appResourcesRootDir.set(appResources)
            // What the app uses of the JDK: the AWT window, preferences, TLS with elliptic curves,
            // the logging OkHttp writes to, sun.misc.Unsafe for the libraries that want it, and
            // the time zones and locales dates are written in.
            modules(
                "java.desktop",
                "java.prefs",
                "java.logging",
                "java.naming",
                "java.net.http",
                "java.management",
                "jdk.crypto.ec",
                "jdk.unsupported",
                "jdk.localedata",
                "jdk.charsets",
                "jdk.accessibility",
            )
            macOS {
                bundleID = "com.meticulouscreations.homesafe.desktop"
                dockName = "HomeSafe"
                appCategory = "public.app-category.utilities"
                minimumSystemVersion = "12.0"
                iconFile.set(layout.projectDirectory.file("icons/HomeSafe.icns"))
                infoPlist {
                    // macOS 15 asks before an app talks to the house's LAN (the server's local address); this is what it says why.
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>HomeSafe talks to your home server directly when you're on its network, without going through Tailscale.</string>
                        <key>NSSupportsAutomaticGraphicsSwitching</key>
                        <true/>
                    """.trimIndent()
                }
            }
            linux {
                iconFile.set(layout.projectDirectory.file("icons/HomeSafe.png"))
            }
            windows {
                iconFile.set(layout.projectDirectory.file("icons/HomeSafe.ico"))
            }
        }
        buildTypes.release.proguard {
            // Reflection in Room, Ktor and Metro's generated code would each need rules; the size saved isn't worth it.
            isEnabled.set(false)
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    // Not the Compose resource accessors generated under build/ (see shared/build.gradle.kts).
    filter {
        exclude { entry -> entry.file.path.contains("/build/") }
    }
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

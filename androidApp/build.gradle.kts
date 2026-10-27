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
    implementation(project(":app"))

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
    implementation(libs.androidx.work.runtime)

    // Installs the shipped Baseline Profile into ART on first run (see androidApp/src/release/generated/baselineProfiles/).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // End-to-end tests on the real MainActivity (src/androidTest), signed in to the fake Frigate
    // that runs inside the instrumented process. See docs/testing.md.
    androidTestImplementation(project(":fake-frigate"))
    androidTestImplementation(libs.compose.uiTestJunit4)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
}

// A file at the repo root. Through `isolated`, because with Gradle's Isolated Projects a project
// may read where the root project is but not call into it (`rootProject.file(...)` is a violation).
fun rootFile(path: String): File = isolated.rootProject.projectDirectory.file(path).asFile

// Local, gitignored test credentials for the debug-only "Autofill test credentials" button
// on SecureConnectionScreen. File may be absent (fresh checkout); missing values just leave
// autofill blank. Never populated for release builds — see buildTypes below.
val localCredentials = Properties().apply {
    val file = rootFile("local.credentials.properties")
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
        val file = rootFile("keystore.properties")
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

// -PpreviewBuild=true: a release build that installs beside the real one, to try a branch on a
// phone that has HomeSafe installed without touching it (a different signature couldn't replace
// it, and uninstalling it would take its sign-in and settings with it). Own package
// (".preview"), "-preview" version, "HomeSafe Preview" label and a violet icon (src/preview/res).
// Firebase has no client for that package, so push is off in it (see googleServicesPackages), and
// ANDROID_ID differs per signing key, so Away mode sees it as a different phone. The "Preview APK"
// workflow builds it for a pull request labelled preview-apk.
val previewBuild = providers.gradleProperty("previewBuild").orNull == "true"

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
        // "1.0.62" for the release shipped by PR #62 (-PreleasePr=62, set by the same publish job),
        // so the version the app shows names the pull request it came from. See appVersionBase
        // in gradle.properties.
        val appVersionBase = providers.gradleProperty("appVersionBase").get()
        versionName = providers.gradleProperty("releasePr").map { "$appVersionBase.$it" }.getOrElse(appVersionBase)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // With the orchestrator below, each E2E test runs in its own instrumentation and the app's
        // data is wiped first: sign-in history lives in Room and the app graph is per process, so
        // a test must never inherit the previous one's server.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        // Compose Preview Screenshot Testing, as an AGP test suite (android.experimental.testSuiteSupport
        // in gradle.properties): every @Preview in :shared, the features and :app, drawn by
        // Layoutlib on the host through the wrappers generatePreviewTests writes (below). See
        // docs/ui-previews.md.
        //   ./gradlew :androidApp:updateDebugScreenshotTestDefaultTestSuite  draws them under src/screenshotTestDefaultDebug/reference/
        //   ./gradlew :androidApp:testDebugScreenshotTestDefaultTestSuite    compares against those; report in build/reports/tests/
        // With PREVIEWS=finance,HomeFeed in the environment (or -Ppreviews=…), only the previews whose
        // module, function or name contains one of those words are drawn.
        // Layoutlib resolves layout XML and theme attributes from compiled resources; without them
        // AGP won't create the suite's tasks at all ("Compose Preview requires compiled Android resources").
        unitTests.isIncludeAndroidResources = true
        screenshotTests.create("screenshotTest") {
            engineVersion = libs.versions.screenshot.get()
            targetVariants.add("debug")
            dependencies {
                implementation(libs.compose.uiTooling)
                implementation(libs.screenshot.validation.api)
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Duplicated by the Ktor server jars the E2E test APK carries (:fake-frigate).
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
    signingConfigs {
        // All four or none: a half-configured signing config fails deep inside the packaging task
        // with a much worse message than the debug-key warning below.
        if (keystoreProperties.keys.containsAll(listOf("storeFile", "storePassword", "keyAlias", "keyPassword"))) {
            create("release") {
                storeFile = rootFile(keystoreProperties.getValue("storeFile"))
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
            if (previewBuild) {
                applicationIdSuffix = ".preview"
                versionNameSuffix = "-preview"
            }
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
                        "androidApp: no release signing config — ${rootFile("keystore.properties")} is absent " +
                            "and ANDROID_KEYSTORE_FILE/_PASSWORD, ANDROID_KEY_ALIAS/_PASSWORD are not all set. " +
                            "-PrequireReleaseSigning=true, so refusing to sign the release build with the debug key."
                    )
                } else {
                    signingConfigs.getByName("debug").also {
                        logger.warn(
                            "androidApp: keystore.properties not found at ${rootFile("keystore.properties")} — " +
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
        // -PbenchmarkAppIdSuffix=.benchmark gives both their own package, so a benchmark can be run on
        // a phone that has the real app installed (a different signature can't replace it, and
        // uninstalling it would take its sign-in and places with it). The :baselineprofile journeys
        // read the same property. Firebase has no client for that package, so push is off in it.
        val benchmarkAppIdSuffix = providers.gradleProperty("benchmarkAppIdSuffix").orNull
        listOf("benchmarkRelease", "nonMinifiedRelease").forEach { name ->
            extension.buildTypes.getByName(name) {
                if (benchmarkAppIdSuffix != null) applicationIdSuffix = benchmarkAppIdSuffix
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
    if (previewBuild) {
        onVariants(selector().withBuildType("release")) { variant ->
            variant.sources.res?.addStaticSourceDirectory("src/preview/res")
        }
    }
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

// One emulator can't run two instrumented suites at once: their Activities cover each other,
// and a test waiting for its own to resume or draw waits for good. With the configuration cache
// Gradle runs tasks of different projects in parallel, so order the E2E suite after the modules'
// device tests (the last of which is :app's) whenever both are asked for.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    mustRunAfter(":app:connectedAndroidDeviceTest")
}

/**
 * Writes a @PreviewTest wrapper for every @Preview in the app's modules, so the screenshot suite
 * draws all of them with no list to keep. The tool only finds @PreviewTest functions in this
 * module's screenshotTest sources, and a preview in another module is usually private, so each
 * wrapper copies the preview's @Preview arguments and calls it by reflection (DrawPreview in
 * src/screenshotTest).
 *
 * The previews are read from source, not bytecode: a top-level `@Preview` (possibly several on one
 * function) whose arguments are literals or a `const val` of the same file. Anything else fails
 * the build with the file and the argument, rather than drawing the wrong thing.
 */
abstract class GeneratePreviewTests : DefaultTask() {
    /** Kotlin sources of the modules whose previews are drawn, one tree per source set. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The build's root directory, to name each preview's module from its path. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** PREVIEWS or -Ppreviews: comma-separated words; only previews whose module, function or name contains one. */
    @get:Input
    @get:Optional
    abstract val filter: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private class Found(val module: String, val className: String, val function: String, val arguments: List<Pair<String, String>>) {
        val name: String get() = arguments.firstOrNull { it.first == "name" }?.second?.removeSurrounding("\"").orEmpty()
    }

    @TaskAction
    fun generate() {
        val root = rootDirectory.get().asFile
        val found = sources.asFileTree.files.filter { it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
            val module = file.relativeTo(root).invariantSeparatorsPath.substringBefore("/src/").substringAfterLast('/')
            previewsIn(file, module)
        }
        val words = filter.orNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val drawn = if (words.isEmpty()) {
            found
        } else {
            found.filter { preview -> words.any { word -> listOf(preview.module, preview.function, preview.name).any { it.contains(word, ignoreCase = true) } } }
        }
        if (drawn.isEmpty()) {
            throw GradleException("No @Preview matches '${filter.orNull}'. There are ${found.size}: " + found.joinToString { "${it.module}/${it.function}" })
        }

        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        val dir = out.resolve("com/meticulouscreations/homesafe/screenshots/generated").apply { mkdirs() }
        val taken = mutableSetOf<String>()
        drawn.groupBy { it.module }.forEach { (module, previews) ->
            val prefix = module.replaceFirstChar { it.uppercase() }
            val text = buildString {
                appendLine("// Generated by :androidApp:generatePreviewTests from the @Preview functions in $module. Don't edit.")
                appendLine("package com.meticulouscreations.homesafe.screenshots.generated")
                appendLine()
                appendLine("import androidx.compose.runtime.Composable")
                appendLine("import androidx.compose.ui.tooling.preview.Preview")
                appendLine("import com.android.tools.screenshot.PreviewTest")
                appendLine("import com.meticulouscreations.homesafe.screenshots.DrawPreview")
                previews.forEach { preview ->
                    val base = "${prefix}_${preview.function}"
                    val wrapper = generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { taken.add(it) }
                    appendLine()
                    appendLine("@PreviewTest")
                    appendLine(
                        if (preview.arguments.isEmpty()) "@Preview" else "@Preview(${preview.arguments.joinToString { (key, value) -> "$key = $value" }})"
                    )
                    appendLine("@Composable")
                    appendLine("fun $wrapper() = DrawPreview(\"${preview.className}\", \"${preview.function}\")")
                }
            }
            dir.resolve("${prefix}Previews.kt").writeText(text)
        }
        // What's there to ask for with -Ppreviews, for people and agents: module, function, name.
        out.resolve("previews.txt").writeText(found.joinToString("\n", postfix = "\n") { "${it.module}\t${it.function}\t${it.name}" })
        // The same for tools/preview-server, which draws the previews themselves rather than these
        // wrappers: module, JVM class, function, name, and the @Preview arguments as Layoutlib's
        // renderer takes them (key=value, plain values, separated by U+001F).
        out.resolve("previews.tsv").writeText(
            found.joinToString("\n", postfix = "\n") { preview ->
                val arguments = preview.arguments.joinToString("\u001F") { (key, value) -> "$key=${plainValue(value)}" }
                listOf(preview.module, preview.className, preview.function, preview.name, arguments).joinToString("\t")
            }
        )
        logger.lifecycle("generatePreviewTests: ${drawn.size} of ${found.size} previews")
    }

    private fun previewsIn(file: File, module: String): List<Found> {
        val text = file.readText()
        if (!text.contains("@Preview")) return emptyList()
        val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        val jvmName = Regex("""@file:JvmName\("(\w+)"\)""").find(text)?.groupValues?.get(1)
        val simpleClass = jvmName ?: (file.name.removeSuffix(".kt").replace('.', '_') + "Kt")
        val className = if (pkg == null) simpleClass else "$pkg.$simpleClass"
        val constants = Regex("""^\s*(?:private |internal )?const val (\w+)(?:\s*:\s*\w+)?\s*=\s*([^\n]+?)\s*$""", RegexOption.MULTILINE)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

        val previews = mutableListOf<Found>()
        val pending = mutableListOf<List<Pair<String, String>>>()
        var at = 0
        val annotation = Regex("""^[ \t]*@Preview\b(?![\w.])""", RegexOption.MULTILINE)
        val function = Regex("""^((?:(?:private|internal|public)\s+)?)fun\s+(\w+)\s*\(""", RegexOption.MULTILINE)
        while (true) {
            val next = annotation.find(text, at) ?: break
            var end = next.range.last + 1
            val arguments = if (text.getOrNull(end) == '(') {
                val close = closingParen(text, end) ?: throw GradleException("${file.path}: unclosed @Preview(")
                val parsed = splitArguments(text.substring(end + 1, close)).map { argument ->
                    val key = argument.substringBefore('=').trim()
                    val raw = argument.substringAfter('=', missingDelimiterValue = "").trim()
                    if (key.isEmpty() || raw.isEmpty()) throw GradleException("${file.path}: can't read @Preview argument '$argument' (write it as name = value)")
                    key to resolve(raw, constants, file)
                }
                end = close + 1
                parsed
            } else {
                emptyList()
            }
            pending += arguments
            at = end
            // The function these annotations are on: the next `fun` at the start of a line, with only
            // more annotations (another @Preview, @Composable, ...) and whitespace in between.
            val target = function.find(text, at) ?: throw GradleException("${file.path}: @Preview with no function after it")
            val between = text.substring(at, target.range.first)
            if (annotation.containsMatchIn(between)) continue
            if (between.contains('{') || between.contains('=')) {
                throw GradleException("${file.path}: the @Preview before line ${text.lineNumberAt(target.range.first)} isn't on a top-level function")
            }
            pending.forEach { previews += Found(module, className, target.groupValues[2], it) }
            pending.clear()
            at = target.range.last + 1
        }
        return previews
    }

    private fun resolve(raw: String, constants: Map<String, String>, file: File): String = when {
        raw.startsWith('"') -> raw
        raw.first().isDigit() || raw.first() == '-' || raw == "true" || raw == "false" -> raw
        raw in constants -> resolve(constants.getValue(raw), constants, file)
        else -> throw GradleException("${file.path}: @Preview argument '$raw' isn't a literal or a const val of the same file")
    }

    /** A Kotlin literal as the renderer's preview parameters hold it: "Home" -> Home, 1.5f -> 1.5. */
    private fun plainValue(literal: String): String = when {
        literal.startsWith('"') -> literal.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        literal.startsWith("0x") -> literal.removeSuffix("L")
        literal.endsWith('f') || literal.endsWith('F') || literal.endsWith('L') -> literal.dropLast(1)
        else -> literal
    }

    private fun closingParen(text: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    private fun splitArguments(arguments: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var depth = 0
        var i = 0
        while (i < arguments.length) {
            val c = arguments[i]
            when {
                inString && c == '\\' -> { current.append(c).append(arguments[i + 1]); i += 2; continue }
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> depth--
                !inString && depth == 0 && c == ',' -> { parts += current.toString(); current.clear(); i++; continue }
            }
            current.append(c)
            i++
        }
        parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun String.lineNumberAt(index: Int) = take(index).count { it == '\n' } + 1
}

val generatePreviewTests = tasks.register<GeneratePreviewTests>("generatePreviewTests") {
    val root = isolated.rootProject.projectDirectory
    rootDirectory = root
    val modules = listOf(root.dir("shared"), root.dir("app")) +
        root.dir("features").asFile.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.map { root.dir("features/${it.name}") }
    modules.forEach { module ->
        listOf("commonMain", "androidMain").forEach { sourceSet -> sources.from(module.dir("src/$sourceSet/kotlin")) }
    }
    // PREVIEWS in the environment is read only when the task runs, so changing it keeps the
    // configuration cache; a different -Ppreviews makes Gradle configure the build again.
    filter = providers.environmentVariable("PREVIEWS").orElse(providers.gradleProperty("previews"))
    outputDirectory = layout.buildDirectory.dir("generated/previewTests")
}

/**
 * Writes the launch file tools/preview-server starts from: the JVM and classpath the screenshot
 * suite runs with, and where the preview catalogue is. It only rewrites the file when that
 * changes (a dependency did), which scripts/preview takes as the cue to restart the server.
 */
abstract class PreviewServerPrepare : DefaultTask() {
    @get:Internal
    abstract val classpath: ConfigurableFileCollection

    @get:Internal
    abstract val java: Property<String>

    @get:Internal
    abstract val catalogue: RegularFileProperty

    @get:Internal
    abstract val launchFile: RegularFileProperty

    @TaskAction
    fun write() {
        val text = buildString {
            appendLine("java=${java.get()}")
            appendLine("catalogue=${catalogue.get().asFile.absolutePath}")
            appendLine("classpath=${classpath.asPath}")
        }
        val file = launchFile.get().asFile
        if (!file.exists() || file.readText() != text) {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
    }
}

// scripts/preview runs this before every render: it builds what Layoutlib reads (each module's
// classes and R classes, the resources, the preview catalogue) and nothing else.
tasks.register<PreviewServerPrepare>("previewServerPrepare") {
    val suite = tasks.named<Test>("updateDebugScreenshotTestDefaultTestSuite")
    classpath.from(suite.map { it.classpath })
    java = suite.flatMap { it.javaLauncher }.map { it.executablePath.asFile.absolutePath }
    catalogue = generatePreviewTests.flatMap { it.outputDirectory.file("previews.tsv") }
    launchFile = layout.buildDirectory.file("preview-server/launch.properties")
    outputs.upToDateWhen { false }

    val root = isolated.rootProject.projectDirectory
    val modules = listOf(":shared", ":app") +
        root.dir("features").asFile.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.map { ":${it.name}" }
    modules.forEach { module ->
        dependsOn("$module:bundleAndroidMainClassesToRuntimeJar", "$module:generateAndroidMainRFile")
    }
    dependsOn(generatePreviewTests, "compileDebugKotlin", "packageDebugForScreenshotTest")
}

// Layoutlib gives each render step 10 s (layoutlib.thread.timeout, in ms) and fails the preview
// after three slow tries. The heaviest previews (Fitness's stone workout figure) take longer than
// that on CI's Linux runners, though not here, so allow a minute: still a failure when one hangs.
// Every preview renders in one test JVM, and Gradle's default 512 MB heap ran out on CI ("Java
// heap space") once there were a few more tall pages ahead of the weather screens' shaders.
tasks.withType<Test>().matching { it.name.contains("ScreenshotTest") }.configureEach {
    systemProperty("layoutlib.thread.timeout", "60000")
    maxHeapSize = "2g"
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.suites["screenshotTest"]?.sources?.filterIsInstance<com.android.build.api.variant.TestSuiteSourceSet.HostJar>()?.forEach {
            it.kotlin?.addGeneratedSourceDirectory(generatePreviewTests, GeneratePreviewTests::outputDirectory)
        }
    }
}

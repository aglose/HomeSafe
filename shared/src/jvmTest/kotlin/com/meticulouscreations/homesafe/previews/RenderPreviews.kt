package com.meticulouscreations.homesafe.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassGraph
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Method
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders every `@Preview` in the module to a PNG, on the desktop runtime: no emulator, no
 * Layoutlib, nothing to install beyond the JDK. It is how a change to a composable gets looked
 * at from a terminal — by a person, or by an agent that reads the images back — and what the
 * "UI previews" workflow puts in a pull request's description. See docs/ui-previews.md.
 *
 * Run it through its own task, not jvmTest (where it does nothing):
 *
 *     ./gradlew :shared:renderPreviews                 # every preview
 *     ./gradlew :shared:renderPreviews -Ppreview=Home  # ids containing "Home"
 *
 * The previews are found in the compiled classes rather than listed anywhere: `@Preview` has
 * binary retention, so reflection can't see it, but ClassGraph reads it straight from the class
 * files. Each is then called the way the Compose compiler would call it — a no-argument
 * composable is a static method taking the [Composer] and a `$changed` int — inside an
 * [ImageComposeScene] the size of a phone, with [LocalInspectionMode] on as in Android Studio.
 *
 * The app ships its own fonts (Albert Sans, Fraunces), so text here is drawn with the same
 * glyphs as on a phone. What differs from Android is what Android draws itself: system bars,
 * ripples mid-flight, and anything behind an `expect`/`actual` (a player, a map).
 */
class RenderPreviews {

    @Test
    fun renderEveryPreview() {
        val outDir = System.getProperty(OUT_DIR_PROPERTY)?.let(::File) ?: return
        val filter = System.getProperty(FILTER_PROPERTY)?.takeIf { it.isNotBlank() }

        val found = findPreviews()
        if (found.isEmpty()) fail("No @Preview functions found under $APP_PACKAGE — is the scan looking at the right classes?")
        val previews = found.filter { filter == null || it.id.contains(filter, ignoreCase = true) }
        if (previews.isEmpty()) fail("No preview id contains \"$filter\". Ids: ${found.joinToString { it.id }}")

        outDir.deleteRecursively()
        outDir.mkdirs()
        val results = previews.map { preview ->
            val image = runCatching { render(preview) }
            image.getOrNull()?.let { ImageIO.write(it, "png", File(outDir, "${preview.id}.png")) }
            preview to image
        }
        writeManifest(outDir, results)

        val failures = results.filter { it.second.isFailure }
        println("Rendered ${results.size - failures.size} of ${results.size} previews to ${outDir.absolutePath}")
        results.forEach { (preview, image) ->
            image.onSuccess { println("  ${preview.id}.png  ${it.width}x${it.height}  (${preview.source})") }
            image.onFailure { println("  ${preview.id}  FAILED: ${rootCause(it)}") }
        }
        if (failures.isNotEmpty()) {
            val report = failures.joinToString("\n\n") { (preview, image) ->
                "${preview.id} (${preview.source}):\n${image.exceptionOrNull()!!.let(::rootCause).stackTraceToString().lines().take(STACK_LINES).joinToString("\n")}"
            }
            fail("${failures.size} preview(s) failed to render:\n\n$report")
        }
    }

    /** What actually went wrong, under the reflection and composition layers wrapped around it. */
    private fun rootCause(error: Throwable): Throwable = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.last()

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(preview: PreviewFunction): BufferedImage {
        val method = preview.resolve()
        val density = Density(DENSITY, preview.fontScale)
        var contentSize = IntSize.Zero
        // Every argument spelled out: the class also keeps an older constructor without layoutDirection.
        val scene = ImageComposeScene(
            width = (preview.widthDp * DENSITY).roundToInt(),
            height = (preview.heightDp * DENSITY).roundToInt(),
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            coroutineContext = Dispatchers.Unconfined,
        ) {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart)
                        .onGloballyPositioned { contentSize = it.size }
                        .then(if (preview.showBackground) Modifier.background(preview.background) else Modifier),
                ) {
                    CallPreview(method)
                }
            }
        }
        try {
            // A few frames' worth of time, so first-frame effects, fades-in and resource loads land.
            var image = scene.render(0)
            for (frame in 1..SETTLE_FRAMES) image = scene.render(frame * FRAME_NANOS)
            val bitmap = image.toComposeImageBitmap().toAwtImage()
            val width = contentSize.width.coerceIn(1, bitmap.width)
            val height = contentSize.height.coerceIn(1, bitmap.height)
            return bitmap.getSubimage(0, 0, width, height)
        } finally {
            scene.close()
        }
    }

    private fun writeManifest(outDir: File, results: List<Pair<PreviewFunction, Result<BufferedImage>>>) {
        fun String.json() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val entries = results.joinToString(",\n") { (preview, image) ->
            val fields = buildList {
                add("\"id\": ${preview.id.json()}")
                add("\"function\": ${"${preview.className}.${preview.methodName}".json()}")
                add("\"source\": ${preview.source.json()}")
                preview.name?.let { add("\"name\": ${it.json()}") }
                image.onSuccess {
                    add("\"file\": ${"${preview.id}.png".json()}")
                    add("\"width\": ${it.width}")
                    add("\"height\": ${it.height}")
                }
                image.onFailure { add("\"error\": ${rootCause(it).toString().json()}") }
            }
            "  {" + fields.joinToString(", ") + "}"
        }
        File(outDir, "previews.json").writeText("[\n$entries\n]\n")
    }

    private companion object {
        const val OUT_DIR_PROPERTY = "homesafe.previews.out"
        const val FILTER_PROPERTY = "homesafe.previews.filter"
        const val APP_PACKAGE = "com.meticulouscreations.homesafe"

        /** Pixels per dp. Phones are 2.625–3; 2 keeps the images small enough to read back cheaply. */
        const val DENSITY = 2f
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 16_000_000L
        const val STACK_LINES = 25
    }
}

/**
 * Calls a no-argument composable found by reflection, exactly as compiled code would: the
 * current composer and `$changed = 0` ("nothing known about the arguments", so it always runs).
 */
@Composable
private fun CallPreview(method: Method) {
    method.invoke(null, currentComposer, 0)
}

/** One `@Preview` (a function carrying several is one of these per annotation). */
internal data class PreviewFunction(
    val className: String,
    val methodName: String,
    val sourceFile: String?,
    val name: String?,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val showBackground: Boolean,
    val background: Color,
    val id: String,
) {
    val source: String get() = "${sourceFile ?: className.substringAfterLast('.')} · ${methodName.substringBefore('-')}"

    fun resolve(): Method = Class.forName(className)
        .getDeclaredMethod(methodName, Composer::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
}

private const val PREVIEW = "androidx.compose.ui.tooling.preview.Preview"
private const val PREVIEW_CONTAINER = "androidx.compose.ui.tooling.preview.Preview\$Container"
private const val PREVIEW_PACKAGE = "androidx.compose.ui.tooling.preview"

/** A phone in portrait, for previews that don't say how big they are. */
private const val DEFAULT_WIDTH_DP = 412
private const val DEFAULT_HEIGHT_DP = 915

/**
 * Every function in the app's packages carrying `@Preview` — directly, several times over, or
 * through a multipreview annotation such as `@PreviewFontScale` — that takes no parameters.
 * Previews fed by a `@PreviewParameter` aren't supported and are skipped.
 */
internal fun findPreviews(): List<PreviewFunction> = ClassGraph()
    .enableClassInfo()
    .enableMethodInfo()
    .enableAnnotationInfo()
    .ignoreClassVisibility()
    .ignoreMethodVisibility()
    .acceptPackages("com.meticulouscreations.homesafe", PREVIEW_PACKAGE)
    .scan()
    .use { scan ->
        val found = scan.allClasses
            .filter { it.packageName.startsWith("com.meticulouscreations.homesafe") && !it.isAnnotation }
            .flatMap { classInfo ->
                classInfo.declaredMethodInfo
                    .filter(::takesOnlyTheComposer)
                    .flatMap { method ->
                        val annotations = previewAnnotations(method.annotationInfo, scan)
                        annotations.mapIndexed { index, annotation ->
                            previewFunction(classInfo.name, classInfo.sourceFile, method, annotation, index.takeIf { annotations.size > 1 })
                        }
                    }
            }
        // Two previews could still collide (same file and function name in two packages).
        found.groupBy { it.id }.flatMap { (_, same) ->
            if (same.size == 1) same else same.mapIndexed { i, preview -> preview.copy(id = "${preview.id}_${i + 1}") }
        }.sortedBy { it.id }
    }

private fun takesOnlyTheComposer(method: MethodInfo): Boolean {
    val parameters = method.parameterInfo
    return method.isStatic &&
        parameters.size == 2 &&
        parameters[0].typeDescriptor.toString() == Composer::class.java.name &&
        parameters[1].typeDescriptor.toString() == "int"
}

private fun previewAnnotations(annotations: List<AnnotationInfo>, scan: ScanResult, depth: Int = 0): List<AnnotationInfo> =
    annotations.flatMap { annotation ->
        when (annotation.name) {
            PREVIEW -> listOf(annotation)
            PREVIEW_CONTAINER -> (annotation.parameterValues.getValue("value") as? Array<*>).orEmpty().filterIsInstance<AnnotationInfo>()
            else -> {
                // A multipreview: an annotation class that is itself annotated with @Preview.
                val annotationClass = scan.getClassInfo(annotation.name)
                if (annotationClass == null || depth >= MAX_MULTIPREVIEW_DEPTH) emptyList() else previewAnnotations(annotationClass.annotationInfo, scan, depth + 1)
            }
        }
    }

private const val MAX_MULTIPREVIEW_DEPTH = 3

private fun previewFunction(className: String, sourceFile: String?, method: MethodInfo, preview: AnnotationInfo, index: Int?): PreviewFunction {
    val values = preview.parameterValues
    fun <T> value(name: String): T? {
        @Suppress("UNCHECKED_CAST")
        return values.getValue(name) as T?
    }
    val name = value<String>("name")?.takeIf { it.isNotBlank() }
    val file = className.substringAfterLast('.').removeSuffix("Kt")
    // Kotlin mangles some names (`Foo-abc123` for inline-class parameters); the id keeps the readable part.
    val function = method.name.substringBefore('-')
    val suffix = name?.let { "_" + it.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') } ?: index?.let { "_${it + 1}" }.orEmpty()
    return PreviewFunction(
        className = className,
        methodName = method.name,
        sourceFile = sourceFile,
        name = name,
        widthDp = value<Int>("widthDp")?.takeIf { it > 0 } ?: DEFAULT_WIDTH_DP,
        heightDp = value<Int>("heightDp")?.takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP,
        fontScale = value<Float>("fontScale")?.takeIf { it > 0f } ?: 1f,
        showBackground = value<Boolean>("showBackground") ?: false,
        background = value<Long>("backgroundColor")?.takeIf { it != 0L }?.let { Color(it.toInt()) } ?: Color.White,
        id = "$file.$function$suffix",
    )
}

package com.meticulouscreations.homesafe.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.StringResource

/**
 * Something Claude can look at to answer: one part of the app's data, read on request. Each module
 * contributes its own into the graph's set (`@ContributesIntoSet(AppScope::class)`), so the
 * assistant can see the cameras, the finances, the weather and the rest without knowing any of
 * them. Tools only read; nothing Claude does changes the house.
 *
 * What a tool returns is for Claude, not the person: plain text or JSON in English, written for the
 * model, so it isn't localised. Data classes' own `toString()` is fine. [activity] is what the
 * screen says while the tool runs, and that is copy.
 */
interface AssistantTool {
    /** The tool's name as Claude calls it: `snake_case`, unique across the app. */
    val name: String

    /** What it gives back and when it is worth calling, for Claude. */
    val description: String

    /** The JSON Schema of its input; [noInput] for a tool that takes nothing. */
    val inputSchema: JsonObject

    /** What the screen says while it runs ("Checking the doors"). */
    val activity: StringResource

    suspend fun run(input: JsonObject): String
}

/** The schema of a tool that takes no input. */
val noInput: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(emptyMap()))
}

/** The schema of a tool whose input is the named properties, each a JSON Schema of its own; none required. */
fun inputOf(vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties.toMap()))
}

fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun integerProperty(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

fun JsonObject.stringInput(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.intInput(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

/** The flow's current value, or null when it has none within [timeoutMillis] (not signed in, nothing loaded yet). */
suspend fun <T> Flow<T>.current(timeoutMillis: Long = 5_000): T? = withTimeoutOrNull(timeoutMillis) { first() }

/** [items] one per line, the newest [limit] of them, saying how many more were left out. */
fun <T> listing(items: List<T>, limit: Int, describe: (T) -> String = { it.toString() }): String {
    if (items.isEmpty()) return "(none)"
    val shown = items.take(limit).joinToString("\n") { "- " + describe(it) }
    return if (items.size > limit) "$shown\n(${items.size - limit} more not shown)" else shown
}

/** A tool result trimmed to what is worth putting in front of the model. */
internal fun String.capped(maxChars: Int = MAX_TOOL_RESULT_CHARS): String =
    if (length <= maxChars) this else take(maxChars) + "\n…(cut off; ask for less)"

private const val MAX_TOOL_RESULT_CHARS = 60_000

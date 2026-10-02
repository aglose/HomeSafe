package com.meticulouscreations.homesafe.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Words for the screen that are decided outside a composable — by a view model, a use case, a
 * domain model's presenter — but still have to come out of the `strings*.xml` files under
 * `composeResources/values` so the app can be translated. The text is resolved where it is shown: [resolve] in a
 * composable, [load] anywhere else that can suspend (a notification, a share sheet).
 *
 * Arguments may themselves be [UiText] (resolved first, in the same language) or plain values
 * (numbers, already-formatted dates, names that come from the server), which are passed through
 * with `toString()`. Resource strings take positional `%1$s` / `%1$d` placeholders only.
 *
 * Equality is structural, so tests assert on the resource and its arguments rather than on the
 * English: `assertEquals(UiText.of(Res.string.moments_since, "6:55 PM"), visit.timeLabel)`.
 */
@Immutable
sealed interface UiText {

    /** Text that is data, not copy: a camera's name, a person's name, a number already formatted. Never English prose. */
    @Immutable
    data class Verbatim(val value: String) : UiText

    @Immutable
    data class Resource(val res: StringResource, val args: List<Any> = emptyList()) : UiText

    @Immutable
    data class Plural(val res: PluralStringResource, val quantity: Int, val args: List<Any> = emptyList()) : UiText

    /** [parts] side by side, with [separator] (itself translatable) between each pair. */
    @Immutable
    data class Joined(val parts: List<UiText>, val separator: UiText = Verbatim("")) : UiText

    companion object {
        fun of(res: StringResource, vararg args: Any): UiText = Resource(res, args.toList())

        /** [quantity] picks the plural form; it is also `%1$d` unless [args] are given. */
        fun plural(res: PluralStringResource, quantity: Int, vararg args: Any): UiText =
            Plural(res, quantity, if (args.isEmpty()) listOf(quantity) else args.toList())

        fun verbatim(value: String): UiText = Verbatim(value)

        val Empty: UiText = Verbatim("")
    }
}

/** Shorthand for [UiText.verbatim]: marks a string as data that needs no translation. */
fun String.asUiText(): UiText = UiText.Verbatim(this)

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Verbatim -> value
    is UiText.Resource -> stringResource(res, *resolveArgs(args))
    is UiText.Plural -> pluralStringResource(res, quantity, *resolveArgs(args))
    is UiText.Joined -> {
        val sep = separator.resolve()
        val resolved = parts.map { it.resolve() }
        resolved.joinToString(sep)
    }
}

/** [resolve] for code that is not in a composition. */
suspend fun UiText.load(): String = when (this) {
    is UiText.Verbatim -> value
    is UiText.Resource -> getString(res, *loadArgs(args))
    is UiText.Plural -> getPluralString(res, quantity, *loadArgs(args))
    is UiText.Joined -> {
        val sep = separator.load()
        parts.map { it.load() }.joinToString(sep)
    }
}

@Composable
private fun resolveArgs(args: List<Any>): Array<Any> = args.map { if (it is UiText) it.resolve() else it }.toTypedArray()

private suspend fun loadArgs(args: List<Any>): Array<Any> = args.map { if (it is UiText) it.load() else it }.toTypedArray()

/**
 * An exception whose message is meant for the person using the app. Code that throws something
 * the UI will show throws this, so the view model can show [text] in the reader's language
 * instead of an English `message`. [technical] becomes [message]: keep whatever callers match on
 * (an HTTP status, "Not connected") there, since [text] is no longer English to search.
 */
open class LocalizedException(
    val text: UiText,
    technical: String? = null,
    cause: Throwable? = null,
) : Exception(technical ?: text.toString(), cause)

/**
 * What to show for this failure: its [LocalizedException.text] when it has one, otherwise
 * [fallback] (which may take the raw message as `%1$s`, e.g. "Couldn't save: %1$s"). The raw
 * message is server or platform text, so it is passed through untranslated.
 */
fun Throwable.userMessage(fallback: StringResource? = null): UiText {
    (this as? LocalizedException)?.let { return it.text }
    val raw = message.orEmpty()
    return if (fallback != null) UiText.of(fallback, raw) else UiText.Verbatim(raw)
}

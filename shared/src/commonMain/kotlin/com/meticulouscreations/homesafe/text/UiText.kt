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
 * (numbers, already-formatted dates, names that come from the server), which are handed to the
 * resource formatter as they are; it writes each with `toString()` into its positional `%1$s` /
 * `%1$d` placeholder. Arguments must be values that don't change (strings, numbers, other
 * [UiText]): [UiText] is [Immutable], and the lists it holds are copies taken when it is built.
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
    class Resource(val res: StringResource, args: List<Any> = emptyList()) : UiText {
        val args: List<Any> = args.toList()

        override fun equals(other: Any?) = other is Resource && res == other.res && args == other.args
        override fun hashCode() = 31 * res.hashCode() + args.hashCode()
        override fun toString() = "Resource(res=${res.key}, args=$args)"
    }

    @Immutable
    class Plural(val res: PluralStringResource, val quantity: Int, args: List<Any> = emptyList()) : UiText {
        val args: List<Any> = args.toList()

        override fun equals(other: Any?) = other is Plural && res == other.res && quantity == other.quantity && args == other.args
        override fun hashCode() = 31 * (31 * res.hashCode() + quantity) + args.hashCode()
        override fun toString() = "Plural(res=${res.key}, quantity=$quantity, args=$args)"
    }

    /** [parts] side by side, with [separator] (itself translatable) between each pair. */
    @Immutable
    class Joined(parts: List<UiText>, val separator: UiText = Verbatim("")) : UiText {
        val parts: List<UiText> = parts.toList()

        override fun equals(other: Any?) = other is Joined && parts == other.parts && separator == other.separator
        override fun hashCode() = 31 * parts.hashCode() + separator.hashCode()
        override fun toString() = "Joined(parts=$parts, separator=$separator)"
    }

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
 * What to show for this failure: why it failed — its [LocalizedException.text] when it has one,
 * otherwise its raw message, which is server or platform text and so shown untranslated — set
 * into [fallback] as its `%1$s` argument when given ("Couldn't save: %1$s"), like any other
 * [UiText] argument.
 */
fun Throwable.userMessage(fallback: StringResource? = null): UiText {
    val localized = (this as? LocalizedException)?.text
    val raw = message.orEmpty()
    return when {
        fallback != null -> UiText.of(fallback, localized ?: raw)
        else -> localized ?: UiText.Verbatim(raw)
    }
}

/**
 * [load] behind an interface, for code outside the UI that hands a finished `String` to the
 * platform, such as a notification. Tests substitute it where resources can't be read: Android
 * host tests have no `Resources` to load them through.
 */
fun interface TextLoader {
    suspend fun load(text: UiText): String

    companion object {
        val Resources: TextLoader = TextLoader { it.load() }
    }
}

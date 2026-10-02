package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.error_no_server_to_ask
import homesafe.shared.generated.resources.error_not_connected
import org.jetbrains.compose.resources.StringResource

/** No server is signed in to, so there is nowhere to ask. The message keeps "Not connected" for callers that look for it. */
internal fun notConnected(): FrigateResponseException =
    FrigateResponseException(UiText.of(Res.string.error_not_connected), technical = "Not connected to a server")

/** None of the addresses this install knows for its server could be tried. */
internal fun noServerToAsk(): LocalizedException = LocalizedException(UiText.of(Res.string.error_no_server_to_ask), technical = "No server to ask")

/**
 * What to show for a failed fetch: its own words when it has any (a [LocalizedException]'s text,
 * or the platform's message, which is passed through as it is), otherwise [fallback].
 */
internal fun Throwable.errorText(fallback: StringResource): UiText = when {
    this is LocalizedException -> text
    message.isNullOrBlank() -> UiText.of(fallback)
    else -> UiText.Verbatim(message.orEmpty())
}

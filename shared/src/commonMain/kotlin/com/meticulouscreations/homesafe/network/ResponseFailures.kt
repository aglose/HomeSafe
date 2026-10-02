package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import org.jetbrains.compose.resources.StringResource

/**
 * Throws unless this response succeeded. [res] says what couldn't be done and takes the status
 * ("404 Not Found") as `%1$s`; the exception's message is "HTTP <status>", so a caller can still
 * look for a status code in it.
 */
internal fun HttpResponse.requireSuccess(res: StringResource) {
    if (!status.isSuccess()) throw LocalizedException(UiText.of(res, status.toString()), technical = "HTTP $status")
}

/**
 * A write the server turned down. Frigate usually says why in its own words ([serverMessage]),
 * which are shown as they are; without them, [res] says what failed, with [status] as `%1$s`.
 */
internal fun responseFailure(serverMessage: String?, status: HttpStatusCode, res: StringResource): FrigateResponseException =
    if (serverMessage != null) {
        FrigateResponseException(serverMessage)
    } else {
        FrigateResponseException(UiText.of(res, status.toString()), technical = "HTTP $status")
    }

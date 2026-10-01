package com.meticulouscreations.homesafe.finance.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending work: failures become a [Result], but cancellation stays
 * cancellation. A request cut short because the drawer closed isn't an error to show anyone.
 */
internal suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

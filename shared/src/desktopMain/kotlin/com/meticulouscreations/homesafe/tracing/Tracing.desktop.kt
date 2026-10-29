package com.meticulouscreations.homesafe.tracing

// No Tracing 2.0 on the desktop (androidx.tracing has no JVM driver wired up here): the sections cost nothing.
actual inline fun <T> traceSection(name: String, noinline detail: (() -> String)?, block: () -> T): T = block()

actual suspend fun <T> traceSuspending(name: String, block: suspend () -> T): T = block()

actual fun traceInstant(name: String) = Unit

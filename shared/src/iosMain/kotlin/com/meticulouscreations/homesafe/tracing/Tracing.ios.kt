package com.meticulouscreations.homesafe.tracing

actual inline fun <T> traceSection(name: String, noinline detail: (() -> String)?, block: () -> T): T = block()

actual suspend fun <T> traceSuspending(name: String, block: suspend () -> T): T = block()

actual fun traceInstant(name: String) = Unit

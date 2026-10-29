package com.meticulouscreations.homesafe.tracing

import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer

// The token parents this section on whatever is open on this thread, as Tracer.trace does.
@OptIn(DelicateTracingApi::class)
actual inline fun <T> traceSection(name: String, noinline detail: (() -> String)?, block: () -> T): T {
    val tracer = Tracer.global
    // The disabled driver's tracer says no to every category: no section, no metadata built.
    if (!tracer.isCategoryEnabled(TRACE_CATEGORY)) return block()
    val section = tracer.beginSection(TRACE_CATEGORY, name, tracer.tokenFromThreadContext(), metadataBlock = { if (detail != null) addMetadataEntry("detail", detail()) })
    try {
        return block()
    } finally {
        section.close()
    }
}

actual suspend fun <T> traceSuspending(name: String, block: suspend () -> T): T =
    Tracer.global.traceCoroutine(category = TRACE_CATEGORY, name = name) { block() }

actual fun traceInstant(name: String) {
    val tracer = Tracer.global
    if (tracer.isCategoryEnabled(TRACE_CATEGORY)) tracer.instant(TRACE_CATEGORY, name)
}

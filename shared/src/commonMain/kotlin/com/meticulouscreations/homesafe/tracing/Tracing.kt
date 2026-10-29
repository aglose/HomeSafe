package com.meticulouscreations.homesafe.tracing

/**
 * Category of the app's own trace sections, beside Metro's `dev.zacsweers.metro` ones. A
 * category is how a trace driver filters, and how a Perfetto query finds them (`slice.category`).
 */
const val TRACE_CATEGORY = "com.meticulouscreations.homesafe"

/**
 * Runs [block] inside a trace section called [name]. On Android it is a Tracing 2.0 section,
 * written to the app's in-process Perfetto file when the build traces (`-Phomesafe.tracing=true`)
 * and a no-op otherwise; iOS has no Tracing 2.0, so there it only runs [block].
 *
 * For a stretch of work on one thread whose cost a system trace can't name: a teardown, a
 * copy, a parse. Keep [name] a constant; what varies goes in [detail], which is only built
 * while tracing.
 */
expect inline fun <T> traceSection(name: String, noinline detail: (() -> String)? = null, block: () -> T): T

/** The same around suspending work: the section follows the coroutine across threads and suspensions. */
expect suspend fun <T> traceSuspending(name: String, block: suspend () -> T): T

/**
 * A point in time called [name] on the current thread's track: for something that happens rather
 * than lasts, such as an animation clock moving on (which, counted per second, says which clock
 * keeps a screen drawing at rest).
 */
expect fun traceInstant(name: String)

package com.meticulouscreations.homesafe

import android.app.Application
import androidx.tracing.AbstractTraceDriver
import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer
import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import com.meticulouscreations.homesafe.data.BackgroundGraph
import com.meticulouscreations.homesafe.di.createAppGraph

/**
 * Tells :shared how to build the app graph for code the OS wakes with no screen (receivers,
 * workers, a push). The graph is in :app, which :shared can't see; see BackgroundGraph. Here
 * because the application is created before any of that code can run.
 *
 * Also owns the process's Tracing 2.0 driver ([AbstractTraceDriver.Factory] is how the library's
 * profiler receiver finds it). In a `-Phomesafe.tracing=true` build it writes the app graph's
 * binding sections and the app's own to `perfetto_traces/` in the app's no-backup directory;
 * otherwise nothing makes it and every trace call goes to the library's no-op stub. See
 * docs/perf-tracing.md.
 */
class HomeSafeApplication :
    Application(),
    AbstractTraceDriver.Factory {
    // Made on first use: by onCreate in a tracing build, or by the library's profiler receiver when
    // a profiler asks this process to start tracing.
    private val traceDriver: TraceDriver by lazy { TraceDriver(context = this, sink = TraceSink(this)) }

    @OptIn(DelicateTracingApi::class)
    override fun onCreate() {
        super.onCreate()
        // Before any graph exists: each one takes the global tracer when it is made (createAppGraph).
        // Otherwise the global tracer stays the library's no-op stub.
        if (BuildConfig.IN_PROCESS_TRACING) Tracer.setGlobalTracer(traceDriver.tracer)
        BackgroundGraph.install { context -> createAppGraph(PlatformContext(context)) }
    }

    override fun create(): AbstractTraceDriver = traceDriver

    /**
     * The sink writes whole 16 KB chunks as they fill; the rest waits for a flush. Leaving the app
     * (Home, another app) is the natural end of a traced journey, so that is when it is written.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (BuildConfig.IN_PROCESS_TRACING && level >= TRIM_MEMORY_UI_HIDDEN) traceDriver.flush()
    }
}

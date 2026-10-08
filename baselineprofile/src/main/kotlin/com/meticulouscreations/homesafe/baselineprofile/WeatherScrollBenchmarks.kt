package com.meticulouscreations.homesafe.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while dragging and flinging the weather app's Today and Forecast tabs over the
 * shader-drawn sky. Sign-in and opening the weather app happen in `setupBlock`, so neither is
 * measured. What the sky draws is the weather at the journey's place right now (`weatherPlace`,
 * see Journeys.kt), and an overcast or a wet sky costs far more than a clear one: compare runs
 * of the same place made within the hour, and name the place with the numbers.
 *
 * Three flavours, to tell apart where a slow frame comes from:
 * - `None`: a fresh install before ART has compiled anything, every weather method interpreted or JIT-cold;
 * - `Partial(Require)`: what ships, the committed Baseline Profile compiled at install;
 * - `Full`: everything compiled ahead of time, the most any profile could give.
 *
 * If `Full` is no smoother than `None`, the time is not going to the interpreter. To see where it
 * is going, read the two frame metrics together with the trace:
 * - `frameDurationCpuMs` is the CPU's part of a frame, on the UI thread and then the RenderThread,
 *   up to the frame being handed to the GPU;
 * - `frameOverrunMs` is how far past its deadline the frame was finished (negative: with time to
 *   spare), whatever made it late: composition, the RenderThread's work, or the GPU's.
 *
 * So a late frame whose CPU time was small points at what was drawn (the sky's shaders), and one
 * whose CPU time was large points at the app's own threads, but neither number says which alone:
 * the iteration's Perfetto trace does (`Choreographer#doFrame` on the main thread, `DrawFrames`
 * on the RenderThread, and the frame timeline's jank type). The two trace sections split the UI
 * thread's side into composition and layout.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class WeatherScrollBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollNoCompilation() = scroll(CompilationMode.None())

    @Test
    fun scrollBaselineProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun scrollFullCompilation() = scroll(CompilationMode.Full())

    private fun scroll(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("Recomposer:recompose", TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("AndroidOwner:measureAndLayout", TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = compilationMode,
        iterations = 5,
        setupBlock = {
            // A new process each time: the first scroll of a session is the one the interpreter
            // and the JIT have had no chance at, and five of those are five samples of it.
            killProcess()
            grantPermissions()
            startActivityAndWait()
            signInToHome()
            openWeather()
        },
    ) {
        scrollWeather()
    }
}

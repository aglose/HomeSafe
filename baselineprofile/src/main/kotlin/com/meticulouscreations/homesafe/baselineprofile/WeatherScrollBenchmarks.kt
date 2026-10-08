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
 * If `Full` is no smoother than `None` the time is not going to the interpreter, and the place to
 * look is the GPU: `frameDurationCpuMs` stops when the UI thread hands the frame over, while
 * `frameOverrunMs` runs to when the frame was actually presented, shaders included. A frame whose
 * CPU time is small and whose overrun is positive was late because of what was drawn, not what
 * was composed. The two trace sections split the CPU side into composition and layout.
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

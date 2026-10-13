package com.meticulouscreations.homesafe.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing in the atlas app, in two journeys measured apart because what costs is different
 * in each: [map] is the map under the finger (panned, pinched, glided, the years played), where a
 * frame is thousands of points and three shaders over the whole screen; [browse] is the other
 * tabs and the pages pushed over them, where it is composition and lists.
 *
 * Sign-in and opening the atlas are in `setupBlock`. The history is the signed-in account's own,
 * from the relay, and the first open after an install waits for all of it to arrive (see
 * `openAtlas`); what a map frame costs depends on how much history that is, so compare runs
 * against the same relay. Read the numbers as WeatherScrollBenchmarks says to.
 *
 * Home's live players go on decoding under an app opened from the drawer for a minute and a half
 * (`LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS`), and on an emulator, where video is decoded on
 * the CPU, that is most of the machine: frames are several times slower while it lasts, whatever
 * the atlas is doing. `-e atlasQuietHome true` waits that out before measuring, which is the run
 * to compare atlas changes by; without it the journey is what a person opening the atlas straight
 * from Home gets. `-e atlasIterations 3` runs fewer than the usual five.
 *
 * Each iteration is a new process, and none of the atlas is in the Baseline Profile until the
 * profile is next regenerated with it: the first seconds of a journey are the interpreter's and
 * the JIT's. `-e atlasWarm true` goes through the journey once before measuring it, for the
 * numbers of a session already under way.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class AtlasBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun map() = measure { handleAtlasMap() }

    @Test
    fun browse() = measure { browseAtlas() }

    private fun measure(journey: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("Recomposer:recompose", TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("AndroidOwner:measureAndLayout", TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = InstrumentationRegistry.getArguments().getString("atlasIterations")?.toIntOrNull() ?: 5,
        setupBlock = {
            // A new process each time, as WeatherScrollBenchmarks: the session a person has.
            keepScreenOn()
            killProcess()
            grantPermissions()
            startActivityAndWait()
            signInToHome()
            openAtlas()
            if (InstrumentationRegistry.getArguments().getString("atlasQuietHome") == "true") waitOutHomePlayers()
            if (InstrumentationRegistry.getArguments().getString("atlasWarm") == "true") journey()
        },
    ) {
        journey()
    }
}

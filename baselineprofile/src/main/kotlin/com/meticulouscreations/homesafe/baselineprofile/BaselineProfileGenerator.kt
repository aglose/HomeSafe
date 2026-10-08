package com.meticulouscreations.homesafe.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code paths ART should AOT-compile at install: cold start to the sign-in form,
 * signing in, the Home camera list composing and its first scroll, then the weather app, opened
 * and scrolled through both of its tabs. Startup alone would leave first-scroll and the
 * live-player binding interpreted, which is exactly where it stutters; and an app inside the
 * app that the journey never visits has no rules at all (the weather app had none until it was
 * added here). The weather leg needs the internet: its forecast is Open-Meteo's.
 *
 * Run with `./gradlew :androidApp:generateBaselineProfile`; the output is committed under
 * androidApp/src/release/generated/baselineProfiles/ and packaged as assets/dexopt/baseline.prof.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        grantPermissions()
        startActivityAndWait()
        signInToHome()
        scrollHomeFeed()
        openWeather()
        scrollWeather()
    }
}

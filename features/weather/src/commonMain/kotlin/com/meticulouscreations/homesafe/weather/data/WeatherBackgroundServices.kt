package com.meticulouscreations.homesafe.weather.data

/**
 * What the weather's background job (Android's worker) needs from the app graph, which is in
 * :app where this module can't reach it. AppGraph implements it; the worker gets the graph from
 * BackgroundGraph.
 */
interface WeatherBackgroundServices {
    val weatherAlertCheck: WeatherAlertCheck
}

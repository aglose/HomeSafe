package com.meticulouscreations.homesafe.network

data class FrigateSession(
    val serverUrl: String,
    val cameras: List<FrigateCamera>,
)

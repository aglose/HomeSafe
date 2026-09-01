package com.meticulouscreations.homesafe

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
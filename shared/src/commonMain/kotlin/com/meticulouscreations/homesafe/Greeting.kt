package com.meticulouscreations.homesafe

import dev.zacsweers.metro.Inject

@Inject
class Greeting(private val platform: Platform) {
    fun greet(): String = sayHello(platform.name)
}

package com.meticulouscreations.homesafe.network

/** A page can't see the machine's interfaces, so a browser never claims to know. */
actual fun createTailnetProbe(): TailnetProbe = TailnetProbe { null }

package com.meticulouscreations.homesafe.network

import kotlin.test.Test
import kotlin.test.assertNotNull

/** The desktop probe against this machine's real interfaces: it must answer yes or no, whichever is true here. */
class TailnetProbeDesktopTest {

    @Test
    fun theProbeReadsThisMachinesInterfaces() {
        val onTailnet = createTailnetProbe().isOnTailnet()
        println("TailnetProbe on this machine: $onTailnet")
        assertNotNull(onTailnet, "the interface list couldn't be read")
    }
}

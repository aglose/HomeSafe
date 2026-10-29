package com.meticulouscreations.homesafe.network

/** The computer's own addresses: while Tailscale is connected, its `utun` (or `tailscale0`) interface carries the tailnet one. */
actual fun createTailnetProbe(): TailnetProbe = TailnetProbe { tailnetVerdict(upIpv4Addresses().values.flatten()) }

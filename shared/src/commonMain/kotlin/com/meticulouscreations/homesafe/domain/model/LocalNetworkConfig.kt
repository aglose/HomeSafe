package com.meticulouscreations.homesafe.domain.model

/**
 * The Frigate server's private LAN address. Hardcoded rather than user-entered: this app talks
 * to exactly one household server, so there's nothing for a settings field to add beyond a
 * place to typo it. Used automatically whenever it answers — i.e. this device is on the same
 * Wi-Fi as the server — for direct, no-VPN-hop video; falls back to the Tailscale address
 * entered on the connect screen otherwise. See [ConnectionRepositoryImpl][com.meticulouscreations.homesafe.data.ConnectionRepositoryImpl].
 *
 * Update this if the server's LAN address changes. A DHCP reservation on the router for the
 * server's MAC address is what keeps it from changing on its own.
 */
const val LOCAL_SERVER_URL = "http://192.168.68.64:8971"

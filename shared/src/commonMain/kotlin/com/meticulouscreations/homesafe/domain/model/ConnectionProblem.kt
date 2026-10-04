package com.meticulouscreations.homesafe.domain.model

/** Why the app, though signed in, has no server to talk to. See `ObserveConnectionProblemUseCase`. */
enum class ConnectionProblem {
    /**
     * The server is reached through Tailscale and this device isn't on the tailnet: Tailscale is
     * switched off, signed out or not installed here. The server may well be fine.
     */
    TailscaleOff,

    /** Nothing answers on any of the server's addresses, and Tailscale isn't known to be the reason. */
    ServerUnreachable,
}

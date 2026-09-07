package com.meticulouscreations.homesafe.domain.platform

/** What this install tells the relay about itself when it registers. Cosmetic except for [build]. */
interface DeviceInfo {
    /** "android", "ios", "desktop", "web". */
    val platform: String

    /** A human-readable name for the Settings list, e.g. "Google Pixel 10 Pro XL". */
    val name: String

    /** "release" or "debug". Only release installs count towards away mode — see `docs/away-mode.md`. */
    val build: String
}

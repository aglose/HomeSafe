package com.meticulouscreations.homesafe.domain.platform

/** What this install tells the relay about itself when it registers. Cosmetic except for [build]. */
interface DeviceInfo {
    /** "android", "ios", "desktop", "web". */
    val platform: String

    /** A human-readable name for the Settings list, e.g. "Google Pixel 10 Pro XL". */
    val name: String

    /** "release" or "debug". Only release installs count towards away mode — see `docs/away-mode.md`. */
    val build: String

    /**
     * The installed app's version for people to read, e.g. "1.0.62 (431)": the version name, then
     * the build number in brackets. CI names a release after the pull request it shipped (see
     * `docs/release-to-play.md`), so this says which PR is on the phone.
     */
    val appVersion: String
}

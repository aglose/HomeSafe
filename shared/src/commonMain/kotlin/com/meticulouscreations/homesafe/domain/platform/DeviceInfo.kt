package com.meticulouscreations.homesafe.domain.platform

/** What this install tells the relay about itself when it registers. Cosmetic except for [build]. */
interface DeviceInfo {
    /** "android", "ios", "desktop", "web". */
    val platform: String

    /** A human-readable name for the Settings list, e.g. "Google Pixel 10 Pro XL". */
    val name: String

    /** "release" or "debug". Only a release install can be one of away mode's tracked phones — see `docs/away-mode.md`. */
    val build: String

    /**
     * The phone this install runs on, the same across reinstalls, so the relay can tell a reinstall
     * from a second phone: Android's `ANDROID_ID` (one per app signing key, so the release app's
     * isn't the debug app's), and on iOS a UUID kept in the Keychain, which outlives deleting the
     * app. Not a MAC address, which iOS randomises.
     */
    val hardwareId: String

    /** Running on an emulator or simulator: never a phone anyone carries, whatever the [build]. */
    val emulator: Boolean

    /**
     * The installed app's version for people to read, e.g. "1.0.62 (431)": the version name, then
     * the build number in brackets. CI names a release after the pull request it shipped (see
     * `docs/release-to-play.md`), so this says which PR is on the phone.
     */
    val appVersion: String

    /**
     * Whether this install can be one of the household's two tracked phones: a release build on a
     * real phone. Anything else never watches the geofence or reports presence; the relay ignores
     * it anyway.
     */
    val canBeTracked: Boolean get() = build == "release" && !emulator
}

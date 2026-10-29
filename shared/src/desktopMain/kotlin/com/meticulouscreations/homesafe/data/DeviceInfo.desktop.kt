package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.isMacOs
import com.meticulouscreations.homesafe.runProcess
import java.net.InetAddress
import java.util.UUID

/**
 * The computer as the relay knows it. Never one of away mode's tracked phones: the relay tracks
 * only Android and iOS installs (`TRACKED_PLATFORMS` in relay/relay.py), and a desktop stays home.
 */
private class DesktopDeviceInfo : DeviceInfo {
    override val platform = if (isMacOs) "macos" else "desktop"

    /** The name the person gave the computer: "Sam's MacBook Pro" from System Settings → General → Sharing. */
    override val name: String by lazy {
        (if (isMacOs) command("scutil", "--get", "ComputerName") else null)
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: System.getProperty("os.name")
    }

    /** A packaged app (jpackage names its version) is a release; `./gradlew :desktopApp:run` is a debug build. */
    override val build = if (System.getProperty("jpackage.app-version") != null) "release" else "debug"

    override val emulator = false

    /**
     * The Mac's own hardware UUID, which outlives a reinstall of the app as ANDROID_ID and the
     * Keychain's id do on the phones; elsewhere a UUID made the first time and kept in the preferences.
     */
    override val hardwareId: String by lazy {
        (if (isMacOs) macPlatformUuid() else null) ?: DesktopStorage.preferences("device").let { preferences ->
            preferences.get(HARDWARE_ID, null) ?: UUID.randomUUID().toString().also {
                preferences.put(HARDWARE_ID, it)
                preferences.flush()
            }
        }
    }

    override val appVersion: String = System.getProperty("jpackage.app-version") ?: System.getProperty("homesafe.version") ?: "dev"

    private fun macPlatformUuid(): String? = command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
        ?.lineSequence()
        ?.firstOrNull { "IOPlatformUUID" in it }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }

    private fun command(vararg command: String): String? = runProcess(command.toList(), timeoutSeconds = 5)
        ?.takeIf { it.exitCode == 0 }
        ?.output
        ?.decodeToString()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private companion object {
        const val HARDWARE_ID = "hardware_id"
    }
}

actual fun createDeviceInfo(platformContext: PlatformContext): DeviceInfo = DesktopDeviceInfo()

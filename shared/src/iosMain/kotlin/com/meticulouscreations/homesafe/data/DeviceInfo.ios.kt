package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFAutorelease
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIDevice
import kotlin.experimental.ExperimentalNativeApi

private const val HARDWARE_SERVICE = "com.meticulouscreations.homesafe.device"
private const val HARDWARE_ACCOUNT = "hardware_id"

private class IosDeviceInfo : DeviceInfo {
    override val platform = "ios"

    /** iOS 16+ hides the user's device name behind an entitlement, so this is "Apple iPhone". */
    override val name = "Apple ${UIDevice.currentDevice.model}"

    /**
     * Whether this binary was built for debugging, which follows the Xcode configuration: only a
     * Release build can be away mode's tracked iPhone — see `docs/away-mode.md`.
     */
    @OptIn(ExperimentalNativeApi::class)
    override val build = if (kotlin.native.Platform.isDebugBinary) "debug" else "release"

    /** The simulator says so in its environment; a phone never does. */
    override val emulator = NSProcessInfo.processInfo.environment["SIMULATOR_UDID"] != null

    /**
     * A UUID made the first time and kept in the Keychain, which iOS leaves in place when the app
     * is deleted, so a reinstall is the same phone to the relay. `identifierForVendor` isn't: it
     * changes once the last of our apps is gone. "This device only" keeps it off a backup restored
     * to a new phone, which is a new phone.
     */
    override val hardwareId: String by lazy { readHardwareId() ?: NSUUID().UUIDString.also(::saveHardwareId) }

    /** MARKETING_VERSION and CURRENT_PROJECT_VERSION from `iosApp/Configuration/Config.xcconfig`. */
    override val appVersion = run {
        val info = NSBundle.mainBundle
        "${info.objectForInfoDictionaryKey("CFBundleShortVersionString")} (${info.objectForInfoDictionaryKey("CFBundleVersion")})"
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun readHardwareId(): String? = memScoped {
    val service = CFBridgingRetain(HARDWARE_SERVICE)
    val account = CFBridgingRetain(HARDWARE_ACCOUNT)
    try {
        val result = alloc<CFTypeRefVar>()
        val query = keychainQuery(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to account,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding)?.toString()?.takeIf { it.isNotBlank() }
    } finally {
        CFBridgingRelease(service)
        CFBridgingRelease(account)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun saveHardwareId(id: String) {
    val service = CFBridgingRetain(HARDWARE_SERVICE)
    val account = CFBridgingRetain(HARDWARE_ACCOUNT)
    val data = CFBridgingRetain(NSString.create(string = id).dataUsingEncoding(NSUTF8StringEncoding))
    try {
        // A failure only means the next launch makes another id: the relay then sees a new phone,
        // which a reinstall would have shown it anyway.
        SecItemAdd(
            keychainQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                kSecValueData to data,
            ),
            null,
        )
    } finally {
        CFBridgingRelease(service)
        CFBridgingRelease(account)
        CFBridgingRelease(data)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun keychainQuery(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val dict = CFDictionaryCreateMutable(null, pairs.size.convert(), null, null)
    pairs.forEach { (key, value) -> CFDictionaryAddValue(dict, key, value) }
    CFAutorelease(dict)
    return dict
}

actual fun createDeviceInfo(platformContext: PlatformContext): DeviceInfo = IosDeviceInfo()

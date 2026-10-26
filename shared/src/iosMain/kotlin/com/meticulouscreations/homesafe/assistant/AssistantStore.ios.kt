package com.meticulouscreations.homesafe.assistant

import com.meticulouscreations.homesafe.PlatformContext
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
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
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

private const val SERVICE_NAME = "com.meticulouscreations.homesafe.assistant"
private const val ACCOUNT_NAME = "anthropic_api_key"
private const val DEFAULTS_PREFIX = "assistant_"

/**
 * The API key in the Keychain, readable once the phone has been unlocked after starting and never
 * copied to another device; the preferences in the standard user defaults. The key asks for no
 * Face ID: the assistant is opened far too often for that.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private object KeychainAssistantStore : AssistantStore {

    override fun apiKey(): String? {
        val service = CFBridgingRetain(SERVICE_NAME)
        val account = CFBridgingRetain(ACCOUNT_NAME)
        try {
            val query = buildQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            )
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
                (CFBridgingRelease(result.value) as? NSData)?.toKotlinString()
            }
        } finally {
            CFBridgingRelease(service)
            CFBridgingRelease(account)
        }
    }

    override fun saveApiKey(key: String?) {
        deleteKey()
        if (key == null) return
        val service = CFBridgingRetain(SERVICE_NAME)
        val account = CFBridgingRetain(ACCOUNT_NAME)
        val data = CFBridgingRetain(key.toNSData())
        try {
            SecItemAdd(
                buildQuery(
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

    override fun string(name: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(DEFAULTS_PREFIX + name)

    override fun saveString(name: String, value: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (value == null) defaults.removeObjectForKey(DEFAULTS_PREFIX + name) else defaults.setObject(value, DEFAULTS_PREFIX + name)
    }

    private fun deleteKey() {
        val service = CFBridgingRetain(SERVICE_NAME)
        val account = CFBridgingRetain(ACCOUNT_NAME)
        try {
            SecItemDelete(
                buildQuery(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to service,
                    kSecAttrAccount to account,
                ),
            )
        } finally {
            CFBridgingRelease(service)
            CFBridgingRelease(account)
        }
    }

    private fun buildQuery(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(null, pairs.size.convert(), null, null)
        pairs.forEach { (key, value) -> CFDictionaryAddValue(dict, key, value) }
        CFAutorelease(dict)
        return dict
    }

    private fun String.toNSData(): NSData? = NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding)

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun NSData.toKotlinString(): String? = NSString.create(this, NSUTF8StringEncoding) as String?
}

actual fun createAssistantStore(platformContext: PlatformContext): AssistantStore = KeychainAssistantStore

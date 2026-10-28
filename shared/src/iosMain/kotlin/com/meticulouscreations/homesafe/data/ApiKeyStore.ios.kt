package com.meticulouscreations.homesafe.data

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

/**
 * The API key in the Keychain, readable once the phone has been unlocked after starting and never
 * copied to another device; the preferences in the standard user defaults. The key asks for no
 * Face ID: a key is used far too often for that (every message, every map tile).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class KeychainApiKeyStore(name: ApiKeyStoreName) : ApiKeyStore {
    private val serviceName = "com.meticulouscreations.homesafe.${name.name}"
    private val accountName = name.account
    private val defaultsPrefix = "${name.name}_"

    override fun apiKey(): String? {
        val service = CFBridgingRetain(serviceName)
        val account = CFBridgingRetain(accountName)
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
        val service = CFBridgingRetain(serviceName)
        val account = CFBridgingRetain(accountName)
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

    override fun string(name: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(defaultsPrefix + name)

    override fun saveString(name: String, value: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (value == null) defaults.removeObjectForKey(defaultsPrefix + name) else defaults.setObject(value, defaultsPrefix + name)
    }

    private fun deleteKey() {
        val service = CFBridgingRetain(serviceName)
        val account = CFBridgingRetain(accountName)
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

actual fun createApiKeyStore(platformContext: PlatformContext, name: ApiKeyStoreName): ApiKeyStore = KeychainApiKeyStore(name)

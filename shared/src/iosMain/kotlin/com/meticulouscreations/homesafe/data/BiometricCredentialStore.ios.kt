package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFAutorelease
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecUseOperationPrompt
import platform.Security.kSecValueData
import kotlin.coroutines.resume

private const val SERVICE_NAME = "com.meticulouscreations.homesafe.biometric"
private const val ACCOUNT_NAME = "saved_credentials"

/**
 * iOS implementation of [BiometricCredentialStore].
 *
 * Security model: [save] stores the credentials in the Keychain (`kSecClassGenericPassword`)
 * under a `SecAccessControl` created with the `.biometryCurrentSet` flag
 * ([kSecAccessControlBiometryCurrentSet]). That flag is what ties the item's *readability* to
 * biometrics at the OS level — it also invalidates the item outright if the user's enrolled
 * Face ID/Touch ID set changes, so credentials saved under one biometric enrollment can never
 * be unlocked by a newly-added one. [authenticateAndRetrieve] never separately asks "did the
 * user pass a Face ID check?" and then fetch an unprotected secret; instead it hands a fresh
 * [LAContext] to `SecItemCopyMatching` itself via `kSecUseAuthenticationContext`, so the
 * Security framework performs (and enforces) the biometric check as an intrinsic part of the
 * Keychain read, using the same OS mechanism that unlocks the item's protected data.
 *
 * [confirmBiometric] additionally shows a plain Face ID/Touch ID prompt before *creating* a
 * saved item, purely as an explicit "yes, enable this" confirmation to match the Android
 * save flow's UX (Android's Keystore *requires* biometric auth for every crypto operation,
 * including the initial save) — it is not itself the mechanism that protects the credentials;
 * the `SecAccessControl` on the Keychain item is.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosBiometricCredentialStore : BiometricCredentialStore {

    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean = memScoped {
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, errorVar.ptr)
    }

    // biometryType is only populated once canEvaluatePolicy has run on this context.
    override fun displayName(): String = memScoped {
        val context = LAContext()
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, errorVar.ptr)
        when (context.biometryType) {
            LABiometryTypeFaceID -> "Face ID"
            LABiometryTypeTouchID -> "Touch ID"
            else -> "biometrics"
        }
    }

    override fun hasSavedCredentials(): Boolean {
        val service = CFBridgingRetain(SERVICE_NAME)
        val account = CFBridgingRetain(ACCOUNT_NAME)
        try {
            val query = buildQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                kSecReturnData to kCFBooleanFalse,
            )
            return SecItemCopyMatching(query, null) == errSecSuccess
        } finally {
            CFBridgingRelease(service)
            CFBridgingRelease(account)
        }
    }

    override suspend fun save(credentials: SavedCredentials): Result<Unit> = runCatching {
        val confirmed = confirmBiometric("Confirm your fingerprint or face to save this login")
        check(confirmed) { "Biometric confirmation was cancelled" }

        val accessControl = createBiometricAccessControl()
        val service = CFBridgingRetain(SERVICE_NAME)
        val account = CFBridgingRetain(ACCOUNT_NAME)
        val data = CFBridgingRetain(
            json.encodeToString(SavedCredentials.serializer(), credentials).toNSData(),
        )
        try {
            // Replace any previously saved item so a stale one can't linger under a different key.
            SecItemDelete(
                buildQuery(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to service,
                    kSecAttrAccount to account,
                ),
            )

            val addQuery = buildQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                kSecAttrAccessControl to accessControl,
                kSecValueData to data,
            )
            val status = SecItemAdd(addQuery, null)
            check(status == errSecSuccess) { "Couldn't save credentials to the Keychain (status $status)" }
        } finally {
            CFBridgingRelease(service)
            CFBridgingRelease(account)
            CFBridgingRelease(data)
        }
    }

    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> = runCatching {
        // SecItemCopyMatching blocks the calling thread while the system Face ID/Touch ID
        // sheet is up, so run it off the main thread.
        withContext(Dispatchers.Default) {
            val laContext = LAContext()
            val service = CFBridgingRetain(SERVICE_NAME)
            val account = CFBridgingRetain(ACCOUNT_NAME)
            val authContext = CFBridgingRetain(laContext)
            val prompt = CFBridgingRetain("Sign in with Face ID or Touch ID")
            try {
                val query = buildQuery(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to service,
                    kSecAttrAccount to account,
                    kSecReturnData to kCFBooleanTrue,
                    kSecMatchLimit to kSecMatchLimitOne,
                    kSecUseAuthenticationContext to authContext,
                    kSecUseOperationPrompt to prompt,
                )
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    val status = SecItemCopyMatching(query, result.ptr)
                    check(status == errSecSuccess) {
                        "Biometric sign-in failed or was cancelled (status $status)"
                    }
                    val savedData = CFBridgingRelease(result.value) as? NSData
                        ?: error("No saved biometric credentials")
                    json.decodeFromString(SavedCredentials.serializer(), savedData.toKotlinString())
                }
            } finally {
                CFBridgingRelease(service)
                CFBridgingRelease(account)
                CFBridgingRelease(authContext)
                CFBridgingRelease(prompt)
            }
        }
    }

    override fun clear() {
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

    /** Plain Face ID/Touch ID confirmation, shown before enrolling a new saved credential (see class doc). */
    private suspend fun confirmBiometric(reason: String): Boolean = suspendCancellableCoroutine { continuation ->
        LAContext().evaluatePolicy(
            policy = LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            localizedReason = reason,
        ) { success, _ ->
            if (continuation.isActive) continuation.resume(success)
        }
    }

    private fun createBiometricAccessControl(): CFTypeRef? = memScoped {
        val errorVar = alloc<CFErrorRefVar>()
        val control = SecAccessControlCreateWithFlags(
            allocator = null,
            protection = kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            flags = kSecAccessControlBiometryCurrentSet,
            error = errorVar.ptr,
        )
        requireNotNull(control) { "Couldn't create Keychain access control for biometric storage" }
        CFAutorelease(control)
        control
    }

    private fun buildQuery(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(null, pairs.size.convert(), null, null)
        pairs.forEach { (key, value) -> CFDictionaryAddValue(dict, key, value) }
        CFAutorelease(dict)
        return dict
    }

    private fun String.toNSData(): NSData? = NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding)

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun NSData.toKotlinString(): String =
        requireNotNull(NSString.create(this, NSUTF8StringEncoding) as String?)
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    IosBiometricCredentialStore()

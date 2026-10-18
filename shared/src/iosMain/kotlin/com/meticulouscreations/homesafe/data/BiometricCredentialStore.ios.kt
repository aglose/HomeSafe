package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_confirm_save_reason
import homesafe.shared.generated.resources.biometric_confirmation_cancelled
import homesafe.shared.generated.resources.biometric_keychain_save_failed
import homesafe.shared.generated.resources.biometric_no_saved_login
import homesafe.shared.generated.resources.biometric_sign_in_failed
import homesafe.shared.generated.resources.biometric_sign_in_reason
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
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
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.LocalAuthentication.LAContext
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlUserPresence
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

// Saved under .userPresence, which takes the passcode too (see the class doc).
private const val ACCOUNT_NAME = "saved_credentials_user_presence"

// Saved before the passcode was allowed, under .biometryCurrentSet: Face ID or Touch ID alone.
private const val LEGACY_ACCOUNT_NAME = "saved_credentials"

/**
 * iOS implementation of [BiometricCredentialStore].
 *
 * Security model: [save] stores the credentials in the Keychain (`kSecClassGenericPassword`)
 * under a `SecAccessControl` created with the `.userPresence` flag
 * ([kSecAccessControlUserPresence]): Face ID or Touch ID, or the phone's passcode when those fail
 * or aren't set up. That flag is what ties the item's *readability* to the owner being there, at
 * the OS level. [authenticateAndRetrieve] never separately asks "did the user pass a check?" and
 * then fetches an unprotected secret; instead it hands a fresh [LAContext] to `SecItemCopyMatching`
 * itself via `kSecUseAuthenticationContext`, so the Security framework performs (and enforces) the
 * check as an intrinsic part of the Keychain read, using the same OS mechanism that unlocks the
 * item's protected data.
 *
 * Logins saved before the passcode was allowed sit under [LEGACY_ACCOUNT_NAME] with
 * `.biometryCurrentSet`, which has no passcode fallback. Once such an item has been read, its data
 * is added under [ACCOUNT_NAME] and only then is the old item deleted, so a failed add leaves it in
 * place to try again on the next sign-in.
 *
 * [confirmOwner] additionally shows a plain Face ID/Touch ID (or passcode) prompt before *creating*
 * a saved item, purely as an explicit "yes, enable this" confirmation to match the Android save
 * flow's UX (Android's Keystore *requires* biometric auth for every crypto operation, including the
 * initial save) — it is not itself the mechanism that protects the credentials; the
 * `SecAccessControl` on the Keychain item is.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosBiometricCredentialStore : BiometricCredentialStore {

    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean = canAuthenticateDeviceOwner()

    override fun displayName(): StringResource = deviceOwnerMethodName()

    override fun hasSavedCredentials(): Boolean = itemExists(ACCOUNT_NAME) || itemExists(LEGACY_ACCOUNT_NAME)

    /**
     * Whether the item under [accountName] exists, asked without a Face ID prompt. A context that
     * may not interact makes the Keychain answer at once: a phone's Secure Enclave refuses the
     * protected item with `errSecInteractionNotAllowed`, which still means it is there. Only "not
     * found" means no saved login. (The simulator has no Secure Enclave and answers success, which
     * is why a check for success alone worked there and found nothing on a phone.)
     */
    private fun itemExists(accountName: String): Boolean {
        val service = CFBridgingRetain(SERVICE_NAME)
        val account = CFBridgingRetain(accountName)
        val authContext = CFBridgingRetain(LAContext().apply { interactionNotAllowed = true })
        try {
            val query = buildQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                kSecReturnData to kCFBooleanFalse,
                kSecUseAuthenticationContext to authContext,
            )
            val status = SecItemCopyMatching(query, null)
            return status == errSecSuccess || status == errSecInteractionNotAllowed
        } finally {
            CFBridgingRelease(service)
            CFBridgingRelease(account)
            CFBridgingRelease(authContext)
        }
    }

    override suspend fun save(credentials: SavedCredentials): Result<Unit> = runCatching {
        val confirmed = confirmOwner(getString(Res.string.biometric_confirm_save_reason))
        if (!confirmed) throw LocalizedException(UiText.of(Res.string.biometric_confirmation_cancelled), technical = "Biometric confirmation was cancelled")
        val data = json.encodeToString(SavedCredentials.serializer(), credentials).toNSData()
        // Replace any previously saved item so a stale one can't linger under a different key.
        deleteItem(ACCOUNT_NAME)
        addItem(data)
        deleteItem(LEGACY_ACCOUNT_NAME)
    }

    /** Puts [value] in the Keychain under [ACCOUNT_NAME], behind the owner check. Fails if an item is already there. */
    private fun addItem(value: NSData?) {
        val accessControl = createOwnerAccessControl()
        val service = CFBridgingRetain(SERVICE_NAME)
        val account = CFBridgingRetain(ACCOUNT_NAME)
        val data = CFBridgingRetain(value)
        try {
            val addQuery = buildQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                kSecAttrAccessControl to accessControl,
                kSecValueData to data,
            )
            val status = SecItemAdd(addQuery, null)
            if (status != errSecSuccess) {
                throw LocalizedException(
                    UiText.of(Res.string.biometric_keychain_save_failed, status.toString()),
                    technical = "Couldn't save credentials to the Keychain (status $status)",
                )
            }
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
            val legacy = !itemExists(ACCOUNT_NAME) && itemExists(LEGACY_ACCOUNT_NAME)
            val laContext = LAContext()
            val service = CFBridgingRetain(SERVICE_NAME)
            val account = CFBridgingRetain(if (legacy) LEGACY_ACCOUNT_NAME else ACCOUNT_NAME)
            val authContext = CFBridgingRetain(laContext)
            val prompt = CFBridgingRetain(getString(Res.string.biometric_sign_in_reason))
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
                    if (status != errSecSuccess) {
                        throw LocalizedException(
                            UiText.of(Res.string.biometric_sign_in_failed),
                            technical = "Biometric sign-in failed or was cancelled (status $status)",
                        )
                    }
                    val savedData = CFBridgingRelease(result.value) as? NSData
                        ?: throw LocalizedException(UiText.of(Res.string.biometric_no_saved_login), technical = "No saved biometric credentials")
                    val credentials = json.decodeFromString(SavedCredentials.serializer(), savedData.toKotlinString())
                    // Move a legacy item under the access control that also takes the passcode.
                    // Adding an item asks for nothing, so this shows no second prompt; the old one
                    // goes only once the new one is in, so a failed add is tried again next time.
                    if (legacy && runCatching { addItem(savedData) }.isSuccess) deleteItem(LEGACY_ACCOUNT_NAME)
                    credentials
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
        deleteItem(ACCOUNT_NAME)
        deleteItem(LEGACY_ACCOUNT_NAME)
    }

    private fun deleteItem(accountName: String) {
        val service = CFBridgingRetain(SERVICE_NAME)
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

    /** Plain Face ID/Touch ID (or passcode) confirmation, shown before enrolling a new saved credential (see class doc). */
    private suspend fun confirmOwner(reason: String): Boolean = suspendCancellableCoroutine { continuation ->
        // Held until the reply: a context freed while its prompt is up cancels the prompt.
        val context = LAContext()
        context.evaluatePolicy(
            policy = DeviceOwnerPolicy,
            localizedReason = reason,
        ) { success, _ ->
            context.invalidate()
            if (continuation.isActive) continuation.resume(success)
        }
    }

    private fun createOwnerAccessControl(): CFTypeRef? = memScoped {
        val errorVar = alloc<CFErrorRefVar>()
        val control = SecAccessControlCreateWithFlags(
            allocator = null,
            protection = kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            flags = kSecAccessControlUserPresence,
            error = errorVar.ptr,
        )
        requireNotNull(control) { "Couldn't create Keychain access control for saved credentials" }
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

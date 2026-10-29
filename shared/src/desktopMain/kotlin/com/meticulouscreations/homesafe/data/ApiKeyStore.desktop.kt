package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext

/**
 * The plain preferences in the store's own [java.util.prefs.Preferences] node. There is no secret
 * slot: it only ever held a key an older phone build kept on the device, for the vault to take
 * over, and no desktop build has kept one.
 */
private class PreferencesApiKeyStore(name: ApiKeyStoreName) : ApiKeyStore {
    private val preferences = DesktopStorage.preferences(name.name)

    override fun apiKey(): String? = null

    override fun saveApiKey(key: String?) = Unit

    override fun string(name: String): String? = preferences.get(name, null)

    override fun saveString(name: String, value: String?) {
        if (value == null) preferences.remove(name) else preferences.put(name, value)
        preferences.flush()
    }
}

actual fun createApiKeyStore(platformContext: PlatformContext, name: ApiKeyStoreName): ApiKeyStore = PreferencesApiKeyStore(name)

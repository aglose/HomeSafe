package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext

/**
 * An API key the person has pasted in, held the way the platform keeps secrets (the Android
 * Keystore, the iOS Keychain), and a few plain preferences beside it. Kept outside the database:
 * none of it is worth a migration, and a cleared app only means pasting the key again. Each part
 * of the app that takes a key has a store of its own ([ApiKeyStoreName]): the assistant's
 * Anthropic key, the maps' Google key.
 */
interface ApiKeyStore {
    fun apiKey(): String?

    fun saveApiKey(key: String?)

    fun string(name: String): String?

    fun saveString(name: String, value: String?)
}

/**
 * Where a store is kept on the device: [name] names its preferences and the key that seals its
 * secret (and, on iOS, its Keychain service), [account] its Keychain entry. Changing either
 * loses what was saved under the old one.
 */
class ApiKeyStoreName(val name: String, val account: String) {
    companion object {
        val ASSISTANT = ApiKeyStoreName("assistant", "anthropic_api_key")
        val MAPS = ApiKeyStoreName("maps", "google_maps_api_key")
    }
}

expect fun createApiKeyStore(platformContext: PlatformContext, name: ApiKeyStoreName): ApiKeyStore

/** For tests and previews: forgets everything with the process. */
class InMemoryApiKeyStore(private var key: String? = null) : ApiKeyStore {
    private val values = HashMap<String, String>()

    override fun apiKey(): String? = key

    override fun saveApiKey(key: String?) {
        this.key = key
    }

    override fun string(name: String): String? = values[name]

    override fun saveString(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
    }
}

/** The qualifiers the graph (`AppGraph`) provides each store under. */
const val ASSISTANT_KEYS = "assistantKeys"
const val MAPS_KEYS = "mapsKeys"

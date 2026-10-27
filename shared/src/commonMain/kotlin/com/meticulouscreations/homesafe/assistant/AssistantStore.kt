package com.meticulouscreations.homesafe.assistant

import com.meticulouscreations.homesafe.PlatformContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * What the assistant keeps on this install: the person's Anthropic API key, held the way the
 * platform keeps secrets (the Android Keystore, the iOS Keychain), and a few plain preferences
 * beside it. Kept outside the database: none of it is worth a migration, and a cleared app only
 * means pasting the key again.
 */
interface AssistantStore {
    fun apiKey(): String?

    fun saveApiKey(key: String?)

    fun string(name: String): String?

    fun saveString(name: String, value: String?)
}

expect fun createAssistantStore(platformContext: PlatformContext): AssistantStore

/** For tests and previews: forgets everything with the process. */
class InMemoryAssistantStore(private var key: String? = null) : AssistantStore {
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

@ContributesTo(AppScope::class)
interface AssistantStoreProviders {
    @SingleIn(AppScope::class)
    @Provides
    fun provideAssistantStore(platformContext: PlatformContext): AssistantStore = createAssistantStore(platformContext)
}

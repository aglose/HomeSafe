package com.meticulouscreations.homesafe.vault

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [VaultCrypto]: what one login seals only that login opens, and a grant only the member it was made for. */
class VaultCryptoTest {

    @Test
    fun aMemberKeyPairOpensOnlyWithItsOwnPassword() = runTest {
        val key = VaultCrypto.loginKey("andrew", "hunter2", ITERATIONS)
        assertContentEquals(key, VaultCrypto.loginKey("andrew", "hunter2", ITERATIONS), "the same login, the same key, on any phone")
        val member = VaultCrypto.newMember(key)
        assertNotNull(VaultCrypto.openMember(key, member.public, member.sealedPrivate))
        assertNull(VaultCrypto.openMember(VaultCrypto.loginKey("andrew", "new password", ITERATIONS), member.public, member.sealedPrivate), "a changed password")
        assertNull(VaultCrypto.openMember(VaultCrypto.loginKey("sarah", "hunter2", ITERATIONS), member.public, member.sealedPrivate), "another user with the same password")
    }

    @Test
    fun aGrantOpensForItsMemberAndNobodyElse() = runTest {
        val andrew = VaultCrypto.newMember(VaultCrypto.loginKey("andrew", "a", ITERATIONS))
        val sarah = VaultCrypto.newMember(VaultCrypto.loginKey("sarah", "s", ITERATIONS))
        val household = VaultCrypto.newHouseholdKey()
        assertEquals(32, household.size)
        val grant = VaultCrypto.grant(household, sarah.public)
        assertContentEquals(household, VaultCrypto.openGrant(sarah, grant))
        assertNull(VaultCrypto.openGrant(andrew, grant))
    }

    @Test
    fun aSecretOpensUnderItsOwnNameOnly() = runTest {
        val household = VaultCrypto.newHouseholdKey()
        val box = VaultCrypto.sealSecret(household, "google_maps", "AIza-key")
        assertEquals("AIza-key", VaultCrypto.openSecret(household, "google_maps", box))
        assertNull(VaultCrypto.openSecret(household, "anthropic", box), "moved under another name")
        assertNull(VaultCrypto.openSecret(VaultCrypto.newHouseholdKey(), "google_maps", box), "another household")
        assertNull(VaultCrypto.openSecret(household, "google_maps", "not base64!"))
    }

    private companion object {
        /** The real count takes about a second a time; the derivation is the same. */
        const val ITERATIONS = 1_000
    }
}

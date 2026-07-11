package com.ScienceFiction.TokenWatchAndroid.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val agentId = UUID.randomUUID()
    private lateinit var vault: AndroidCredentialVault

    @Before
    fun setUp() {
        vault = AndroidCredentialVault(context)
        vault.delete(agentId)
    }

    @After
    fun tearDown() {
        vault.delete(agentId)
    }

    @Test
    fun roundTripIsEncryptedAndDeleteIsAtomic() {
        val tokens = OAuthTokens(
            accessToken = "secret-access-token",
            refreshToken = "secret-refresh-token",
            expiresAt = Instant.parse("2026-07-12T00:00:00Z"),
            scopes = listOf("openid", "profile"),
            accountEmail = "test@example.com",
            plan = "pro",
            idToken = "header.payload.signature",
            accountId = "account-1",
        )
        vault.save(agentId, tokens)

        assertEquals(tokens, vault.load(agentId))
        val raw = context.getSharedPreferences(
            AndroidCredentialVault.STORAGE_NAME,
            Context.MODE_PRIVATE,
        ).getString("tokens.$agentId", null).orEmpty()
        assertFalse(raw.contains(tokens.accessToken))
        assertFalse(raw.contains(tokens.refreshToken!!))

        vault.delete(agentId)
        assertNull(vault.load(agentId))
    }
}

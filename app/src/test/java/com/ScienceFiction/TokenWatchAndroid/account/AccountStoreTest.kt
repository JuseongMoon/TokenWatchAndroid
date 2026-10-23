package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarPreset
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfileCache
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterApiClient
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DATE = "Sun, 06 Nov 1994 08:49:37 GMT"

class AccountStoreTest {
    /** One ordered log of auth calls and network requests, to pin the deletion sequence. */
    private val log = mutableListOf<String>()
    private val events = mutableListOf<AnalyticsEvent>()
    private val user = AccountUser("uid1", listOf(AccountMethod.APPLE))

    private inner class FakeAuth : AccountAuth {
        override var isAvailable = true
        override var isGoogleAvailable = true
        override var currentUser: AccountUser? = null
        var signInResult: AccountAuthResult = AccountAuthResult.Success(user, isNew = true)
        var linkResult: AccountAuthResult = AccountAuthResult.Success(user.copy(methods = AccountMethod.entries), false)
        var reauthResult = AccountReauthResult.OK
        var pending: AccountAuthResult? = null
        var listener: ((AccountUser?) -> Unit)? = null
        var operatorClaim = false
        var sessionStatus = AccountSessionStatus.VALID
        val operatorRefreshes = mutableListOf<Boolean>()

        override fun startListening(onChange: (AccountUser?) -> Unit) {
            listener = onChange
        }

        // Not logged: the call sequences pinned below are about the network and the deletion steps.
        override suspend fun reloadUser(): AccountSessionStatus =
            if (currentUser == null) AccountSessionStatus.REVOKED else sessionStatus

        override suspend fun pendingSignIn(): AccountAuthResult? = pending?.also { result ->
            log += "pending"
            (result as? AccountAuthResult.Success)?.let { currentUser = it.user }
        }

        override suspend fun signIn(method: AccountMethod): AccountAuthResult {
            log += "signIn:${method.wireId}"
            (signInResult as? AccountAuthResult.Success)?.let { currentUser = it.user }
            return signInResult
        }

        override suspend fun link(method: AccountMethod): AccountAuthResult {
            log += "link:${method.wireId}"
            return linkResult
        }

        override suspend fun reauthenticateForDeletion(): AccountReauthResult {
            log += "reauth"
            return reauthResult
        }

        override suspend fun signOut() {
            log += "signOut"
            currentUser = null
        }

        override suspend fun idToken(forceRefresh: Boolean): String? {
            // Forced refreshes are logged: deletion needs a token carrying the reauthentication.
            if (forceRefresh) log += "token:refresh"
            return currentUser?.let { "token" }
        }

        override suspend fun isOperator(forceRefresh: Boolean): Boolean {
            operatorRefreshes += forceRefresh
            return currentUser != null && operatorClaim
        }
    }

    private class FakeCache(var value: PlayerProfileCache? = null) : PlayerProfileCacheStore {
        override suspend fun load(): PlayerProfileCache? = value
        override suspend fun save(cache: PlayerProfileCache) {
            value = cache
        }
        override suspend fun clear() {
            value = null
        }
    }

    private inner class LoggingTransport(vararg responses: NetworkResponse) : NetworkTransport {
        val inner = ScriptedTransport(*responses)
        val requests get() = inner.requests

        override suspend fun execute(request: Request): NetworkResponse {
            log += "net:${request.method} ${request.url.pathSegments.last()}"
            return inner.execute(request)
        }
    }

    /** The callable server: queued answers per function, every call's `data` recorded and logged. */
    private inner class FakeCallables : NetworkTransport {
        val responses = mutableMapOf<String, ArrayDeque<NetworkResponse>>()
        val calls = mutableListOf<Pair<String, Map<*, *>>>()
        var failTransport = false

        fun respond(name: String, status: Int, body: String) {
            responses.getOrPut(name) { ArrayDeque() } += response(status, body)
        }

        override suspend fun execute(request: Request): NetworkResponse {
            val name = request.url.pathSegments.last()
            log += "call:$name"
            if (failTransport) throw java.io.IOException("offline")
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = Moshi.Builder().build().adapter(Any::class.java).fromJson(body) as Map<*, *>
            calls += name to (json["data"] as Map<*, *>)
            return responses[name]?.removeFirstOrNull() ?: response(500)
        }

        fun data(name: String) = calls.first { it.first == name }.second
    }

    private val auth = FakeAuth()
    private val cache = FakeCache()
    private val callables = FakeCallables()
    private var demo = false

    private fun store(scope: CoroutineScope, transport: NetworkTransport, profiles: Boolean = true): AccountStore =
        AccountStore(
            auth = auth,
            profiles = if (profiles) PlayerProfileClient("p", transport, auth::idToken, ServerClock { 1_000_000L }) else null,
            server = if (profiles) MonsterApiClient("p", null, auth::idToken, callables, "1.0") else null,
            cache = cache,
            scope = scope,
            isDemo = { demo },
            log = { events += it },
        )

    /** A `setProfile` answer (the server's public profile, plain JSON). */
    private fun profileResult(nickname: String = "Tok", tag: String = "8571", terms: Int = 2) =
        """{"result":{"profile":{"nickname":"$nickname","nicknameLower":"${NicknamePolicy.lower(nickname)}","tag":"$tag",""" +
            """"nicknameChangedAtMs":5,"avatar":{"kind":"preset","id":"cat"},"termsAcceptedVersion":$terms,""" +
            """"createdAtMs":5,"updatedAtMs":5}}}"""

    private fun serverError(code: String) = """{"error":{"status":"INVALID_ARGUMENT","details":{"code":"$code"}}}"""

    /** Lets work launched on the test scope run until [done] holds (bounded, so a bug cannot hang). */
    private suspend fun awaitUntil(done: () -> Boolean) {
        repeat(1_000) {
            if (done()) return
            yield()
        }
        throw AssertionError("condition never held")
    }

    @Test fun withoutFirebaseTheFeatureIsUnavailable() = runBlocking {
        auth.isAvailable = false
        assertEquals(AccountState.Unavailable, store(this, LoggingTransport()).state.value)
        auth.isAvailable = true
        assertEquals(AccountState.Unavailable, store(this, LoggingTransport(), profiles = false).state.value)
    }

    @Test fun startDrawsTheCacheThenRereadsTheServer() = runBlocking {
        auth.currentUser = user
        cache.value = PlayerProfileCache("uid1", "Cached", "0001", "dragon", 5)
        val transport = LoggingTransport(response(200, PROFILE_BODY))
        val store = store(this, transport)
        store.start()
        // The cached profile is replaced by the server copy once the re-read lands.
        awaitUntil { (store.state.value as? AccountState.SignedIn)?.profile?.nickname == "Tok" }
        assertEquals(1, transport.requests.size)
        val state = store.state.value as AccountState.SignedIn
        assertEquals("Tok", state.profile.nickname)
        assertEquals("Tok", cache.value?.nickname)
        assertEquals("0042", cache.value?.tag)
        assertEquals(listOf(AccountMethod.APPLE), store.methods.value)
    }

    @Test fun aCacheFromAnotherAccountIsNotShown() = runBlocking {
        auth.currentUser = user
        cache.value = PlayerProfileCache("someone-else", "Other", "0001", "cat", 5)
        val store = store(this, LoggingTransport(response(404)))
        store.start()
        awaitUntil { store.state.value == AccountState.NeedsProfile }
        assertNull(cache.value)
    }

    @Test fun signedOutStartClearsAStaleCache() = runBlocking {
        cache.value = PlayerProfileCache("uid1", "Tok", "0042", "cat", 5)
        val store = store(this, LoggingTransport())
        store.start()
        awaitUntil { auth.listener != null }
        assertNull(cache.value)
        assertEquals(AccountState.SignedOut, store.state.value)
    }

    @Test fun anAccountDeletedElsewhereSignsOutOnTheNextRead() = runBlocking {
        auth.signInResult = AccountAuthResult.Success(user, isNew = false)
        val transport = LoggingTransport(response(200, PROFILE_BODY))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        assertTrue(store.state.value is AccountState.SignedIn)
        auth.sessionStatus = AccountSessionStatus.REVOKED
        store.refreshProfile()
        assertEquals(AccountState.SignedOut, store.state.value)
        assertNull(cache.value)
        assertTrue("signOut" in log)
        assertEquals(1, transport.requests.size) // no Firestore read for a deleted account
        assertFalse(events.any { it is AnalyticsEvent.AccountLogout }) // not a sign-out the user chose
    }

    @Test fun anUncheckableSessionKeepsTheProfileOnScreen() = runBlocking {
        auth.signInResult = AccountAuthResult.Success(user, isNew = false)
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY))) // the second read gets a 500
        store.signIn(AccountMethod.APPLE)
        auth.sessionStatus = AccountSessionStatus.UNKNOWN
        store.refreshProfile()
        assertEquals("Tok", (store.state.value as AccountState.SignedIn).profile.nickname)
        assertFalse("signOut" in log)
    }

    @Test fun revalidateRightAfterAReadDoesNotCallTheServer() = runBlocking {
        auth.signInResult = AccountAuthResult.Success(user, isNew = false)
        val transport = LoggingTransport(response(200, PROFILE_BODY))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        auth.sessionStatus = AccountSessionStatus.REVOKED
        store.revalidate()
        assertTrue(store.state.value is AccountState.SignedIn)
        assertEquals(1, transport.requests.size)
    }

    @Test fun firstSignInLeadsToProfileSetup() = runBlocking {
        val transport = LoggingTransport(response(404))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        assertEquals(AccountState.NeedsProfile, store.state.value)
        val login = events.single() as AnalyticsEvent.AccountLogin
        assertEquals(mapOf("method" to "apple", "is_new" to "1"), login.parameters)
    }

    @Test fun cancellingSignInReturnsToSignedOutWithoutNetwork() = runBlocking {
        auth.signInResult = AccountAuthResult.Cancelled
        val transport = LoggingTransport()
        val store = store(this, transport)
        store.signIn(AccountMethod.GOOGLE)
        assertEquals(AccountState.SignedOut, store.state.value)
        assertTrue(transport.requests.isEmpty())
        assertEquals(mapOf("method" to "google", "stage" to "cancel"), events.single().parameters)
        assertNull(store.notice.value)
    }

    @Test fun signInConflictsAndFailuresRaiseNotices() = runBlocking {
        val store = store(this, LoggingTransport())
        auth.signInResult = AccountAuthResult.ExistsWithDifferentMethod
        store.signIn(AccountMethod.GOOGLE)
        assertEquals(AccountNotice.ExistsWithDifferentMethod, store.notice.value)
        assertEquals("conflict", events.last().parameters["stage"])

        auth.signInResult = AccountAuthResult.Unavailable
        store.signIn(AccountMethod.GOOGLE)
        assertEquals(AccountNotice.GoogleUnavailable, store.notice.value)
        assertEquals("provider", events.last().parameters["stage"])

        auth.signInResult = AccountAuthResult.Failed(AccountFailStage.FIREBASE)
        store.signIn(AccountMethod.APPLE)
        assertEquals(AccountNotice.LoginFailed, store.notice.value)
        assertEquals("firebase", events.last().parameters["stage"])
        assertEquals(AccountState.SignedOut, store.state.value)
    }

    @Test fun aPendingAppleSignInIsCollectedAtStart() = runBlocking {
        auth.pending = AccountAuthResult.Success(user, isNew = false)
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.start()
        awaitUntil { store.state.value is AccountState.SignedIn }
        assertEquals("pending", log.first())
        assertEquals(mapOf("method" to "apple", "is_new" to "0"), events.single().parameters)
    }

    @Test fun profileCreationValidatesLocallyBeforeAnyRequest() = runBlocking {
        val transport = LoggingTransport(response(404))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        transport.requests.clear()
        assertEquals(SaveOutcome.Invalid(NicknameError.TOO_SHORT), store.createProfile("a", "cat", true))
        assertEquals(SaveOutcome.Invalid(NicknameError.BANNED_WORD), store.createProfile("admin1", "cat", true))
        assertEquals(SaveOutcome.Invalid(NicknameError.TERMS_REQUIRED), store.createProfile("Tok", "cat", false))
        assertTrue(transport.requests.isEmpty())
        assertTrue(callables.calls.isEmpty())
    }

    @Test fun profileCreationCallsTheServerWithTheCurrentTerms() = runBlocking {
        val transport = LoggingTransport(response(404, date = DATE))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        callables.respond("setProfile", 200, profileResult(nickname = "Tok_1"))
        assertEquals(SaveOutcome.Saved, store.createProfile("  Tok_1 ", "dragon", termsAccepted = true))

        // The normalized nickname goes out; the tag is the server's to draw.
        assertEquals(
            mapOf("op" to "create", "nickname" to "Tok_1", "avatarId" to "dragon", "termsAcceptedVersion" to 2.0),
            callables.data("setProfile"),
        )
        val profile = store.profile!!
        assertEquals("Tok_1 #8571", profile.nametag)
        assertEquals(2, profile.termsAcceptedVersion)
        assertEquals("8571", cache.value?.tag)
        assertEquals(2, cache.value?.termsAcceptedVersion)
        // Nothing is written over Firestore REST.
        assertEquals(1, transport.requests.size)
    }

    @Test fun aServerNicknameErrorShowsAsAnInputError() = runBlocking {
        val store = store(this, LoggingTransport(response(404)))
        store.signIn(AccountMethod.APPLE)
        callables.respond("setProfile", 400, serverError("banned_word"))
        assertEquals(SaveOutcome.Invalid(NicknameError.BANNED_WORD), store.createProfile("Tok", "cat", true))
        assertNull(store.notice.value)
        assertEquals(AccountState.NeedsProfile, store.state.value)
    }

    @Test fun aLostCreateAnswerRecoversByReading() = runBlocking {
        val store = store(this, LoggingTransport(response(404), response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        callables.failTransport = true
        assertEquals(SaveOutcome.Saved, store.createProfile("Tok", "cat", true))
        assertEquals("0042", store.profile?.tag)
        assertNull(store.notice.value)
    }

    @Test fun aFailedCreateWithNoProfileOnTheServerIsAFailedSave() = runBlocking {
        val store = store(this, LoggingTransport(response(404), response(404)))
        store.signIn(AccountMethod.APPLE)
        callables.respond("setProfile", 500, "{}")
        assertEquals(SaveOutcome.Failed, store.createProfile("Tok", "cat", true))
        assertEquals(AccountNotice.SaveFailed, store.notice.value)
        assertEquals(AccountState.NeedsProfile, store.state.value)
    }

    @Test fun aNicknameChangeTakesTheServersNewTag() = runBlocking {
        // The Date header puts the server clock decades after the last change, so the cooldown is over.
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY, date = DATE)))
        store.signIn(AccountMethod.APPLE)
        callables.respond("setProfile", 200, profileResult(nickname = "TOK", tag = "1234", terms = 1))
        assertEquals(SaveOutcome.Saved, store.changeNickname("TOK"))
        assertEquals(mapOf("op" to "nickname", "nickname" to "TOK"), callables.data("setProfile"))
        assertEquals("TOK #1234", store.profile?.nametag)
        assertEquals("1234", cache.value?.tag)
        assertEquals(mapOf("field" to "nickname"), events.last().parameters)
    }

    @Test fun nicknameChangesRespectTheThirtyDayCooldown() = runBlocking {
        // The profile was changed at 1000 ms; the clock reads 1_000_000 ms, well inside 30 days.
        val transport = LoggingTransport(response(200, PROFILE_BODY))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        assertTrue(store.nicknameCooldownRemainingMs() > 0)
        assertEquals(SaveOutcome.Invalid(NicknameError.COOLDOWN), store.changeNickname("Other"))
        // Same nickname after normalization is saved without a call (the server refuses it).
        assertEquals(SaveOutcome.Saved, store.changeNickname(" Tok "))
        assertTrue(callables.calls.isEmpty())
    }

    @Test fun theServersCooldownIsAnInputErrorToo() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY, date = DATE)))
        store.signIn(AccountMethod.APPLE)
        callables.respond("setProfile", 400, serverError("cooldown"))
        assertEquals(SaveOutcome.Invalid(NicknameError.COOLDOWN), store.changeNickname("Other"))
        assertNull(store.notice.value)
        assertEquals("Tok", store.profile?.nickname)
    }

    @Test fun anAvatarChangeTakesTheServersProfile() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        callables.respond(
            "setProfile",
            200,
            profileResult(tag = "0042", terms = 1).replace(""""id":"cat"""", """"id":"mimic""""),
        )
        assertEquals(SaveOutcome.Saved, store.changeAvatar("mimic"))
        assertEquals(mapOf("op" to "avatar", "avatarId" to "mimic"), callables.data("setProfile"))
        assertEquals("mimic", store.profile?.avatarId)
        assertEquals(5L, store.profile?.updatedAtMs)
        assertEquals(mapOf("field" to "avatar"), events.last().parameters)

        callables.respond("setProfile", 500, "{}")
        assertEquals(SaveOutcome.Failed, store.changeAvatar("cat"))
        assertEquals(AccountNotice.SaveFailed, store.notice.value)
    }

    @Test fun deletionReauthenticatesRefreshesTheTokenThenCallsTheServer() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        var deleted: String? = null
        store.onAccountDeleted = { deleted = it }
        store.signIn(AccountMethod.APPLE)
        callables.respond("deleteAccount", 200, """{"result":{"deleted":true}}""")
        log.clear()
        store.deleteAccount()

        assertEquals(listOf("reauth", "token:refresh", "call:deleteAccount", "signOut"), log)
        assertEquals(emptyMap<String, Any>(), callables.data("deleteAccount"))
        assertEquals(AccountState.SignedOut, store.state.value)
        assertNull(cache.value)
        assertEquals("uid1", deleted)
        assertTrue(events.last() === AnalyticsEvent.AccountDelete)
    }

    @Test fun cancelledReauthenticationErasesNothing() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        val before = store.state.value
        auth.reauthResult = AccountReauthResult.CANCELLED
        log.clear()
        store.deleteAccount()
        assertEquals(listOf("reauth"), log)
        assertEquals(before, store.state.value)
        assertNull(store.notice.value)
    }

    @Test fun aFailedAppleRevocationStopsTheDeletion() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        auth.reauthResult = AccountReauthResult.REVOKE_FAILED
        log.clear()
        store.deleteAccount()
        assertEquals(listOf("reauth"), log)
        assertTrue(store.state.value is AccountState.SignedIn)
        assertEquals(AccountNotice.RevokeFailed, store.notice.value)
    }

    @Test fun aRefusedServerDeletionKeepsTheAccount() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        callables.respond(
            "deleteAccount",
            400,
            """{"error":{"status":"FAILED_PRECONDITION","details":{"code":"requires_recent_login"}}}""",
        )
        log.clear()
        store.deleteAccount()
        assertFalse("signOut" in log)
        assertEquals(AccountNotice.DeleteFailed, store.notice.value)
        assertEquals("Tok", store.profile?.nickname)
    }

    @Test fun aLostDeleteAnswerCountsWhenTheUserIsGone() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        callables.failTransport = true
        auth.sessionStatus = AccountSessionStatus.REVOKED
        store.deleteAccount()
        assertEquals(AccountState.SignedOut, store.state.value)
        assertNull(store.notice.value)
        assertTrue(events.last() === AnalyticsEvent.AccountDelete)
    }

    @Test fun deletingWithoutAProfileUsesTheSameServerCall() = runBlocking {
        val store = store(this, LoggingTransport(response(404)))
        store.signIn(AccountMethod.APPLE)
        callables.respond("deleteAccount", 200, """{"result":{"deleted":true}}""")
        log.clear()
        store.deleteAccount()
        assertEquals(listOf("reauth", "token:refresh", "call:deleteAccount", "signOut"), log)
        assertEquals(AccountState.SignedOut, store.state.value)
    }

    @Test fun aCacheWithoutTheTermsVersionReadsAsNotConsented() = runBlocking {
        auth.currentUser = user
        cache.value = PlayerProfileCache("uid1", "Cached", "0001", "dragon", 5)
        // The re-read fails, so the cached profile stays on screen.
        val store = store(this, LoggingTransport(response(500)))
        store.start()
        awaitUntil { store.state.value is AccountState.SignedIn }
        assertEquals(1, store.profile?.termsAcceptedVersion)

        cache.value = PlayerProfileCache("uid1", "Cached", "0001", "dragon", 5, termsAcceptedVersion = 2)
        val consented = store(this, LoggingTransport(response(500)))
        consented.start()
        awaitUntil { consented.state.value is AccountState.SignedIn }
        assertEquals(2, consented.profile?.termsAcceptedVersion)
    }

    @Test fun aMonsterConsentUpdatesTheProfileAndTheCache() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        assertEquals(1, cache.value?.termsAcceptedVersion)
        store.markTermsAccepted(2)
        assertEquals(2, store.profile?.termsAcceptedVersion)
        awaitUntil { cache.value?.termsAcceptedVersion == 2 }
    }

    @Test fun linkingReportsSuccessAndConflicts() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        store.link(AccountMethod.GOOGLE)
        assertEquals(AccountNotice.Linked(AccountMethod.GOOGLE), store.notice.value)
        assertTrue(store.notice.value!!.isPositive)
        assertEquals(AccountMethod.entries, store.methods.value)
        assertEquals(mapOf("method" to "google"), events.last().parameters)
        // Already linked: nothing happens.
        log.clear()
        store.link(AccountMethod.GOOGLE)
        assertTrue(log.isEmpty())
    }

    @Test fun linkConflictSaysTheSignInBelongsToAnotherAccount() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        auth.linkResult = AccountAuthResult.AlreadyInUse
        store.link(AccountMethod.GOOGLE)
        assertEquals(AccountNotice.AlreadyInUse, store.notice.value)
        assertEquals(listOf(AccountMethod.APPLE), store.methods.value)
    }

    @Test fun signOutClearsLocalStateAndLogs() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        store.signOut()
        assertEquals(AccountState.SignedOut, store.state.value)
        assertNull(cache.value)
        assertTrue(store.methods.value.isEmpty())
        assertTrue(events.last() === AnalyticsEvent.AccountLogout)
    }

    @Test fun demoModeBlocksEveryEntryPointWithoutNetwork() = runBlocking {
        val transport = LoggingTransport(response(200, PROFILE_BODY))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        transport.requests.clear()
        log.clear()
        events.clear()
        demo = true

        store.refreshProfile()
        store.link(AccountMethod.GOOGLE)
        assertEquals(SaveOutcome.Failed, store.changeAvatar("cat"))
        assertEquals(SaveOutcome.Failed, store.changeNickname("Other"))
        store.deleteAccount()
        store.signOut()

        assertTrue(transport.requests.isEmpty())
        assertTrue(callables.calls.isEmpty())
        assertTrue(log.isEmpty())
        assertTrue(events.isEmpty())
        // Entering demo is not a sign-out.
        assertTrue(store.state.value is AccountState.SignedIn)
    }

    @Test fun demoModeBlocksSignInToo() = runBlocking {
        demo = true
        val store = store(this, LoggingTransport())
        store.signIn(AccountMethod.APPLE)
        assertTrue(log.isEmpty())
        assertEquals(AccountState.SignedOut, store.state.value)
    }

    @Test fun anExternalSignOutResetsTheStore() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.start()
        awaitUntil { auth.listener != null }
        store.signIn(AccountMethod.APPLE)
        auth.listener!!.invoke(null)
        awaitUntil { store.state.value == AccountState.SignedOut }
        assertNull(cache.value)
    }

    private fun preset(id: String) = AvatarPreset(id, id, id, List(16) { List(16) { 0 } }, emptyMap())

    @Test fun operatorsDrawTheOperatorAvatarOnlyWhenItIsBundled() = runBlocking {
        auth.operatorClaim = true
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        assertTrue(store.isOperator.value)
        // The claim is read from a freshly issued token: a claim set later is missing from the cached one.
        assertEquals(listOf(true), auth.operatorRefreshes)
        val profile = store.profile!!

        // No operator bitmap yet: the chosen avatar is drawn, and the grid never offers "operator".
        val today = AvatarCatalog(selectable = listOf(preset("cat"), preset("slime")))
        assertNull(today.operatorAvatar)
        assertEquals("cat", displayAvatarId(profile, isOperator = true, catalog = today))

        val withArt = AvatarCatalog(selectable = listOf(preset("cat")), exclusive = listOf(preset("operator")))
        assertEquals("operator", displayAvatarId(profile, isOperator = true, catalog = withArt))
        assertEquals("cat", displayAvatarId(profile, isOperator = false, catalog = withArt))
        assertFalse(AvatarCatalog.OPERATOR_AVATAR_ID in withArt.ids)
        // The stored avatar stays the ordinary preset.
        assertEquals("cat", profile.avatarId)

        store.signOut()
        assertFalse(store.isOperator.value)
    }

    @Test fun ordinaryAccountsAreNotOperators() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        assertFalse(store.isOperator.value)
    }

    @Test fun performOutlivesTheCallerThatStartedIt() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY)))
        store.signIn(AccountMethod.APPLE)
        // The screen that asked for the sign-out goes away at once; the store finishes anyway.
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { store.perform { signOut() }.await() }
        caller.cancel()
        awaitUntil { events.lastOrNull() === AnalyticsEvent.AccountLogout }
        assertEquals(AccountState.SignedOut, store.state.value)
    }
}

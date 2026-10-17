package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarPreset
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfileCache
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
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
        var deleteResult = true
        var pending: AccountAuthResult? = null
        var listener: ((AccountUser?) -> Unit)? = null
        var operatorClaim = false
        val operatorRefreshes = mutableListOf<Boolean>()

        override fun startListening(onChange: (AccountUser?) -> Unit) {
            listener = onChange
        }

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

        override suspend fun deleteUser(): Boolean {
            log += "deleteUser"
            if (deleteResult) currentUser = null
            return deleteResult
        }

        override suspend fun signOut() {
            log += "signOut"
            currentUser = null
        }

        override suspend fun idToken(forceRefresh: Boolean): String? = currentUser?.let { "token" }

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

    private val auth = FakeAuth()
    private val cache = FakeCache()
    private var demo = false

    private fun store(
        scope: CoroutineScope,
        transport: NetworkTransport,
        profiles: Boolean = true,
        /** Tag draws in order; when it runs dry, draws fall back to random. */
        tags: List<Int> = emptyList(),
    ): AccountStore {
        val queue = ArrayDeque(tags)
        return AccountStore(
            auth = auth,
            profiles = if (profiles) PlayerProfileClient("p", transport, auth::idToken, ServerClock { 1_000_000L }) else null,
            cache = cache,
            scope = scope,
            isDemo = { demo },
            log = { events += it },
            drawTag = { queue.removeFirstOrNull() ?: kotlin.random.Random.nextInt(0, 10_000) },
        )
    }

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

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
    }

    @Test fun profileCreationReservesAndCommitsInOneBatch() = runBlocking {
        val transport = LoggingTransport(
            response(404, date = DATE), // GET players (sign-in)
            response(404), // GET nametags: free
            response(200), // commit
        )
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        val outcome = store.createProfile("  Tok_1 ", "dragon", termsAccepted = true)

        assertEquals(SaveOutcome.Saved, outcome)
        val profile = (store.state.value as AccountState.SignedIn).profile
        assertEquals("Tok_1", profile.nickname)
        assertEquals("tok_1", profile.nicknameLower)
        assertTrue(NicknamePolicy.isValidTag(profile.tag) && !NicknamePolicy.isRepdigit(profile.tag))
        assertEquals(profile.createdAtMs, profile.nicknameChangedAtMs)
        assertEquals("nametags", transport.requests[1].url.pathSegments.dropLast(1).last())
        assertEquals("tok_1#${profile.tag}", transport.requests[1].url.pathSegments.last())
        val commit = transport.requests.last()
        assertTrue(commit.url.encodedPath.endsWith("documents:commit"))
        assertTrue(commit.bodyText().contains("\"exists\":false"))
        assertTrue(commit.bodyText().contains("documents/nametags/tok_1#${profile.tag}"))
        assertEquals("dragon", cache.value?.avatarId)
        assertEquals(profile.tag, cache.value?.tag)
    }

    @Test fun aTakenTagIsRedrawnAndTheUserNeverSeesTaken() = runBlocking {
        val transport = LoggingTransport(
            response(404, date = DATE), // GET players (sign-in)
            response(200, ownerBody("other")), // tag 1 belongs to someone else
            response(404), // tag 2 is free
            response(200), // commit
        )
        val store = store(this, transport, tags = listOf(1234, 5678))
        store.signIn(AccountMethod.APPLE)
        assertEquals(SaveOutcome.Saved, store.createProfile("Tok", "cat", true))
        assertEquals(4, transport.requests.size)
        assertEquals("tok#1234", transport.requests[1].url.pathSegments.last())
        assertEquals("tok#5678", transport.requests[2].url.pathSegments.last())
        assertEquals("5678", store.profile?.tag)
    }

    @Test fun aCommitLostToARaceIsRedrawnToo() = runBlocking {
        val transport = LoggingTransport(
            response(404, date = DATE), // GET players (sign-in)
            response(404), // tag 1 looks free
            response(409), // …but another account committed it first
            response(200, ownerBody("other")), // the re-read shows the other owner
            response(404), // tag 2 is free
            response(200), // commit
        )
        val store = store(this, transport, tags = listOf(1234, 5678))
        store.signIn(AccountMethod.APPLE)
        assertEquals(SaveOutcome.Saved, store.createProfile("Tok", "cat", true))
        assertEquals("5678", store.profile?.tag)
    }

    @Test fun sixTakenTagsEndInTaken() = runBlocking {
        val responses = listOf(response(404, date = DATE)) + List(6) { response(200, ownerBody("other")) }
        val transport = LoggingTransport(*responses.toTypedArray())
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        assertEquals(SaveOutcome.Invalid(NicknameError.TAKEN), store.createProfile("Tok", "cat", true))
        assertEquals(7, transport.requests.size)
        assertEquals(AccountState.NeedsProfile, store.state.value)
        assertNull(store.notice.value)
    }

    @Test fun aNicknameChangeDrawsANewTagEvenForACaseOnlyChange() = runBlocking {
        // The Date header puts the server clock decades after the last change, so the cooldown is over.
        val transport = LoggingTransport(
            response(200, PROFILE_BODY, date = DATE), // GET players (sign-in), tag 0042
            response(404), // GET nametags: free
            response(200), // commit
        )
        // 42 repeats the old tag and 7777 is a repdigit: both are skipped.
        val store = store(this, transport, tags = listOf(42, 7777, 1234))
        store.signIn(AccountMethod.APPLE)
        assertEquals(SaveOutcome.Saved, store.changeNickname("TOK"))

        val profile = store.profile!!
        assertEquals("TOK #1234", profile.nametag)
        assertEquals("tok#1234", transport.requests[1].url.pathSegments.last())
        val body = transport.requests.last().bodyText()
        assertTrue(body.contains("documents/nametags/tok#1234"))
        assertTrue(body.contains("\"delete\":\"projects/p/databases/(default)/documents/nametags/tok#0042\""))
        assertTrue(body.contains("\"tag\""))
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
        // Same nickname after normalization is a no-op save.
        assertEquals(SaveOutcome.Saved, store.changeNickname(" Tok "))
    }

    @Test fun avatarChangeIsMaskedAndLogged() = runBlocking {
        val transport = LoggingTransport(response(200, PROFILE_BODY, date = DATE), response(200))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        assertEquals(SaveOutcome.Saved, store.changeAvatar("mimic"))
        assertEquals("mimic", store.profile?.avatarId)
        assertTrue(transport.requests.last().bodyText().contains("\"fieldPaths\":[\"avatar\",\"updatedAtMs\"]"))
        assertEquals(mapOf("field" to "avatar"), events.last().parameters)
    }

    @Test fun deletionRunsReauthThenCommitThenAuthDelete() = runBlocking {
        val transport = LoggingTransport(response(200, PROFILE_BODY), response(200))
        val store = store(this, transport)
        store.signIn(AccountMethod.APPLE)
        log.clear()
        store.deleteAccount()

        assertEquals(listOf("reauth", "net:POST documents:commit", "deleteUser"), log)
        val body = transport.requests.last().bodyText()
        assertTrue(body.contains("documents/nametags/tok#0042"))
        assertTrue(body.contains("documents/players/uid1"))
        assertEquals(AccountState.SignedOut, store.state.value)
        assertNull(cache.value)
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

    @Test fun aFailedProfileDeleteKeepsTheAuthUser() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY), response(500)))
        store.signIn(AccountMethod.APPLE)
        log.clear()
        store.deleteAccount()
        assertEquals(listOf("reauth", "net:POST documents:commit"), log)
        assertEquals(AccountNotice.DeleteFailed, store.notice.value)
        assertTrue(store.state.value is AccountState.SignedIn)
    }

    @Test fun aFailedAuthDeleteLeavesProfileSetupAndAsksToRetry() = runBlocking {
        val store = store(this, LoggingTransport(response(200, PROFILE_BODY), response(200)))
        store.signIn(AccountMethod.APPLE)
        auth.deleteResult = false
        store.deleteAccount()
        assertEquals(AccountState.NeedsProfile, store.state.value)
        assertEquals(AccountNotice.DeleteFailed, store.notice.value)
        assertNull(cache.value)
    }

    @Test fun deletingWithoutAProfileSkipsTheCommit() = runBlocking {
        val store = store(this, LoggingTransport(response(404)))
        store.signIn(AccountMethod.APPLE)
        log.clear()
        store.deleteAccount()
        assertEquals(listOf("reauth", "deleteUser"), log)
        assertEquals(AccountState.SignedOut, store.state.value)
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

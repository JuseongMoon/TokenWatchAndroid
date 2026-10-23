package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.analytics.ProfileField
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfileCache
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App account (TokenWatch's own account) state machine, mirroring iOS `AccountStore`. It coordinates
 * sign-in, profile saves, linking, sign-out and deletion, and is entirely separate from the provider
 * accounts in AgentStore — no provider credential, email or account label travels this path.
 *
 * - In demo mode every entry point returns immediately: zero server calls. Entering demo is not a
 *   sign-out.
 * - The server holds the profile. A minimal cache only lets the PLAYER box draw offline. When the
 *   PLAYER box shows, the server is asked again, and an account deleted on the web or another device
 *   is tidied up into the signed-out state.
 * - Profile writes and deletion go through the V2 server callables (`setProfile`, `deleteAccount`). The
 *   server judges banned words, draws the four-digit tag (`nickname#1234`) and enforces the 30-day
 *   interval; the app only screens input errors first to save calls. Profile reads stay on Firestore REST.
 * - Deletion is reauthenticate → (Apple) revoke → server deletion (every record and the Auth user). A
 *   cancelled reauthentication or a failed revocation stops it before anything is erased.
 */
sealed class AccountState {
    /** Firebase is unusable; the PLAYER box is hidden. */
    data object Unavailable : AccountState()
    data object SignedOut : AccountState()
    data class SigningIn(val method: AccountMethod) : AccountState()

    /** Signed in, reading the profile. */
    data object Loading : AccountState()

    /** Signed in without a profile (first sign-in, or a deletion that stopped half way). */
    data object NeedsProfile : AccountState()
    data class SignedIn(val profile: PlayerProfile) : AccountState()

    /** Signed in, but the profile could not be read (network) — retry. */
    data object LoadFailed : AccountState()
    data object Deleting : AccountState()
}

/** One-shot notices (conflicts, failures), shown once and cleared. The copy comes from L10n. */
sealed class AccountNotice {
    data object LoginFailed : AccountNotice()
    data object GoogleUnavailable : AccountNotice()
    data object ExistsWithDifferentMethod : AccountNotice()
    data object AlreadyInUse : AccountNotice()
    data object LinkFailed : AccountNotice()
    data class Linked(val method: AccountMethod) : AccountNotice()
    data object SaveFailed : AccountNotice()
    data object DeleteFailed : AccountNotice()
    data object RevokeFailed : AccountNotice()

    /** A completion notice (green) rather than a failure (red). */
    val isPositive: Boolean get() = this is Linked
}

/** Profile save outcome. Input errors show inline; transport failures go out as a notice. */
sealed class SaveOutcome {
    data object Saved : SaveOutcome()
    data class Invalid(val error: NicknameError) : SaveOutcome()
    data object Failed : SaveOutcome()
}

/**
 * The avatar actually drawn for [profile]: the operator avatar for an operator account (when its bitmap
 * is bundled), otherwise the chosen one. The stored avatar stays an ordinary preset either way.
 */
fun displayAvatarId(profile: PlayerProfile, isOperator: Boolean, catalog: AvatarCatalog): String =
    catalog.operatorAvatar?.takeIf { isOperator }?.id ?: profile.avatarId

/** The offline profile cache, behind an interface so the store is testable without DataStore. */
interface PlayerProfileCacheStore {
    suspend fun load(): PlayerProfileCache?
    suspend fun save(cache: PlayerProfileCache)
    suspend fun clear()
}

class AccountStore(
    private val auth: AccountAuth,
    private val profiles: PlayerProfileClient?,
    /** The profile write and deletion callables. */
    private val server: MonsterApiClient?,
    private val cache: PlayerProfileCacheStore,
    private val scope: CoroutineScope,
    private val isDemo: () -> Boolean = { false },
    private val log: (AnalyticsEvent) -> Unit = {},
) {
    private val _state = MutableStateFlow<AccountState>(
        if (auth.isAvailable && profiles != null) AccountState.SignedOut else AccountState.Unavailable,
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _methods = MutableStateFlow<List<AccountMethod>>(emptyList())

    /** Linked sign-in methods. */
    val methods: StateFlow<List<AccountMethod>> = _methods.asStateFlow()

    private val _isWorking = MutableStateFlow(false)

    /** A server write is in flight (buttons disabled). */
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _notice = MutableStateFlow<AccountNotice?>(null)
    val notice: StateFlow<AccountNotice?> = _notice.asStateFlow()

    private val _isOperator = MutableStateFlow(false)

    /** Operator account (custom claim): drawn with the operator avatar whatever it picked. */
    val isOperator: StateFlow<Boolean> = _isOperator.asStateFlow()

    @Volatile
    private var currentUid: String? = null
    private var started = false

    /** When the profile was last re-read from the server (for the [revalidate] interval). */
    @Volatile
    private var lastRefreshAtMs: Long? = null

    val isAvailable: Boolean get() = _state.value != AccountState.Unavailable
    val isGoogleAvailable: Boolean get() = auth.isGoogleAvailable

    val profile: PlayerProfile? get() = (_state.value as? AccountState.SignedIn)?.profile

    /** The signed-in account's uid, with or without a profile. The monster store keys its records with it. */
    val uid: String? get() = currentUid

    /** Called with the uid once an in-app deletion finishes, so the app can drop that account's monster records. */
    @Volatile
    var onAccountDeleted: ((String) -> Unit)? = null

    /** An ID token for the monster server calls, fetched right before each request. Null in demo mode. */
    suspend fun idToken(forceRefresh: Boolean): String? = if (isDemo()) null else auth.idToken(forceRefresh)

    /** Shows a monster consent (terms version 2) the server just recorded, in the on-screen profile and the cache. */
    fun markTermsAccepted(version: Int) {
        val current = _state.value as? AccountState.SignedIn ?: return
        val uid = currentUid ?: return
        if (current.profile.termsAcceptedVersion >= version) return
        val updated = current.profile.copy(termsAcceptedVersion = version)
        _state.value = AccountState.SignedIn(updated)
        scope.launch { cache.save(cacheOf(updated, uid)) }
    }

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Runs an operation on the store's own scope. Screens call this rather than their composition
     * scope: a sign-out or a save closes the screen, and that must not cut the operation short.
     */
    fun <T> perform(block: suspend AccountStore.() -> T): Deferred<T> = scope.async { block() }

    /** Milliseconds until the next nickname change (0 = now), on the skew-corrected server clock. */
    fun nicknameCooldownRemainingMs(): Long {
        val profile = profile ?: return 0L
        val profiles = profiles ?: return 0L
        return NicknamePolicy.cooldownRemainingMs(profile.nicknameChangedAtMs, profiles.nowMs)
    }

    // MARK: Start and restore

    /**
     * Once at app start: draws the cached profile, subscribes to sign-in changes, collects an Apple
     * sign-in that finished while the activity was gone, and re-reads the profile from the server.
     */
    fun start() {
        if (!isAvailable || started) return
        started = true
        scope.launch {
            restoreFromCache()
            auth.startListening { user -> scope.launch { authChanged(user) } }
            val pending = auth.pendingSignIn()
            if (pending != null) {
                handleSignIn(AccountMethod.APPLE, pending)
            } else if (currentUid != null) {
                refreshProfile()
            }
        }
    }

    /** The cache draws immediately when it belongs to the signed-in account (no flash before the server). */
    private suspend fun restoreFromCache() {
        val user = auth.currentUser
        if (user == null) {
            cache.clear()
            return
        }
        currentUid = user.uid
        _methods.value = user.methods
        val cached = cache.load()
        _state.value = if (cached != null && cached.uid == user.uid) {
            AccountState.SignedIn(
                PlayerProfile(
                    nickname = cached.nickname,
                    nicknameLower = NicknamePolicy.lower(cached.nickname),
                    tag = cached.tag,
                    nicknameChangedAtMs = cached.nicknameChangedAtMs,
                    avatarId = cached.avatarId,
                    // Caches from before the terms version have none: read as not yet consented (1); the
                    // server's profile corrects it right after.
                    termsAcceptedVersion = cached.termsAcceptedVersion ?: 1,
                    createdAtMs = cached.nicknameChangedAtMs,
                    updatedAtMs = cached.nicknameChangedAtMs,
                ),
            )
        } else {
            AccountState.Loading
        }
    }

    private suspend fun authChanged(user: AccountUser?) {
        if (user == null) {
            // Expiry or a sign-out from elsewhere. The deletion flow tidies up after itself.
            if (_state.value == AccountState.Deleting) return
            currentUid = null
            _methods.value = emptyList()
            _isOperator.value = false
            cache.clear()
            if (_state.value != AccountState.Unavailable) _state.value = AccountState.SignedOut
            return
        }
        _methods.value = user.methods
        // The sign-in flow reads the profile itself; the listener must not read it a second time.
        if (user.uid == currentUid || _state.value is AccountState.SigningIn || _state.value == AccountState.Deleting) {
            return
        }
        currentUid = user.uid
        refreshProfile()
    }

    /**
     * Re-reads the profile, and the operator claim with it. The token is force-refreshed for the claim:
     * a claim set by an admin rides only on a new token, and an operator's repdigit tag makes every
     * profile write fail the rules until it does. A profile already on screen stays when the read fails.
     */
    suspend fun refreshProfile() {
        if (isDemo()) return
        val profiles = profiles ?: return
        val user = auth.currentUser ?: return
        lastRefreshAtMs = System.currentTimeMillis()
        if (profile == null) _state.value = AccountState.Loading
        // Deleted on the web or another device: sign out here too rather than keep showing the cache.
        if (auth.reloadUser() == AccountSessionStatus.REVOKED) {
            auth.signOut()
            resetLocal()
            return
        }
        _isOperator.value = auth.isOperator(forceRefresh = true)
        when (val result = profiles.fetchProfile(user.uid)) {
            is PlayerProfileClient.FetchResult.Found -> apply(result.profile, user.uid)
            PlayerProfileClient.FetchResult.NotFound -> {
                cache.clear()
                _state.value = AccountState.NeedsProfile
            }
            PlayerProfileClient.FetchResult.Failed -> if (profile == null) _state.value = AccountState.LoadFailed
        }
    }

    /**
     * Called when the PLAYER box shows. Picks up an account deleted, or a profile changed, on the web or
     * another device. Skipped shortly after the last read so reopening settings does not call the server
     * each time.
     */
    suspend fun revalidate() {
        val state = _state.value
        if (currentUid == null || _isWorking.value || state is AccountState.SigningIn || state == AccountState.Deleting) return
        val last = lastRefreshAtMs
        if (last != null && System.currentTimeMillis() - last < REVALIDATE_INTERVAL_MS) return
        refreshProfile()
    }

    // MARK: Sign-in, linking, sign-out

    suspend fun signIn(method: AccountMethod) {
        val state = _state.value
        if (isDemo() || !isAvailable || (state != AccountState.SignedOut && state != AccountState.LoadFailed)) return
        _state.value = AccountState.SigningIn(method)
        handleSignIn(method, auth.signIn(method))
    }

    private suspend fun handleSignIn(method: AccountMethod, result: AccountAuthResult) {
        when (result) {
            is AccountAuthResult.Success -> {
                log(AnalyticsEvent.AccountLogin(method, result.isNew))
                currentUid = result.user.uid
                _methods.value = result.user.methods
                _state.value = AccountState.Loading
                refreshProfile()
            }
            AccountAuthResult.Cancelled -> {
                _state.value = AccountState.SignedOut
                log(AnalyticsEvent.AccountLoginFail(method, AccountFailStage.CANCEL))
            }
            AccountAuthResult.Unavailable -> {
                _state.value = AccountState.SignedOut
                _notice.value = AccountNotice.GoogleUnavailable
                log(AnalyticsEvent.AccountLoginFail(method, AccountFailStage.PROVIDER))
            }
            is AccountAuthResult.Failed -> {
                _state.value = AccountState.SignedOut
                _notice.value = AccountNotice.LoginFailed
                log(AnalyticsEvent.AccountLoginFail(method, result.stage))
            }
            AccountAuthResult.ExistsWithDifferentMethod, AccountAuthResult.AlreadyInUse -> {
                _state.value = AccountState.SignedOut
                _notice.value = AccountNotice.ExistsWithDifferentMethod
                log(AnalyticsEvent.AccountLoginFail(method, AccountFailStage.CONFLICT))
            }
        }
    }

    suspend fun link(method: AccountMethod) {
        if (isDemo() || currentUid == null || method in _methods.value || _isWorking.value) return
        working {
            when (val result = auth.link(method)) {
                is AccountAuthResult.Success -> {
                    _methods.value = result.user.methods
                    _notice.value = AccountNotice.Linked(method)
                    log(AnalyticsEvent.AccountLink(method))
                }
                AccountAuthResult.Cancelled -> Unit
                AccountAuthResult.Unavailable -> _notice.value = AccountNotice.GoogleUnavailable
                AccountAuthResult.ExistsWithDifferentMethod, AccountAuthResult.AlreadyInUse ->
                    _notice.value = AccountNotice.AlreadyInUse
                is AccountAuthResult.Failed -> _notice.value = AccountNotice.LinkFailed
            }
        }
    }

    suspend fun signOut() {
        if (isDemo() || currentUid == null || _state.value == AccountState.Deleting) return
        auth.signOut()
        resetLocal()
        log(AnalyticsEvent.AccountLogout)
    }

    private suspend fun resetLocal() {
        currentUid = null
        _methods.value = emptyList()
        _isOperator.value = false
        cache.clear()
        _state.value = AccountState.SignedOut
    }

    // MARK: Profile

    /**
     * The first profile save. The server draws the tag and creates the profile with its reservation. A new
     * sign-up agrees to the current terms (the monster hunt included), so the consent screen never follows.
     */
    suspend fun createProfile(nickname: String, avatarId: String, termsAccepted: Boolean): SaveOutcome {
        val profiles = profiles
        val server = server
        val uid = currentUid
        if (isDemo() || _state.value != AccountState.NeedsProfile || profiles == null || server == null ||
            uid == null || _isWorking.value
        ) {
            return SaveOutcome.Failed
        }
        val normalized = NicknamePolicy.normalize(nickname)
        NicknamePolicy.validate(normalized)?.let { return SaveOutcome.Invalid(it) }
        if (!termsAccepted) return SaveOutcome.Invalid(NicknameError.TERMS_REQUIRED)
        return working {
            val change = MonsterApiClient.ProfileChange.Create(normalized, avatarId, PlayerProfile.CURRENT_TERMS_VERSION)
            when (val result = server.setProfile(change)) {
                is MonsterApiClient.CallResult.Success -> {
                    apply(result.value, uid)
                    SaveOutcome.Saved
                }
                is MonsterApiClient.CallResult.Failure -> {
                    nicknameError(result.error)?.let { return@working SaveOutcome.Invalid(it) }
                    // Only the answer may have been lost (the server never creates twice): read to check.
                    val fetched = profiles.fetchProfile(uid)
                    if (fetched is PlayerProfileClient.FetchResult.Found) {
                        apply(fetched.profile, uid)
                        return@working SaveOutcome.Saved
                    }
                    _notice.value = AccountNotice.SaveFailed
                    SaveOutcome.Failed
                }
            }
        }
    }

    /** Nickname change (once every 30 days). The server draws a new tag, different from the previous one. */
    suspend fun changeNickname(nickname: String): SaveOutcome {
        val current = profile
        val profiles = profiles
        val server = server
        val uid = currentUid
        if (isDemo() || current == null || profiles == null || server == null || uid == null || _isWorking.value) {
            return SaveOutcome.Failed
        }
        val normalized = NicknamePolicy.normalize(nickname)
        // The server refuses the same nickname, so it is saved without a call.
        if (normalized == current.nickname) return SaveOutcome.Saved
        NicknamePolicy.validate(normalized)?.let { return SaveOutcome.Invalid(it) }
        return working {
            profiles.ensureServerTime(uid)
            if (nicknameCooldownRemainingMs() > 0) return@working SaveOutcome.Invalid(NicknameError.COOLDOWN)
            when (val result = server.setProfile(MonsterApiClient.ProfileChange.Nickname(normalized))) {
                is MonsterApiClient.CallResult.Success -> {
                    apply(result.value, uid)
                    log(AnalyticsEvent.ProfileUpdate(ProfileField.NICKNAME))
                    SaveOutcome.Saved
                }
                is MonsterApiClient.CallResult.Failure -> {
                    nicknameError(result.error)?.let { return@working SaveOutcome.Invalid(it) }
                    _notice.value = AccountNotice.SaveFailed
                    SaveOutcome.Failed
                }
            }
        }
    }

    suspend fun changeAvatar(avatarId: String): SaveOutcome {
        val current = profile
        val server = server
        val uid = currentUid
        if (isDemo() || current == null || server == null || uid == null || _isWorking.value) return SaveOutcome.Failed
        if (avatarId == current.avatarId) return SaveOutcome.Saved
        return working {
            when (val result = server.setProfile(MonsterApiClient.ProfileChange.Avatar(avatarId))) {
                is MonsterApiClient.CallResult.Success -> {
                    apply(result.value, uid)
                    log(AnalyticsEvent.ProfileUpdate(ProfileField.AVATAR))
                    SaveOutcome.Saved
                }
                is MonsterApiClient.CallResult.Failure -> {
                    _notice.value = AccountNotice.SaveFailed
                    SaveOutcome.Failed
                }
            }
        }
    }

    private suspend fun apply(profile: PlayerProfile, uid: String) {
        _state.value = AccountState.SignedIn(profile)
        cache.save(cacheOf(profile, uid))
    }

    private fun cacheOf(profile: PlayerProfile, uid: String) = PlayerProfileCache(
        uid = uid,
        nickname = profile.nickname,
        tag = profile.tag,
        avatarId = profile.avatarId,
        nicknameChangedAtMs = profile.nicknameChangedAtMs,
        termsAcceptedVersion = profile.termsAcceptedVersion,
    )

    // MARK: Deletion

    /** Deletes the account from inside the app. Without a profile (NeedsProfile) the same call removes the sign-in records. */
    suspend fun deleteAccount() {
        val server = server
        val uid = currentUid
        if (isDemo() || server == null || uid == null || _state.value == AccountState.Deleting || _isWorking.value) {
            return
        }
        val previous = _state.value
        _state.value = AccountState.Deleting
        when (auth.reauthenticateForDeletion()) {
            AccountReauthResult.OK -> Unit
            AccountReauthResult.CANCELLED -> {
                _state.value = previous
                return
            }
            AccountReauthResult.FAILED -> {
                _state.value = previous
                _notice.value = AccountNotice.DeleteFailed
                return
            }
            AccountReauthResult.REVOKE_FAILED -> {
                _state.value = previous
                _notice.value = AccountNotice.RevokeFailed
                return
            }
        }
        // The server only takes a token from a sign-in in the last five minutes: fetch one carrying the
        // reauthentication.
        auth.idToken(forceRefresh = true)
        if (server.deleteAccount() is MonsterApiClient.CallResult.Failure) {
            // Only the answer may have been lost: with the sign-in record already gone, the deletion finished.
            if (auth.reloadUser() != AccountSessionStatus.REVOKED) {
                _state.value = previous
                _notice.value = AccountNotice.DeleteFailed
                return
            }
        }
        auth.signOut()
        resetLocal()
        onAccountDeleted?.invoke(uid)
        log(AnalyticsEvent.AccountDelete)
    }

    private companion object {
        /** The minimum gap between two [revalidate] reads. */
        const val REVALIDATE_INTERVAL_MS = 30_000L
    }

    /** A server error that is a nickname input error (the contract's nickname codes). Anything else is a failed save. */
    private fun nicknameError(error: MonsterApiClient.CallError): NicknameError? {
        val code = (error as? MonsterApiClient.CallError.Server)?.code ?: return null
        return NicknameError.entries.firstOrNull { it.wireId == code }
    }

    private suspend fun <T> working(block: suspend () -> T): T {
        _isWorking.value = true
        return try {
            block()
        } finally {
            _isWorking.value = false
        }
    }
}

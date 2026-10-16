package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.analytics.ProfileField
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfileCache
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
 * - The server holds the profile. A minimal cache only lets the PLAYER box draw offline.
 * - Deletion is reauthenticate → (Apple) revoke → profile delete commit → Auth user delete. A cancelled
 *   reauthentication or a failed revocation stops it before anything is erased.
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

/** The offline profile cache, behind an interface so the store is testable without DataStore. */
interface PlayerProfileCacheStore {
    suspend fun load(): PlayerProfileCache?
    suspend fun save(cache: PlayerProfileCache)
    suspend fun clear()
}

class AccountStore(
    private val auth: AccountAuth,
    private val profiles: PlayerProfileClient?,
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

    @Volatile
    private var currentUid: String? = null
    private var started = false

    val isAvailable: Boolean get() = _state.value != AccountState.Unavailable
    val isGoogleAvailable: Boolean get() = auth.isGoogleAvailable

    val profile: PlayerProfile? get() = (_state.value as? AccountState.SignedIn)?.profile

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
                    nicknameChangedAtMs = cached.nicknameChangedAtMs,
                    avatarId = cached.avatarId,
                    termsAcceptedVersion = PlayerProfile.CURRENT_TERMS_VERSION,
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

    /** Re-reads the profile. A profile already on screen stays when the read fails. */
    suspend fun refreshProfile() {
        if (isDemo()) return
        val profiles = profiles ?: return
        val user = auth.currentUser ?: return
        if (profile == null) _state.value = AccountState.Loading
        when (val result = profiles.fetchProfile(user.uid)) {
            is PlayerProfileClient.FetchResult.Found -> apply(result.profile, user.uid)
            PlayerProfileClient.FetchResult.NotFound -> {
                cache.clear()
                _state.value = AccountState.NeedsProfile
            }
            PlayerProfileClient.FetchResult.Failed -> if (profile == null) _state.value = AccountState.LoadFailed
        }
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
        cache.clear()
        _state.value = AccountState.SignedOut
    }

    // MARK: Profile

    /** The first profile save: the nickname reservation and the profile go out in one commit. */
    suspend fun createProfile(nickname: String, avatarId: String, termsAccepted: Boolean): SaveOutcome {
        val profiles = profiles
        val uid = currentUid
        if (isDemo() || _state.value != AccountState.NeedsProfile || profiles == null || uid == null || _isWorking.value) {
            return SaveOutcome.Failed
        }
        val normalized = NicknamePolicy.normalize(nickname)
        NicknamePolicy.validate(normalized)?.let { return SaveOutcome.Invalid(it) }
        if (!termsAccepted) return SaveOutcome.Invalid(NicknameError.TERMS_REQUIRED)
        val lower = NicknamePolicy.lower(normalized)
        return working {
            when (profiles.nicknameAvailability(lower, uid)) {
                PlayerProfileClient.Availability.TAKEN -> return@working SaveOutcome.Invalid(NicknameError.TAKEN)
                PlayerProfileClient.Availability.FAILED -> {
                    _notice.value = AccountNotice.SaveFailed
                    return@working SaveOutcome.Failed
                }
                PlayerProfileClient.Availability.AVAILABLE -> Unit
            }
            profiles.ensureServerTime(uid)
            val now = profiles.nowMs
            val profile = PlayerProfile(
                nickname = normalized,
                nicknameLower = lower,
                nicknameChangedAtMs = now,
                avatarId = avatarId,
                termsAcceptedVersion = PlayerProfile.CURRENT_TERMS_VERSION,
                createdAtMs = now,
                updatedAtMs = now,
            )
            when (profiles.commit(profiles.documents.create(uid, profile), uid, lower)) {
                PlayerProfileClient.CommitResult.OK -> {
                    apply(profile, uid)
                    SaveOutcome.Saved
                }
                PlayerProfileClient.CommitResult.TAKEN -> SaveOutcome.Invalid(NicknameError.TAKEN)
                PlayerProfileClient.CommitResult.FAILED -> {
                    _notice.value = AccountNotice.SaveFailed
                    SaveOutcome.Failed
                }
            }
        }
    }

    suspend fun changeNickname(nickname: String): SaveOutcome {
        val current = profile
        val profiles = profiles
        val uid = currentUid
        if (isDemo() || current == null || profiles == null || uid == null || _isWorking.value) return SaveOutcome.Failed
        val normalized = NicknamePolicy.normalize(nickname)
        if (normalized == current.nickname) return SaveOutcome.Saved
        NicknamePolicy.validate(normalized)?.let { return SaveOutcome.Invalid(it) }
        return working {
            profiles.ensureServerTime(uid)
            if (nicknameCooldownRemainingMs() > 0) return@working SaveOutcome.Invalid(NicknameError.COOLDOWN)
            val lower = NicknamePolicy.lower(normalized)
            if (lower != current.nicknameLower) {
                when (profiles.nicknameAvailability(lower, uid)) {
                    PlayerProfileClient.Availability.TAKEN -> return@working SaveOutcome.Invalid(NicknameError.TAKEN)
                    PlayerProfileClient.Availability.FAILED -> {
                        _notice.value = AccountNotice.SaveFailed
                        return@working SaveOutcome.Failed
                    }
                    PlayerProfileClient.Availability.AVAILABLE -> Unit
                }
            }
            val now = profiles.nowMs
            val writes = profiles.documents.changeNickname(uid, current.nicknameLower, normalized, lower, now)
            when (profiles.commit(writes, uid, lower)) {
                PlayerProfileClient.CommitResult.OK -> {
                    apply(
                        current.copy(
                            nickname = normalized,
                            nicknameLower = lower,
                            nicknameChangedAtMs = now,
                            updatedAtMs = now,
                        ),
                        uid,
                    )
                    log(AnalyticsEvent.ProfileUpdate(ProfileField.NICKNAME))
                    SaveOutcome.Saved
                }
                PlayerProfileClient.CommitResult.TAKEN -> SaveOutcome.Invalid(NicknameError.TAKEN)
                PlayerProfileClient.CommitResult.FAILED -> {
                    _notice.value = AccountNotice.SaveFailed
                    SaveOutcome.Failed
                }
            }
        }
    }

    suspend fun changeAvatar(avatarId: String): SaveOutcome {
        val current = profile
        val profiles = profiles
        val uid = currentUid
        if (isDemo() || current == null || profiles == null || uid == null || _isWorking.value) return SaveOutcome.Failed
        if (avatarId == current.avatarId) return SaveOutcome.Saved
        return working {
            profiles.ensureServerTime(uid)
            val now = profiles.nowMs
            when (profiles.commit(profiles.documents.changeAvatar(uid, avatarId, now), uid)) {
                PlayerProfileClient.CommitResult.OK -> {
                    apply(current.copy(avatarId = avatarId, updatedAtMs = now), uid)
                    log(AnalyticsEvent.ProfileUpdate(ProfileField.AVATAR))
                    SaveOutcome.Saved
                }
                PlayerProfileClient.CommitResult.TAKEN, PlayerProfileClient.CommitResult.FAILED -> {
                    _notice.value = AccountNotice.SaveFailed
                    SaveOutcome.Failed
                }
            }
        }
    }

    private suspend fun apply(profile: PlayerProfile, uid: String) {
        _state.value = AccountState.SignedIn(profile)
        cache.save(
            PlayerProfileCache(
                uid = uid,
                nickname = profile.nickname,
                avatarId = profile.avatarId,
                nicknameChangedAtMs = profile.nicknameChangedAtMs,
            ),
        )
    }

    // MARK: Deletion

    /** Deletes the account from inside the app. Without a profile (NeedsProfile) only the Auth user goes. */
    suspend fun deleteAccount() {
        val profiles = profiles
        val uid = currentUid
        if (isDemo() || profiles == null || uid == null || _state.value == AccountState.Deleting || _isWorking.value) {
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
        if (previous is AccountState.SignedIn) {
            val writes = profiles.documents.delete(uid, previous.profile.nicknameLower)
            if (profiles.commit(writes, uid) != PlayerProfileClient.CommitResult.OK) {
                _state.value = previous
                _notice.value = AccountNotice.DeleteFailed
                return
            }
        }
        if (!auth.deleteUser()) {
            // The profile is gone and only the Auth user is left; the next sign-in starts at profile
            // setup. The user is told to retry.
            cache.clear()
            _state.value = AccountState.NeedsProfile
            _notice.value = AccountNotice.DeleteFailed
            return
        }
        resetLocal()
        log(AnalyticsEvent.AccountDelete)
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

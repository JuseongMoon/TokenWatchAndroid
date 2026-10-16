package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod

/**
 * App account authentication boundary, mirroring iOS `AccountAuthenticating`. [AccountStore] depends
 * only on this interface, so its state machine is unit tested without Firebase (whose classes cannot
 * run in JVM tests). The Firebase implementation is [FirebaseAccountAuth].
 *
 * Token handling: app code never stores a Firebase, Apple or Google token. firebase-auth keeps its own
 * session; the ID token is fetched right before a request and only placed in its header.
 */

/** The minimum about a signed-in app account. Email and name are never handled. */
data class AccountUser(
    val uid: String,
    /** Linked sign-in methods, in [AccountMethod] order. */
    val methods: List<AccountMethod>,
)

/** Where an app account sign-in stopped. The analytics `stage` value. */
enum class AccountFailStage(val wireId: String) {
    CANCEL("cancel"),
    PROVIDER("provider"),
    FIREBASE("firebase"),
    CONFLICT("conflict"),
}

sealed class AccountAuthResult {
    data class Success(val user: AccountUser, val isNew: Boolean) : AccountAuthResult()
    data object Cancelled : AccountAuthResult()

    /** The method cannot be used on this device or build (no Google client configured, no GMS). */
    data object Unavailable : AccountAuthResult()
    data class Failed(val stage: AccountFailStage) : AccountAuthResult()

    /** Sign-in: the same email is registered with another method — sign in that way, then link. */
    data object ExistsWithDifferentMethod : AccountAuthResult()

    /** Linking: that sign-in already belongs to another TokenWatch account (no merging). */
    data object AlreadyInUse : AccountAuthResult()
}

enum class AccountReauthResult {
    OK,
    CANCELLED,
    FAILED,

    /**
     * Reauthenticated, but revoking the Apple token failed. Deletion stops here: the server cannot
     * revoke it later on its own.
     */
    REVOKE_FAILED,
}

interface AccountAuth {
    /** Whether Firebase is usable at all. False hides the whole account feature. */
    val isAvailable: Boolean
    val isGoogleAvailable: Boolean
    val currentUser: AccountUser?

    /** Sign-in state changes (restore, expiry, sign-out). Registered once for the app's lifetime. */
    fun startListening(onChange: (AccountUser?) -> Unit)

    /**
     * An Apple web sign-in that finished while the activity was reclaimed, or null. Firebase keeps the
     * result for the restarted activity to collect.
     */
    suspend fun pendingSignIn(): AccountAuthResult?
    suspend fun signIn(method: AccountMethod): AccountAuthResult
    suspend fun link(method: AccountMethod): AccountAuthResult

    /** Reauthentication right before deletion. With Apple linked, Apple is used and its token revoked. */
    suspend fun reauthenticateForDeletion(): AccountReauthResult
    suspend fun deleteUser(): Boolean
    suspend fun signOut()
    suspend fun idToken(forceRefresh: Boolean): String?
}

/** No Firebase (previews, tests of the hidden state). Everything fails. */
object UnavailableAccountAuth : AccountAuth {
    override val isAvailable: Boolean = false
    override val isGoogleAvailable: Boolean = false
    override val currentUser: AccountUser? = null
    override fun startListening(onChange: (AccountUser?) -> Unit) = Unit
    override suspend fun pendingSignIn(): AccountAuthResult? = null
    override suspend fun signIn(method: AccountMethod): AccountAuthResult = AccountAuthResult.Unavailable
    override suspend fun link(method: AccountMethod): AccountAuthResult = AccountAuthResult.Unavailable
    override suspend fun reauthenticateForDeletion(): AccountReauthResult = AccountReauthResult.FAILED
    override suspend fun deleteUser(): Boolean = false
    override suspend fun signOut() = Unit
    override suspend fun idToken(forceRefresh: Boolean): String? = null
}

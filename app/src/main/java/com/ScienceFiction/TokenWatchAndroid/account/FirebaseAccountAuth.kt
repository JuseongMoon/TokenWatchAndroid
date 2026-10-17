package com.ScienceFiction.TokenWatchAndroid.account

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.ScienceFiction.TokenWatchAndroid.MainActivity
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthCredential
import com.google.firebase.auth.OAuthProvider
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * firebase-auth (authentication only — Firestore stays REST) plus the two sign-in flows.
 *
 * - **Apple**: Firebase's web flow (`startActivityFor…WithProvider`, a Custom Tab on Firebase's handler).
 *   The activity may be reclaimed while the tab is open, so a finished result is collected through
 *   [FirebaseAuth.getPendingAuthResult] ([pendingSignIn]). No scopes are added here; with the console's
 *   "one account per email address" setting Firebase itself requests email and name, and the app
 *   never reads either.
 * - **Google**: Credential Manager with [GetSignInWithGoogleOption] (button-initiated flow), then a
 *   Google credential for Firebase. The server client id is the web OAuth client Firebase generates
 *   into `default_web_client_id` once Google sign-in is enabled; without it Google is unavailable.
 *
 * Flows need the visible [MainActivity], tracked through activity callbacks so the store and its tests
 * stay free of Android types. Raw error messages are never surfaced or logged.
 */
class FirebaseAccountAuth(
    private val application: Application,
    private val language: () -> Lang,
) : AccountAuth {
    private val auth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()
    private val credentialManager by lazy { CredentialManager.create(application) }
    private var listener: FirebaseAuth.AuthStateListener? = null
    private var foreground = WeakReference<Activity>(null)

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = track(activity)
                override fun onActivityStarted(activity: Activity) = track(activity)
                override fun onActivityResumed(activity: Activity) = track(activity)
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    if (foreground.get() === activity) foreground = WeakReference(null)
                }

                // Only the app's own screen hosts the flows; Firebase's handler activity runs in this
                // process too and must not become the anchor.
                private fun track(activity: Activity) {
                    if (activity is MainActivity) foreground = WeakReference(activity)
                }
            },
        )
    }

    override val isAvailable: Boolean get() = auth != null

    override val isGoogleAvailable: Boolean get() = isAvailable && googleServerClientId() != null

    override val currentUser: AccountUser? get() = auth?.currentUser?.toAccountUser()

    override fun startListening(onChange: (AccountUser?) -> Unit) {
        val auth = auth ?: return
        if (listener != null) return
        listener = FirebaseAuth.AuthStateListener { onChange(it.currentUser?.toAccountUser()) }
            .also(auth::addAuthStateListener)
    }

    override suspend fun pendingSignIn(): AccountAuthResult? {
        val pending = auth?.pendingAuthResult ?: return null
        return runAuth { pending.await().toSuccess() }
    }

    override suspend fun signIn(method: AccountMethod): AccountAuthResult {
        val auth = auth ?: return AccountAuthResult.Unavailable
        return when (method) {
            AccountMethod.APPLE -> {
                pendingSignIn()?.let { return it }
                val activity = foreground.get() ?: return AccountAuthResult.Failed(AccountFailStage.PROVIDER)
                runAuth { auth.startActivityForSignInWithProvider(activity, appleProvider()).await().toSuccess() }
            }
            AccountMethod.GOOGLE -> when (val outcome = googleCredential()) {
                is CredentialOutcome.Ready -> runAuth { auth.signInWithCredential(outcome.credential).await().toSuccess() }
                else -> outcome.toFailure()
            }
        }
    }

    override suspend fun link(method: AccountMethod): AccountAuthResult {
        val user = auth?.currentUser ?: return AccountAuthResult.Failed(AccountFailStage.FIREBASE)
        val result = when (method) {
            AccountMethod.APPLE -> {
                val activity = foreground.get() ?: return AccountAuthResult.Failed(AccountFailStage.PROVIDER)
                runAuth { user.startActivityForLinkWithProvider(activity, appleProvider()).await().toSuccess(isNew = false) }
            }
            AccountMethod.GOOGLE -> when (val outcome = googleCredential()) {
                is CredentialOutcome.Ready -> runAuth { user.linkWithCredential(outcome.credential).await().toSuccess(isNew = false) }
                else -> outcome.toFailure()
            }
        }
        // While linking, both collisions mean "tied to another account" (accounts are never merged).
        return if (result == AccountAuthResult.ExistsWithDifferentMethod) AccountAuthResult.AlreadyInUse else result
    }

    override suspend fun reauthenticateForDeletion(): AccountReauthResult {
        val auth = auth ?: return AccountReauthResult.FAILED
        val user = auth.currentUser ?: return AccountReauthResult.FAILED
        val methods = user.toAccountUser().methods
        if (AccountMethod.APPLE in methods || methods.isEmpty()) {
            val activity = foreground.get() ?: return AccountReauthResult.FAILED
            val result = try {
                user.startActivityForReauthenticateWithProvider(activity, appleProvider()).await()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                return if (mapError(error) == AccountAuthResult.Cancelled) {
                    AccountReauthResult.CANCELLED
                } else {
                    AccountReauthResult.FAILED
                }
            }
            // Disconnects the app from the Apple ID ("Sign in with Apple" list). An access token, not
            // an authorization code, on Android. A failure stops the deletion.
            val accessToken = (result.credential as? OAuthCredential)?.accessToken
                ?: return AccountReauthResult.REVOKE_FAILED
            return try {
                auth.revokeAccessToken(accessToken).await()
                AccountReauthResult.OK
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                AccountReauthResult.REVOKE_FAILED
            }
        }
        val credential = when (val outcome = googleCredential()) {
            is CredentialOutcome.Ready -> outcome.credential
            CredentialOutcome.Cancelled -> return AccountReauthResult.CANCELLED
            else -> return AccountReauthResult.FAILED
        }
        return try {
            user.reauthenticate(credential).await()
            AccountReauthResult.OK
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            AccountReauthResult.FAILED
        }
    }

    override suspend fun deleteUser(): Boolean {
        val user = auth?.currentUser ?: return false
        return try {
            user.delete().await()
            clearGoogleState()
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun signOut() {
        auth?.signOut()
        clearGoogleState()
    }

    override suspend fun idToken(forceRefresh: Boolean): String? {
        val user = auth?.currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun isOperator(forceRefresh: Boolean): Boolean {
        val user = auth?.currentUser ?: return false
        return try {
            user.getIdToken(forceRefresh).await().claims[OPERATOR_CLAIM] == true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }

    // MARK: Credentials

    private sealed class CredentialOutcome {
        data class Ready(val credential: AuthCredential) : CredentialOutcome()
        data object Cancelled : CredentialOutcome()
        data object Unavailable : CredentialOutcome()
        data object Failed : CredentialOutcome()

        fun toFailure(): AccountAuthResult = when (this) {
            is Ready, Failed -> AccountAuthResult.Failed(AccountFailStage.PROVIDER)
            Cancelled -> AccountAuthResult.Cancelled
            Unavailable -> AccountAuthResult.Unavailable
        }
    }

    /** Apple through Firebase's handler, with Apple's screen in the app language. */
    private fun appleProvider(): OAuthProvider = OAuthProvider.newBuilder(APPLE_PROVIDER_ID)
        .addCustomParameter("locale", if (language() == Lang.KO) "ko" else "en")
        .build()

    private suspend fun googleCredential(): CredentialOutcome {
        val clientId = googleServerClientId() ?: return CredentialOutcome.Unavailable
        val activity: Context = foreground.get() ?: return CredentialOutcome.Failed
        val request = GetCredentialRequest(listOf(GetSignInWithGoogleOption.Builder(clientId).build()))
        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                CredentialOutcome.Ready(GoogleAuthProvider.getCredential(idToken, null))
            } else {
                CredentialOutcome.Failed
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: GetCredentialCancellationException) {
            CredentialOutcome.Cancelled
        } catch (_: NoCredentialException) {
            CredentialOutcome.Unavailable
        } catch (_: GetCredentialProviderConfigurationException) {
            CredentialOutcome.Unavailable
        } catch (_: GetCredentialUnsupportedException) {
            CredentialOutcome.Unavailable
        } catch (_: GetCredentialException) {
            CredentialOutcome.Failed
        } catch (_: Exception) {
            CredentialOutcome.Failed
        }
    }

    /**
     * `R.string.default_web_client_id` exists only after `google-services.json` carries the web OAuth
     * client, so it is looked up by name: the build keeps compiling before then, and Google simply stays
     * hidden. `res/raw/keep.xml` keeps the string through resource shrinking.
     */
    @SuppressLint("DiscouragedApi")
    private fun googleServerClientId(): String? {
        val id = application.resources.getIdentifier("default_web_client_id", "string", application.packageName)
        if (id == 0) return null
        return application.getString(id).takeIf(String::isNotBlank)
    }

    private suspend fun clearGoogleState() {
        runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
    }

    private suspend fun runAuth(block: suspend () -> AccountAuthResult): AccountAuthResult = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        mapError(error)
    }

    private fun AuthResult.toSuccess(isNew: Boolean? = null): AccountAuthResult {
        val user = user ?: return AccountAuthResult.Failed(AccountFailStage.FIREBASE)
        return AccountAuthResult.Success(
            user = user.toAccountUser(),
            isNew = isNew ?: (additionalUserInfo?.isNewUser == true),
        )
    }

    private companion object {
        const val APPLE_PROVIDER_ID = "apple.com"

        /** Operator custom claim name; the same value as the server rules and the admin script. */
        const val OPERATOR_CLAIM = "tokenwatchOperator"

        fun FirebaseUser.toAccountUser(): AccountUser {
            val ids = providerData.map { it.providerId }.toSet()
            return AccountUser(uid = uid, methods = AccountMethod.entries.filter { it.firebaseProviderId in ids })
        }

        /** Firebase error → result, by error code only. */
        fun mapError(error: Exception): AccountAuthResult = when ((error as? FirebaseAuthException)?.errorCode) {
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> AccountAuthResult.ExistsWithDifferentMethod
            "ERROR_CREDENTIAL_ALREADY_IN_USE", "ERROR_EMAIL_ALREADY_IN_USE" -> AccountAuthResult.AlreadyInUse
            "ERROR_WEB_CONTEXT_CANCELED" -> AccountAuthResult.Cancelled
            else -> AccountAuthResult.Failed(AccountFailStage.FIREBASE)
        }
    }
}

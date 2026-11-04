package com.ScienceFiction.TokenWatchAndroid.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/**
 * Whether the app is in front during a sign-in, for the requests that have to survive the sign-in
 * tab covering it.
 *
 * While a Custom Tab or Auth Tab is in front the app is in the background, and Android may block
 * its network access entirely (`blocked=APP_BACKGROUND`): every request fails with an
 * `IOException` such as `UnknownHostException` although the device itself is online. A sign-in
 * therefore treats a network failure in the background as "not yet", and acts the moment the app
 * returns: [onForeground] wakes a waiting poll at once instead of after its interval.
 */
class ForegroundSignal {
    private val foreground = MutableStateFlow(true)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val isForeground: Boolean get() = foreground.value

    /** Only a return from the background wakes a pause; a resume while already in front does not. */
    fun onForeground() {
        val wasBackground = !foreground.value
        foreground.value = true
        if (wasBackground) wake.trySend(Unit)
    }

    fun onBackground() {
        foreground.value = false
    }

    /** Suspends until the app is in front. */
    suspend fun awaitForeground() {
        foreground.first { it }
    }

    /**
     * Runs [sleep] (a poll interval), ending it early when the app comes back to the front. A
     * return that happened while no pause was running still ends the next pause immediately.
     */
    suspend fun pause(sleep: suspend () -> Unit) = coroutineScope {
        val sleeping = async { sleep() }
        select<Unit> {
            sleeping.onAwait {}
            wake.onReceive {}
        }
        sleeping.cancel()
    }
}

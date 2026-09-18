package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One-shot loopback HTTP listener that receives a single OAuth callback, ported from iOS
 * `LoopbackCallbackServer` (4f0eda5, 7d0d9da).
 *
 * Why it exists: the claude.ai authorization server rejects custom-scheme redirect URIs, so the
 * sign-in window is sent to `http://localhost:<port>/callback` and the app receives the code itself
 * (the same structure as the Claude Code and Grok CLI logins).
 *
 * The response is `302 Found` to `tokenwatch://login-complete?code=…&state=…`. The Auth Tab closes
 * itself on a redirect to that scheme, and without Auth Tab support the scheme opens
 * `LoginCompleteActivity`, which returns to the app. The code is handed over only after that response
 * has been written: handing it over first let the receiver's cleanup cut the connection before the
 * 302 left (the iOS 1.1.0 build 12 defect).
 *
 * Security: bound to 127.0.0.1/::1 only, never the LAN. Only a request carrying the expected state is
 * accepted, and the code is useless without the PKCE verifier. Speculative browser preconnects that
 * never send a request line time out on their own thread without blocking the real request.
 */
class LoopbackCallbackServer(
    /** Only a callback with exactly this state is accepted (CSRF defence). */
    private val expectedState: String,
    parentScope: CoroutineScope,
    /** Called at most once, on a background thread, after the 302 has been written. */
    private val onCode: (code: String, state: String) -> Unit,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    private val delivered = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Binds an ephemeral loopback port and returns it. */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val v4 = ServerSocket(0, BACKLOG, InetAddress.getByAddress(IPV4_LOOPBACK))
        serverSockets += v4
        acceptLoop(v4)
        val port = v4.localPort
        // A browser may try `localhost` as ::1 first. Listen there on the same port as well; failing
        // is fine because the browser falls back to IPv4.
        runCatching { ServerSocket(port, BACKLOG, InetAddress.getByAddress(IPV6_LOOPBACK)) }
            .getOrNull()
            ?.let { v6 ->
                serverSockets += v6
                acceptLoop(v6)
            }
        scope.launch {
            delay(LIFETIME_MILLIS)
            stop()
        }
        port
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        serverSockets.forEach { runCatching { it.close() } }
        serverSockets.clear()
        job.cancel()
    }

    private fun acceptLoop(server: ServerSocket) {
        scope.launch {
            while (isActive && !stopped.get()) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                launch { handle(socket) }
            }
        }
    }

    private fun handle(socket: Socket) {
        val callback = try {
            socket.use { connection ->
                connection.soTimeout = READ_TIMEOUT_MILLIS
                val head = readHead(connection.getInputStream()) ?: return
                val target = requestLine(head)?.let(::target)
                if (target == null) {
                    respond(connection, "400 Bad Request", body = "bad request")
                    return
                }
                val parsed = parseCallback(target, expectedState)
                if (parsed == null) {
                    respond(connection, "404 Not Found", body = "not found")
                    return
                }
                respond(
                    connection,
                    "302 Found",
                    extraHeaders = listOf("Location: ${redirectLocation(parsed.code, parsed.state)}"),
                )
                parsed
            }
        } catch (_: IOException) {
            // A dropped connection before a valid request line carries nothing to deliver.
            return
        }
        // The 302 is out; only now hand over the code. A delivery that fails to reach the browser
        // still yields the code, which is all the exchange needs.
        deliver(callback)
        // Fold the listener once the response has had time to leave.
        scope.launch {
            delay(SHUTDOWN_DELAY_MILLIS)
            stop()
        }
    }

    private fun deliver(callback: OAuthCallback) {
        if (!delivered.compareAndSet(false, true)) return
        onCode(callback.code, callback.state)
    }

    private fun respond(
        socket: Socket,
        status: String,
        extraHeaders: List<String> = emptyList(),
        body: String = "",
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = (listOf("HTTP/1.1 $status") + extraHeaders + listOf(
            "Content-Type: text/plain; charset=utf-8",
            "Content-Length: ${bodyBytes.size}",
            "Cache-Control: no-store",
            "Connection: close",
        )).joinToString("\r\n", postfix = "\r\n\r\n")
        val output = socket.getOutputStream()
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bodyBytes)
        output.flush()
        // FIN after the data rather than a reset on close.
        runCatching { socket.shutdownOutput() }
    }

    private fun readHead(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(1_024)
        while (buffer.size() < MAX_REQUEST_BYTES) {
            val read = input.read(chunk)
            if (read < 0) return null
            buffer.write(chunk, 0, read)
            val bytes = buffer.toByteArray()
            if (requestLine(bytes) != null) return bytes
        }
        return null
    }

    companion object {
        /** Scheme and host of the redirect that closes the sign-in window. */
        const val SESSION_CALLBACK_SCHEME = "tokenwatch"
        const val SESSION_CALLBACK_HOST = "login-complete"

        /** Upper bound on the listener's life, so an abandoned login never leaks the port. */
        private const val LIFETIME_MILLIS = 600_000L
        private const val SHUTDOWN_DELAY_MILLIS = 1_000L
        private const val READ_TIMEOUT_MILLIS = 15_000

        /** The callback is a few hundred bytes; anything larger is not for us. */
        private const val MAX_REQUEST_BYTES = 16 * 1_024
        private const val BACKLOG = 8
        private val IPV4_LOOPBACK = byteArrayOf(127, 0, 0, 1)
        private val IPV6_LOOPBACK = ByteArray(16).also { it[15] = 1 }

        /** The first line, once the header block has ended (`\r\n\r\n` or `\n\n`). */
        fun requestLine(buffer: ByteArray): String? {
            val text = buffer.toString(Charsets.ISO_8859_1)
            if ("\r\n\r\n" !in text && "\n\n" !in text) return null
            return text.lineSequence().firstOrNull()?.trimEnd('\r')
        }

        /** `GET /callback?code=… HTTP/1.1` → `/callback?code=…`; null for any other method. */
        fun target(requestLine: String): String? {
            val parts = requestLine.split(' ').filter(String::isNotEmpty)
            if (parts.size < 2 || parts[0] != "GET") return null
            return parts[1]
        }

        /** Code and state, only for the callback path with the expected state. */
        fun parseCallback(target: String, expectedState: String): OAuthCallback? {
            val url = "http://localhost$target".toHttpUrlOrNull() ?: return null
            if (url.encodedPath != "/callback") return null
            val code = url.queryParameter("code")?.takeIf(String::isNotEmpty) ?: return null
            val state = url.queryParameter("state") ?: return null
            if (state != expectedState) return null
            return OAuthCallback(code, state)
        }

        /** Redirect that closes the sign-in window, with code and state strictly percent-encoded. */
        fun redirectLocation(code: String, state: String): String =
            "$SESSION_CALLBACK_SCHEME://$SESSION_CALLBACK_HOST" +
                "?code=${percentEncodeStrict(code)}&state=${percentEncodeStrict(state)}"

        /** Code and state from the sign-in window's result URL; null for any other address. */
        fun parseSessionCallback(url: String): OAuthCallback? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != SESSION_CALLBACK_SCHEME || uri.host != SESSION_CALLBACK_HOST) return null
            val query = uri.rawQuery?.split('&').orEmpty().associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                decode(name) to decode(value)
            }
            val code = query["code"]?.takeIf(String::isNotEmpty) ?: return null
            val state = query["state"] ?: return null
            return OAuthCallback(code, state)
        }

        private fun decode(text: String): String =
            URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8.name())
    }
}

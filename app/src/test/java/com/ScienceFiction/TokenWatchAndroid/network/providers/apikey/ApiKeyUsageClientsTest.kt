package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyUsageClientsTest {
    private val tokens = OAuthTokens.apiKey("secret-key")

    @Test
    fun requestsAndSuccessMappingsMatchIosParityBaseline() {
        fixtures().forEach { fixture ->
            val overrideEndpoint = "https://fixture.invalid/${fixture.id}"
            val transport = FakeNetworkTransport(networkResponse(body = fixture.successBody))

            val windows = runBlocking {
                fixture.create(transport, overrideEndpoint).fetch(tokens)
            }

            val request = transport.requests.single()
            assertEquals("${fixture.id} method", "GET", request.method)
            assertEquals("${fixture.id} endpoint override", overrideEndpoint, request.url.toString())
            assertEquals("${fixture.id} accept", "application/json", request.header("Accept"))
            assertEquals("${fixture.id} user agent", "TokenWatch/1.0", request.header("User-Agent"))
            assertEquals(
                "${fixture.id} auth header",
                fixture.authHeaderValue,
                request.header(fixture.authHeaderName),
            )
            fixture.extraHeaders.forEach { (name, value) ->
                assertEquals("${fixture.id} $name", value, request.header(name))
            }
            assertEquals("${fixture.id} mapping", fixture.expectedWindows, windows)
        }
    }

    @Test
    fun defaultEndpointsMatchCleanIosBaseline() {
        assertEquals("https://api.elevenlabs.io/v1/user/subscription", ElevenLabsUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://openrouter.ai/api/v1/credits", OpenRouterUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.deepseek.com/user/balance", DeepSeekUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.poe.com/usage/current_balance", PoeUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.fal.ai/v1/account/billing?expand=credits", FalUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.stability.ai/v1/user/balance", StabilityUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://external.api.recraft.ai/v1/users/me", RecraftUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.lumalabs.ai/dream-machine/v1/credits", LumaUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.dev.runwayml.com/v1/organization", RunwayUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.d-id.com/credits", DIDUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://api.heygen.com/v2/user/remaining_quota", HeyGenUsageClient.DEFAULT_ENDPOINT)
        assertEquals("https://cloud.leonardo.ai/api/rest/v1/me", LeonardoUsageClient.DEFAULT_ENDPOINT)
    }

    @Test
    fun everyClientMaps401ToUnauthorized() {
        fixtures().forEach { fixture ->
            val transport = FakeNetworkTransport(networkResponse(statusCode = 401, body = "denied"))
            assertThrows("${fixture.id} 401", UsageException.Unauthorized::class.java) {
                runBlocking { fixture.create(transport, "https://fixture.invalid/${fixture.id}").fetch(tokens) }
            }
        }
    }

    @Test
    fun providersThatTreat403AsAuthenticationFailureMatchIos() {
        val unauthorizedOn403 = setOf("poe", "fal", "luma", "runway", "did", "heygen", "leonardo")
        fixtures().forEach { fixture ->
            val transport = FakeNetworkTransport(networkResponse(statusCode = 403, body = "forbidden"))
            val expected = if (fixture.id in unauthorizedOn403) {
                UsageException.Unauthorized::class.java
            } else {
                UsageException.Http::class.java
            }
            assertThrows("${fixture.id} 403", expected) {
                runBlocking { fixture.create(transport, "https://fixture.invalid/${fixture.id}").fetch(tokens) }
            }
        }
    }

    @Test
    fun everyClientMaps429AndRetryAfter() {
        fixtures().forEach { fixture ->
            val before = Instant.now()
            val transport = FakeNetworkTransport(
                networkResponse(
                    statusCode = 429,
                    headers = mapOf("retry-after" to listOf("120")),
                ),
            )
            val error = assertThrows("${fixture.id} 429", UsageException.RateLimited::class.java) {
                runBlocking { fixture.create(transport, "https://fixture.invalid/${fixture.id}").fetch(tokens) }
            }
            val retryAt = error.retryAfter
            assertTrue("${fixture.id} Retry-After", retryAt != null && !retryAt.isBefore(before.plusSeconds(119)) && !retryAt.isAfter(before.plusSeconds(125)))
        }
    }

    @Test
    fun everyClientPreservesUnexpectedHttpStatusAndBody() {
        fixtures().forEach { fixture ->
            val transport = FakeNetworkTransport(networkResponse(statusCode = 503, body = "maintenance"))
            val error = assertThrows("${fixture.id} 503", UsageException.Http::class.java) {
                runBlocking { fixture.create(transport, "https://fixture.invalid/${fixture.id}").fetch(tokens) }
            }
            assertEquals(503, error.statusCode)
            assertEquals("maintenance", error.responseBody)
        }
    }

    @Test
    fun malformedSuccessfulBodiesBecomeDecodeErrors() {
        fixtures().forEach { fixture ->
            val transport = FakeNetworkTransport(networkResponse(body = "{"))
            assertThrows("${fixture.id} malformed JSON", UsageException.Decode::class.java) {
                runBlocking { fixture.create(transport, "https://fixture.invalid/${fixture.id}").fetch(tokens) }
            }
        }
    }

    @Test
    fun optionalProviderPayloadsCanYieldNoWindows() {
        val emptyCases = listOf<ProviderUsageClient>(
            DeepSeekUsageClient(FakeNetworkTransport(networkResponse())),
            FalUsageClient(FakeNetworkTransport(networkResponse())),
            HeyGenUsageClient(FakeNetworkTransport(networkResponse())),
            LeonardoUsageClient(FakeNetworkTransport(networkResponse())),
        )
        emptyCases.forEach { client ->
            assertEquals(emptyList<UsageWindow>(), runBlocking { client.fetch(tokens) })
        }
    }

    private fun fixtures(): List<Fixture> = listOf(
        Fixture(
            id = "elevenlabs",
            authHeaderName = "xi-api-key",
            authHeaderValue = "secret-key",
            successBody = """{"character_count":250,"character_limit":1000,"next_character_count_reset_unix":2000000000}""",
            expectedWindows = listOf(
                UsageWindow(
                    label = "Monthly characters",
                    usedPercent = 25.0,
                    resetsAt = Instant.ofEpochSecond(2_000_000_000),
                    kind = WindowKind.WEEKLY,
                ),
            ),
            create = { transport, endpoint -> ElevenLabsUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "openrouter",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            successBody = """{"data":{"total_credits":10.5,"total_usage":3.0}}""",
            expectedWindows = listOf(
                balance("Credits", "7.50 credits left", remaining = 7.5, total = 10.5),
            ),
            create = { transport, endpoint -> OpenRouterUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "deepseek",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            successBody = """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"6.50"}]}""",
            expectedWindows = listOf(balance("Balance", "6.50 USD", remaining = 6.5)),
            create = { transport, endpoint -> DeepSeekUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "poe",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            successBody = """{"current_point_balance":1250000}""",
            expectedWindows = listOf(
                balance("Compute points", "1,250,000 pts", remaining = 1_250_000.0),
            ),
            create = { transport, endpoint -> PoeUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "fal",
            authHeaderName = "Authorization",
            authHeaderValue = "Key secret-key",
            successBody = """{"credits":{"current_balance":12.345,"currency":"USD"}}""",
            expectedWindows = listOf(balance("Balance", "12.35 USD", remaining = 12.345)),
            create = { transport, endpoint -> FalUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "stability",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            successBody = """{"credits":8.5}""",
            expectedWindows = listOf(balance("Credits", "8.50 credits", remaining = 8.5)),
            create = { transport, endpoint -> StabilityUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "recraft",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            successBody = """{"credits":42}""",
            expectedWindows = listOf(balance("Credits", "42 credits", remaining = 42.0)),
            create = { transport, endpoint -> RecraftUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "luma",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            successBody = """{"credit_balance":650}""",
            expectedWindows = listOf(balance("Balance", "6.50 USD", remaining = 6.5)),
            create = { transport, endpoint -> LumaUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "runway",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            extraHeaders = mapOf("X-Runway-Version" to "2024-11-06"),
            successBody = """{"creditBalance":12.6}""",
            expectedWindows = listOf(balance("Credits", "13 credits", remaining = 12.6)),
            create = { transport, endpoint -> RunwayUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "did",
            authHeaderName = "Authorization",
            authHeaderValue = "Basic secret-key",
            successBody = """{"credits":[{"remaining":4.6,"total":10}]}""",
            expectedWindows = listOf(
                balance("Credits", "5 credits", remaining = 4.6, total = 10.0),
            ),
            create = { transport, endpoint -> DIDUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "heygen",
            authHeaderName = "X-Api-Key",
            authHeaderValue = "secret-key",
            successBody = """{"data":{"remaining_quota":180}}""",
            expectedWindows = listOf(balance("Credits", "3 credits", remaining = 3.0)),
            create = { transport, endpoint -> HeyGenUsageClient(transport, endpoint) },
        ),
        Fixture(
            id = "leonardo",
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-key",
            successBody = """{"user_details":[{"subscriptionTokens":99,"apiSubscriptionTokens":10,"apiPaidTokens":3}]}""",
            expectedWindows = listOf(balance("API tokens", "13 tokens", remaining = 13.0)),
            create = { transport, endpoint -> LeonardoUsageClient(transport, endpoint) },
        ),
    )

    private fun balance(
        label: String,
        text: String,
        remaining: Double? = null,
        total: Double? = null,
    ) = UsageWindow(
        label = label,
        usedPercent = 0.0,
        resetsAt = null,
        kind = WindowKind.WEEKLY,
        style = UsageStyle.BALANCE,
        valueText = text,
        balanceRemaining = remaining,
        balanceTotal = total,
    )

    private data class Fixture(
        val id: String,
        val authHeaderName: String,
        val authHeaderValue: String,
        val extraHeaders: Map<String, String> = emptyMap(),
        val successBody: String,
        val expectedWindows: List<UsageWindow>,
        val create: (NetworkTransport, String) -> ProviderUsageClient,
    )
}

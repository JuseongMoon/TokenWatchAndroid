package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.squareup.moshi.Moshi
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import okhttp3.Request

internal object SubscriptionProviderJson {
    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    fun root(body: ByteArray): Map<String, Any?> {
        val decoded = adapter.fromJson(body.toString(Charsets.UTF_8))
        return decoded.asObject()
            ?: throw IllegalArgumentException("Expected a JSON object")
    }
}

internal suspend fun NetworkTransport.executeUsage(
    request: Request,
    forbiddenIsUnauthorized: Boolean,
): NetworkResponse {
    val response = execute(request)
    if (forbiddenIsUnauthorized && response.statusCode == 403) {
        throw UsageException.Unauthorized()
    }
    return response.requireUsageSuccess()
}

internal inline fun <T> decodeSubscriptionUsage(
    body: ByteArray,
    decode: (Map<String, Any?>) -> T,
): T = try {
    decode(SubscriptionProviderJson.root(body))
} catch (error: UsageException) {
    throw error
} catch (error: Exception) {
    throw UsageException.Decode(error.message ?: "Failed to decode provider usage", error)
}

internal fun Any?.asObject(): Map<String, Any?>? {
    val source = this as? Map<*, *> ?: return null
    val output = linkedMapOf<String, Any?>()
    for ((key, value) in source) {
        output[key as? String ?: return null] = value
    }
    return output
}

internal fun Any?.asArray(): List<Any?>? = this as? List<*>

internal fun Any?.asNumber(): Double? = (this as? Number)?.toDouble()

internal fun Any?.asNumberOrString(): Double? = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
}

internal fun Any?.asLong(): Long? {
    val number = this as? Number ?: return null
    val value = number.toDouble()
    if (!value.isFinite() || value % 1.0 != 0.0 || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
        return null
    }
    return value.toLong()
}

internal fun parseIsoInstant(value: String?): Instant? {
    if (value == null) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
}

internal fun String.capitalizedWords(locale: Locale = Locale.getDefault()): String =
    replace('_', ' ')
        .split(' ')
        .filter(String::isNotEmpty)
        .joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { character -> character.titlecase(locale) }
        }

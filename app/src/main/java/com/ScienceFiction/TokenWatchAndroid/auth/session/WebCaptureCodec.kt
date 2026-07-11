package com.ScienceFiction.TokenWatchAndroid.auth.session

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/** Pure decoding helpers for values returned by Android's browser APIs. */
internal object WebCaptureCodec {
    const val LOCAL_STORAGE_SCRIPT =
        "JSON.stringify(Object.assign({}, window.localStorage))"

    private val moshi = Moshi.Builder().build()
    private val javascriptStringAdapter = moshi.adapter(String::class.java)
    private val objectType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java,
    )
    private val objectAdapter = moshi.adapter<Map<String, Any?>>(objectType)

    /**
     * `evaluateJavascript` JSON-encodes its callback value. Because the script itself returns a
     * JSON string, localStorage arrives as a JSON string containing a second JSON document.
     */
    fun decodeLocalStorageEvaluation(result: String?): Map<String, String>? {
        if (result == null) return null
        val localStorageJson = runCatching {
            javascriptStringAdapter.fromJson(result)
        }.getOrNull() ?: return null
        val values = runCatching {
            objectAdapter.fromJson(localStorageJson)
        }.getOrNull() ?: return null
        return buildMap {
            values.forEach { (key, value) ->
                if (value is String) put(key, value)
            }
        }
    }

    /** Converts CookieManager's `name=value; ...` header into the shared browser-cookie shape. */
    fun decodeCookieHeader(header: String?, domain: String): List<BrowserCookie> {
        if (header.isNullOrBlank() || domain.isBlank()) return emptyList()
        return header.split(';').mapNotNull { segment ->
            val cookie = segment.trim()
            val separator = cookie.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = cookie.substring(0, separator).trim()
            if (name.isEmpty()) return@mapNotNull null
            BrowserCookie(
                name = name,
                value = cookie.substring(separator + 1).trim(),
                domain = domain.lowercase(),
            )
        }
    }
}

package com.omymaxz.download

import android.net.Uri

/**
 * Android/WebView adaptation of the GoPlay Extension 1.0.2 Declarative Net Request rules.
 *
 * Chrome's declarativeNetRequest rules cannot be installed in Android WebView. This helper
 * reproduces the extension's request-header behavior where WebView exposes request headers.
 * WebView cannot rewrite response headers through shouldInterceptRequest without proxying and
 * fully streaming the response, so the original extension's ACAO response-header mutations
 * are intentionally not emulated here.
 */
internal object GoPlayRequestRules {
    private const val TAG = "GoPlayRequestRules"

    data class HeaderOverrides(
        val headers: Map<String, String>,
        val ruleId: Int
    )

    private val viuOrigin = HeaderOverrides(
        headers = mapOf(
            "Referer" to "https://www.viu.com/",
            "Origin" to "https://www.viu.com"
        ),
        ruleId = 1
    )
    private val vikiOrigin = HeaderOverrides(
        headers = mapOf(
            "Referer" to "https://www.viki.com/",
            "Origin" to "https://www.viki.com"
        ),
        ruleId = 2
    )
    private val gagaOrigin = HeaderOverrides(
        headers = mapOf(
            "Referer" to "https://www.gagaoolala.com/",
            "Origin" to "https://www.gagaoolala.com"
        ),
        ruleId = 3
    )
    private val goPlayMarker = HeaderOverrides(
        headers = mapOf("X-Gp-Chrome-Ext" to "1.02"),
        ruleId = 4
    )

    /**
     * Returns the source extension's intended request-header modifications for a URL.
     * Rules 1-3 apply only to fetch/XHR-style calls (not top-level document loads).
     */
    fun overridesFor(url: String, isMainFrame: Boolean, method: String): HeaderOverrides? {
        val lower = url.lowercase()
        if (isMainFrame) {
            return if (matchesGoPlayMarker(lower)) goPlayMarker else null
        }

        // The extension applies these patterns to XMLHttpRequest resources (rule 1 and 3),
        // or XHR/image resources (rule 2). Android WebView doesn't expose resource type,
        // so limit the patterns to their original URL signatures.
        if (matchesViuResource(lower)) return viuOrigin
        if (matchesVikiResource(lower)) return vikiOrigin
        if (matchesGagaResource(lower)) return gagaOrigin
        if (matchesGoPlayMarker(lower)) return goPlayMarker
        return null
    }

    fun isSupportedHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "armsasuncion.com" || host.endsWith(".armsasuncion.com") ||
            host == "goplay.ml" || host.endsWith(".goplay.ml") ||
            host == "goplay.su" || host.endsWith(".goplay.su") ||
            host == "cloudfront.net" || host.endsWith(".cloudfront.net") ||
            host == "akamaized.net" || host.endsWith(".akamaized.net") ||
            host == "viu.com" || host.endsWith(".viu.com") ||
            host == "ottuat.com" || host.endsWith(".ottuat.com") ||
            host == "viki.io" || host.endsWith(".viki.io") ||
            host == "viki.net" || host.endsWith(".viki.net") ||
            host == "vikiplatform.com" || host.endsWith(".vikiplatform.com") ||
            host == "img.gagaoolala.com" || host == "media.gagaoolala.com" ||
            host == "gagaoolala.com" || host.endsWith(".gagaoolala.com")
    }

    private fun matchesViuResource(url: String): Boolean =
        url.contains("_layer") ||
            url.contains("odcp124") ||
            url.contains("d2anahhhmp1ffz") ||
            Regex("/vp6.*_v20").containsMatchIn(url) ||
            url.contains("rm/getke")

    private fun matchesVikiResource(url: String): Boolean =
        Regex("ik.*/dash/").containsMatchIn(url) ||
            url.contains("fest-vik")

    private fun matchesGagaResource(url: String): Boolean =
        Regex("/videos/.+/(video|audio)/.+/dash/.+?(mp4|m4s)").containsMatchIn(url)

    private fun matchesGoPlayMarker(url: String): Boolean =
        url.contains("goplay.") || url.contains(".armsasuncion.com")
}

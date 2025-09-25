package com.omymaxz.download

import android.content.SharedPreferences
import android.webkit.WebResourceRequest
import java.net.URL

class RedirectLogic(private val prefs: SharedPreferences) {

    private var lastNavigationTime = 0L
    private var navigationCount = 0

    private fun getHost(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return try {
            URL(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    fun shouldOverrideUrlLoading(request: WebResourceRequest?, webViewUrl: String?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (isUrlWhitelisted(url)) {
            return false
        }
        if (url.contains("perchance.org")) {
            return true
        }
        val currentTime = System.currentTimeMillis()
        if (isAdDomain(url) || isInBlockedList(url) || (!isUrlWhitelisted(url) && isSuspiciousRedirectPattern(url, currentTime, webViewUrl))) {
            return true
        }
        lastNavigationTime = currentTime
        navigationCount++
        return false
    }

    private fun isSuspiciousRedirectPattern(url: String, currentTime: Long, previousUrl: String?): Boolean {
        if (!prefs.getBoolean("BLOCK_REDIRECTS", true)) return false
        val timeSinceLastNav = currentTime - lastNavigationTime
        val currentHost = getHost(url)
        val previousHost = getHost(previousUrl)
        val isDifferentHost = currentHost != null && currentHost != previousHost
        return isDifferentHost && (timeSinceLastNav < 1000 && navigationCount > 0)
    }

    private fun isUrlWhitelisted(url: String): Boolean {
        val host = getHost(url) ?: return false
        val whitelist = prefs.getStringSet("WHITELIST_URLS", setOf()) ?: setOf()
        return whitelist.any { whitelistedDomain -> host == whitelistedDomain || host.endsWith(".$whitelistedDomain") }
    }

    private fun isAdDomain(url: String): Boolean {
        if (isUrlWhitelisted(url)) return false
        val host = getHost(url) ?: return false
        val suspiciousDomains = setOf(
            "googleads.com", "doubleclick.net", "googlesyndication.com",
            "facebook.com/tr", "amazon-adsystem.com", "adsystem.amazon.com",
            "outbrain.com", "taboola.com", "popads.net", "adnxs.com",
            "adsymptotic.com", "advertising.com", "adsystem.com",
            "profitableratecpm.com", "popunder.net", "pop-ads.com", "adcash.com",
            "propellerads.com", "revcontent.com", "mgid.com"
        )
        return suspiciousDomains.any { host.contains(it) }
    }

    private fun isInBlockedList(url: String): Boolean {
        val host = getHost(url) ?: return false
        val blockedUrls = prefs.getStringSet("BLOCKED_URLS", setOf()) ?: setOf()
        return blockedUrls.any { blockedHost -> host == blockedHost || host.endsWith(".$blockedHost") }
    }
}
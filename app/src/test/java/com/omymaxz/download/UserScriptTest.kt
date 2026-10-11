package com.omymaxz.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserScriptTest {
    private fun script(targetUrl: String = "https://example.com/*", matches: List<String> = emptyList(), excludes: List<String> = emptyList(), source: String = "console.log('test')") =
        UserScript(name = "Test script", script = source, targetUrl = targetUrl, matches = matches, excludes = excludes)

    @Test fun matchPatternSupportsExplicitHostAndPathWildcard() {
        val value = script()
        assertTrue(value.shouldRunOnUrl("https://example.com/watch/123"))
        assertFalse(value.shouldRunOnUrl("https://example.com.evil.test/watch/123"))
        assertFalse(value.shouldRunOnUrl("http://example.com/watch/123"))
    }

    @Test fun wildcardSubdomainMatchesRootAndNestedHost() {
        val value = script(targetUrl = "https://*.example.com/*")
        assertTrue(value.shouldRunOnUrl("https://example.com/"))
        assertTrue(value.shouldRunOnUrl("https://video.example.com/watch"))
        assertFalse(value.shouldRunOnUrl("https://example.com.evil.test/watch"))
    }

    @Test fun excludeTakesPrecedenceOverMatchingInclude() {
        val value = script(targetUrl = "*", matches = listOf("https://example.com/*"), excludes = listOf("https://example.com/private/*"))
        assertTrue(value.shouldRunOnUrl("https://example.com/public"))
        assertFalse(value.shouldRunOnUrl("https://example.com/private/account"))
    }

    @Test fun legacyHostSubstringTargetRemainsCompatible() {
        val value = script(targetUrl = "youtube.com")
        assertTrue(value.shouldRunOnUrl("https://www.youtube.com/watch?v=123"))
        assertFalse(value.shouldRunOnUrl("https://example.org/"))
    }

    @Test fun standardLineUserscriptHeaderParsesDirectives() {
        val value = script(source = """
            // ==UserScript==
            // @name Example script
            // @match https://example.com/*
            // @exclude https://example.com/private/*
            // @require https://cdn.example.com/lib.js
            // @grant GM_getValue
            // @grant GM_setValue
            // @run-at document-start
            // ==/UserScript==
            console.log('ready')
        """.trimIndent()).parseMetadata()
        assertTrue(value.name == "Example script")
        assertTrue(value.matches.contains("https://example.com/*"))
        assertTrue(value.excludes.contains("https://example.com/private/*"))
        assertTrue(value.requires.contains("https://cdn.example.com/lib.js"))
        assertTrue(value.grants.contains("GM_getValue"))
        assertTrue(value.grants.contains("GM_setValue"))
        assertTrue(value.runAt == UserScript.RunAt.DOCUMENT_START)
    }
}

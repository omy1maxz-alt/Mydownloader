package com.omymaxz.download

import android.content.SharedPreferences
import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RedirectLogicTest {

    private lateinit var redirectLogic: RedirectLogic
    private lateinit var mockPrefs: SharedPreferences

    @Before
    fun setUp() {
        mockPrefs = mock()
        redirectLogic = RedirectLogic(mockPrefs)
    }

    private fun mockRequest(url: String): WebResourceRequest {
        val mockUri: Uri = mock {
            on { toString() } doReturn url
        }
        return mock {
            on { getUrl() } doReturn mockUri
        }
    }

    @Test
    fun `shouldOverrideUrlLoading returns false for whitelisted URL`() {
        // Given
        val whitelistedUrl = "https://safe.example.com"
        whenever(mockPrefs.getStringSet("WHITELIST_URLS", setOf())).thenReturn(setOf("safe.example.com"))
        val request = mockRequest(whitelistedUrl)

        // When
        val result = redirectLogic.shouldOverrideUrlLoading(request, "https://initial.com")

        // Then
        assertFalse("Whitelisted URL should not be overridden", result)
    }

    @Test
    fun `shouldOverrideUrlLoading returns true for ad domain`() {
        // Given
        val adUrl = "https://googleads.g.doubleclick.net/pagead/ads"
        whenever(mockPrefs.getStringSet("WHITELIST_URLS", setOf())).thenReturn(setOf())
        val request = mockRequest(adUrl)

        // When
        val result = redirectLogic.shouldOverrideUrlLoading(request, "https://some-site.com")

        // Then
        assertTrue("Ad domain should be overridden", result)
    }

    @Test
    fun `shouldOverrideUrlLoading returns true for suspicious redirect`() {
        // Given
        val initialUrl = "https://initial.com"
        val redirectUrl = "https://suspicious-redirect.com"
        whenever(mockPrefs.getBoolean("BLOCK_REDIRECTS", true)).thenReturn(true)
        whenever(mockPrefs.getStringSet("WHITELIST_URLS", setOf())).thenReturn(setOf())
        whenever(mockPrefs.getStringSet("BLOCKED_URLS", setOf())).thenReturn(setOf())

        val request1 = mockRequest(initialUrl)
        val request2 = mockRequest(redirectUrl)

        // When
        redirectLogic.shouldOverrideUrlLoading(request1, null)
        val result = redirectLogic.shouldOverrideUrlLoading(request2, initialUrl)

        // Then
        assertTrue("Suspicious redirect should be overridden", result)
    }

    @Test
    fun `shouldOverrideUrlLoading returns false for normal navigation`() {
        // Given
        val initialUrl = "https://initial.com"
        val nextUrl = "https://initial.com/page2"
        whenever(mockPrefs.getBoolean("BLOCK_REDIRECTS", true)).thenReturn(true)
        whenever(mockPrefs.getStringSet("WHITELIST_URLS", setOf())).thenReturn(setOf())
        whenever(mockPrefs.getStringSet("BLOCKED_URLS", setOf())).thenReturn(setOf())
        val request1 = mockRequest(initialUrl)
        val request2 = mockRequest(nextUrl)

        // When
        redirectLogic.shouldOverrideUrlLoading(request1, null)
        Thread.sleep(1100)
        val result = redirectLogic.shouldOverrideUrlLoading(request2, initialUrl)

        // Then
        assertFalse("Normal navigation should not be overridden", result)
    }
}
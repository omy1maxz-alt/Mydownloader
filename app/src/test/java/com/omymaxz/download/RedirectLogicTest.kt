package com.omymaxz.download

import android.content.SharedPreferences
import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class RedirectLogicTest {

    private lateinit var mockActivity: MainActivity
    private lateinit var webViewClient: MyWebViewClient
    private lateinit var mockPrefs: SharedPreferences

    @Before
    fun setUp() {
        mockPrefs = mock {
            on { getBoolean("BLOCK_REDIRECTS", true) } doReturn true
            on { getStringSet("BLOCKED_URLS", setOf()) } doReturn setOf()
            on { getStringSet("WHITELIST_URLS", setOf()) } doReturn setOf()
        }
        mockActivity = mock {
            on { getSharedPreferences("AdBlocker", 0) } doReturn mockPrefs
        }
        webViewClient = MyWebViewClient(mockActivity)
    }

    @Test
    fun `shouldOverrideUrlLoading should return true for suspicious redirect`() {
        // Given
        // First navigation
        webViewClient.shouldOverrideUrlLoading(null, mockRequest("https://first.com"))

        // When
        // A second, rapid navigation to a different host
        val result = webViewClient.shouldOverrideUrlLoading(null, mockRequest("https://second.com"))

        // Then
        // The navigation should be blocked (return true)
        assertTrue("Second rapid navigation should be blocked as a suspicious redirect", result)
    }

    private fun mockRequest(url: String): WebResourceRequest {
        return mock {
            on { getUrl() } doReturn Uri.parse(url)
        }
    }
}

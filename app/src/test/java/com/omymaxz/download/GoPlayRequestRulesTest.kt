package com.omymaxz.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoPlayRequestRulesTest {
    @Test
    fun addsGoPlayMarkerForMainFrameAndRequestUrls() {
        assertEquals(
            "1.02",
            GoPlayRequestRules.overridesFor("https://www.goplay.su/watch/1", true, "GET")
                ?.headers?.get("X-Gp-Chrome-Ext")
        )
        assertEquals(
            "1.02",
            GoPlayRequestRules.overridesFor("https://edge.armsasuncion.com/api", false, "GET")
                ?.headers?.get("X-Gp-Chrome-Ext")
        )
    }

    @Test
    fun appliesViuRefererToKnownViuRuleSignatures() {
        val override = GoPlayRequestRules.overridesFor(
            "https://cdn.example/abc/_Layer/data", false, "GET"
        )
        assertEquals(1, override?.ruleId)
        assertEquals("https://www.viu.com/", override?.headers?.get("Referer"))
        assertEquals("https://www.viu.com", override?.headers?.get("Origin"))
    }

    @Test
    fun appliesVikiRefererToDashSegments() {
        val override = GoPlayRequestRules.overridesFor(
            "https://ik.example/dash/manifest.mpd", false, "GET"
        )
        assertEquals(2, override?.ruleId)
        assertEquals("https://www.viki.com/", override?.headers?.get("Referer"))
    }

    @Test
    fun appliesGagaRefererToDashMediaSegments() {
        val override = GoPlayRequestRules.overridesFor(
            "https://media.example/videos/abc/video/1/dash/chunk.m4s", false, "GET"
        )
        assertEquals(3, override?.ruleId)
        assertEquals("https://www.gagaoolala.com", override?.headers?.get("Origin"))
    }

    @Test
    fun ignoresUnmatchedUrls() {
        assertNull(GoPlayRequestRules.overridesFor("https://example.com/app.js", false, "GET"))
        assertTrue(GoPlayRequestRules.isSupportedHost("https://cdn.goplay.su/video.m3u8"))
    }
}

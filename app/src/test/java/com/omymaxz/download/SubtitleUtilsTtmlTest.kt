package com.omymaxz.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SubtitleUtilsTtmlTest {
    @Test
    fun extractsCaptionTextFromYoutubeTtml() {
        val ttml = """<?xml version="1.0" encoding="utf-8" ?>
            <tt xml:lang="en" xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:11.063" end="00:00:13.263">Two Three H2H!</p>
                <p begin="00:00:14.981" end="00:00:17.059">General audiences<br />welcome</p>
              </div></body>
            </tt>"""

        val result = SubtitleUtils.extractSnippet(ttml)

        assertEquals("en", result.language)
        assertNotNull(result.snippet)
        assertEquals("Two Three H2H! General audiences welcome", result.snippet)
    }
}

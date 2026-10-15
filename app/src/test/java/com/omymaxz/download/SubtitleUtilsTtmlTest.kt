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
        val normalized = SubtitleUtils.normalizeForDownload(ttml)

        assertEquals(
            "WEBVTT\n\n00:00:11.063 --> 00:00:13.263\nTwo Three H2H!\n\n00:00:14.981 --> 00:00:17.059\nGeneral audiences\nwelcome\n",
            normalized
        )
        assertEquals("en", result.language)
        assertNotNull(result.snippet)
        assertEquals("Two Three H2H! General audiences welcome", result.snippet)
    }

    @Test
    fun removesYoutubeSpeakerPrefixFromVttCuesOnly() {
        val vtt = """WEBVTT

00:00:00.000 --> 00:00:02.000
>> Hello there.

00:00:02.000 --> 00:00:04.000
>> Yes, that's right.
A line with >> in the middle.
"""
        assertEquals(
            """WEBVTT

00:00:00.000 --> 00:00:02.000
Hello there.

00:00:02.000 --> 00:00:04.000
Yes, that's right.
A line with >> in the middle.
""",
            SubtitleUtils.normalizeForDownload(vtt)
        )
    }

    @Test
    fun removesYoutubeSpeakerPrefixFromConvertedTtml() {
        val ttml = """<tt xml:lang="en"><body><div>
            <p begin="00:00:00.000" end="00:00:02.000">&gt;&gt; Hello there.</p>
            <p begin="00:00:02.000" end="00:00:04.000">>> Yes!</p>
        </div></body></tt>"""
        assertEquals(
            "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nHello there.\n\n00:00:02.000 --> 00:00:04.000\nYes!\n",
            SubtitleUtils.normalizeForDownload(ttml)
        )
    }

    @Test
    fun convertsLegacyYoutubeTranscriptXmlForTheEntireTimeline() {
        val xml = """<transcript>
            <text start="1.25" dur="2.5">First caption</text>
            <text start="372.5" dur="3.0">Caption after six minutes</text>
        </transcript>"""

        val normalized = SubtitleUtils.normalizeForDownload(xml)

        assertEquals(true, normalized.startsWith("WEBVTT\n\n"))
        assertEquals(true, normalized.contains("00:00:01.250 --> 00:00:03.750\nFirst caption"))
        assertEquals(true, normalized.contains("00:06:12.500 --> 00:06:15.500\nCaption after six minutes"))
        assertEquals(2, Regex("-->").findAll(normalized).count())
    }

    @Test
    fun convertsYoutubeTtmlMillisecondTickAttributes() {
        val xml = """<tt><body><div>
            <p t="1000" d="2500">First</p>
            <p t="360500" d="1500">Later</p>
        </div></body></tt>"""

        val normalized = SubtitleUtils.normalizeForDownload(xml)

        assertEquals(true, normalized.contains("00:00:01.000 --> 00:00:03.500\nFirst"))
        assertEquals(true, normalized.contains("00:06:00.500 --> 00:06:02.000\nLater"))
        assertEquals(2, Regex("-->").findAll(normalized).count())
    }


    @Test
    fun languageMetadataMustBeRecognizedAndSnippetIsNotLanguage() {
        assertEquals("en", SubtitleUtils.normalizeLanguageCode("English"))
        assertEquals("th", SubtitleUtils.normalizeLanguageCode("th"))
        assertEquals(null, SubtitleUtils.normalizeLanguageCode("The subtitle starts here"))
        val vtt = """WEBVTT
Language: The subtitle starts here

00:00:01.000 --> 00:00:03.000
The subtitle starts here.
"""
        val result = SubtitleUtils.extractSnippet(vtt)
        assertEquals(null, result.language)
        assertEquals("The subtitle starts here.", result.snippet)
    }

    @Test
    fun canonicalUrlRemovesFragmentButPreservesSignedAndLanguageQueries() {
        val url = "https://cdn.example/sub.vtt?token=secret-value&lang=th&tlang=en#player"
        assertEquals("https://cdn.example/sub.vtt?token=secret-value&lang=th&tlang=en", SubtitleUtils.canonicalMediaUrl(url))
        assertEquals(SubtitleUtils.canonicalMediaUrl(url), SubtitleUtils.canonicalMediaUrl(url.substringBefore("#")))
        assertEquals(false, SubtitleUtils.canonicalMediaUrl(url) == SubtitleUtils.canonicalMediaUrl(url.replace("lang=th", "lang=en")))
        assertEquals(false, SubtitleUtils.canonicalMediaUrl(url) == SubtitleUtils.canonicalMediaUrl(url.replace("secret-value", "other-token")))
    }

    @Test
    fun originalSubtitleFilenameComesFromUrlPath() {
        assertEquals("The_Vanishing_Beauty__kisskh.vtt", SubtitleUtils.originalSubtitleFilename("https://sub.cdn.example/The_Vanishing_Beauty__kisskh.vtt?token=x#track"))
        assertEquals(null, SubtitleUtils.originalSubtitleFilename("https://www.youtube.com/api/timedtext?lang=th"))
    }

    @Test
    fun subtitlePayloadValidationRejectsHtmlButAcceptsVttAndTtml() {
        assertEquals(false, SubtitleUtils.isValidSubtitleContent("<html><body>Forbidden</body></html>"))
        assertEquals(true, SubtitleUtils.isValidSubtitleContent("""WEBVTT

00:00:01.000 --> 00:00:03.000
Hello.
"""))
        assertEquals(true, SubtitleUtils.isValidSubtitleContent("""<tt><body><div><p begin="00:00:01.000" end="00:00:03.000">Hello.</p></div></body></tt>"""))
    }


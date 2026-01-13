package com.omymaxz.download

import android.webkit.WebView
import com.google.gson.Gson

object YouTubeHelper {

    // JavaScript to extract video data from ytInitialPlayerResponse
    const val EXTRACTION_SCRIPT = """
        (function() {
            try {
                var playerResponse = window.ytInitialPlayerResponse;

                // If not found in window, try to find it in the DOM script tags (common on mobile)
                if (!playerResponse) {
                     var scripts = document.querySelectorAll('script');
                     for (var i = 0; i < scripts.length; i++) {
                         if (scripts[i].textContent.includes('var ytInitialPlayerResponse =')) {
                             var content = scripts[i].textContent;
                             var start = content.indexOf('var ytInitialPlayerResponse =') + 30;
                             var end = content.indexOf('};', start) + 1;
                             try {
                                 playerResponse = JSON.parse(content.substring(start, end));
                                 break;
                             } catch(e) {}
                         }
                     }
                }

                if (!playerResponse) {
                    YouTubeInterface.onError("Could not find video data.");
                    return;
                }

                var videoDetails = playerResponse.videoDetails;
                var streamingData = playerResponse.streamingData;

                if (!streamingData) {
                     YouTubeInterface.onError("No streaming data found.");
                     return;
                }

                var rawFormats = [];
                if (streamingData.formats) {
                    rawFormats = rawFormats.concat(streamingData.formats);
                }
                if (streamingData.adaptiveFormats) {
                    rawFormats = rawFormats.concat(streamingData.adaptiveFormats);
                }

                if (rawFormats.length === 0) {
                     YouTubeInterface.onError("No streams found.");
                     return;
                }

                var formats = rawFormats.map(function(f) {
                    return {
                        itag: f.itag,
                        url: f.url,
                        mimeType: f.mimeType,
                        qualityLabel: f.qualityLabel || "", // Adaptive audio might not have qualityLabel
                        width: f.width,
                        height: f.height,
                        contentLength: f.contentLength
                    };
                }).filter(function(f) {
                    // Filter for valid URLs (skipping ciphered signatures for now as requested)
                    // We allow all mimeTypes (video/mp4, video/webm, audio/mp4, etc.)
                    return f.url && f.url.startsWith('http');
                });

                if (formats.length === 0) {
                     YouTubeInterface.onError("No downloadable streams found (all might be encrypted).");
                     return;
                }

                var result = {
                    title: videoDetails.title,
                    formats: formats
                };

                YouTubeInterface.onVideoData(JSON.stringify(result));

            } catch(e) {
                YouTubeInterface.onError("Extraction error: " + e.toString());
            }
        })();
    """

    fun parseVideoData(json: String): YouTubeVideo? {
        return try {
            Gson().fromJson(json, YouTubeVideo::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

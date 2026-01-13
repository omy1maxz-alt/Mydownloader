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

                if (!streamingData || !streamingData.formats) {
                     YouTubeInterface.onError("No progressive streams found.");
                     return;
                }

                var formats = streamingData.formats.map(function(f) {
                    return {
                        itag: f.itag,
                        url: f.url,
                        mimeType: f.mimeType,
                        qualityLabel: f.qualityLabel,
                        width: f.width,
                        height: f.height,
                        contentLength: f.contentLength
                    };
                }).filter(function(f) {
                    // Filter for MP4 and valid URLs
                    return f.url && f.mimeType && f.mimeType.includes('mp4');
                });

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

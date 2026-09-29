# Subtitle Regression Investigation: PR #285 vs Master

## 1. #285 Subtitle Implementation
*   **FACT:** In PR #285, `CustomPlayerActivity.kt` explicitly populated a `subtitleConfigs` mutable list based on local files.
*   **FACT:** PR #285 unconditionally constructed `MediaItem.SubtitleConfiguration` instances and appended them to the base `MediaItem.Builder()` using `.setSubtitleConfigurations(subtitleConfigs)`.
*   **FACT:** Crucially, PR #285 initialized the ExoPlayer instance by calling `p.setMediaItem(newBaseItem)` directly for **both** HLS and Progressive streams. It did **not** manually construct an `HlsMediaSource.Factory`.

## 2. Current Master Subtitle Implementation
*   **FACT:** Current master also populates `subtitleConfigs` and attaches them to `newBaseItem` via `.setSubtitleConfigurations(subtitleConfigs)`.
*   **FACT:** However, current master contains an explicit `HlsMediaSource.Factory` initialization path for HLS streams:
    ```kotlin
    if (isHls && actualMimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
        val hlsMediaSource = HlsMediaSource.Factory(cacheFactory)
            .setExtractorFactory(hlsExtractorFactory)
            .setAllowChunklessPreparation(false)
            .setLoadErrorHandlingPolicy(CustomRetryPolicy())
            .createMediaSource(newBaseItem)
        p.setMediaSource(hlsMediaSource)
    } else {
        p.setMediaItem(newBaseItem)
    }
    ```
*   **FACT:** Current master also introduces a fallback dummy subtitle config (`emptySubtitleConfig`) that uses a `data:text/vtt` URI.

## 3. First Meaningful Divergence
*   **OBSERVATION:** The first meaningful divergence in the core subtitle pipeline between #285 and master is the explicit bypassing of `DefaultMediaSourceFactory` via the direct instantiation of `HlsMediaSource.Factory(cacheFactory).createMediaSource(newBaseItem)`.
*   **OBSERVATION:** The second divergence is the injection of the dummy `emptySubtitleConfig`.

## 4. Evidence Explaining the Divergence Cause
*   **HYPOTHESIS:** The direct use of `HlsMediaSource.Factory` drops the side-loaded `MediaItem.SubtitleConfiguration`.
*   **EVIDENCE:** According to ExoPlayer/Media3 architectural design, an `HlsMediaSource` is exclusively responsible for parsing the `.m3u8` manifest and exposing tracks natively declared within that manifest. It does not automatically inspect the `MediaItem`'s `subtitleConfigurations` list to perform a track merger.
*   **EVIDENCE:** In PR #285, `setMediaItem()` was used. Under the hood, `ExoPlayer.setMediaItem()` invokes `DefaultMediaSourceFactory`. The `DefaultMediaSourceFactory` internally detects the `subtitleConfigurations` inside the `MediaItem` and wraps the core source (e.g., the `HlsMediaSource`) together with `SingleSampleMediaSource` instances (the subtitles) using a `MergingMediaSource`.
*   **CONCLUSION:** By explicitly overriding `setMediaItem` with a manual `HlsMediaSource.Factory`, the current master branch explicitly bypassed the `MergingMediaSource` composition logic provided by `DefaultMediaSourceFactory`, causing the player to literally drop the subtitle objects before they ever reached the renderer.

## 5. Other Hypotheses and Missing Evidence
*   **HYPOTHESIS 2:** The `emptySubtitleConfig` (the `data:text/vtt` fallback) corrupts the text track renderer.
    *   *Missing Evidence:* While injecting a dummy track could confuse track selection parameters or cause a parser crash (if `data:` URIs are restricted), it would only affect cases where no local subtitles are present (since it's guarded by `if (subtitleConfigs.isEmpty())`). In this bug report, a valid `.srt` *is* detected, so the dummy config block is skipped entirely. Therefore, Hypothesis 1 is the primary culprit for *this* specific failure, though Hypothesis 2 is a distinct architectural bug when no subtitles are present.

## 6. Smallest Recommended Fix (Do Not Implement)
1.  **For HLS streams:** Remove the direct `HlsMediaSource.Factory` usage and revert to using `DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory).createMediaSource(newBaseItem)`. This safely preserves the custom data source (caching, interceptors) while restoring the `MergingMediaSource` wrapper for side-loaded subtitles.
2.  **For the Dummy Config:** Remove the `emptySubtitleConfig` block entirely. ExoPlayer does not require a text track to be present to function.

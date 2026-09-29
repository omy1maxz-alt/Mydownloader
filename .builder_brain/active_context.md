# The Builder's Anchor (Active Context)

This file is the absolute source of truth for the Builder. When conversation history gets long, refer to this file to prevent hallucinations, regressions, or forgetting how a specific issue was fixed.

## 1. Current Focus
- Maintain strict adherence to the Builder persona (no conversational padding, clear decisions).
- Ensure HLS exporting, ExoPlayer caching, and UI scaling do not regress.
- Use this file as the primary anchor for context retrieval.

## 2. Completed Milestones (Do not ask to build these, they are done)
- Configured media detection hooks for standard web video sources.
- Built a custom overflow bottom sheet menu replacing the standard Toolbar popup.
- Integrated offline playback capabilities by caching segments via `HlsDownloadService`.
- Re-routed standard M3U8 exports to utilize the native Media3 Transformer.
- Patched FFmpeg network fallback to manually fetch correct variant urls.
- Expanded media list detection to include WebVTT and SRT subtitle files.
- Built modern URL stretching animations on focus.
- Overhauled base64 hidden iframe decoding.

## 3. Crucial Technical Constraints & How Things Were Fixed (NEVER REVERT THESE)

### Caching and Player Memory
- **Unified Cache:** To clear an ExoPlayer `SimpleCache` efficiently and avoid an ANR, explicitly call `cache.release()`, nullify the reference, and then use `deleteRecursively()` on the cache directory within a background thread (`Dispatchers.IO`).
- **Cache Keys:** `HlsDownloadHelper.customCacheKeyFactory` strips queries and fragments from URIs before caching to prevent session tokens from fragmenting the video cache.
- **Track Indexing:** When creating `StreamKey`s from `player.currentTracks.groups`, use the standard loop index (`groupIndex`) from `forEachIndexed`. Do not use `group.mediaTrackGroupIndex` (it causes compilation failures).

### Video Export (Transformer & FFmpeg)
- **Transformer Remuxing:** To prevent audio-only (blank screen) exports when using Media3 Transformer on cached HLS streams, build the `MediaItem` directly from the master playlist URL with its corresponding `StreamKeys`. DO NOT resolve the master URL to a variant URL before passing it to Transformer. DO NOT force `.setVideoMimeType` or `.setAudioMimeType` on the `Transformer.Builder`.
- **FFmpeg Execution:** To prevent command injection vulnerabilities when executing FFmpeg commands via FFmpegKit, build a `MutableList<String>` of arguments and pass them directly to `executeWithArguments()`.
- **FFmpeg Obfuscation Bypass:** Append `-allowed_extensions ALL` to the FFmpeg command line to bypass the demuxer's strict extension whitelist (fixes `.ts` chunks hidden as `.PNG`).
- **MP4 Cache Export:** Bypass the M3U8 parser, use `copyMp4FromCache`, and ensure the `DataSpec` is built using the exact custom cache key via `HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(...)`.

### WebViews & Layouts
- **Google Sign-In Crash:** Dynamically created popup WebViews must be completely removed from their parent views and destroyed using a delayed post on the main looper (`Handler(Looper.getMainLooper()).postDelayed({ newWebView.destroy() }, 500)`) in `onCloseWindow` to prevent memory leaks and `cr_AwContents` crashes.
- **Fullscreen Exit Shrinking:** To prevent persistent layout shrinking after exiting immersive fullscreen web media, explicitly reset the absolute layout root's `LayoutParams`, margins, and padding during `onHideCustomView`.
- **Display Cutouts:** Allow `CustomPlayerActivity` to stretch a video into physical display cutouts by setting `window.attributes.layoutInDisplayCutoutMode` to `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`.

### Network & Security
- **403 Forbidden Errors:** `HlsDownloadHelper` HTTP methods strictly require `userAgent`, `referer`, and `cookie` parameters.
- **IPC Intent Crashes:** Never pass a Media3 `MediaItem` via Intent using `.toBundle()` and `fromBundle()` to a Service across IPC. Reconstruct the `MediaItem` directly inside the receiving Service using primitive strings.
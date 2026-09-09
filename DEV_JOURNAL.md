# DEV_JOURNAL - The Absolute Source of Truth

*This file tracks all major edits, feature additions, architectural pivots, and bug fixes. The Builder MUST append a single line here after any significant task is completed to maintain historical memory.*

### Project History & Decisions

- Re-established the 4 Heads of the Builder persona guidelines in AGENTS.md.
- Adjusted WebView and RecyclerView top padding to dynamically offset below the 48dp transparent toolbar, resolving UI overlap issues.
- Added GlassyPopupMenu style and applied it to Toolbar to make the 3-dot overflow menu transparent/glossy.
- Re-applied setVideoMimeType and setAudioMimeType explicitly to Media3 Transformer exports to fix 'blank video' bug.
- Fixed audio-only blank screen exports in HlsExportService by sending the exact MediaItem (master URL + StreamKeys) via Bundle from CustomPlayerActivity, preventing Transformer track resolution failures.
- Fixed Transformer muxer crash in HlsExportService by stripping out custom FormatSuppressing wrappers and injecting DefaultMediaSourceFactory directly into DefaultAssetLoaderFactory to guarantee native HlsMediaPeriod generation.
- Enhanced `AGENTS.md` to strictly enforce Builder Mode, anti-slop rules, and mandatory updates to this journal file.
- Replaced standard Toolbar overflow menu with a custom ListPopupWindow to dynamically adapt background and text colors to the current theme color without reflection hacks.
[Friday, 04 September 2026 | 17:36]
- Refactored main layout to FrameLayout to allow Toolbar to overlap WebView, and injected CSS padding to ensure content starts below the toolbar but scrolls underneath its transparency.
[Saturday, 05 September 2026 | 02:58]
- Shifted standard media sniffing (variable scanning, iframe query extraction) to run automatically on page load.
- Repurposed the overflow menu option into 'Advanced Media Sniffer' which executes a heavy-duty DOM, window variable, and network interception (fetch/XHR override) script on demand.
[Sunday, 06 September 2026 | 15:19]
- Switched main toolbar overflow menu from a floating `ListPopupWindow` to a Material `BottomSheetDialog` (Soul Browser style). Includes custom rounded-corner background logic that dynamically respects the user's `glossy_theme_color` preference without losing corners.
- Suppressed the 'No media detected' and 'No new media found' Toasts from the automatic media scanner callback in MainActivity to prevent spamming the user on every page load.
- Replaced the full-screen web pop-up wrapper in MainActivity's onCreateWindow with a minimal BottomSheetDialog notification box. It hides the ad/pop-up content and allows the user to manually close or view it without disrupting the main browsing experience.
- Refactored all AlertDialogs across the application to dynamically follow the user's `glossy_theme_color` preference using a new `createThemedDialogBuilder` extension, complete with adaptive text luminance calculations to ensure contrast.
- Prevented the Detected Media List from automatically sliding up into view on page loads. It is now strictly user-initiated via the Floating Action Button.
[Sunday, 06 September 2026 | 19:14]
- Updated the BottomSheetDialog base theme (`TransparentBottomSheetDialogTheme`) to strip its default opaque white backdrop, ensuring that the dynamically applied `glossy_theme_color` (including any transparency) renders fully transparently without washing out or blocking the UI beneath it.
- Re-enabled the manual showing of the Detected Media List after advanced sniffing and YouTube interception. Introduced an `isManualScanPending` boolean to distinguish between automatic (silent) on-page-load scans and user-initiated scans via the FAB.
- Fixed Media3 Transformer "asset loaded error" during HLS export by removing explicit `.setVideoMimeType` and `.setAudioMimeType` overrides, restoring its native ability to remux supported cached codecs rather than forcing a hardware-dependent transcode that crashes on unsupported formats.
- Rewired `HlsExportService.kt` to catch Transformer `ExportException`s gracefully and trigger an automatic fallback to the FFmpeg download method, ensuring heavily obfuscated streams that defeat the Media3 AssetLoader can still be exported.
- Implemented `muxToMp4FromCache` to allow FFmpeg to remux HLS videos directly from the local ExoPlayer Cache (`CacheDataSource`). This resolves the issue where Media3 Transformer fails on discontinuity formats (mid-stream ads), and prevents FFmpeg from wastefully re-downloading the entire video over the network.
- Added heuristic Ad-Stripping to the `muxToMp4FromCache` export path by parsing `#EXT-X-DISCONTINUITY` boundaries and filtering out short segment blocks (<90 seconds).
- Fixed Media3 Transformer "audio only" export bugs (e.g., from KissKH) by discarding numeric `streamKeys` when rebuilding the `MediaItem` in `HlsExportService.kt`. Re-parsing live network playlists caused group index drift (due to ad insertions/dubs), resulting in incorrect track selections. Stripping the keys allows the `AssetLoader` to correctly fallback to resolving tracks by MIME type dynamically.
- Completely bypassed Media3 Transformer for fully cached HLS exports, opting to exclusively use the new `muxToMp4FromCache` FFmpeg method. This guarantees the exported video exactly matches what the ExoPlayer cached locally (circumventing track-selection bugs and network playlist drift) and avoids the audio-only glitches present in Transformer when dealing with re-ordered HLS playlists.
- Fixed URL resolution in `muxToMp4FromCache` that caused cache misses (and fallback to network FFmpeg) for protocol-relative segment URLs (e.g., `//hls19.site/seg.ts`). Replaced manual substring appending with standard `java.net.URI.resolve()` to correctly construct the absolute segment URLs to match the CacheDataSource keys.
- Enhanced the `LogcatViewerActivity` to automatically prepend the contents of `export_logs.txt` and filter system logs to include `MediaCodec`, `ExoPlayer`, and `Transformer` tags. This provides a unified, comprehensive view of export pipeline states directly within the app settings.
- Rewrote the `muxToMp4FromCache` FFmpeg fallback to support fully local fMP4 maps, DRM keys, and split audio/video HLS playlists. It now generates an explicit audio playlist and merges them using `-map 0:v:0 -map 1:a:0` internally, fixing silent-video cache exports on providers like KissKH.
- Forced `muxToMp4FromCache` to strict CacheOnly reads for video segments (falling back to network ONLY for init/key chunks) to guarantee zero-quota redundant downloading.
- Updated `LogcatViewerActivity` and FFmpeg failure logging to capture and dump STDERR log tails inside the `export_logs.txt` trace, providing accurate diagnostics instead of a generic "rc=1".
- Normalized file extensions for `EXT-X-MAP` (init) segments during cached HLS export to prevent FFmpeg local-playlist security restrictions from rejecting `.PNG` and other fake image extensions used in obfuscated streams.
- Normalized data segment extensions correctly based on stream type (`.m4s` if `#EXT-X-MAP` is present, otherwise `.ts`) rather than forcing `.ts` blindly. This resolves FFmpeg failing with "Stream map matches no streams" on split fMP4 playlists (e.g. Heart Signal on KissKH) where forcing `.ts` on ISO-BMFF data caused the HLS demuxer to skip tracking headers.
- Implemented Media3's native `HlsPlaylistParser` inside `muxToMp4FromCache` to accurately extract video and audio variant URLs based on ExoPlayer's `StreamKeys`. This definitively fixes "Stream map matches no streams" errors in FFmpeg cache exports on KissKH, resolving the issue where manual string parsing incorrectly assumed group 0 was always video and mapped the audio rendition URL to the video playlist generator.
- Fixed a critical file overwriting bug in `muxToMp4FromCache` where the initialization segment (`init_0.mp4`) for the audio playlist overwrote the init segment for the video playlist, causing FFmpeg to mistakenly identify the video stream as audio-only and fail with "Stream map '0:v:0' matches no streams". The init and key segments are now prefixed with their respective playlist names to ensure uniqueness.
appsummary.md
Fix Chinese site custom player error, mark media item.
Fix missing scroll offset padding on chinese kisskh site.
- Corrected CSS injection for kisskh.co to properly shift page layout using `margin-top` instead of `padding-top` to account for fixed headers, and implemented `isMainVideoContent` safely to mark actively playing media items.
- Refactored kissing site CSS injection to correctly target only the kisskh domain and applied translateY for headers.
- Fixed `currentVideoUrl` not being updated dynamically by emitting `onVideoFound` directly from JS state tracker.
- Refactored MediaFile data class `isMainContent` from `val` to `var` to allow dynamic state mutations.
- Updated `MainActivity.kt` to trigger UI update on `currentMediaListAdapter` when the playing media is successfully matched with a detected media file, properly highlighting it in the list.
* Enhanced MediaDetection to decrypt base64 data-play iframes on sites like kpkuang and explicitly pass the source Referer to fix ExoPlayer 403 HTTP Bad Status errors.
* Replaced global padding injection with 'smart' JS targeting fixed/sticky elements to fix transparent toolbar layout without breaking 100vh grids on sites like kisskh.
* Added Gemini AI Model Switcher dialog and configured CustomPlayerActivity with Video Resize Mode toggle.
* Added handling for direct MP4 URLs in HlsExportService to bypass M3U8 parsers and extract video bytes directly from cache.

SECTION D — CUSTOM PLAYER SUBTITLES (INVESTIGATION ONLY)

1. Exact subtitle implementation in #285:
   In PR #285, `CustomPlayerActivity` received a single subtitle URL via `intent.getStringExtra(EXTRA_SUBTITLE_URL)`. It appended this URL to the MediaItem with `.setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_FORCED)` and called `player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setPreferredTextLanguage("en").setSelectUndeterminedTextLanguage(true).build()`.

2. Exact subtitle implementation in current master:
   The current master uses an `ArrayList<String>` via `EXTRA_SUBTITLE_URLS` and explicitly downloads them into a local directory using `HlsDownloadHelper.httpGetBytes` (in a background coroutine), reading them as bytes, guessing the language from the filename, writing them to disk, and adding them dynamically to `SubtitleConfigurations` (with `.setSelectionFlags(1)`). It explicitly overrides `p.trackSelectionParameters` to select `"en"` text tracks, but does not explicitly un-disable `TRACK_TYPE_TEXT` (unless hot-swapping logic was altered).

3. First meaningful divergence:
   The original PR directly fed the network URL into the `MediaItem.SubtitleConfiguration.Builder` and explicitly applied `SELECTION_FLAG_FORCED`. The current master manually fetches the URL using `HlsDownloadHelper.httpGetBytes`, saves it to disk (`file://`), and injects local files using flag `1` (`SELECTION_FLAG_DEFAULT`).

4. Evidence that the divergence can cause the failure:
   The `SELECTION_FLAG_FORCED` instructs Media3 to display the track even if it doesn't strictly align with system-wide user preferences or language matching. Flag `1` (Default) merely marks it as the default choice, but if Media3 decides text tracks are disabled globally, or if `setPreferredTextLanguage("en")` fails to exactly match the extracted language tag (e.g. `Track_Sub` vs `en`), Media3 will silently drop the track selection.

5. Any other plausible causes (Hypotheses):
   - HYPOTHESIS: The custom `HlsDownloadHelper.httpGetBytes` is failing due to missing `Referer` or cookies (unlike ExoPlayer which may have had them injected), resulting in 0-byte or failed local subtitle writes, meaning the `SubtitleConfiguration` uses an empty file.
   - HYPOTHESIS: The `.srt`/`.vtt` extraction logic (`contentString.contains("thank")`) mislabels the track language, causing the track selection parameters (`setPreferredTextLanguage("en")`) to miss it.

6. Exact smallest change you recommend, but DO NOT implement it yet:
   In `CustomPlayerActivity.kt`, where the `MediaItem.SubtitleConfiguration.Builder` is initialized (around line 507 and 732), change `.setSelectionFlags(1)` to `.setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_FORCED)`.

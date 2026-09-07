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

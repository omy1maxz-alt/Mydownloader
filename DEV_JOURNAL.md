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

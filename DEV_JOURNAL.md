# DEV_JOURNAL

- Re-established the 4 Heads of the Builder persona guidelines in AGENTS.md.
- Adjusted WebView and RecyclerView top padding to dynamically offset below the 48dp transparent toolbar, resolving UI overlap issues.
- Added GlassyPopupMenu style and applied it to Toolbar to make the 3-dot overflow menu transparent/glossy.
- Re-applied setVideoMimeType and setAudioMimeType explicitly to Media3 Transformer exports to fix 'blank video' bug.
- Fixed audio-only blank screen exports in HlsExportService by sending the exact MediaItem (master URL + StreamKeys) via Bundle from CustomPlayerActivity, preventing Transformer track resolution failures.

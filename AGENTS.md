# AGENTS.md — Jules Builder Instructions

This is the project-level instruction set for Jules and other coding agents working in `omy1maxz-alt/Mydownloader`.

## 1. Mission and communication

Act as a practical, evidence-driven Android/Kotlin engineer. Solve the user's actual problem with the smallest complete change that fits the current architecture.

- Start work by inspecting the live repository state, not by trusting old prompts or memory.
- Be direct, specific, and natural. Avoid filler and generic AI phrasing.
- Do not expose private reasoning or internal deliberations. Report findings and evidence, not chain-of-thought.
- Do not claim a test, build, device check, or runtime behavior succeeded unless you actually verified it.
- If a requirement is ambiguous but a safe, useful interpretation is available, proceed and state the assumption. Ask a concise question only when an answer is necessary to avoid a materially wrong change.

## 2. Required start-of-task checks

Before every new task:

1. Identify the current default branch, starting branch, HEAD commit, and working-tree status.
2. Do not overwrite, reset, or discard existing uncommitted work.
3. Read this file and the relevant parts of `DEV_JOURNAL.md`; inspect other architecture documentation if present.
4. Ask the user for an **installed-app runtime handoff** before starting, unless it is already included in the task:
   - What works after the last merge/install?
   - What is broken or regressed?
   - Any crashes or error messages?
   - What was actually tested on the device?
   - What remains untested?
5. Keep repository state and installed-device state separate. A clean build does not prove the app works on the user's phone.

Do not ask for the runtime handoff again if the user already supplied it in the current task. If the task is explicitly read-only or documentation-only, do not block that work waiting for device details; record that runtime status was not supplied.

## 3. Branch and pull-request workflow

For each independent implementation task:

1. Start from the current default branch unless the user explicitly specifies another base.
2. Create a **new, task-specific branch**. Do not reuse an unrelated feature/fix branch.
3. Report the starting branch, starting commit, working-tree status, and new branch name before editing.
4. Keep the change focused on one problem or closely related fix.
5. Run the relevant tests and build where available.
6. Open one PR targeting the current default branch and summarize the change, evidence, test/build results, known risks, and device tests still needed.
7. Never merge a PR automatically unless the user explicitly requests it.

If operating through a system that does not let you create branches or PRs, explain that limitation rather than pretending the workflow occurred.

## 4. Evidence-first investigation

- Trace the actual path through current code before editing: entry point, callers/callees, state owners, threading/lifecycle, network or storage boundaries, and error handling.
- Use symbol search and focused file regions first. Do not read a very large file linearly unless necessary.
- Separate facts, hypotheses, and unknowns. A log line or correlation is not proof of root cause.
- For bugs, reproduce or inspect the exact failure path when possible. Add targeted, privacy-safe diagnostics if the cause remains unclear.
- Do not broaden an investigation into unrelated build or architecture work.
- When the user's requested task is read-only, remain read-only: do not edit files, commit, branch, or create a PR.

## 5. Scope and regression protection

Preserve unrelated working behavior. Do not do broad refactors, dependency upgrades, or architecture redesigns unless the task requires them.

This app contains tightly coupled browser and media behavior. Before changes, inspect the relevant current implementation and protect these areas when in scope:

- WebView lifecycle, navigation, redirects, popups, SPA/history navigation, file chooser, cookies, request headers and authentication.
- Media detection, candidate ranking/grouping, active-player telemetry, iframe/MSE/blob handling, ad/preview filtering.
- HLS/DASH and progressive audio/video playback, subtitle discovery and language selection.
- Media3/ExoPlayer playback, SimpleCache/cache-key behavior, FFmpeg fallbacks, export/muxing, background playback, notifications and PiP.
- Download services, file naming, Android storage permissions and failure reporting.
- Doubao/RemoveMark injected scripts and their Android JavaScript bridges, when relevant.
- Existing browser UI, dialogs, settings, themes and gestures.

Do not change unrelated flows just to make a new test pass. Check existing project-specific media/cache rules in this file and the journal before touching those areas.

## 6. Android implementation rules

- Inspect the current Gradle, Android Gradle Plugin, Kotlin, Java/JDK, SDK and dependency versions before recommending upgrades.
- Follow current project conventions and APIs. Do not invent classes, methods, endpoints, dependencies, or files.
- Keep Android UI work main-thread safe and perform blocking file/network work off the main thread.
- Validate external input, handle cancellation and errors, and avoid leaking cookies, auth tokens, API keys, signed URLs or private user data in logs.
- Do not put credentials in source code, test fixtures, commits, shell history, or journal entries.
- Avoid new dependencies when the existing stack can solve the problem.
- For WebView JavaScript injection, check domain/origin scoping, repeat injection, SPA/dynamic content, listener cleanup and page lifecycle. Never inject app-specific controls into unrelated sites.
- For downloads and exports, verify output type and required tracks/content rather than treating a completed task as proof of a valid file.

## 7. Build and test requirements

- Inspect the repository's current build instructions before choosing commands.
- Run focused tests first, then relevant broader tests/builds when practical.
- Use the repository's actual build setup and AndroidIDE-compatible command if specified in the project.
- Report the exact checks run and their outcomes. If a command cannot run in the environment, state why.
- Separate verification levels:
  - **Source verification:** code and static checks inspected.
  - **Build/test verification:** build or automated tests completed.
  - **Device verification:** user or agent tested the installed app on a real Android device.
- Never describe source/build verification as device verification.
- If the build fails, report the first actionable error with evidence. Do not assume Kapt, caches, Gradle versions, or duplicate classes are root cause without tracing the compiler output and generated/source paths.

## 8. Project memory and journal

- Read relevant entries in `DEV_JOURNAL.md` before investigating recurring bugs.
- After a meaningful feature, bug fix, failed experiment, or architectural decision, append one concise, factual entry to `DEV_JOURNAL.md`.
- Do not claim the journal was updated unless the file was changed.
- Journal evidence and outcomes, not speculation presented as fact.
- Avoid adding repetitive entries that merely restate existing history.

## 9. Completion report

For a coding task, conclude with:
- Root cause or problem addressed, with evidence.
- Files changed and why.
- Tests/build commands and actual results.
- Risks, limitations, and what remains unknown.
- Exact real-device checks the user still needs to perform.

For a read-only task, report findings, supporting paths/lines or logs, uncertainties, and the next actionable step. Do not request user approval for ordinary decisions that are already specified by the task.

## 10. Project-specific media and cache constraints

These rules are specific to observed failure modes in this repository. Confirm that the current code still matches the relevant path before applying them.

### HLS cache key and cross-domain fallback

- HLS cache lookups must respect the exact cache key and URI actually used by the downloader/player. Relative playlist paths and cross-domain redirects can make the manifest host differ from the cached segment host.
- When matching a cached URI by path, avoid collisions across unrelated hosts or streams. Require additional evidence such as host, known parent manifest, grouping key, or active session context.
- If a fallback constructs a `DataSpec` for a matched cache key, the URI and cache key must remain consistent.
- Do not reuse a `CacheDataSource` after a failed open if the implementation can leave it in an error state. Build a fresh source for a genuine retry.
- Where custom cache-key factories rewrite keys, verify whether a `CacheDataSource` lookup actually honors the supplied key before relying on it. Prefer a proven direct cache-span path only where current APIs and code support it.
- Prefer cached playlist data before network fallback when the active session may have expired. Network fallback should be explicit, bounded and diagnosable.
- Detect cache gaps and terminal storage failures; do not silently report an incomplete export as success or blindly switch to a failing fallback.

### YouTube and split audio/video streams

- Do not pass raw `googlevideo.com/videoplayback` chunks to the generic WebView media sniffer/player when the current pipeline expects extractor-resolved media.
- Inspect current `YoutubeExtractorHelper`, `NewPipeDownloader`, `CustomPlayerActivity`, `MediaDetectionEngine` and `HlsExportService` paths before changing YouTube extraction, track selection or export.
- YouTube video and audio may be separate streams. Verify manifest/stream keys, cached segment availability and the final file's video/audio tracks. Do not assume Transformer or FFmpeg is always the right path; choose based on evidence.
- Do not report export success if the output is audio-only when video was requested, or incomplete/corrupt.

### Detection quality and injected scripts

- Filter likely ad, tracker, preview and hidden background media using evidence from URL, dimensions, duration, active-player state, page visibility and related candidates. Do not apply a blanket duration cutoff if it could exclude legitimate content; inspect current rules and user expectations.
- WebView detector scripts must avoid duplicate event listeners and infinite reinjection, support dynamic/SPA content where needed, and clean up safely.
- Keep site-specific integrations scoped to their intended origins and preserve the normal browser on all other sites.
- For `blob:` downloads, remember that a WebView-local blob URL cannot normally be passed directly to Android `DownloadManager`. Bridge bytes/data safely with size limits and correct MIME/extension validation; avoid logging file contents or large base64 payloads.

### Authentication and diagnostics

- A 403 usually means the server refused the request, but the cause can be expired authentication, missing headers, access policy, IP or endpoint restrictions. Trace the exact request and response before changing behavior.
- Preserve required cookies and request headers without printing their values. Redact signed query parameters and credentials from logs.
- Never retry permanent 401/403 errors indefinitely; retries should be bounded and reserved for errors that are plausibly transient.

## 11. Style and UI

- Mobile-first. Prefer simple layouts that work on a phone before desktop.
- Avoid arbitrary gradients, excessive borders, nested cards and decoration with no functional reason.
- Preserve established app theme and component patterns.
- Test touch targets, scrolling, keyboard interaction, orientation and WebView overlay interactions when relevant.

## 12. Current state is not memory

This file and `DEV_JOURNAL.md` are context, not proof of current behavior. Verify the current code and the user's latest installed-app report before deciding what is working. If the user reports a regression after a merge, treat that runtime report as current evidence even if the journal says the feature was fixed previously.

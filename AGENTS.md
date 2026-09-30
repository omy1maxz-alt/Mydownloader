<runtime_state_handoff>

MANDATORY CURRENT APP STATE CHECK BEFORE EVERY NEW TASK

The repository is NOT the same thing as the currently installed/tested app.

After Jules creates a PR, the user may:
1. merge the PR,
2. build/install the app,
3. test it on the real Android device,
4. discover runtime behavior that is not reflected in the repository.

Therefore, BEFORE starting ANY new implementation, bug-fix, refactor, or investigation task, Jules MUST first ask the user for the current runtime/app state.

Do not assume the previous PR worked simply because:
- the code compiled,
- the build succeeded,
- tests passed,
- the PR was merged,
- or the previous task was reported as fixed.

Ask for a concise runtime handoff covering:

1. CURRENT INSTALLED APP
   - Has the latest merged build been installed on the real device?
   - If not, say so.

2. CURRENT FEATURE STATUS
   Ask the user:
   - What features are currently working?
   - What features are currently broken?
   - What features are partially working?
   - What changed since the previous PR?

3. RUNTIME ERRORS
   Ask whether there are:
   - crashes
   - playback failures
   - UI/rendering problems
   - downloads failing
   - detection failures
   - missing subtitles
   - cache-related problems
   - network/API errors
   - unexpected behavior

4. USER TEST RESULTS
   Ask what the user actually tested on the installed app.
   Distinguish:
   - TESTED AND WORKING
   - TESTED AND BROKEN
   - NOT TESTED
   - UNKNOWN

5. REGRESSION CHECK
   Ask whether any previously working feature became broken after the latest merge.

6. EVIDENCE
   If a problem exists, ask for the most useful evidence available:
   - Logcat
   - screenshot/screen recording
   - exact reproduction steps
   - URL/site involved when relevant
   - before/after behavior

Do NOT repeatedly ask for information that the user has already provided in the current conversation.

If the user provides a runtime result such as:
"subtitle fixed, YouTube works, but Custom Player doesn't play after clearing cache"

treat that as current runtime evidence and record it in the task model.

IMPORTANT DISTINCTION:

Repository state:
"What code currently exists?"

Runtime state:
"What does the installed app actually do?"

Both are required.

The runtime state supplied by the user is authoritative for observed device behavior.

Do not replace runtime evidence with assumptions based on the source code.

---

MANDATORY TASK START PROTOCOL

For EVERY new task:

PHASE 0 — RUNTIME HANDOFF

Before changing code, ask:

"Before I start this task, tell me the current state of the installed app after the last merge:
- What is working?
- What is broken?
- Any errors/crashes?
- What did you actually test?
- Any new regression?"

Keep this question concise.

If the user already supplied the runtime state in the task description, do not ask again. Extract it and proceed.

PHASE 1 — REPOSITORY STATE

After the runtime handoff:

- inspect current branch
- inspect current commit
- inspect working tree
- inspect recent commits/PR-related changes
- inspect relevant project memory/journal
- identify whether the repository matches the build the user tested

If the installed build may come from a different commit than the current repository, explicitly record that uncertainty.

PHASE 2 — RECONCILE RUNTIME + CODE

Create a compact state table internally:

FEATURE | RUNTIME STATUS | REPOSITORY EVIDENCE | CONFIDENCE

Use:
- WORKING
- BROKEN
- PARTIAL
- NOT TESTED
- UNKNOWN

Do not assume "working" merely because the code appears correct.

PHASE 3 — TASK INVESTIGATION

Only after the runtime state and repository state are understood:

- reproduce/trace the relevant code path
- inspect historical known-good implementations when relevant
- identify the smallest safe change
- implement
- build
- verify

PHASE 4 — POST-PR HANDOFF

After creating the PR, clearly tell the user:

- what changed
- what was verified by Jules
- what still requires real-device testing

The user is the final runtime tester.

After the user merges and installs the PR, the NEXT task must begin with a fresh runtime-state handoff.

Never carry forward "fixed" as a permanent fact merely because Jules previously reported it fixed.

</runtime_state_handoff>
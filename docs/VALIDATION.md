# Validation Contract

For each milestone record:
1. source commit
2. exact build command
3. tests and results
4. target device/environment
5. runtime behavior
6. APK SHA-256 when applicable
7. known limitations
8. next milestone

Evidence levels:
- Compile: source compiles.
- Build: installable artifact produced.
- Device: artifact installs and launches on real device.
- Behavior: intended feature works end-to-end.
- MVP: feature contributes to a real agent task.

Never label a lower evidence level as a higher one.

---

## Milestone: Task 6 — chat template + generation control (2026-10-01)

Evidence level reached: **Behavior** — the feature works end-to-end on a real
device through the real app UI, with generation running inside the Waqti process.

1. **Source commit** — base `d95561a6` (Task 5); all Task 6 work is a single
   commit on top (see `docs/EXPERIMENTS.md` EXP-6A…6G for the iteration).
2. **Build** — `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.
3. **Tests and results** —
   - `./gradlew :core:test` → **71 tests, 70 PASSED, 1 SKIPPED, 0 FAILED**.
     The SKIPPED one is `AgentLiveE2ETest`, which targets an external
     OpenAI-compatible endpoint (`assumeTrue(endpointReachable())`) and is
     deliberately never counted as on-device evidence.
   - New `AgentLoopHistoryTest` (5 tests) pins the conversation transcript the
     loop hands to the provider: default single-turn behaviour unchanged,
     history placed between SYSTEM and the new task, SYSTEM/TOOL/blank turns
     dropped, oldest turns trimmed past `MAX_HISTORY_MESSAGES = 24`, history
     survives a tool round trip.
   - Device suite (LIVE-OBSERVED, fresh app start, `waqti-task4` logcat):
     A basic conversation `tokens=29 stop=eog`; A-repeat byte-identical;
     B "exactly three words" → `tokens=1 stop=eog text=Paris`;
     C continuity → `tokens=11 stop=eog text=The secret passcode is 7391.`;
     D same question with no history cannot produce 7391;
     E `n_predict=8` → `tokens=8 stop=n_predict`; E2 `n_predict=4` →
     `tokens=4 stop=n_predict`; release → unload → `createContext` =
     `error|no model loaded` → reload → `tokens=1 stop=eog text=OK`.
   - Task 5 regression on the same binary: `generate` with `n_predict=12` →
     `tokens=12 stop=n_predict`, `n_predict=5` → `tokens=5 stop=n_predict`.
   - Memory: native heap flat over six clear-then-prefill cycles
     (249,798 → 251,422 → 251,438 → 251,730 → 251,462 → 251,470 → 251,342 kB);
     PSS 2.40–2.44 GB, unchanged from Task 5.
4. **Target device/environment** — Redmi/onyx, Android 16, arm64, 8 cores,
   11.5 GB RAM; APK installed with
   `adb install -r -t app/build/outputs/apk/debug/app-debug.apk` → `Success`.
   No `llama-server`, no Termux-side inference, no HTTP loopback, no ADB
   reverse, no cloud API: every token was produced in the Waqti process
   (pid logged in every `waqti-native` / `waqti-task4` line).
5. **Runtime behaviour** — real UI, two turns typed with `adb shell input text`
   and sent with the on-screen Send button:
   `My name is Sara and my favourite number is 42. Just acknowledge.` →
   `Acknowledged, Sara.`; `What is my name and my favourite number? Answer in
   one short sentence.` → `Your name is Sara and your favourite number is 42.`
   Conversation read back from the accessibility tree
   (`evidence/task6_ui_final_uiautomator.xml`, screenshot
   `evidence/task6_ui_final.png`); all four bubbles present. A passcode variant
   (`7391`) was reproduced twice. Scan of all 31 rendered text nodes for
   `<|`, `|>`, `im_start`, `im_end`, `\x` → **NONE**.
6. **APK SHA-256** — `375848189d787abf7e9a15336f9ad34e98b241bedce226e2a8b47cfc9785c88f`
   (35,487,461 bytes).
7. **Known limitations** —
   - `LocalModelProvider` returns `ModelResponse.Text` only; it does not map
     generated text to `ModelResponse.Calls`, so tool calls are not yet driven
     by the local model (risk recorded in `docs/ON_DEVICE_INFERENCE.md` §1,
     still open).
   - Generation quality at Q4_K_M / temperature 0.7 is uneven: recall of
     concrete facts is reliable, recall of weakly-worded preferences is not.
     Model capability, not a runtime defect — the prompt is logged and provably
     contains the history.
   - Throughput unchanged from Task 5 (~10–14 tok/s). The app's ~940-byte
     system prompt makes a UI turn 17–23 s.
8. **Next milestone** — none. STOP after Task 6; Task 7 is not started.

## 2026-10-01 — UI-driven local inference: PASS (LIVE-OBSERVED)

Device: Redmi/onyx, Android 16 (SDK 36), arm64, 1280x2772, density 521.
Model: `qwen2.5-3b-OFFICIAL-Q4_K_M.gguf`, 2,104,932,768 B in app `filesDir`
(survives `install -r -t`; never `pm clear`).

Chain exercised end to end through the real UI (no broadcast harness — see
`LESSONS_LEARNED.md` on the MIUI `Greezer Denial`):

```
Waqti -> Ready (qwen2.5-3b-OFFICIAL-Q4_K_M.gguf)
      -> composer: "Name the three Kotlin visibility modifiers."
      -> Send
      -> run card: "Working on a reply"
      -> 16 tokens -> EOG id=151645 piece=<|im_end|>
      -> parsed reply: content=84 chars, 0 tool call(s)
      -> Compose renders: 'None of the files in the workspace contain the phrase "K...'
```

Second prompt, same build, to rule out a lucky sample:

```
"In one sentence, what does the Kotlin mutableListOf return?"
      -> 18 tokens -> EOG id=151645 -> content=99 chars
      -> renders: 'Kotlin mutableListOf returns a mutable list that can hav...'
```

Both replies are coherent prose; before commit `71ac124` the same path returned
`None,500)@&357647355918575766562426213124855808...`.

- Level A static/source: PASS (`git diff` reviewed, three focused changes)
- Level B host tests: PASS (106 tests, 0 failures, 1 pre-existing skip)
- Level C native build: PASS
- Level D APK build/install: PASS
- Level E device: PASS
- Crash buffer: empty (the earlier `ggml_abort` no longer occurs)

**Caveat, stated plainly.** The first reply is *semantically* wrong — the model
chose to reach for `SearchFiles` instead of answering from its own knowledge.
That is a model/prompt-quality issue, not a decoding defect, and it is the
subject of Task 7 tool-calling work. Decoding is now correct.

Artifacts: `evidence/ui/02-empty-ready.png`, `04-answer-fixed.png`,
`05-answer-second.png`. Not visually reviewed (this model has no image input);
verified through the accessibility hierarchy and log evidence instead.

RSS is NOT a model-loaded signal on this device (file-backed mapping); the UI
status pill is authoritative.

## Task 7 — tool calling, three-layer isolation (2026-10-01)

Build under test: HEAD `71ac124931685d3e40f0d86fdf2b00b13f147ff6`,
`app/build/outputs/apk/debug/app-debug.apk`, 37,988,557 bytes,
SHA-256 `34d7def49685c645199a080ff6e9471d1f8fd61fe6734f61c54572927268f3b7`,
package `com.waqti.agent`, versionName `0.1.0`, versionCode `1`, minSdk 30,
targetSdk 36. Built with `./gradlew :core:test --rerun-tasks`,
`:app:testDebugUnitTest`, `:app:assembleDebug` after removing the temporary
`PROMPTCHUNK` logging diagnostic; `app/src/main/cpp/waqti_local_runtime.cpp` is
byte-identical to HEAD after that removal (`git diff` empty).

- Level B host tests: PASS — core 92 tests (0 failures, 1 pre-existing skip),
  app 14 tests (0 failures).
- Level D APK install: PASS — `adb install -r -t`, model preserved (2,104,932,768 B).
- Layer A (prompt construction): **PASS**, byte-verified on device.
- Layer C (tool loop): **PASS**, end-to-end with real content.
- Layer B (model decision): **FAIL**, characterised; see below.

Layer C evidence, real UI, real tool, real content. `SearchFiles
{"query":"fun","recursive":true}` → `30 ms`, result `matches: 2 in 2 file(s)
(showing 2, limit 100)`, and the model's round-2 reply:

> The files that contain the word "fun" are:
> - `src/Greeting.kt` on line 1
> - `src/MathUtil.kt` on line 1

Two rounds, two generations, both `stop=eog`. The answer is grounded in the
actual matches, not invented. **This only became verifiable after the workspace
was populated** — see "empty workspace" in `LESSONS_LEARNED.md`. Earlier runs that
reported `matches: 0 in 0 file(s)` were searching an empty directory and prove
nothing about tool correctness.

Layer B evidence, same build, real UI:

| prompt | tool_calls | tokens | text |
|---|---|---|---|
| What is the capital of France? | 0 | 12 | `None of the files contain the phrase "capital of France".` |
| What are the three Kotlin visibility modifiers? | 0 | 13 | `None of the files contain the phrase "Kotlin visibility modifiers".` |
| Explain what a Kotlin data class is, in three sentences. | 0 | 75 | correct prose answer |

`tool_calls = 0` is the *expected* count for a knowledge question. The defect is
the prose: the first generated token is `None` (id 4064), the lazy grammar never
fires, and the model writes a fabricated search result. Reproducible — `seed=0`
is a fixed seed. Root cause is Layer B (Qwen2.5-3B decision quality on a correct
official Qwen2.5 prompt), not Waqti. No code fix applied, per the stop condition.

Braces: the doubled `{{`/`}}` come from this GGUF's embedded
`tokenizer.chat_template`, which diverges from both the official HF template and
llama.cpp's built-in `Qwen-Qwen2.5-7B-Instruct.jinja`. Cosmetic — the model emits
single braces and the PEG parser accepts them.

All device validation went through the real UI; the debug broadcast receiver is
dead on this HyperOS device (`Greezer Denial`). Screenshots in `evidence/ui/` are
not visually reviewed (no image input); everything above is from the
accessibility hierarchy and `logcat`.

## 2026-10-01 — MVP finalization (LIVE-OBSERVED)

Provenance of the APK that was actually validated on the device:

- Compiled from source tree `d29178020406e9840e932d360cd73d2a6aa98b3c` on branch
  `main`. The commit that finally records this section differs from `d291780`
  only in this file (`git diff d291780 HEAD -- app core` is empty), so the
  compiled inputs are exactly the ones committed. A commit cannot record its own
  hash, so the build commit is named instead.
- APK `app/build/outputs/apk/debug/app-debug.apk`, 32,633,674 bytes
- SHA-256 `a50c7caca15a80c8f821ea5bab42cc651debad6b5f95bbf00926e975d7c07dad`
- package `com.waqti.agent`, versionName `0.1.0`, versionCode `1`,
  minSdk 30, targetSdk 36
- native payload: `lib/arm64-v8a/libwaqti_local_runtime.so`
- build command: `./gradlew clean` then
  `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`

The APK build is **not byte-reproducible**. The first validated build came from
the same source content but from the working tree before those changes were
committed; it hashed to
`05f512447a5a807252709f08a46df51b5d8686011d8a496a11b3d3756ae2d546` at the same
32,633,674 bytes. The source is therefore unchanged, but the hash is not, so the
APK was rebuilt from the committed HEAD, reinstalled, and re-validated from
scratch — and the hash above is the one the device results below belong to.
Nothing here is inherited from the earlier build.

Tests: core 92 (0 failures, 1 pre-existing skip), app 14 (0 failures).

Device: `25053RT47C` (HyperOS), serial `192.168.1.6:38383`.

Installed with `adb install -r -t`; both GGUF files survived (2,104,932,768 B and
4,683,074,336 B). The active model was switched **back to the 3B** through the
real UI (Model sheet → Advanced → Model) because the 3B is the only model whose
tool protocol currently conforms. The persisted setting was read back out of
`shared_prefs/waqti_settings.xml`.

Three end-to-end runs through the real UI, real 3B on device, real workspace
files, real tool execution:

| # | prompt | round 1 | round 2 | verdict |
|---|---|---|---|---|
| 1 | Find Kotlin files containing TODO and summarize | `SearchFiles {"query":"TODO","path":"src","recursive":true,"include":"*.kt","ignoreCase":true,"limit":100}` → `matches: 1 in 1 file(s)` | 39 tok, `stop=eog` | PASS |
| 2 | List the files directly inside the workspace | `ListFiles {"path":"."}` → `entries: 2 of 2 (limit 200) in /` | 14 tok, `stop=eog` | PASS |
| 3 | Name the three Kotlin visibility modifiers | 16 tok, `tool_calls=0` | — | FAIL (known Layer-B limitation, documented above) |

Runs 1 and 2 were repeated end-to-end on the rebuilt APK above, after a fresh
`am force-stop` + `am start`, and produced byte-identical output both times
(`seed=0` is fixed; only `0xFFFFFFFF` is random). The model is therefore
reproducible on the real device, not merely consistent within one process.

Run 1's final answer cites the real line it found —
``I found a Kotlin file in the workspace: `Greeting.kt` … `// TODO: localize the
greeting` at line 2`` — which is byte-present in
`/storage/emulated/0/Android/data/com.waqti.agent/files/workspace/src/Greeting.kt`.
That is the MVP's demonstrable path: prompt → local model → tool decision → real
filesystem result → grounded answer → UI.

UI states were exercised for real, not asserted:

- **No model**: with the 3B temporarily renamed out of `filesDir`, the app showed
  `No model` / `No model on this device` and an `Import a model` button. Tapping
  it opened the OEM SAF picker (`com.android.fileexplorer.picker.PickMainNavigatorActivity`
  — the system's default handler for `ACTION_OPEN_DOCUMENT` on this device). The
  3B was renamed back afterwards; both model files verified intact.
- **Ready**: `qwen2.5-3b-OFFICIAL-Q4_K_M.gguf` / `Ready`.
- **Loading / Working / Using a tool**: reached during the runs above; the state
  word is derived from real `RunStage`, never faked.

Screenshots for the runs are in `evidence/ui/` (338,024 B and 338,667 B). They
were **not** visually reviewed — this host has no image input — so no claim is
made about how they look; every UI assertion above comes from the accessibility
hierarchy.

Measurements taken from `logcat` (`waqti-native` tag), 3B Q4_K_M:

- model load: 784 / 840 / 1544 / 1624 / 1773 / 1821 ms across six runs
- tool execution: 27 ms (`SearchFiles`), observed in the run that reported
  `matches: 1 in 1 file(s)`
- first-token latency: **UNKNOWN** — this build logs no per-phase timing
- decode speed: **UNKNOWN** — round wall time includes prefill and cannot be
  divided by token count to give a rate; see `LESSONS_LEARNED.md`
- RSS with model loaded: 2,584,848 KB (`TOTAL PSS` 2,462,624 KB) of 11,502,936 KB
  total device RAM

Device state left clean: `accelerometer_rotation` back to `1`, Waqti force-stopped,
Termux foreground, no model file moved or deleted.

## CP5 — model import validation (2026-10-02, LIVE-OBSERVED)

Device `25053RT47C` (SM8735, 8 cores), serial `192.168.1.6:38383`, Android 15.
All steps were driven through the real app and the real SAF picker
(`com.android.fileexplorer.picker.PickMainNavigatorActivity`, the device's default
`ACTION_OPEN_DOCUMENT` handler). No debug receiver was used.

### Defect reproduced before the fix

Two invalid fixtures were staged on the device and picked through the real picker:

| fixture | bytes | first bytes |
|---|---|---|
| `fake-empty.gguf` | 0 | (none) |
| `fake-text.gguf` | 67 | `this` (`74 68 69 73`) |

`importModel()` validated only `filename.endsWith(".gguf")`. Observed result for
**both** files, with no change to any other code:

- the file was copied into `files/` and renamed into place;
- `waqti_settings.xml` was updated to that name;
- the model sheet reported **`Ready`** with `Size 0 B` / `Size 67 B`.

`currentModelState()` reported `Ready` for any existing file, so a 0-byte file was
presented to the user as a working model.

### Fix

`app/src/main/java/com/waqti/agent/ui/ChatViewModel.kt`

- `hasGgufMagic(file)` reads the first four bytes and requires ASCII `GGUF`.
  Deliberately not a GGUF parser and does not duplicate llama.cpp's loader; a
  file shorter than four bytes cannot match.
- `importModel()` validates the **staged** `.part` copy *after* the copy and
  *before* it is renamed into place or written to settings. On mismatch the
  staged file is deleted and the import fails, so nothing is added to the model
  registry and the previously active model is left untouched.
- `currentModelState()` projects `ModelUiState.Invalid` instead of `Ready` when a
  file exists but fails the magic check.

`app/src/main/java/com/waqti/agent/ui/ChatState.kt` adds `ModelUiState.Invalid`.
`ChatScreen.kt`, `WaqtiComponents.kt` and `ModelSheet.kt` render it; the header
shows `<name> (not a GGUF)`, the status word is `Not a model`, and the sheet
offers `Choose a GGUF file`. `canRunTask` is unchanged and still requires `Ready`,
so an invalid file can never be used for a run.

### Verified after the fix

| Check | Result |
|---|---|
| Import 0-byte `fake-empty.gguf` | **PASS** — rejected |
| Import 67-byte `fake-text.gguf` | **PASS** — rejected |
| Error shown to user | **PASS** — *"That file is not a valid GGUF model. Choose a real .gguf model file."* |
| Nothing added to registry | **PASS** — `files/` unchanged, no `.part` left behind |
| Active model preserved | **PASS** — `settings.model` unchanged across both failures |
| Existing invalid file reported honestly | **PASS** — 67-byte file shows *"...is not a valid GGUF model, so Waqti cannot load it."* instead of `Ready` |
| Import valid GGUF | **PASS** — `qwen2.5-0.5b-instruct-q4_k_m.gguf`, 491,400,032 B copied to `files/` |
| State after valid import | **PASS** — sheet `Ready`, `File qwen2.5-0.5b-instruct-q4_k_m.gguf`, `Size 491 MB` |
| Runtime loads imported model | **PASS** — `loading model: .../files/qwen2.5-0.5b-instruct-q4_k_m.gguf (491400032 bytes)`; `loaded meta data with 26 key-value pairs and 291 tensors ... GGUF V3` |
| Inference from imported model | **PASS** — `generation done: ok|7696 ms|tokens=6|stop=eog|tool_calls_b64=W10=|text=Day in one short sentence.` |

The imported 0.5B was then replaced by the previously validated 3B through the
app's own settings sheet, and all CP5 fixtures were deleted from the device.

### Regression check on the fixed build

Same APK re-validated for the core path with `qwen2.5-3b-OFFICIAL-Q4_K_M.gguf`:

- `generation done: ok|55070 ms|tokens=25|stop=eog|text=` with
  `tool call name=SearchFiles id= arguments={"query": "Sprint", "path": "."}`
- tool executed for real: `SearchFiles` `39 ms` `matches: 1 in 1 file(s)`
- `generation done: ok|67354 ms|tokens=14|stop=eog|tool_calls_b64=W10=|text=Found "Sprint" in docs/plan.md: # Sprint plan`

### Performance measured while validating (3B Q4_K_M, LIVE-OBSERVED)

Taken from per-token `waqti-native` log timestamps, **not** from round wall time:

- model load to first generation (mmap, `n_ctx=4096`): 13:11:25.861 -> 13:11:27.725, about **1.86 s**
- decode interval round 2: ten consecutive tokens, 0.139-0.154 s apart, mean **about 0.142 s/token, about 7 tok/s**
- the remainder of each round is **prompt evaluation**, about 51 s (round 1) and
  about 65 s (round 2). This build logs no explicit prefill/decode split, so the
  split is derived from log timestamps, not from a runtime-internal timer.
- first-token latency: **UNKNOWN** — no first-token timer exists in this build
- peak memory for this run: **UNKNOWN** — not sampled during this checkpoint

Earlier in the same session, rounds of 131,374 ms / 13 tok and 136,732 ms / 32 tok
were observed. Those are consistent with the split above: the anomaly is prompt
evaluation, not decode. Root cause is not established and was not investigated --
performance work is out of scope for this checkpoint.

### Device state left clean

`accelerometer_rotation` restored to `1`, CP5 fixtures removed from
`/sdcard/Download/` and `/sdcard/Download/models/`, `files/` restored to the two
validated models, Waqti force-stopped, Termux foreground.

### Build / tests

`./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` -- BUILD SUCCESSFUL.
core 92 tests, 0 failures, 1 skipped; app 14 tests, 0 failures.

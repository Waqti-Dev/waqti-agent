# On-device inference — runtime boundary and runtime selection

Status: Tasks 1–6 done (boundary, runtime research, in-process GGUF load,
generation, chat template + generation control). All six are **PASS** on a real
device. Per-task detail lives in its own section below; the experiment log is
`docs/EXPERIMENTS.md`.

## 1. Runtime boundary (Task 1)

```text
UI / ChatViewModel
      ↓
ModelProvider                 (existing seam, unchanged)
      ↓
LocalModelProvider            (new, :core — application-level concepts only)
      ↓
LocalInferenceRuntime         (new interface, :core — plain Kotlin types)
      ↓
JNI bridge                    (new, :app — the only file that imports native)
      ↓
llama.cpp (native, arm64-v8a)
      ↓
GGUF file
```

Knowledge rules:

| Layer | May know | Must not know |
|---|---|---|
| UI / `ChatViewModel` | `ModelProvider`, settings, UI state | JNI, llama.cpp, GGUF internals, native pointers/threads |
| `AgentLoop` | `ModelProvider`, `ToolRegistry`, `AgentPolicy` | JNI, llama.cpp, HTTP transport, model file internals |
| `LocalModelProvider` | `LocalInferenceRuntime`, `ModelRequest`/`ModelResponse` | JNI types, native pointers |
| JNI bridge | `external fun`, native handles | UI state, `AgentLoop` |

Existing seams that already satisfy this (verified, unchanged):

- `ModelProvider` — `val label` + `suspend respond(ModelRequest): ModelResponse`.
- `AgentLoop` depends only on `ModelProvider`/`ToolRegistry`/`AgentPolicy`; it has
  no HTTP or vendor knowledge.
- `OpenAICompatProvider` remains the only production provider until the local
  provider is proven (migration rule 13).

Smallest required additions (all additive, no rewrite):

1. `:core` — `LocalInferenceRuntime` interface: load(modelPath), generate(...),
   cancel(), release(), using only plain Kotlin/Java types.
2. `:core` — `LocalModelProvider : ModelProvider` delegating to that interface.
3. `:app` — JNI implementation of `LocalInferenceRuntime` plus the native build
   wiring in `app/build.gradle.kts`.
4. `:app` — `ModelManager` for app-private storage/import/selection (later tasks).

Known integration risk (carried to Task 6/7): the in-process runtime returns
**raw generated text**, whereas `AgentLoop` also consumes
`ModelResponse.Calls` (OpenAI tool-call JSON, previously produced by
llama-server). `LocalModelProvider` must map generated text to `ToolCall`s;
`AgentLoop` itself does not change.

## 2. Runtime selection (Task 2)

Research basis: upstream `llama.cpp` master (`a894dae`) checked out on this
host, plus the live official `docs/build.md` and `docs/android.md`.

```text
Selected runtime:  Option A — in-process native library (Kotlin → JNI → llama.cpp)

Why:
- Officially supported: llama.cpp ships `docs/android.md`, an Android arm64-v8a
  NDK CMake recipe in `docs/build.md`, and a working Android Studio project at
  `examples/llama.android` (`:lib` Android library + `externalNativeBuild` CMake
  linking `llama`, `llama-common`, `android`, `log`).
- ARM64 support is first-class: `arm64-v8a`, `GGML_NATIVE=OFF`,
  `GGML_LLAMAFILE=OFF`, `LLAMA_OPENSSL=OFF`, optional `GGML_CPU_KLEIDIAI=ON`.
  Target min API android-28 ≤ our minSdk 30.
- MIT licensed (no copyleft obligations for redistribution in the APK).
- Gradle integration is a supported path (AGP `externalNativeBuild` + CMake).
- Same in-process llama.cpp already runs on this device as a Termux binary
  (`~/llama-snapdragon`, built "for Android 34, NDK r29"), so the toolchain and
  the model are known-good on the target hardware.
- Matches the required architecture: no localhost, no bundled server process,
  no ADB reverse.

Rejected alternative (Option B — bundled llama-server over localhost):
- Would keep an HTTP server process inside the APK: lifecycle, port conflicts,
  background-execution limits, an extra surface for the security model, and it
  still requires hosting a 2 GB binary + server scaffolding.
- Not needed: Option A is practical here, so the plan forbids B.

Risks:
- llama.cpp's C API is not semantically versioned; headers change between
  releases. Mitigation: pin the llama.cpp source revision used for the build.
- The build will depend on an out-of-repo llama.cpp checkout whose path is
  configured the same way `sdk.dir` already is (see `local.properties`).
  A repo-only build (vendored/submodule source) is a follow-up decision.
- Binary size and model memory are unmeasured so far (recorded as UNKNOWN
  until Task 3/14 measure them).
- On-device CPU inference is slow (observed external-server generation
  1.15–8.03 tok/s), so responses will stay slow until optimisation work
  (explicitly out of scope until correctness is established).

Unknowns:
- Exact packaged `.so` size for our configuration: UNKNOWN until Task 3.
- Whether tool-call generation quality survives the switch from llama-server's
  OpenAI-format tool support to raw-text generation: UNKNOWN until Task 6/7.
- Peak RSS of the app process holding the 2.06 GiB Qwen GGUF: UNKNOWN until
  Task 4/14.

## 3. Building llama.cpp natively on the device (Task 3 environment)

The build host is the phone itself (Termux, aarch64 Android). Every obstacle
below was hit in order, with the exact error that identified it:

| # | Exact error | Root cause | Fix |
|---|---|---|---|
| 1 | `C/C++: .../cmake/3.22.1/bin/cmake[1]: syntax error: unexpected ')'` | The SDK `cmake`/`ninja` packages are x86-64 ELF binaries; the JDK falls back to shell-parsing them on aarch64 | `bin/cmake`, `bin/ninja` in the SDK replaced with wrappers that exec Termux's cmake/ninja (same pattern as the existing `android.aapt2FromMavenOverride`) |
| 2 | `The CMAKE_C_COMPILER: .../toolchains/llvm/prebuilt/bin/clang is not a full path` | CMake reports `CMAKE_HOST_SYSTEM_NAME=Android` here, a case the NDK toolchain file does not handle → `ANDROID_HOST_TAG` empty (and its clang is x86-64 anyway) | Our own `app/src/main/cpp/waqti.android.toolchain.cmake`, passed as the **last** `-DCMAKE_TOOLCHAIN_FILE` (CMake keeps the last one) |
| 3 | `ld.lld: error: unable to find library -l:libunwind.a` | Host clang defaults to linking a static libunwind; the NDK ships none for Android targets | `-unwindlib=none` — bionic `libc.so` exports `_Unwind_*` (present in the API ≥ 30 stubs), so NDK builds link no unwinder either |
| 4 | `ld.lld: error: unable to find library -lc++_shared` | Host clang defaults to Termux's `libc++_shared.so` and ignores `-static-libstdc++` for Android targets | `-nostdlib++` + absolute paths to the NDK's `libc++_static.a`/`libc++abi.a` (keeps the packaged `.so` self-contained) |
| 5 | `java.lang.NullPointerException` / `There was an error parsing CMake File API result` | AGP reads `cache.getCacheString(CMAKE_LINKER)!!` from the CMake File API reply; setting `CMAKE_LINKER` as a *normal* variable makes CMake skip linker detection, so no cache entry is ever published | `set(CMAKE_LINKER ... CACHE FILEPATH "" FORCE)` |
| 6 | `relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used against symbol ... recompile with -fPIC` | llama.cpp/ggml are static libraries linked into our shared object; static libs default to non-PIC (the NDK toolchain normally sets this) | `CMAKE_POSITION_INDEPENDENT_CODE=TRUE` |
| 7 | `Unable to strip the following libraries, packaging them as they are` (first `llvm-strip[1]: syntax error: unexpected '('`, then `llvm-objcopy: error: unknown argument '-o'`) | AGP strips packaged libraries with the NDK's `llvm-strip`, another x86-64 binary; and the host `llvm-objcopy` has no GNU-style `-o` (it takes `input [output]`) | a wrapper at the NDK `bin/llvm-objcopy` (which `llvm-strip` symlinks to) that translates `--strip-unneeded -o <out> <in>` to the positional form and execs the native tool. This matters: the NDK's `libc++_static.a` ships debug sections, so the unstripped `.so` is 12,951,088 bytes vs 5,513,240 stripped |

Toolchain inputs (all derivable inside the file, so CMake's `try_compile()`
sub-configures succeed too):

- compilers/binutils: Termux `$PREFIX/bin` (`clang` 21.1.8, `llvm-*`, `ld.lld`)
- sysroot: `<ndk>/toolchains/llvm/prebuilt/<host>/sysroot` (derived from the
  `CMAKE_ANDROID_NDK` that AGP passes) — headers, crt objects, stub libraries,
  static libc++
- target: `aarch64-linux-android<minSdk>` (`--target=…30`), API from AGP's
  `-DANDROID_PLATFORM`

Toolchain proof (standalone CMake project, same toolchain, not the app):

- Compile: **PASS** (`__ANDROID_API__==30` enforced by `#error`)
- Link (shared lib + exe): **PASS**
- `.so` dependencies: `liblog.so`, `libdl.so`, `libm.so`, `libc.so` — no
  libc++ runtime to ship: **PASS**
- Run on device: **PASS** (`ran ok api=30`, exit 0)

Task 3 evidence — fresh APK built from this repository (2026-09-30, UTC):

- llama.cpp revision used: `a894dae939d426954ce54bb604824f1ae918a0c5`
  (clean tree, out-of-repo path from `local.properties`), NDK
  28.2.13676358, Termux clang 21.1.8
- Native build: **PASS** — `:app:buildCMakeDebug[arm64-v8a]` succeeded,
  object regenerated from current sources
- Fresh APK: `app/build/outputs/apk/debug/app-debug.apk`, 29,942,283 bytes,
  sha256 `3bba34fa6c6e60e7923b237d2066a285ef207343062d70fbf4d74ae401fe9b60`
  (every task rebuilds its own APK; this hash identifies *this* evidence only)
- ABI: **PASS** — native entries under `lib/arm64-v8a/`
- Runtime library present: **PASS** — `lib/arm64-v8a/libwaqti_local_runtime.so`,
  5,513,240 bytes, stripped ELF 64-bit ARM aarch64, "for Android 30"
- JNI entry point exported: **PASS** —
  `Java_com_waqti_agent_runtime_NativeRuntime_versionInfo`
- llama.cpp actually linked in: **PASS** — 1,119 `llama_*`/`ggml_*` dynamic
  symbols; `NEEDED` = `libandroid.so liblog.so libm.so libdl.so libc.so` only
- Loading the library and the model inside the app process: **not tested here**
  (Task 4/5)

## 4. Task 4 — first in-process load attempt: FAIL (2026-09-30)

```text
Model:            Qwen2.5-3B Q4_K_M
Model size:       2,104,932,768 bytes
Model SHA-256:    626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d
                  (host == device, verified before load)
llama.cpp rev:    a894dae (a894dae939d426954ce54bb604824f1ae918a0c5, 0.4.1-dev)
Result:           FAIL
Failure:          Android LMK SIGKILL during llama_model_load_from_file()
Peak RSS:         ~1.51 GB (1,513,292 kB, LMK-reported at kill time)
oom_score_adj:    905 (cached/background process)
Important:        mmap was enabled (load_mode = mmap), but mmap does NOT
                  guarantee low RSS during initialization — touched pages
                  become resident and count toward the LMK decision.
```

Proven by that run (do not re-test): APK installation; GGUF transfer into
app-private storage; device SHA == host SHA; correct private model path;
debug-receiver → JNI `loadModel` invocation inside the Waqti process; llama.cpp
parsing the real GGUF (26 KV, 435 tensors, GGUF V3); tensor creation; tokenizer
initialization. The process was killed by Android memory management — **no**
llama.cpp exception, **no** GGUF corruption, **no** remaining path/broadcast
problem.

### Task 4 final status: PASS (2026-09-30, after memory investigation)

Root cause of the failure above: initialization ran while the process was a
**cached process** (`oom_score_adj 905`), and llama.cpp's hardcoded prefetch
(`llama-model.cpp:1734` `init_mappings(true,…)` → `MAP_POPULATE`,
`llama-mmap.cpp:480`) drives load-time RSS to ≈ file size — so under system
reclaim pressure LMK SIGKILLed us. Not a llama.cpp error, not a copy, not a
path/broadcast problem (all ruled out, `docs/EXPERIMENTS.md` EXP-B…EXP-F).

Fix = **operating rule, no code change**: model initialization must run while
the process is foreground-important. Proven twice, same APK `27ca831f…`, same
model (LIVE-OBSERVED):

| Run | Process state | Load result | Peak RSS | adj |
|---|---|---|---|---|
| EXP-00 | cached (broadcast cold start) | FAIL — SIGKILL | 1,513,292 kB at kill | 905 |
| EXP-A | MainActivity resumed | **ok, 1357 ms** | 1,908,000 kB | 0 |
| EXP-A2 | MainActivity resumed | **ok, 1438 ms** | 2,295,632 kB | 0 |

Evidence chain — all in pid 14312/23339 of `com.waqti.agent`
(`topResumedActivity=…MainActivity`, adj 0), no external server anywhere:

```text
Waqti Android process
  → JNI NativeRuntime.loadModel
  → llama.cpp 0.4.1-dev ("CPU_Mapped model buffer size = 2001.74 MiB", progress 0–100%)
  → real Qwen GGUF (sha256 626b4a66…15c62d verified before load)
  → loadModel result: ok|1357 ms|qwen2 3B Q4_K - Medium|name=qwen2.5-3b-instruct|bytes=2104932768
```

Measured (load only): duration 1357–1438 ms; peak RSS ≤ 2,295,632 kB
(transient, file-backed — reclaims to VmRSS 318,584 kB / PSS 272,344 kB with
the model still held); context/KV = 0; CPU ≈ 1.37 core-seconds. First-token
latency / generation / thermal: UNKNOWN — Task 5, not started.

Operating constraint for Task 6+: load models only while the process is
foreground-important (resumed activity; a foreground service only if a genuine
background-load requirement ever appears — rationale in
`docs/LESSONS_LEARNED.md`). Task 5 may start now; it must measure context /
KV / generation memory separately from load memory.

## 5. Task 5 — real local inference/generation: PASS (2026-10-01)

```text
Model:            Qwen2.5-3B Q4_K_M (same as Task 4)
llama.cpp:        a894dae (0.4.1-dev)
Runtime:          in-process JNI, libwaqti_local_runtime.so, arm64-v8a
Device:           Redmi/onyx, Android 16, 8 cores, 11.5 GB RAM
APK:              5bd6b35 sha 5bd6b35c9b5c92b791e090566632a6b1b3226f7f
```

Implemented generation pipeline:
```text
loadModel(path) → createContext(n_ctx=4096, n_batch=512) → generate(prompt, params)
```

Measured (LIVE-OBSERVED, foreground adj=0):
| Stage | Duration | VmRSS delta | Notes |
|---|---|---|---|
| Model loaded | 1414–1632 ms | 2,310,800 kB | mmap file-backed |
| Context created | 72–109 ms | +55 MB | KV cache (f16 K/V, 36 layers, 4096 cells) |
| Generation (128 tok) | 5,854–11,679 ms | +98 MB | compute buffers, ~11 tok/s |
| Generation (64 tok) | 2,986 ms | — | |
| Release context | <1 ms | — | KV freed |

Process importance: foreground activity (adj 0) required; cached process (adj 905) would be LMK victim during peak RSS.

Repeated generation: first generation in context succeeds; subsequent fail (KV position tracking not reset) — workaround: releaseContext → createContext.

Chat template: not yet applied (raw prompt fed directly); output quality reduced.

First-token latency / generation tok/s / thermal: measured ~11 tok/s; thermal UNKNOWN.

Evidence chain: `docs/EXPERIMENTS.md` EXP-5A…EXP-5E, `docs/RUNTIME_MEMORY.md` section 7.

Task 5 status: **PASS** — real in-process generation proven; limitations documented for Task 6+.

## 6. Task 6 — chat template + generation control: PASS (2026-10-01)

```text
Source commit:    d95561a6 (Task 5) + uncommitted Task 6 working tree
Build:            ./gradlew :app:assembleDebug
APK:              35,487,461 bytes  sha256 375848189d787abf7e9a15336f9ad34e98b241bedce226e2a8b47cfc9785c88f
Install:          adb install -r -t app/build/outputs/apk/debug/app-debug.apk → Success
Model:            Qwen2.5-3B Q4_K_M, 2,104,932,768 bytes (device == host, verified in Task 4)
llama.cpp:        a894dae939d426954ce54bb604824f1ae918a0c5 (0.4.1-dev), pinned, unmodified
Device:           Redmi/onyx, Android 16, arm64, 8 cores, 11.5 GB RAM
Evidence:         LIVE-OBSERVED (device logcat `waqti-native` / `waqti-task4`, pid = Waqti process)
Host tests:       ./gradlew :core:test → 71 tests, 70 PASSED, 1 SKIPPED, 0 FAILED
```

### 6.1 Pipeline

```text
Waqti UI → ChatViewModel → AgentLoop → LocalModelProvider
        → LocalInferenceRuntime → NativeRuntime.generateChat (JNI)
        → llama_model_chat_template + llama_chat_apply_template
        → llama_tokenize → llama_decode → sampler loop → llama_token_to_piece
        → text → Compose UI
```

No HTTP, no localhost, no bundled server, no ADB reverse, no external
`llama-server`. Every token below was produced inside the Waqti process.

### 6.2 The defect this task actually fixed

The reported symptom was a literal end-of-turn marker appearing in generated
text. Comparing our code against the pinned llama.cpp API gave the real cause —
**not** the detokenizer.

`llama_tokenize(..., add_special, parse_special)` was called with
`(false, false)`. In `src/llama-vocab.cpp` `tokenizer_st_partition()`:

```cpp
if (!parse_special && (data.attr & (LLAMA_TOKEN_ATTR_CONTROL | LLAMA_TOKEN_ATTR_UNKNOWN))) {
    // Ignore control and unknown tokens when parse_special == false
    continue;
}
```

So the ChatML markers in the formatted prompt were **not** mapped to the model's
own control ids; they were split into ordinary text pieces. The model therefore
saw a prompt that looked nothing like its training distribution and answered by
writing the marker out as literal text.

Every llama.cpp example tokenizes the way `common_tokenize` does, with
`add_special=true, parse_special=true` (`examples/embedding/embedding.cpp:195`,
`common/chat.cpp:149`, `common/sampling.cpp:282`). We now do the same.
`add_special=true` is safe for this GGUF: `tokenizer.ggml.add_bos_token=False`
and no `add_eos_token` key, so `llama_vocab_get_add_bos/get_add_eos` are both 0 —
verified LIVE-OBSERVED in the log line
`prompt tokenized to 14 tokens (add_bos=0 add_eos=0)`.

Decisive LIVE-OBSERVED proof (prompt ids logged before prefill):

```text
formatted prompt (84 bytes) = <|im_start|>user\nHello, introduce yourself briefly.<|im_end|>\n<|im_start|>assistant\n
prompt ids = 151644 872 198 9707 11 19131 6133 26753 13 151645 198 151644 77091 198
control tokens in prompt = 3
EOG id=151645 piece=<|im_end|> after 29 token(s)
```

`151644` / `151645` are the real `<|im_start|>` / `<|im_end|>` ids (confirmed
against the GGUF token table), and the EOG token is detected and **not**
rendered.

### 6.3 Generation control

| Requirement | Implementation | Evidence |
|---|---|---|
| nPredict is a hard upper bound | `for (i = 0; i < n_predict; ++i)`; loop cannot exceed it | `tokens=8 stop=n_predict` for `n_predict=8`; `tokens=4 stop=n_predict` for 4 |
| Clean EOS/EOG, no control-token leakage | `llama_vocab_is_eog()` breaks **before** rendering; `llama_token_to_piece(..., special=false)` returns 0 chars for CONTROL/UNKNOWN attrs | 31 rendered UI text nodes scanned: **no** `<|`, `|>`, `im_start`, `im_end`, or `\x` escapes |
| Stop reason is observable | `ok|<ms> ms\|tokens=<n>\|stop=<eog\|n_predict\|sampler_eof\|decode_error>\|text=<text>` | every result line in §6.4 |
| Shared vs fresh conversation | the transcript in `ModelRequest.messages` *is* the context: the whole thing is formatted and prefilled every call and the KV cache is reset before each prefill | see §6.5 |

`llama_detokenize(..., remove_special)` is **not** the knob here: it applies to
a whole token array, while generation is incremental. `llama_token_to_piece`'s
`special` argument is the correct one for the incremental path.

### 6.4 Device results (LIVE-OBSERVED, headless debug receiver, fresh app start)

| Test | n_predict | Result |
|---|---|---|
| A basic conversation | 64 | `ok\|3783 ms\|tokens=29\|stop=eog\|text=Hello! I'm an artificial intelligence here to assist you with any information or tasks you might need help with. How can I assist you today?` |
| A repeated (determinism) | 64 | byte-identical to A — proves no state carried between calls |
| B instruction following ("exactly three words") | 48 | `tokens=1\|stop=eog\|text=Paris` |
| C continuity (passcode stated in turn 1) | 32 | `tokens=11\|stop=eog\|text=The secret passcode is 7391.` |
| D fresh context (same question, no history) | 32 | cannot produce 7391 — the difference is the history, not the plumbing |
| E generation limit | 8 | `tokens=8\|stop=n_predict` |
| E2 generation limit | 4 | `tokens=4\|stop=n_predict` |
| reload cycle | 16 | release → unload → `createContext` = `error\|no model loaded` → reload → `tokens=1\|stop=eog\|text=OK` |

Task 5 regression on the same APK — plain `generate()` (no template), which is
the path Task 5 validated:

```text
generate result: ok|1541 ms|tokens=12|stop=n_predict|text= = "Paris"
generate result: ok|840 ms |tokens=5 |stop=n_predict|text= = "Paris"
```

### 6.5 Real UI validation (LIVE-OBSERVED, `MainActivity` + `uiautomator`)

Settings were reset so the app uses its own defaults (`base_url=local`,
`model=qwen2.5-3b-OFFICIAL-Q4_K_M.gguf`). Two turns typed with `adb shell input`
and sent with the on-screen Send button; the rendered conversation was read back
from the accessibility tree:

```text
[232,432][1189,578]   'My name is Sara and my favourite number is 42. Just acknowledge.'
[91,677][589,750]     'Acknowledged, Sara.'
[232,849][1189,995]   'What is my name and my favourite number? Answer in one short sentence.'
[91,1094][1130,1240]  'Your name is Sara and your favourite number is 42.'
```

Scan of every rendered text node for control-token / escape leakage:
**NONE**. Screenshots and the dump are in `evidence/`
(`task6_ui_final.png`, `task6_ui_final_uiautomator.xml`).

An independent passcode variant was also run through the UI
(`The secret passcode is 7391.` → `Acknowledged.` → `Repeat the secret passcode
exactly.` → `7391`), reproduced twice.

### 6.6 Defects found and fixed on the UI path

These were only reachable through the real app, not the debug receiver:

1. **Settings store a model name, not a path.** `SettingsStore.DEFAULT_MODEL`
   is a bare filename, and it was handed straight to `loadModel()`, which
   `stat()`s it — resolved against the process working directory, so it always
   failed with `error|cannot stat file`. `ChatViewModel.resolveLocalModelPath()`
   now resolves a bare name inside the app's private files directory
   (LIVE-OBSERVED load path: `/data/user/0/com.waqti.agent/files/qwen2.5-3b-OFFICIAL-Q4_K_M.gguf`).
2. **The KV cache was never reset.** `llama_batch_get_one` positions continue
   from `memory->seq_pos_max()+1`, so every call re-prefilled the entire history
   on top of the previous call's KV. Fixed with `llama_memory_clear(
   llama_get_memory(ctx), true)` before each prefill — the same call
   `examples/embedding` and `common.cpp` make. Proven by the byte-identical
   repeat of test A.
3. **The UI never sent earlier turns.** `AgentLoop.run(task)` built a fresh
   `SYSTEM + task` transcript every time, so a follow-up question could not refer
   to anything said earlier. `run(task, history = emptyList())` now accepts the
   turns the UI is already showing; the default keeps every existing caller and
   test unchanged. `MAX_HISTORY_MESSAGES = 24` bounds a long chat, and
   SYSTEM/TOOL/blank turns are dropped from the supplied history.
   Regression test: `core/src/test/kotlin/com/.../AgentLoopHistoryTest.kt` (5 tests).
4. **The JNI status string was ambiguous.** `ok|…|tokens=n|text=…` was parsed
   with `split('|')`, which silently truncated any answer containing a pipe.
   `text` is now the last field and is taken with `substringAfter("|text=")`.
5. **`llama_chat_message` only borrows `const char *`.** The old parser built
   each message from loop-local `std::string`s whose storage died at the end of
   the iteration, leaving dangling pointers — undefined behaviour that happened
   to work. Roles and contents are now collected into owning `std::vector`s and
   the message array is built afterwards. The same rewrite added correct
   `\uXXXX` / surrogate-pair handling and proper `"` escaping.
6. **Dead code removed**: `LocalModelProvider.startFreshConversation()` had no
   caller and, after (2), no distinct meaning — a fresh conversation is simply a
   request whose history is empty.

### 6.7 Known limitations (unchanged, still open)

- `LocalModelProvider` returns `ModelResponse.Text` only. It does **not** map
  generated text to `ModelResponse.Calls`, so the agent's tool calls are not
  driven by the local model yet — this is the risk recorded in §1 and remains
  open for a later task. The UI evidence above shows the loop terminating on the
  model's plain-text turn.
- Generation quality at Q4_K_M with `temperature=0.7` is uneven. Recall of an
  earlier turn is reliable for concrete facts ("passcode 7391") and unreliable
  for weakly-worded preferences ("favourite colour is teal" was sometimes not
  recalled). This is model capability, not a runtime defect: the prompt for
  those turns is logged and provably contains the history.
- Throughput is unchanged from Task 5 (~10–14 tok/s; 29 tokens in 3.8 s here).
  Long prompts cost more: the UI system prompt is ~940 bytes and a turn took
  17–23 s.
- `AgentLiveE2ETest` is still `SKIPPED`: it targets an external OpenAI-compatible
  endpoint and is deliberately never used as on-device evidence.


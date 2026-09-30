# On-device inference — runtime boundary and runtime selection

Status: Task 1 (boundary) and Task 2 (runtime research) of the on-device
inference migration. Research only — no llama.cpp code exists in this
repository yet.

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


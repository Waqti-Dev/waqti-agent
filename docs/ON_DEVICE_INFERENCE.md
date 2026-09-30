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

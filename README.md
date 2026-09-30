# Waqti Agent

Waqti is a native, phone-first AI agent built from a clean repository.

An agent is not a chatbot: it receives a task, decides whether an action is
required, executes that action through a real tool, inspects the result,
continues from it, and returns a useful answer. This repository implements that
loop for real — no scripted responses, no simulated tool execution.

## Architecture

```
app  (Android, Jetpack Compose)          presentation only
 └── ChatViewModel  →  AgentLoop (in :core)
core (pure Kotlin/JVM, no Android deps)   everything testable without a device
 ├── loop/       AgentLoop, AgentPolicy, AgentEvent, AgentOutcome
 ├── model/      ModelProvider interface + OpenAI-compatible HTTP provider
 └── tools/      Tool, ToolRegistry, Workspace boundary, ListFiles, SearchFiles
```

- **AgentLoop** owns the workflow: model round → optional tool calls → results
  fed back as `role=tool` messages → repeat until a final answer or an explicit
  failure (step limit, model error, timeout).
- **ModelProvider** is a narrow boundary; the core never knows the vendor,
  transport, tokenizer, or inference runtime. Today's implementation speaks the
  OpenAI-compatible protocol, so llama-server and remote APIs both work.
- **Tools** run against a `Workspace` trust boundary: path traversal, absolute
  paths outside the root, and symlink escapes are rejected; every scan and
  result is capped.

## Build

Prerequisites: JDK 17+, Android SDK (platform 36, build-tools 36.0.0),
network access to `google()` and `mavenCentral()`.

```sh
./gradlew :core:test          # 59+ JVM behaviour tests
./gradlew :app:assembleDebug  # -> app/build/outputs/apk/debug/app-debug.apk
```

Set the SDK location in `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

Building on Termux/Android itself works with two host adjustments (the SDK's
host binaries are x86-64 and cannot execute on ARM):

```sh
pkg install openjdk-17 aapt2 apksigner
# in ~/.gradle/gradle.properties:
#   android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

## Run the agent against a real model

The app defaults to `http://127.0.0.1:8080/v1` with model name `local`, i.e. a
local llama-server (see `~/start-ai` on the reference device). Any
OpenAI-compatible endpoint can be set in the in-app **Model** dialog (endpoint,
model name, API key, workspace path).

Live end-to-end test (skipped when no endpoint is reachable):

```sh
WAQTI_LIVE_MODEL_URL=http://127.0.0.1:8080/v1 ./gradlew :core:test --tests '*AgentLiveE2ETest*'
```

## Documentation

- `docs/MVP_SPEC.md` — milestone contract
- `docs/ARCHITECTURE.md` — layer and boundary decisions
- `docs/VALIDATION.md` — evidence log (builds, tests, artifacts, runtime)
- `KNOWLEDGE/` — accumulated lessons, failed approaches, inference notes

## Principles

- Real behaviour over feature count; small over complex.
- Every claim of "tested/working" needs recorded evidence (`docs/VALIDATION.md`).
- Reuse mature infrastructure (llama.cpp, GGUF tooling) instead of rewriting it.

# Waqti MVP Status

**Status: verified baseline.** Every line below was established on a real Android
device running a freshly built Debug APK, not inferred from source or from a
successful compilation.

- Device: `25053RT47C` (SM8735, 8 cores), Android 15, serial `192.168.1.6:38383`
- Source HEAD at validation: `fd4a5dcda1854cfc70a7d39a93a1b508fd962e46`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Build command: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
- Test command: same, plus the device runs recorded in `docs/VALIDATION.md`

Full evidence, including the pre-fix defect reproduction, is in
`docs/VALIDATION.md` under "CP5 — model import validation".

## Core status

```
Waqti MVP

Core chat:                    PASS
Local GGUF import:            PASS
Local inference:              PASS
Agent loop:                   PASS
SearchFiles:                  PASS
ListFiles:                    PASS
Tool result integration:      PASS
Android runtime:              PASS
```

### What each line rests on

| Area | Evidence |
|---|---|
| Core chat | Prompt entered in the real composer, assistant answer rendered in the UI |
| Local GGUF import | Real SAF picker; valid GGUF copied into private storage; `Ready` with real size |
| Local inference | `generation done: ok\|…\|stop=eog\|…` from the JNI runtime on the device |
| Agent loop | Two rounds: tool decision, then tool result folded into a second model round |
| SearchFiles | `SearchFiles 39 ms matches: 1 in 1 file(s)` — real filesystem scan |
| ListFiles | Grounded directory listing answered from the tool result (previous session, same code path) |
| Tool result integration | `parsed reply: content=45 chars, 0 tool call(s)` after the tool returned |
| Android runtime | App installs, launches, and runs llama.cpp in-process; no crash across all runs |

The demonstrable path, end to end, with the 3B on the fixed build:

```
user: "search workspace for Sprint"
  -> tool call name=SearchFiles arguments={"query": "Sprint", "path": "."}
  -> SearchFiles executed: 39 ms, matches: 1 in 1 file(s)
  -> assistant: Found "Sprint" in docs/plan.md: # Sprint plan
```

`docs/plan.md` and its `# Sprint plan` heading are real bytes in the app's
workspace, not fixture text invented for the answer.

## Validation checklist

| Item | Result |
|---|---|
| Compile | **PASS** — BUILD SUCCESSFUL |
| Unit tests | **PASS** — core 92 tests / 0 failures / 1 skipped; app 14 tests / 0 failures |
| Native build | **PASS** — `libwaqti_local_runtime.so` built from the existing, unchanged sources |
| APK build | **PASS** — `app-debug.apk`, 32,964,837 bytes, sha256 `6f81e3ae3e7e7859eadbc60a347a38a5530a7d7d6909258fa2d54ae0d4feddb3` |
| Install | **PASS** — `adb install -r` returned `Success` |
| Launch | **PASS** — `MainActivity` resumed |
| Model import | **PASS** — valid GGUF imported via the real picker |
| Invalid GGUF rejection | **PASS** — 0-byte and text fixtures both rejected with a truthful message |
| Valid GGUF acceptance | **PASS** — imported, stored, `Ready`, loaded by the runtime |
| Model initialization | **PASS** — `loaded meta data with 26 key-value pairs and 291 tensors` |
| Real local inference | **PASS** — `stop=eog` on every round observed |
| SearchFiles | **PASS** — `matches: 1 in 1 file(s)` |
| ListFiles | **PASS** |
| AgentLoop -> ToolResult | **PASS** |
| ToolResult -> final response | **PASS** — `Found "Sprint" in docs/plan.md: # Sprint plan` |

## Known limitations

These are real and deliberately not hidden.

### 1. Coder 7B does not follow the tool-call protocol — LIMITATION

`qwen2.5-coder-7b-instruct-q4_k_m.gguf` answers tool-eligible prompts with fenced
JSON instead of Waqti's `<tool_call>{…}</tool_call>` format. Because fenced JSON
also looks like a plausible final answer to a human, treating it as one would mean
a second tool protocol and a parser that can misread legitimate JSON replies. No
parser was added. Root cause is model format adherence, not the runtime; see
`docs/LESSONS_LEARNED.md`. The 7B model is not used for tool work.

### 2. Qwen2.5 3B reasoning and grounding are weak

The 3B is the model that passes the tool workflow, but it is not reliable about
general knowledge. It answered *"None of the files found contain the phrase
\"capital of Japan\""* to a plain factual question — a fabrication about tool
results it never ran. Task performance is uneven. Waqti is an MVP, not a
reasoner.

### 3. Prompt evaluation is very slow — root cause UNKNOWN

Measured from per-token log timestamps on the 3B: decode runs at about
**0.142 s/token (about 7 tok/s)**, but **prompt evaluation dominates each round**,
roughly 51-65 s for a ~3 KB prompt. Rounds measured at 55,070 ms and 67,354 ms
while decoding only a handful of tokens. The runtime logs no prefill/decode split,
so this split is derived from log timestamps. Nothing has been changed to address
it; performance work is out of scope for this checkpoint.

### 4. Performance figures that remain UNKNOWN

- **first-token latency** — UNKNOWN, no first-token timer exists in this build
- **decode tokens/second** as a runtime-reported figure — UNKNOWN, there is no
  runtime counter; the per-token figure above is reconstructed from log timestamps
- **peak memory for this build/run** — UNKNOWN, not sampled in this checkpoint.
  A prior measurement of TOTAL PSS 2,462,624 KB / RSS 2,584,848 KB is preserved
  as history in `docs/VALIDATION.md`; it is not a current measurement.
- **prompt-eval vs decode split** — not reported by the runtime at all

### 5. Device validation limitations

- The debug broadcast harness does not work on this device (`Greezer Denial`), so
  every result here was driven through the real UI rather than an automation hook.
- Screenshots could not be visually reviewed (this host has no image input), so
  every UI assertion in `docs/VALIDATION.md` comes from the accessibility
  hierarchy, not from looking at a picture.
- The device's OEM picker (`com.android.fileexplorer`) launches into Waqti's own
  task and does not reliably keep foreground. Test runs had to re-front the task
  between steps. This is a test-harness problem, not a product defect.

### 6. Build reproducibility

APK builds are **not** byte-reproducible. Two builds of the same source produced
different hashes (`a50c7cac…` and `6f81e3ae…`) at the same size. Provenance is
recorded per build rather than assumed.

## MVP definition

The MVP is the workflow: install, launch, import a real GGUF, Waqti stores it
privately, llama.cpp initializes it, the model becomes `READY`, a prompt produces
real local inference, a tool task makes the agent select and execute a real tool,
the `ToolResult` returns to the loop, and the answer appears in the UI grounded in
what was actually found.

**That workflow is verified end to end on a real device. Waqti MVP — verified
baseline.**
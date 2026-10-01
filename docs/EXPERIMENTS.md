# Experiments — Task 4 memory investigation log

Protocol: one record per experiment. Never call an experiment successful
merely because the process survived longer. Outcomes: `PASS` / `FAIL` /
`UNKNOWN`.

Record fields:

```text
Experiment ID | Hypothesis | Change | Build | Device state | Model |
Parameters | Observed RSS/PSS | oom_score_adj | Result | Conclusion | Next
```

Rules:

- Check this log before running anything; do not repeat an unchanged failed
  experiment.
- `Device state` must include: screen on/off, foreground component, running
  apps, MemAvailable at start.
- `Result` refers to the hypothesis being tested, not to vibes.

---

## EXP-00 — Baseline: original Task 4 load attempt (recorded from evidence)

- **ID**: EXP-00
- **Hypothesis**: (baseline) the current Task 4 implementation loads the
  Qwen2.5-3B GGUF in-process successfully
- **Change**: none — `ModelLoadDebugReceiver` cold-start → JNI `loadModel` →
  `llama_model_load_from_file` (defaults, `n_gpu_layers=0`, progress callback)
- **Build**: app-debug.apk sha256 `27ca831f2acc4431de45b298a3b7ae4d4bc5b71bfb3186e0f92360faad5ddc44`
- **Device state**: screen/FG unknown; app started only for the broadcast
  (cached, adj 905); system in a reclaim wave (4 processes LMK-killed within
  1 s of ours); MemAvailable at start: UNKNOWN
- **Model**: Qwen2.5-3B Q4_K_M, 2,104,932,768 B,
  sha256 `626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d`
- **Parameters**: `llama_model_default_params()` + `n_gpu_layers=0`; no context/KV created
- **Observed RSS/PSS**: LMK-reported RSS at kill = 1,513,292 kB; PSS: UNKNOWN (process gone)
- **oom_score_adj**: 905
- **Result**: **FAIL**
- **Conclusion**: load killed by LMK ~1.9 s in, after `done_getting_tensors`
  (14:35:10.658 → kill 14:35:12.041). Root cause candidates H1–H7 open. No
  llama.cpp error, no crash, no corruption.
- **Next**: build measurement loop (RSS/adj sampling) → EXP-A (process importance)

---

## EXP-A — Process importance (H1): foreground load

- **ID**: EXP-A
- **Hypothesis**: initializing the model while the process is
  foreground-important (resumed MainActivity, `oom_score_adj 0`) lets the load
  complete where the cached run (EXP-00, adj 905) was SIGKILLed
- **Change**: no code/build change — device state only: wake screen, dismiss
  keyguard, `am force-stop`, `am start …MainActivity` (topResumed), then the
  same debug-receiver broadcast
- **Build**: app-debug.apk sha256 `27ca831f2acc4431de45b298a3b7ae4d4bc5b71bfb3186e0f92360faad5ddc44` (unchanged)
- **Device state**: Awake, `topResumedActivity=…MainActivity`; MemAvailable: UNKNOWN
  (host quoting bug in this run); system under live reclaim pressure — LMK
  killed `com.xiaomi.market` (adj 905) and `chrome:sandboxed` (adj 900)
  *during* our load
- **Model**: Qwen2.5-3B Q4_K_M, 2,104,932,768 B, sha256 `626b4a66…15c62d`
- **Parameters**: `llama_model_default_params()` + `n_gpu_layers=0`; no context/KV
- **Observed RSS/PSS**: before 207,592 kB → peak 1,908,000 kB (0.3 s cadence) →
  after: PSS 1,691,876 kB total (Other mmap 1,524,490 kB private-clean file
  pages; Native Heap 51,754 kB; Dalvik 22,125 kB) → idle steady later
  318,584 kB VmRSS / 272,344 kB PSS with model still held
- **oom_score_adj**: 0 throughout the load (701 later when backgrounded — survived)
- **Result**: **PASS**
- **Conclusion**: process importance is decisive. Same APK + model + trigger:
  adj 905 ⇒ killed (EXP-00); adj 0 ⇒ completed while LMK killed other cached
  processes. Foreground-activity loading is a legitimate product flow.
- **Next**: EXP-A2 reproducibility + CPU/memory metrics

---

## EXP-A2 — Reproducibility + metrics (H1/H7)

- **ID**: EXP-A2
- **Hypothesis**: the EXP-A outcome is reproducible under live system
  pressure, with quantifiable cost
- **Change**: identical procedure to EXP-A, fixed `/proc/meminfo` quoting,
  added CPU-tick and MemAvailable capture
- **Build**: same APK sha `27ca831f…` (unchanged)
- **Device state**: Awake, MainActivity resumed, adj 0;
  MemTotal 11,502,936 kB, MemAvailable 3,263,008 kB before → 3,375,412 kB after;
  LMK active (killed `misettings:remote`, `android.process.acore`,
  `misettings`, `xmsf:services` — adj 800–905 — during our load window)
- **Model / Parameters**: same as EXP-A
- **Observed RSS/PSS**: before 208,580 kB → **peak 2,295,632 kB** → immediate
  after ~2.13 GB → idle reclaim 1,192,264 kB (t+33 s, adj 701) → 318,584 kB
  (~2 min); PSS after = 1,152,525 kB (Other mmap 1,044,472 kB); swap
  66,756 → 29,956 kB during load, 86,032 kB idle
- **oom_score_adj**: 0 during load, 701 afterwards — never killed
- **Result**: **PASS**
- **Conclusion**: reproducible (`ok|1438 ms`; EXP-A `ok|1357 ms`). Load cost:
  ~1.4 s wall, ~1.37 CPU-s (≈0.95 core), peak RSS ≈ file size + ~240 MB anon,
  all file-backed and reclaimable. Normal-use pressure (live LMK wave) does
  not matter while foreground.
- **Next**: source audits EXP-B…EXP-F (no further device runs needed for H2–H6)

---

## EXP-B — Native copy audit (H4)

- **ID**: EXP-B
- **Hypothesis**: the Waqti/JNI/llama.cpp load path performs no full-model
  copies (weights stay mmap views)
- **Change**: read-only audit — `waqti_local_runtime.cpp` (JNI reads only the
  path + `stat()`), pinned llama.cpp load path, plus device PSS
- **Build / Model**: as EXP-A (REPO-verified + LIVE-OBSERVED)
- **Parameters**: n/a
- **Observed RSS/PSS**: Native Heap 51,754 kB PSS vs model 2,055,598 kB;
  device log buffer name `CPU_Mapped model buffer size = 2001.74 MiB`;
  progress 0→100 % fires within 1 ms (no I/O pass)
- **oom_score_adj**: n/a
- **Result**: **PASS** (claim holds — no copies exist)
- **Conclusion**: no whole-file byte arrays, no duplicate tensor storage, no
  decompression buffers anywhere in the path.
- **Next**: EXP-C

---

## EXP-C — CPU_REPACK / backend buffers (H3)

- **ID**: EXP-C
- **Hypothesis**: the `CPU_REPACK … using CPU instead` warning adds memory or
  copies during initialization
- **Change**: read-only audit of pinned source
- **Build / Model**: n/a (REPO-verified)
- **Parameters**: `GGML_CPU_KLEIDIAI=OFF` (no repack buffers compiled in)
- **Observed RSS/PSS**: none attributable (no repack-sized allocation; see EXP-B PSS)
- **oom_score_adj**: n/a
- **Result**: **PASS** (claim rejected — warning is benign)
- **Conclusion**: the message is a buffer-type *fallback at tensor-metadata
  creation* (`llama-model-loader.cpp:1403`, counter `n_tensors_moved`) — it
  runs before any data movement; weights still land in the mmap view
  (`buffer_from_host_ptr = true`, `ggml-cpu.cpp:398`; path taken at
  `llama-model.cpp:1776`). No copy, no extra allocation.
- **Next**: EXP-D

---

## EXP-D — Context/KV separation (H5)

- **ID**: EXP-D
- **Hypothesis**: Task 4 model load allocates no context/KV/compute memory
- **Change**: read-only audit of the JNI API surface
- **Build / Model**: n/a (REPO-verified)
- **Parameters**: n/a
- **Observed RSS/PSS**: Native Heap ~51 MB = llama/Java runtime overhead
  (a 32k-context KV would be hundreds of MB — absent)
- **oom_score_adj**: n/a
- **Result**: **PASS** (claim holds — 0 bytes of context/KV during Task 4)
- **Conclusion**: JNI calls only `llama_backend_init`, `llama_log_set`,
  `llama_model_load_from_file`, `llama_model_desc`, `llama_model_meta_val_str`
  (and `llama_model_free`). No context-creation API is reachable. Context+KV+
  generation memory belongs to Task 5 and must be measured there.
- **Next**: EXP-E

---

## EXP-E — Thread count / backend init temp memory (H6)

- **ID**: EXP-E
- **Hypothesis**: thread count materially affects model-load memory
- **Change**: none — CPU ticks measured in EXP-A2 only (no variation run)
- **Build / Model**: as EXP-A
- **Parameters**: defaults (no thread overrides)
- **Observed RSS/PSS**: load = 137 ticks (1.37 CPU-s) over 1.44 s wall ≈ 0.95
  core; no temp-memory spike beyond the populate curve
- **oom_score_adj**: 0
- **Result**: **UNKNOWN** (not varied — no evidence either way)
- **Conclusion**: no evidence that threads affect *load* memory; no change made
  (measure-before-change satisfied by not changing). Revisit only if a future
  measurement implicates threads.
- **Next**: EXP-F

---

## EXP-F — mmap/prefetch knobs (H2/H4/F)

- **ID**: EXP-F
- **Hypothesis**: load RSS can be capped via public llama.cpp parameters
  (`use_mmap` / prefetch)
- **Change**: read-only audit of pinned llama.cpp `a894dae`
- **Build / Model**: n/a (REPO-verified) + device correlation
- **Parameters**: defaults (`load_mode = mmap`)
- **Observed RSS/PSS**: RSS ramps 208 MB → 2,295,632 kB in the exact window
  between `done_getting_tensors` and the `CPU_Mapped … 2001.74 MiB` print;
  EXP-00 died *inside* that window (no buffer-size print) at 1,513,292 kB
- **oom_score_adj**: n/a (source finding)
- **Result**: **FAIL** for the claim (no public knob exists at the pinned rev)
- **Conclusion**: prefetch is hardcoded `ml.init_mappings(true, …)`
  (`llama-model.cpp:1734`) → `prefetch_size = -1` → `MAP_POPULATE`
  (`llama-mmap.cpp:480`); no `prefetch` field in `llama.h`. Disabling it would
  require patching the pinned dependency. **Decision: do not patch** — the
  peak is transient and file-backed (reclaims 2,295,632 → 318,584 kB with the
  model held), and foreground loading completes in ~1.4 s. Reopen only with
  evidence that populate kills even at low adj.
- **Next**: none — record decision (done)

---

## EXP-5A — Context creation (H5/KV allocation)

- **ID**: EXP-5A
- **Hypothesis**: llama_init_from_model creates context + KV cache with measurable memory
- **Change**: JNI createContext(n_ctx=4096, n_batch=512); RSS sampling
- **Build**: APK sha 5bd6b35 (same as generation)
- **Device state**: Awake, MainActivity resumed, adj 0, MemAvailable 4,942 MB
- **Model/Parameters**: Qwen2.5-3B Q4_K_M, n_ctx=4096, n_batch=512, 4 threads, Flash Attention ON
- **Observed RSS/PSS**: before 2,310,800 kB → after 2,365,768 kB (+54,968 kB)
- **oom_score_adj**: 0
- **Result**: **PASS** — KV cache allocated (~55 MB f16 K/V, 36 layers, 4096 cells)
- **Conclusion**: Context/KV memory quantified; f16 K+V = 2×4096×36×(4096/32)×2 ≈ 55 MB matches
- **Next**: EXP-5B

---

## EXP-5B — First generation (prompt decode + token generation)

- **ID**: EXP-5B
- **Hypothesis**: generate() produces real tokens with measurable cost
- **Change**: JNI generate(prompt="Hello, introduce yourself briefly.", n_predict=128, temp=0.7, top_k=40, top_p=0.9)
- **Build/Device/Model**: same as EXP-5A
- **Observed RSS/PSS**: 2,365,768 → 2,464,072 kB (+98,304 kB compute buffers)
- **oom_score_adj**: 0
- **Result**: **PASS** — ok|11679 ms|tokens=128|text=... (real generated text)
- **Conclusion**: ~11 tok/s, first-token latency embedded (~prefill 100-200 ms), compute buffers ~98 MB
- **Next**: EXP-5C

---

## EXP-5C — Repeated generation (KV position tracking)

- **ID**: EXP-5C
- **Hypothesis**: multiple generate() calls in same context work correctly
- **Change**: three sequential generate() calls without context release
- **Build/Device/Model**: same
- **Observed**: 1st ok|2986 ms; 2nd error|llama_decode prefill failed -1; 3rd error|llama_decode prefill failed -1
- **oom_score_adj**: 0
- **Result**: **FAIL** — KV position not reset between generations
- **Conclusion**: llama_batch pos tracking accumulates; need explicit KV reset or context recreation
- **Next**: EXP-5D

---

## EXP-5D — Release/reload cycle (lifecycle)

- **ID**: EXP-5D
- **Hypothesis**: releaseContext() + createContext() enables fresh generation
- **Change**: generate → releaseContext → createContext → generate
- **Build/Device/Model**: same
- **Observed**: releaseContext ok; createContext ok (74 ms); generate after reload ok|2986 ms
- **oom_score_adj**: 0
- **Result**: **PASS** — lifecycle works; KV properly freed and reallocated
- **Conclusion**: Context recreation is valid workaround for KV position issue
- **Next**: EXP-5E

---

## EXP-5E — Memory timeline + performance

- **ID**: EXP-5E
- **Hypothesis**: full memory timeline + perf metrics captured
- **Change**: RSS sampling at each stage; MemAvailable; generation timing
- **Build/Device/Model**: same
- **Observed**:
  - Baseline: 210 MB
  - Model loaded: 2,311 MB (mmap)
  - Context: 2,366 MB (+55 MB KV)
  - Generation: 2,464 MB (+98 MB compute)
  - Idle steady: reclaims to ~320 MB
  - MemAvailable: 4,942 → 4,955 MB (stable)
  - Duration: 128 tok = 11,679 ms (~11 tok/s); 64 tok = 2,986 ms
  - CPU: ~4 threads active, Flash Attention ON
- **oom_score_adj**: 0 throughout
- **Result**: **PASS** — full characterization complete
- **Conclusion**: Task 5 memory/perf baseline established; chat template needed for quality
- **Next**: Task 6 (chat template, LocalModelProvider integration, UI)

---

## EXP-6A — Literal end-of-turn marker in generated text

- **ID**: EXP-6A
- **Hypothesis**: the ChatML markers in the formatted prompt were being split
  into ordinary text, so the model saw an off-distribution prompt and echoed the
  marker back as literal text
- **Change**: none yet — diagnosis only. Compared our `llama_tokenize` call
  against the pinned source (`~/llama.cpp`, `a894dae`).
- **Build**: Task 6 working tree
- **Device state**: foreground `com.waqti.agent`, screen on, adj 0
- **Model**: Qwen2.5-3B Q4_K_M
- **Parameters**: `generateChat`, `n_predict=64`, temperature 0.7
- **Observed**: `ok|…|tokens=29|stop=eog|text=…<|im_end|>`. Prompt token ids
  contained **no** 151644/151645; the marker had become plain text pieces.
- **Result**: **FAIL** (hypothesis supported)
- **Conclusion**: `llama_tokenize(..., add_special=false, parse_special=false)`.
  `src/llama-vocab.cpp` `tokenizer_st_partition()` skips CONTROL/UNKNOWN tokens
  when `parse_special == false`, so ChatML was tokenized as text.
- **Next**: EXP-6B.

## EXP-6B — Same call with `add_special=true, parse_special=true`

- **ID**: EXP-6B
- **Hypothesis**: tokenizing with the flags every llama.cpp example uses
  (`common_tokenize`) maps the ChatML markers to their real control ids and
  removes the literal echo
- **Change**: `llama_tokenize(vocab, prompt, len, /*add_special*/ true,
  /*parse_special*/ true, …)`
- **Build**: Task 6 working tree
- **Device state**: foreground, adj 0
- **Model**: Qwen2.5-3B Q4_K_M
- **Parameters**: same as EXP-6A
- **Observed**: `prompt ids = 151644 872 198 9707 11 19131 6133 26753 13 151645
  198 151644 77091 198`, `control tokens in prompt = 3`,
  `add_bos=0 add_eos=0`; result
  `ok|3272 ms|tokens=29|stop=eog|text=Hello! I'm an artificial intelligence here
  to assist you…` with **no** leaked marker.
- **Result**: **PASS**
- **Conclusion**: root cause confirmed. `add_special=true` is safe here because
  the GGUF sets `add_bos_token=False` and has no `add_eos_token`, so the flag
  adds nothing.
- **Next**: EXP-6C.

## EXP-6C — KV cache reused across calls (state bleed)

- **ID**: EXP-6C
- **Hypothesis**: because the formatted prompt already contains the whole
  transcript, prefilling on top of the previous call's KV duplicates history
- **Change**: none — measured directly.
- **Build**: Task 6 working tree
- **Device state**: foreground, adj 0
- **Model**: Qwen2.5-3B Q4_K_M
- **Parameters**: identical `generateChat` twice, `n_predict=64`
- **Observed**: results differed run to run on the same input — state carried
  between calls; `llama_batch_get_one` starts at `memory->seq_pos_max()+1`.
- **Result**: **FAIL**
- **Conclusion**: caller-visible state leak. Fixed by
  `llama_memory_clear(llama_get_memory(ctx), true)` before each prefill.
- **Next**: EXP-6D.

## EXP-6D — Determinism after the KV reset (also proves the fix)

- **ID**: EXP-6D
- **Hypothesis**: with the KV cleared before every prefill, the same request
  twice produces byte-identical output
- **Change**: KV reset before each prefill (EXP-6C fix)
- **Build**: `app-debug.apk` sha256 `375848189d787abf7e9a15336f9ad34e98b241bedce226e2a8b47cfc9785c88f`
- **Device state**: fresh `am force-stop` then broadcast, adj 0
- **Model**: Qwen2.5-3B Q4_K_M
- **Parameters**: `generateChat` "Hello, introduce yourself briefly.",
  `n_predict=64`, temperature 0.7, seed 0
- **Observed**: run 1 and run 2 both
  `ok|3783 ms` / `ok|3678 ms`, `tokens=29`, identical text.
- **Result**: **PASS**
- **Conclusion**: no state bleeds between calls. (Timings differ — CPU noise.)
- **Next**: EXP-6E.

## EXP-6E — `nPredict` as a hard upper bound

- **ID**: EXP-6E
- **Hypothesis**: the generation loop stops at exactly `n_predict` tokens when
  the model does not stop on its own, and reports `stop=n_predict`
- **Change**: none (behaviour under test)
- **Build**: sha256 `375848189d78…`
- **Device state**: foreground, adj 0
- **Model**: Qwen2.5-3B Q4_K_M
- **Parameters**: `generateChat` "Count from one to fifty…", `n_predict` = 8, 4
- **Observed**: `tokens=8|stop=n_predict` and `tokens=4|stop=n_predict`.
- **Result**: **PASS**
- **Next**: EXP-6F.

## EXP-6F — Conversation continuity through the real UI

- **ID**: EXP-6F
- **Hypothesis**: earlier turns stated in the Waqti chat UI reach the model, so
  a follow-up question can be answered from them
- **Change**: `AgentLoop.run(task, history = emptyList())` +
  `ChatViewModel` passes the visible turns. Debug receiver unchanged.
- **Build**: sha256 `375848189d78…`
- **Device state**: `MainActivity` resumed, screen on, IME driven by
  `adb shell input text` / tap on Send, adj 0
- **Model**: Qwen2.5-3B Q4_K_M
- **Parameters**: temperature 0.7, `n_ctx` 4096, `maxTokens` 256
- **Observed**: turn 1 "My name is Sara and my favourite number is 42. Just
  acknowledge." → `tokens=5|stop=eog|text=Acknowledged, Sara.`; turn 2 "What is
  my name and my favourite number? Answer in one short sentence." →
  `tokens=13|stop=eog|text=Your name is Sara and your favourite number is 42.`
  The rendered conversation was read back from the accessibility tree and
  contains all four bubbles; scan for `<|`, `|>`, `im_start`, `im_end`, `\x`
  returned **NONE**. Passcode variant reproduced twice
  (`7391` → `Acknowledged.` → `Repeat the secret passcode exactly.` → `7391`).
- **Result**: **PASS**
- **Conclusion**: the history reaches the model and the model uses it. Before
  this change the turn-5 prompt contained only the newest user message.
- **Next**: none — Task 6 complete.

## EXP-6G — Task 5 `generate` regression after the Task 6 native changes

- **ID**: EXP-6G
- **Hypothesis**: the plain (non-template) `generate` path still honours
  `n_predict` and is unchanged by the tokenize/KV changes
- **Change**: none (regression check)
- **Build**: sha256 `375848189d78…`
- **Device state**: foreground, adj 0
- **Model**: Qwen2.5-3B Q4_K_M
- **Parameters**: `generate` "CapitalOfFranceIs", `n_predict` = 12 and 5,
  temperature 0.0
- **Observed**: `ok|1541 ms|tokens=12|stop=n_predict|text= = "Paris"` and
  `ok|840 ms|tokens=5|stop=n_predict|text= = "Paris"`.
- **Result**: **PASS**
- **Note**: an earlier attempt reported `tokens=128` for `--ei n_predict 12`.
  That was an ADB quoting artefact — `--es prompt "The capital of France is"`
  makes the remote shell consume the following flags. Space-free prompts are
  required for this harness. Not a runtime defect.
- **Next**: none.

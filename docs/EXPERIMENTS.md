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

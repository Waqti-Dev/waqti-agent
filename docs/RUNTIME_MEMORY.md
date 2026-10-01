# Runtime memory — model load, RSS, and process importance

Status: Task 4 memory investigation **complete — Task 4 PASS** (first attempt
failed: LMK SIGKILL at adj 905; resolved by foreground-important loading, no
code change). This file holds memory/runtime behaviour, the measurement
protocol, results, and decisions. Experiment history → `docs/EXPERIMENTS.md`.

## 1. Memory concepts (never conflate these)

```text
GGUF file size          2,104,932,768 bytes (Qwen2.5-3B Q4_K_M) — disk, not RAM
load RSS                resident pages while llama_model_load_from_file() runs
steady-state RSS        after load completes, before any context exists
generation memory       context + KV cache + compute graph (Task 5, later)
```

Task 4 only requires **load RSS to stay survivable** until
`llama_model_load_from_file()` returns. Generation memory is out of scope
until Task 5.

`mmap` maps file pages on demand: it avoids an explicit full copy but does
**not** cap RSS — anything that touches mapped pages (reads, checks,
readahead) makes them resident and counts toward LMK's decision.

## 2. Confirmed failure (2026-09-30, LIVE-OBSERVED)

```text
Model:            Qwen2.5-3B Q4_K_M (GGUF V3, qwen2, 435 tensors, Q4_K)
Model size:       2,104,932,768 bytes
Model SHA-256:    626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d
                  (host and device identical — transfer verified)
llama.cpp:        a894dae939d426954ce54bb604824f1ae918a0c5 (0.4.1-dev)
Runtime:          in-process JNI, libwaqti_local_runtime.so, arm64-v8a
Result:           FAIL
Failure:          Android/MIUI lowmemorykiller sent SIGKILL during
                  llama_model_load_from_file()
Peak observed RSS: ~1.51 GB (1,513,292 kB, measured by LMK at kill time)
oom_score_adj:    905 (cached/background process)
Crash:            none — no fatal signal, no llama.cpp error, no GGUF
                  corruption; process simply ceased (signal 9)
```

Exact kill line:

```text
09-30 14:35:12.041 I lowmemorykiller: Kill 'com.waqti.agent' (31728), uid 10702,
    oom_score_adj 905 to free 1513292kB rss, 34512kB swap; reason: cw
09-30 14:35:12.118 I Zygote: Process 31728 exited due to signal 9 (Killed)
```

Load timeline (pid 31728, thread 31760 = `waqti-model-load`):

```text
14:35:10.103 waqti-task4 : load requested: /data/data/com.waqti.agent/files/…gguf (2104932768 bytes)
14:35:10.129 waqti-native: loading model: …, llama.cpp 0.4.1-dev
14:35:10.189 waqti-native: llama_model_loader: 26 KV pairs, 435 tensors, GGUF V3
14:35:10.484 waqti-native: init_tokenizer: initializing tokenizer for type 2
14:35:10.647 waqti-native: load_tensors: loading model tensors… (load_mode = mmap)
14:35:10.658 waqti-native: done_getting_tensors: … CPU_REPACK unavailable, using CPU …
14:35:12.041 lowmemorykiller SIGKILL (RSS 1,513,292 kB, adj 905)   ← killed here
             (no "loadModel result:" line — load never returned)
```

Note: `mmap was enabled, but mmap does not guarantee low RSS during
initialization` — the observed peak (1.51 GB) is close to the file size
(1.96 GiB), so ~77% of the mapping had become resident within ~1.4 s of
`done_getting_tensors` before the kill.

### Already proven (do not re-test)

- APK installation works (`adb install -r`, byte-identical sha256).
- GGUF transfer into app-private storage works (`run-as` + push).
- Device SHA-256 == host SHA-256 == `626b4a66…15c62d`.
- Correct private model path works (`filesDir`-relative contract).
- Debug-only receiver triggers the load; JNI `loadModel` is invoked in the
  Waqti process (pid attribution via logcat pid column).
- llama.cpp parses the real GGUF (metadata, 435 tensors) in-process.
- Tensor creation works; tokenizer initialization works.
- The process dies to Android memory management, not to a llama.cpp error.

## 3. Hypotheses (investigation order)

| ID | Hypothesis | Status | Evidence |
|---|---|---|---|
| H1 | Process importance: load at `oom_score_adj 905` (cached) is an LMK victim; a foreground-important component changes the outcome | **CONFIRMED — decisive** | EXP-00 (adj 905 ⇒ SIGKILL) vs EXP-A/A2 (adj 0 ⇒ completed while LMK killed four other processes) |
| H2 | Temporary init memory beyond the final mmap footprint | **CONFIRMED mechanism: MAP_POPULATE prefetch** | `llama-model.cpp:1734` hardcodes `init_mappings(true,…)` → `llama-mmap.cpp:480` `MAP_POPULATE` ⇒ load RSS ≈ file size; RSS ramp sits exactly between `done_getting_tensors` and the `CPU_Mapped … 2001.74 MiB` print; EXP-00 died inside that window. No graph/context temp memory (EXP-D) |
| H3 | CPU_REPACK/backend buffers add copies during init | **CLEARED** | EXP-C: fallback at tensor-metadata creation (`llama-model-loader.cpp:1403`), pre-data; KLEIDIAI OFF ⇒ no repack buffers; mmap-view path taken |
| H4 | Waqti JNI/runtime makes unnecessary copies of the GGUF | **CLEARED** | EXP-B: JNI reads only path + `stat()`; `buffer_from_host_ptr=true` (`ggml-cpu.cpp:398`); progress 0–100 % in 1 ms; PSS Native Heap 51,754 kB vs model 2,055,598 kB |
| H5 | Context/KV allocated during load | **CLEARED — 0 bytes** | EXP-D: JNI surface has no context-creation API; no KV/graph until Task 5 |
| H6 | Thread count / backend init temp memory | **NO EVIDENCE (UNKNOWN)** | EXP-E: load = 1.37 CPU-s / 1.44 s wall ≈ 0.95 core; not varied ⇒ no change made |
| H7 | System pressure / MIUI Greezer / cached status materially affect the outcome | **CHARACTERIZED** | Both PASS runs happened during live LMK waves (kills of adj 800–905 processes) — pressure is routine; *cached status* + populate RSS is what kills (EXP-00); foreground survives (EXP-A/A2). Greezer broadcast quirk = delivery issue, recorded in LESSONS |

## 4. Measurement protocol (read-only, development-time only)

No invasive permissions; nothing here ships as user-facing UI. All commands
run over adb against the debug build.

```bash
# identity + priority
adb shell pidof com.waqti.agent
adb shell cat /proc/<PID>/oom_score_adj
# kernel memory counters (kB)
adb shell grep -E 'VmRSS|VmSize|VmSwap|VmPeak' /proc/<PID>/status
# PSS breakdown (dalvik/native/stack/mmap…) + total
adb shell dumpsys meminfo com.waqti.agent
# system-wide availability
adb shell grep -E 'MemTotal|MemAvailable|MemFree|SwapTotal|SwapFree' /proc/meminfo
```

Sampling loop (before / during / near-peak / after / on-failure):

```bash
PID=$(adb shell pidof com.waqti.agent | tr -d '\r')
while [ -n "$PID" ]; do
  echo "$(date +%H:%M:%S.%3N) adj=$(adb shell cat /proc/$PID/oom_score_adj 2>/dev/null) $(adb shell grep -E 'VmRSS|VmSwap' /proc/$PID/status 2>/dev/null | tr '\n' ' ')"
  sleep 0.2
  PID=$(adb shell pidof com.waqti.agent | tr -d '\r')
done
```

Capture windows: `before load`, `during load` (0.2 s cadence), `near peak`,
`after load` (steady state), `on failure` (kill line from logcat +
`dumpsys meminfo` unavailable ⇒ record LMK-reported RSS from the kill line).

Correlate RSS samples with `waqti-native` logcat timestamps to attribute the
growth to a load phase (metadata / tokenizer / tensor creation / buffer
allocation / post-`done_getting_tensors`).

## 5. Results

Full records with fields → `docs/EXPERIMENTS.md` (EXP-00, EXP-A, EXP-A2,
EXP-B…EXP-F). Summary (all LIVE-OBSERVED unless marked):

| Measurement | Value |
|---|---|
| Before load | VmRSS 207,592–208,580 kB (foreground idle) |
| Load duration | **1357 ms** (EXP-A) / **1438 ms** (EXP-A2) → `ok\|…` result line |
| Peak RSS (during load) | **1,908,000 kB** (EXP-A, 0.3 s cadence) / **2,295,632 kB** (EXP-A2, 0.2 s cadence) |
| PSS at load | 1,691,876 kB total — Other mmap 1,524,490 kB (private-clean file pages), Native Heap 51,754 kB, Dalvik 22,125 kB |
| After load (immediate) | VmRSS ~1.77–2.13 GB, then reclaim |
| Idle steady (~2 min, model still held) | **VmRSS 318,584 kB, PSS 272,344 kB** (Other mmap 176,321 kB) |
| Swap | VmSwap 66,756 → 29,956 kB during load; 86,032 kB idle |
| oom_score_adj | **0** during both PASS loads; 701 idle later (survived); 905 = killed (EXP-00) |
| CPU during load | 137 ticks (1.37 s) / 1.44 s wall ≈ **0.95 core** (EXP-A2) |
| System memory | MemTotal 11,502,936 kB; MemAvailable 3,263,008 → 3,375,412 kB (A2 before→after) |
| Context/KV allocation | **0** (never created in Task 4 — EXP-D) |
| First-token latency / generation tok/s / thermal | UNKNOWN (Task 5 — not started) |

Load RSS ≈ file size is **transient and file-backed**: it reclaims from
2,295,632 kB to 318,584 kB with the model still loaded. The idle cost of a
loaded model is small; generation-time working set + KV is Task 5's separate
question.

## 6. Decisions taken (evidence-based, no guessing)

1. **No code change was required for Task 4 PASS.** The failure was process
   importance, not implementation. Fix = operating rule: model init runs while
   the process is foreground-important (resumed activity). Cached-process
   loads are forbidden.
2. **No foreground service added**: a 1.4 s load in the user's resumed
   activity needs none; Android 14+ FGS types / Android 15 `dataSync` 6-hour
   cap make FGS a poor fit (see `docs/LESSONS_LEARNED.md`). Reopen only for a
   genuine background-load requirement.
3. **No llama.cpp patch**: prefetch/`MAP_POPULATE` is hardcoded with no public
   knob at `a894dae` (EXP-F); the peak is transient + reclaimable and
   foreground loading completes fine. Reopen only with evidence that populate
   kills even at low adj.
4. **No model change**: Qwen2.5-3B Q4_K_M loads successfully; device capacity
   was never the constraint — priority was.
5. `OpenAICompatProvider` untouched; no external server used in any evidence
   (all evidence is in-process JNI logs in the Waqti pid).

References: AOSP lmkd — source.android.com/docs/core/perf/lmkd; FGS types —
developer.android.com/develop/background-work/services/fgs/service-types;
llama.cpp discussion #29347 (mmap lazy-load OOMs) and issue #864 (mmap RSS /
page-cache accounting); pinned llama.cpp `a894dae` (local source lines cited
above).

## 7. Task 5 — generation memory measurements

Task 4 measured model loading; Task 5 adds context/KV/compute/generation memory.

```text
Memory timeline (Qwen2.5-3B Q4_K_M, foreground adj 0, n_ctx=4096):
Baseline (app idle):                    VmRSS ~210 MB
Model loaded:                           VmRSS 2,310 MB (mmap, file-backed)
Context created (KV cache):             VmRSS 2,366 MB (+55 MB, f16 K/V)
Generation (64 tok):                    VmRSS 2,464 MB (+98 MB compute)
Generation (128 tok):                   VmRSS ~2,464 MB (stable)
Release context:                        KV freed, model still mapped
Idle steady (model held):               VmRSS reclaims to ~320 MB
```

Key findings:
- Context/KV memory: **~55 MB** for 4096-cell f16 KV cache (36 layers, 1 seq)
- Generation compute buffers: **~98 MB** peak during llama_decode (CPU sched)
- Total generation peak: **~2.46 GB** (file-backed model + KV + compute)
- MemAvailable stable: 4,942 MB → 4,955 MB (no system pressure)
- First-token latency: embedded in generation time (~50-100 ms for prefill)
- Tokens/sec: ~11 tok/s (CPU, 4 threads, Flash Attention ON)
- No LMK pressure during generation at adj 0; system pressure observed but other processes killed
- Repeated generation in same context fails (KV position not reset) — must releaseContext/createContext

## 8. Task 6 — repeated generation with a per-call KV reset (2026-10-01)

Task 6 added `llama_memory_clear(llama_get_memory(ctx), true)` before every
prefill, because the formatted prompt always contains the whole transcript.
Without the reset, `llama_batch_get_one` continued from
`memory->seq_pos_max() + 1` and each call re-prefilled the history on top of the
previous call's KV.

That raises a new question: does clearing the KV on every call leak or fragment?

Method — same APK as Task 6 (`sha256 375848189d78…`), model loaded and
`n_ctx=4096`, `n_batch=512`, 6 sequential `generateChat` calls
(`n_predict=8`, `temperature=0.0`), `dumpsys meminfo com.waqti.agent` sampled
after each call:

| Point | TOTAL PSS (kB) | Native Heap (kB) |
|---|---|---|
| model loaded, context created | 2,435,559 | 249,798 |
| after call 1 | 2,416,837 | 251,422 |
| after call 2 | 2,416,833 | 251,438 |
| after call 3 | 2,417,085 | 251,730 |
| after call 4 | 2,416,821 | 251,462 |
| after call 5 | 2,416,833 | 251,470 |
| after call 6 | 2,416,697 | 251,342 |

Result: **PASS** — native heap is flat (±0.2 %) across six
clear-then-prefill cycles; PSS is flat within noise. The KV reset does not
accumulate. Peak PSS during real UI turns was 2,403,033–2,423,257 kB
(LIVE-OBSERVED), unchanged from the Task 5 baseline of ~2.46 GB.

Task 5's note "repeated generation in the same context fails (KV position not
reset) — must releaseContext/createContext" is now **superseded**: the runtime
resets the KV itself before each prefill, so the caller does not have to.
`release()`/`unloadModel()` remain available for lifecycle, not for correctness.

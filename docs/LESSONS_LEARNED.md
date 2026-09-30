# Lessons learned — durable discoveries (on-device inference migration)

Project-level pre-migration lessons live in `KNOWLEDGE/LESSONS_LEARNED.md`
(read-only). This file records migration-era facts that future sessions must
not rediscover the hard way. Evidence labels: LIVE-OBSERVED / REPO-verified.

## Triggering the debug receiver (Task 4 harness)

- **Path contract**: `ModelLoadDebugReceiver` resolves *relative* paths against
  `context.filesDir` (…/files/), so the correct extra is `--es path
  <file>.gguf`, NOT `files/<file>.gguf`. Absolute paths inside `filesDir` are
  also accepted. (LIVE-OBSERVED refusal: `files/files/…` → "refused: not a file".)
- **MIUI Greezer defers broadcasts to cached processes**: a second broadcast to
  an already-running-but-cached app process is held (`BroadcastQueue: Greezer
  Denial … need cached broadcast`) and may never arrive while the process sits
  at `oom_score_adj ≥ 900`. (LIVE-OBSERVED, 2026-09-30.)
- Reliable headless sequence: `am force-stop com.waqti.agent` → `logcat -c` →
  `am broadcast …` (cold start for the broadcast ⇒ delivered, proven twice).
- `am broadcast` printing `Broadcast completed: result=0` only means *enqueued*
  for manifest receivers — it does NOT prove `onReceive` ran. Confirm via
  logcat lines from the app's pid.

## Model-load memory (Task 4 failure)

- **mmap ≠ low RSS.** With `load_mode = mmap`, the process still reached
  1,513,292 kB RSS within ~1.9 s of load start; the file pages become resident
  as they are touched, and LMK counts them.
- **A cached (background) process performing a multi-GB load is LMK's first
  target**: `oom_score_adj 905` + system reclaim wave ⇒ SIGKILL mid-load, with
  no llama.cpp error and no crash trace — the only trace is the
  `lowmemorykiller: Kill …` line and `Zygote: … exited due to signal 9`.
- Host-side GGUF validation, APK hashes, and symbol checks say nothing about
  on-device survivability. Only device evidence counts.

## Build/evidence discipline (carried forward)

- Build success ≠ task success; packaging ≠ runtime proof.
- Record artifact sha256 per evidence set; the same APK gets re-hashed at each
  step so "exact bytes" claims stay auditable.
- Check `docs/EXPERIMENTS.md` before running an experiment; never repeat an
  unchanged failed one.

## Why the load was killed (Task 4 root cause — source + device)

- llama.cpp **hardcodes prefetch** of model mappings: `llama-model.cpp:1734`
  `ml.init_mappings(true, …)` → `prefetch_size = -1` → `llama-mmap.cpp:480`
  `flags |= MAP_POPULATE` (no lazy ranges). The whole GGUF is faulted into RSS
  at mmap time ⇒ **load RSS ≈ file size regardless of `use_mmap`**. No public
  `llama.h` knob exists at `a894dae` (grep: no `prefetch` field).
- Those pages are **file-backed and reclaimable**: observed VmRSS
  2,295,632 kB → 318,584 kB within ~2 min *with the model still loaded*.
  Idle steady state is small; the big number is a transient populate.
- **Process importance decided the outcome**, not model size: identical run at
  `oom_score_adj 905` ⇒ SIGKILL (EXP-00); at adj 0 ⇒ completed in ~1.4 s while
  LMK killed four *other* processes instead (EXP-A/A2).
- Upstream corroboration: llama.cpp discussion #29347 (mmap "lazy loading …
  can also cause unexpected OOMs", Sep 2026) and issue #864 (mmap'd weights
  show up in page cache / RSS); AOSP lmkd:
  source.android.com/docs/core/perf/lmkd (adj ladder 900–1000 = cached,
  first to die).

## No copies anywhere in the load path (audited, EXP-B/C/D)

- JNI `waqti_local_runtime.cpp`: touches only the path + `stat()`; invokes
  `llama_model_load_from_file` with defaults (`n_gpu_layers = 0`).
- llama.cpp: CPU backend `buffer_from_host_ptr = true` (`ggml-cpu.cpp:398`) ⇒
  mmap-view path (`llama-model.cpp:1776`); `load_data_range` returns a pointer
  without reading when `use_mmap` (`llama-model-loader.cpp:1474`); progress
  0→100 % within 1 ms = no I/O pass; buffer is named `CPU_Mapped`.
- Device PSS: Native Heap 51,754 kB vs model 2,055,598 kB ⇒ no duplication.
- The `CPU_REPACK … using CPU instead` line is a buffer-type fallback at
  tensor-*metadata* creation (`llama-model-loader.cpp:1403`) — benign.
- Context/KV/compute graph = **0 bytes** during Task 4 (no context API is
  reachable from the JNI surface).

## Foreground-service policy (why none was added)

- Model init takes ~1.4 s while the user's activity is resumed — a
  foreground service would be ceremony, not engineering.
- Android 14+ requires FGS types; Android 15 caps `dataSync` FGS at 6 h
  (developer.android.com) — a poor fit for model initialization. Add an FGS
  only if a genuine user-visible background-load requirement appears; never as
  a way to mask LMK victimhood.
- Operating rule: **model initialization runs while the process is
  foreground-important** (resumed activity). Cached-process loads are
  forbidden — they are LMK victims (EXP-00).

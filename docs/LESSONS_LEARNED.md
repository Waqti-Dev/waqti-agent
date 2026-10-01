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

## Task 5 — generation lessons

- **Chat template is essential**: raw prompt without `llama_chat_apply_template` produces hallucinated/off-topic output. Qwen2.5 uses ChatML template; must apply before tokenization.
- **KV position tracking**: llama.cpp's `llama_batch.pos` auto-increments; repeated `generate()` in same context accumulates position → prefill fails when pos >= n_ctx. Fix: releaseContext/createContext between generations, or explicitly reset KV.
- **Flash Attention + CPU**: `LLAMA_FLASH_ATTN_TYPE_AUTO` enables fused kernels on CPU (confirmed by "Flash Attention enabled" log). ~11 tok/s on 8-core ARM64.
- **Batch initialization matters**: `llama_batch_get_one` returns logits=nullptr → crash on deref. Use `llama_batch_init(n_tokens, 0, 1)` + manual token/logits/pos/seq_id setup.
- **Generation memory is additive**: model (mmap) + KV (55 MB f16) + compute (98 MB) = ~2.5 GB peak RSS. All file-backed except KV/compute.
- **Prompt formatting**: simple "User: ... Assistant: " prefix works but produces low quality. Chat template required for Task 6+.
- **Thermal**: UNKNOWN — no thermal API used; device stayed cool during 12s generation.

## Tokenizing a formatted chat prompt (Task 6)

- **`parse_special` is not a detail.** `llama_tokenize(..., add_special,
  parse_special)` with `parse_special=false` makes
  `tokenizer_st_partition()` **skip** CONTROL/UNKNOWN tokens, so ChatML markers
  in an already-formatted prompt are silently broken into ordinary text pieces.
  The model then sees an off-distribution prompt and echoes the marker back as
  literal text. Symptom looks like a detokenizer bug; it is not. Tokenize the
  way `common_tokenize` does — `add_special=true, parse_special=true`.
  (`add_special=true` is harmless when the GGUF sets `add_bos_token=False` and
  has no `add_eos_token`.)
- **Control tokens are not always CONTROL class.** In this GGUF
  `<|im_start|>`/`<|im_end|>` carry `token_type=3` (NORMAL|UNKNOWN), not 4
  (CONTROL). llama.cpp overrides the attribute for EOG lookalikes, and
  `token_to_piece` returns **0 characters** for CONTROL/UNKNOWN when
  `special=false` — so UNKNOWN alone is already enough to suppress rendering.
  Do not string-compare token text to special tokens.
- **The right EOS/EOG boundary is `llama_vocab_is_eog()` before rendering.**
  `llama_detokenize(..., remove_special)` is the wrong knob for an incremental
  sampler loop: it operates on a whole token array.

## llama.cpp C API lifetimes and context (Task 6)

- **`llama_chat_message` borrows `const char *`.** Its `role`/`content` must
  outlive `llama_chat_apply_template`. Building them from loop-local
  `std::string`s is a use-after-free that happens to work. Collect into owning
  `std::vector<std::string>` storage first, then build the message array.
- **`llama_batch_get_one(&token, 1)` auto-tracks position** from
  `memory->seq_pos_max() + 1` (pos=nullptr, logits=nullptr). That is the
  official pattern — but it means **each prefill starts where the last one
  ended**, so if the prompt already contains the whole transcript you must call
  `llama_memory_clear(llama_get_memory(ctx), true)` first, or history is
  duplicated in the KV. Verified flat native heap over six clear-then-prefill
  cycles.

## Android/ADB harness traps (Task 6)

- **`adb shell am broadcast --es k "a b" --ei n 12` does not survive the remote
  shell** — the unquoted spaces swallow the following flags and the receiver
  falls back to its defaults (a real "n_predict=128" scare). Use space-free
  values, or base64 (`chat_json_b64`), for anything non-trivial.
- **JSON cannot be passed through `--es`**: the shell eats the double quotes.
  Base64 is a single shell-safe word and is the supported transport for
  multi-turn chat validation.
- **`logcat -c` must be issued per test**, otherwise a poll matches a stale
  `generation done` line and you "prove" the wrong generation.
- **`uiautomator dump` on a Compose screen can return a tree without the
  conversation bubbles** when the IME is up or the list has not laid out. Dump
  again after re-foregrounding; the text is really there.
- **Settings store a model *name*, not a path.** A bare filename handed to
  `loadModel()` fails `stat()` and surfaces as the misleading
  `error|cannot stat file`. Resolve bare names against `filesDir`.
- **Compose `savedState` restores stale composer text after reinstall.** Always
  confirm the prompt that was actually sent from the `formatted prompt` log, not
  from the visible field.

## The harness strips `<|...|>` from tool output (Task 6)

- Shell/tool output pipelines in this environment **delete `<|...|>` sequences**
  before display. A token leak therefore looks like a clean line, and a log
  line containing a marker looks like it has none. Read such lines with
  `hexdump -C`, or hex-escape them at the source (`log_escape()` in the native
  runtime: `<`→`\x3c`, `>`→`\x3e`) and unescape in Python. This display
  artefact already caused a leak to be mis-diagnosed as a rendering bug once.

## `llama_token_data_array.sorted` is a caller contract, not an output (Task 7)

**Symptom.** Every tool-enabled reply came back as garbage made of punctuation
and digits — `None,500)@&357647355918575766562426213124855808...` — while the
non-tool path produced clean prose from the very same code.

**Root cause.** Our grammar path builds its own candidate array instead of
calling `llama_sampler_sample()`. `refresh_candidates()` refills the buffer in
**token-id order** from `llama_get_logits_ith()` each step, but it reset `data`,
`size` and `selected` and **not `sorted`**. The chain's `top_k`/`top_p` set
`sorted = true` after reordering, and `llama_sampler_top_k_impl()` skips its
sort entirely when `sorted` is already true:

```cpp
if (!cur_p->sorted) { llama_token_data_array_partial_sort_inplace(cur_p, k); }
cur_p->size = k;
```

So from the second token onward `top_k` truncated to the *first* `k` entries —
token ids `0, 1, 2, …` (`!`, `"`, `#`, …) — instead of the `k` highest logits.
On-device proof: raw argmax was id 315 `" of"` (logit 29.6) while the chain
returned id 11 `,`, and the surviving set was literally `0 1 2 3 4 5 … 11`.

**Fix.** `candidates.sorted = false;` whenever the buffer is rebuilt. One line.

**Transferable rules.**
- Any code that refills a `llama_token_data_array` by hand MUST reset `sorted`.
  It is caller-owned state, and a stale value silently disables sorting.
- Prefer `llama_sampler_sample(smpl, ctx, -1)` over hand-rolling the candidate
  array; it owns this bookkeeping. Hand-rolling is only justified when a sampler
  (e.g. a lazy grammar) must mask the candidate set first.
- Also note `llama_sampler_accept()` must be called on **every** sampler that
  holds state, not just the chain — a lazy grammar advances and matches its
  trigger on accept.

## Instrumenting a sampler: do not trust your own probe (Task 7)

A first attempt at diagnosing this printed "the top candidates are ids 0–4",
which looked like conclusive proof of a corrupt vocabulary. The loop was

```cpp
for (int t = 0; t < candidates.size && nt < 5; ++t)   // BUG
```

The `nt < 5` in the loop condition **terminates the scan** after five entries, so
it reported the first five candidates *in id order*, not the five highest logits.
The conclusion drawn from it was wrong and sent the investigation after
`llama_get_logits_ith()` and the backend-sampled row, which a direct probe
(`get_sampled_logits_ith = nullptr`) then disproved.

**Rule.** Before acting on a diagnostic, confirm the diagnostic can observe what
it claims to. A probe that is silently self-limiting looks exactly like a
strong, surprising finding. Prefer dumping the actual surviving candidate array
after the samplers run over reporting a recomputed argmax.

## MIUI/HyperOS drops broadcasts to apps that have no cached-broadcast state

`am broadcast -n com.waqti.agent/.runtime.ModelLoadDebugReceiver` returns
`Broadcast completed: result=0` but the receiver never runs. logcat shows:

```
W/BroadcastQueue: Greezer Denial: sending Intent {...}, action: ... from null
  (uid=2000) due to receiver ProcessRecord{...com.waqti.agent...}
  need cached broadcast
```

This is the OEM broadcast firewall, not an app bug — it also drops
`BATTERY_CHANGED` and GCM intents. **Do not conclude the receiver is
unregistered, that the class is missing from the dex, or that logs rotated**
without checking for `Greezer Denial`; all three were wrong conclusions here.
On this device, drive native code through the UI instead.

## RSS is not a model-loaded signal on Android (Task 7)

The 2.1 GB GGUF is mapped file-backed, so `ps` RSS sat at ~180 MB with the model
fully loaded and Ready in the UI, and appeared "healthy" at ~2.4 GB in other runs.
Gate on the UI's own state (the status pill) plus log evidence, never on RSS.

## "The tool found nothing" was an empty workspace, not a tool bug (Task 7)

`SearchFiles` returning `matches: 0 in 0 file(s)` for *every* query looked like a
broken grep. It was not. `SettingsStore.defaultWorkspace` is
`getExternalFilesDir(null)/workspace`, i.e.
`/storage/emulated/0/Android/data/com.waqti.agent/files/workspace`, while the test
fixture files had been written to the **internal** `filesDir/workspace`. Two
different directories; the app only ever searched the empty external one.

The `matches: N in M file(s)` header counts *matching* files, not scanned files,
so it cannot distinguish "searched and found nothing" from "scanned nothing". The
`scanned N file(s) under ...` footer does, and the UI only surfaces the header —
**always read the footer before concluding a tool is broken.**

Corollary: an agent whose tools are all pointed at an empty directory will look
like a model that hallucinates ("none of the files contain X") when it is really
an agent with nothing to see. After pushing three real files into the external
workspace, the same `SearchFiles {"query":"fun"}` returned
`matches: 2 in 2 file(s)` and the model cited `src/Greeting.kt:1` and
`src/MathUtil.kt:1`. Populate the workspace *before* judging tool behaviour.

`run-as com.waqti.agent` cannot write into `/storage/emulated/0` (the app's own
external dir is invisible to it — "Permission denied"); use `adb push` instead.

## The GGUF's own chat template doubles the tool-call braces (Task 7)

The `<​tool_call>` example rendered into the prompt is
`{{"name": <function-name>, "arguments": <args-json-object>}}` — doubled braces.
The origin is the **model file**, not Waqti:

- `Qwen/Qwen2.5-3B-Instruct` `tokenizer_config.json` → `5c 6e 7b 5c 22` = `\n{"name`
- llama.cpp `models/templates/Qwen-Qwen2.5-7B-Instruct.jinja` (canonical) → same, single brace
- this GGUF's `tokenizer.chat_template` → `5c 6e 7b 7b 5c 22` = `\n{{"name`

So the divergence is in the GGUF's embedded `tokenizer.chat_template`, which
doubles `{`/`}` in the Jinja string literal. Not serialization, not logging, not
UI, not model output — the model emits *single* braces and the PEG parser
accepts them, so it is cosmetic. Do not "fix" it in Waqti; it would mean
shipping a hardcoded template and would be an unjustified change without evidence
that it affects behaviour.

## Task 7 layer attribution: the protocol is correct, the model decision is not

Isolating the three layers on the real UI:

- **Layer A, prompt construction: PASS.** Byte-verified official Qwen2.5 ChatML —
  3× `<|im_start|>`, `# Tools` block from
  `common_chat_tools_to_json_oaicompat`, `parameters` rendered as a nested object
  (not a string), tool results as `<tool_response>` in a `user` turn, generation
  prompt `<|im_start|>assistant\n`. `tool_choice=AUTO`, `parallel_tool_calls=false`,
  `use_jinja=true`, lazy grammar with a single `<​tool_call>\n` trigger.
- **Layer C, tool loop: PASS.** Real on-device execution (SearchFiles 30 ms),
  results fed back, multi-round convergence, retry after a failed path argument,
  final answer grounded in actual matches.
- **Layer B, model decision: FAIL.** Knowledge-only prompts produce
  `tool_calls = 0` — the *expected count* — but 3 of 5 replies impersonate a
  search result (`None of the files contain the phrase "capital of France"`).

The often-repeated claim that the model "chooses SearchFiles for a knowledge
question" is **wrong**: `tool_calls_b64` decodes to `[]`. The model emits prose
that *looks like* a tool result. First token is `None` (id 4064) and the lazy
grammar never fires, so no tool call is attempted at any point.

Sampling is `temp=0.7, topK=40, topP=0.9, seed=0`, and `llama_sampler_init_dist(0)`
is a fixed seed (only `0xFFFFFFFF` is random), so the failure is **reproducible**,
not noise. Failing: "three Kotlin visibility modifiers" (×2), "capital of France".
Passing: "mutableListOf", "Kotlin data class". Prompt-dependent, model-dependent,
correct-prompt-dependent → a Qwen2.5-3B capability limit, not a Waqti defect.
Per the stop condition: research, do not paper over it with a bigger system prompt.

## A 7B that "calls tools badly" can be right about the tool and wrong about the syntax (Task 7)

The 3B-versus-7B comparison only became meaningful once the *prompt* was held
constant. The GGUF's own `tokenizer.chat_template` was extracted straight from
each file's KV header, re-rendered host-side, and the 3B reconstruction was
checked byte-for-byte against the prompt captured from the live device
(2962/2962 bytes) **before** it was used for the 7B. The two rendered prompts
then differed by exactly two characters: the 3B's embedded template doubles the
braces (`{{"name": …}}`), the 7B's does not. Everything else is identical.

With that held constant, the 7B's behaviour is unambiguous:

- knowledge-only prompt → **correct answer**, better than the 3B
- tool prompts → **fenced JSON**, `tool_calls_b64` decodes to `[]`

So the 7B picked the right tool and the right arguments and then wrote them in
the wrong syntax. It is not choosing the wrong tool, and it is not refusing to
use tools — a claim that reads very differently from "the 7B ignores tools", and
the wrong one to write down.

Reproducibility is the reason this counts as a limitation rather than noise:
`seed=0` is a fixed seed (only `0xFFFFFFFF` is random), and the same failing run
produced byte-identical 50-token output twice.

Two failures were actively avoided:

1. **A fenced-JSON parser.** Adding "parse ```json…``` as a tool call" would have
   turned a model formatting quirk into a Waqti feature, and would have started
   stealing well-formed JSON answers. It is not a fix; it is a second tool
   protocol wearing a hat.
2. **A zero-width-space theory.** Worth mentioning only because it was checked
   and discarded: the `<tool_call>` codepoints are clean
   `003C 0074 006F 006F 006C 005F 0063 0061 006C 006C 003E` in both templates
   and in the live prompt. There is no hidden character to strip.

Also worth knowing when a model "must" be the culprit: `common_chat_peg_parse` in
llama.cpp does *all* tool-call recognition. There is no Waqti-side parser. The
PEG arena loaded (9,831 bytes, `parse_tool_calls=1`) identically for both models,
which rules out the silent-empty-arena failure mode. Zero `stop=parse_error`
across 12 logs, and every tombstone in that window belonged to `com.whatsapp`,
not to Waqti.

## Round wall time is not a token rate (MVP finalization)

Run time for one round is reported as `ok|<ms>|tokens=<n>`. Dividing tokens by
that number gives nonsense — a 50-token round measured 0.78 tok/s on hardware
previously seen at 10–14 tok/s — because the number covers prefill, sampling and
output marshalling, and the dominant term is not decode. There is no per-phase
timing in this build to break it down.

Conclusion: log `llama_perf_context` / `t_eval_ms` / `n_eval` if a real rate is
ever needed. Until then **first-token latency and decode speed are UNKNOWN**,
and quoting a derived rate would be quoting a bug.

## The wireless adb serial is not stable; a mid-session rename is a real risk (MVP finalization)

The device moved from `192.168.1.2:46293` to `192.168.1.6:38383` between
sessions, and `adb connect` on an already-connected serial produces a "more than
one device" failure. Always re-read `adb devices` and address the device with
`-s SERIAL`; never assume the serial from a previous session.

Worse, the device went `offline` *while the 3B model was renamed out of
`filesDir`* to exercise the no-model UI path. A rename is the right primitive
(not deletion), but a rename still leaves the device in a state that only a
successful reconnect can undo. Any file manipulation under `filesDir` should be
followed immediately by a verification read, and the model left renamed or
restored — never in between — when the connection drops.

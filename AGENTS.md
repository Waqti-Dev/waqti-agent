# AGENTS.md — operating rules for OpenCode sessions

Durable rules only. Detailed findings live in `docs/` — never copy them here.

## Before touching anything

1. Run `git status` and `git rev-parse HEAD` first. Never reset, revert,
   rebase, cherry-pick, or rewrite history. Never push. Before any commit:
   `git diff` + `git status`, and every changed file must be justified by the
   current task.
2. Preserve user work — never modify, stage, or commit `.gitignore`,
   `README.md`, `site/`, or `KNOWLEDGE/`.
3. Read the runtime memory docs **before** any native-inference change:
   - `docs/ON_DEVICE_INFERENCE.md` — boundary, build, migration status
   - `docs/RUNTIME_MEMORY.md` — model-load memory behaviour + measurement protocol
   - `docs/EXPERIMENTS.md` — experiment history (check before running one)
   - `docs/LESSONS_LEARNED.md` — durable discoveries

## Evidence rules

- Never repeat an unchanged failed experiment; `docs/EXPERIMENTS.md` is the log.
- Runtime claims require real device evidence (adb/logcat/dumpsys), labelled
  `LIVE-OBSERVED`, `REPO-verified`, or `UNKNOWN`.
- Use only `PASS` / `FAIL` / `UNKNOWN`. Never infer success from compilation,
  APK packaging, symbol presence, host-side checks, or an external
  `llama-server`.
- Record durable discoveries in `docs/` as they happen, with source references.

## Migration state (check, don't assume)

- Task 4 = in-process GGUF load. While it is unresolved, **never start Task 5**
  (generation) or any later task. Status lives in `docs/ON_DEVICE_INFERENCE.md`.
- Keep `OpenAICompatProvider`; never use the external server to claim Task 4
  success. Target chain: `Waqti process → LocalModelProvider → JNI → llama.cpp → GGUF`.
- Preserve AgentLoop, tools, security boundaries, and the `ModelProvider`
  seam. JNI stays confined to `app/.../runtime/`.

## Scope

- No future features: memory/RAG, planner, reviewer/self-correction,
  multi-agent product architecture, browser, voice, vision, cloud routing,
  marketplace, accounts, billing, unrelated UI redesign or cleanup.
- Method: measure first → research second → change third → validate again.
  Do not optimize blindly, guess, or fake success.

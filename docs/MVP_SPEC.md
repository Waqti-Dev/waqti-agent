# Waqti MVP Specification

Waqti MVP is a phone-capable agent that receives a real task, reasons, uses tools, executes the task, and reports/verifies the result.

## M0 — Clean foundation
- clean Android project
- reproducible debug build
- app launches
- no OpenDroid source tree

## M1 — Agent Core
- task enters agent loop
- loop state observable
- model provider abstracted

## M2 — Real tools
- ListFiles works
- SearchFiles works
- tool errors return to loop
- permissions/timeouts explicit

## M3 — Coding workflow
- inspect project
- read relevant files
- make controlled edit
- run verification
- report changes

## M4 — Local model
- real GGUF model loads on target phone
- inference measurable
- memory/latency recorded

## M5 — Agent + local model
- local model drives real agent loop
- model requests tools through Waqti interface

## M6 — Reliability
- bounded retries
- failure recovery
- structured logs
- task trace
- review/verification

## M7 — Demonstrable MVP
- real multi-step task completed on phone
- tool execution evidence visible
- result verified
- build/artifact provenance recorded

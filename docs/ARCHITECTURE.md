# Waqti Architecture

Goal: a real Android-native AI agent whose architecture is owned by Waqti,
small enough to understand in one sitting.

## Modules

| Module  | Type        | Responsibility |
|---------|-------------|----------------|
| `:core` | Kotlin/JVM  | agent loop, model boundary, tools, policy, events. Zero Android imports, so every behaviour test runs on the JVM. |
| `:app`  | Android app | Compose UI, ViewModel state, settings persistence, platform permissions. Contains no agent logic. |

Dependency direction: `:app` → `:core`. Never the reverse.

## Agent loop

```
User task
  → AgentLoop (steps ≤ policy.maxSteps)
      → ModelProvider.respond(request)          # text  → final answer
      #                       or tool calls
      → ToolRegistry.invoke(name, jsonArgs)     # timed out / denied / failed → explicit failure result
      → append role=tool message                # real output or "ERROR: ..."
      → next model round
  → AgentOutcome(ok, answer | error, traces)
```

- **ModelProvider** — narrow: `respond(ModelRequest): ModelResponse`. The only
  code that knows the wire protocol is `OpenAICompatProvider`
  (OpenAI-compatible `POST /chat/completions`, non-streaming, cancellable).
  Remote APIs and local llama-server both fit behind the same interface;
  local/native runtimes (M4/M5) must implement the same interface.
- **ToolProvider** — `Tool` exposes a JSON-schema `ToolSpec`, takes a
  `JSONObject`, returns `ToolResult(success|failure)` and never throws for
  expected failures. `ToolRegistry.invoke` converts unknown tools, malformed
  JSON, and tool crashes into explicit failure results.
- **Policy** — `AgentPolicy(maxSteps, toolTimeoutMs, allowedTools?)`.
  Tool execution runs on a worker thread with a hard wall-clock timeout.
- **Observability** — `AgentEvent` stream (started, model round, tool started /
  finished with durations, completed, failed) consumed by the UI.

## Security boundary

`Workspace` is the single filesystem gate:

- root is canonicalised at construction (must exist and be a directory);
- relative paths resolve under the root; `..` and absolute paths outside are
  rejected with an explicit error;
- existing targets are re-checked after `toRealPath()`, so symlink escapes are
  rejected too;
- tools are read-only (list/search), bounded (scan caps, per-file size caps,
  result limits) and never execute shell commands.

The app requests `MANAGE_EXTERNAL_STORAGE` only for workspaces outside its own
storage; the default workspace is the app-specific external files directory.

## State

`ChatViewModel` exposes one `StateFlow<ChatUiState>` with an explicit
`TaskPhase { IDLE, WORKING, SUCCESS, ERROR }`, a live activity label, and the
tool trace of the run in progress. One running job at a time; Stop cancels it.

## Known future extensions (M3+)

Controlled file editing, local native inference behind the same ModelProvider
interface, bounded retries and task traces (M6). None of these may leak into
`:core` as vendor- or platform-specific types.

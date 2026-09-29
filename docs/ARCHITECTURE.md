# Waqti Architecture

Goal: build a real Android-native AI agent whose architecture is owned by Waqti.

Initial layers:
UI -> Agent API -> Agent Loop -> Model Provider / Tool Provider / Memory / Policy / Observability -> Platform / Native Runtime

Model Provider: one Waqti interface for local GGUF inference, cloud providers later, and routing/failover later.

Tool Provider: explicit capabilities with name, schema, execution, result, timeout, error, and permission policy.

Agent Loop: testable without Android UI and without a specific model implementation.

Native Runtime: isolated behind narrow interfaces; mature inference libraries may be integrated here.

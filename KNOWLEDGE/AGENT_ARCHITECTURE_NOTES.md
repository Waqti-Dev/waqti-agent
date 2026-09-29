# Agent Architecture Notes

Core boundaries:
1. Agent loop — goal, state, next action.
2. Model provider — local or remote model behind one interface.
3. Tool provider — filesystem, shell, search, project inspection.
4. Memory — working state plus durable memory where needed.
5. Planner/reviewer — optional decomposition and verification.
6. Execution policy — permissions, confirmations, limits, failure handling.
7. Observability — structured events, timings, errors, traces.

Local/cloud routing must not leak model-specific details into the rest of Waqti.

# Failed Approaches

## OpenDroid as the long-term base
OpenDroid was useful for experiments, but reshaping an inherited architecture created unnecessary coupling.

**Lesson:** preserve the results, not the architecture.

## Reimplementing mature inference infrastructure
The previous GGUF/inference work produced valuable learning, but that does not make a from-scratch inference engine the right production strategy.

**Lesson:** use the experimental implementation as knowledge and validation evidence; prefer mature upstream infrastructure when it reduces risk.

## Treating CI/build success as product completion
A successful Gradle build does not prove that the agent can reason, call tools, edit files, or complete a real task.

**Lesson:** define behavioral acceptance criteria.

## Patching without scope control
A previous Kotlin nullability issue was safely fixed by converting nullable strings with orEmpty() before ifBlank().

**Lesson:** make the smallest justified fix and record it.

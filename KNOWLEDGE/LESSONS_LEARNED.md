# Lessons Learned

## Clean architecture
Waqti must not be a fork, copy, or architectural continuation of OpenDroid. The old project is a source of lessons and validation evidence, not a source tree.

## Reuse proven infrastructure
When mature implementations such as llama.cpp/GGUF tooling solve difficult systems problems reliably, integrate them behind a small Waqti-native interface instead of rewriting them for pride.

## Build is not MVP
A successful Gradle build proves compilation/package integrity only. MVP validation must exercise real behavior on-device.

## Acceptance criteria
Every milestone needs executable acceptance tests.

## Provenance
Record source SHA, modifications, build command, artifact identity, and SHA-256 for APK validation.

## Knowledge versus implementation
Hard-won lessons belong in KNOWLEDGE/. Production architecture belongs in app/ and docs/.

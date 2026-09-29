# Bugs and Fixes

## Kotlin nullable String
Failure: calling ifBlank() on nullable String?.

Safe pattern: value.orEmpty().ifBlank { "." }

## Build provenance
Failure: an APK can be generated from a patched working tree while appearing to represent an untouched commit.

Fix: record exact source SHA, patch scope, build command, APK SHA-256, package/version, and signing state.

## Numerical inference debugging
Validate dequantization -> projection -> RoPE/GQA/attention -> MLP/residual -> full-layer behavior against independent references.

# Android Knowledge

Target baseline:
- Android 16 / API 36
- ARM64-first
- Redmi Turbo 4 Pro class hardware
- Snapdragon 8s Gen 4
- 12 GB RAM / 256 GB storage

Principles:
- keep UI responsive while model/tool work runs off the main thread
- treat memory pressure and thermal throttling as first-class constraints
- keep native boundaries small and observable
- make local model storage explicit and testable
- validate real APK behavior on the target phone, not only CI

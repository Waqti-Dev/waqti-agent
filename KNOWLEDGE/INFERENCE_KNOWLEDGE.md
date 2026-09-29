# Inference Knowledge

Previous Qwen2.5-0.5B-Instruct Q4_K_M work established practical GGUF/mobile inference knowledge.

Verified concepts:
- GGUF metadata and tensor layout inspection
- read-only mmap with bounds checks
- Q4_K dequantization
- F32/F16/Q5_0/Q8_0/Q6_K
- Q/K/V projection validation against independent Python
- RoPE
- GQA
- attention and residual paths
- MLP execution
- CTest and sanitizer validation

Recorded model metadata:
- embedding 896
- 24 layers
- 14 attention heads
- 2 KV heads
- head dimension 64
- FFN dimension 4864
- vocabulary 151936
- context 32768
- RoPE theta 1e6

This is knowledge, not a requirement to reuse the old implementation.

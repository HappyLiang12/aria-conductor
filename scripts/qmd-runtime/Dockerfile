FROM docker.io/oven/bun:1.4.5

ENV PATH="/root/.bun/bin:${PATH}"

# qmd installs native tree-sitter packages through node-gyp; the runtime image
# carries the compiler toolchain so the same image builds on macOS, Linux, and
# Windows containers.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 make g++ pkg-config libstdc++6 ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

RUN bun add -g @tobilu/qmd@2.9.0

# The vault is mostly Chinese; the default embeddinggemma model is
# English-optimized. Qwen3-Embedding is multilingual and MTEB-ranked.
ENV QMD_EMBED_MODEL="hf:Qwen/Qwen3-Embedding-0.6B-GGUF/Qwen3-Embedding-0.6B-Q8_0.gguf"

WORKDIR /vault
ENTRYPOINT ["qmd"]

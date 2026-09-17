# Aria Conductor - Qoder CLI Sandbox Template Image
# Pre-installs the Qoder CLI (agent runtime) for OpenSandbox sandboxes.
# Build: podman build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox
#
# Artifact provenance (verified 2026-09-17; see e2e/qoder/slice-a/01-boot.md):
#   - Official install methods (Qoder docs, https://docs.qoder.com/cli/installation):
#       1) install script:  curl -fsSL https://qoder.com/install | bash
#       2) npm package:     npm install -g @qoder-ai/qodercli
#   - The install script resolves a per-version release manifest
#     (https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/channels/<version>/manifest.json)
#     that lists each platform archive URL together with its sha256 checksum.
#   - This image pins that exact archive URL + checksum instead of piping a remote
#     script into bash, so the build is reproducible and the artifact is checksum-verified.
#   - The `amd64` (not `amd64-baseline`) artifact is the one the official install script
#     selects when the CPU exposes sse4_2/popcnt/avx/avx2/bmi1/bmi2/fma (verified for the
#     OpenSandbox podman VM on this machine).
#   - No credentials are baked into this image; the CLI is not configured or logged in here.

FROM node:22-slim

ARG QODERCLI_VERSION=1.1.41
ARG QODERCLI_LINUX_X64_SHA256=1c2d174b098eb472a9ac19a80b89a664fdcb7d37dca09e64f44243faa4e3eca7
ARG QODERCLI_LINUX_X64_URL=https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/releases/${QODERCLI_VERSION}/qodercli-linux-x64.tar.gz

# node:22-slim ships no curl; the archive layout is the one the official install
# script expects (a single `qodercli` binary at the archive root).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL --retry 2 --connect-timeout 30 --max-time 300 "$QODERCLI_LINUX_X64_URL" -o /tmp/qodercli.tar.gz \
    && echo "$QODERCLI_LINUX_X64_SHA256  /tmp/qodercli.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/qodercli.tar.gz -C /tmp \
    && install -m 0755 /tmp/qodercli /usr/local/bin/qodercli \
    && rm -f /tmp/qodercli /tmp/qodercli.tar.gz \
    && HOME=/tmp/qodercli-smoke qodercli --version \
    && rm -rf /tmp/qodercli-smoke

# Agents' workspace files are uploaded here by the Java side
WORKDIR /workspace

# Smoke-test default (podman run --rm aria-conductor/qoder-sandbox:0.1)
CMD ["qodercli", "--version"]

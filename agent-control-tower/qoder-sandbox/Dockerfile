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
#
# B4 image contents (CLI + bridge + plugin; evidence: e2e/qoder/slice-b/01-image.md):
#   - The sandbox bridge (bridge/, TypeScript ESM) is compiled in the `bridge-builder`
#     stage with `npm ci && npm run build`; only `dist/` and `package.json` reach the
#     final image, so the image never consumes host-built node_modules/dist output
#     (.dockerignore keeps both out of the build context).
#   - The pinned plugin bundle (plugin/) is baked verbatim at /opt/qoder/plugin. The
#     runtime-upload convention is unchanged: the Java side still uploads the bundle to
#     /workspace/plugin and loads it per run via `--plugin-dir` (A2).
#   - Auto-update is disabled for the pinned CLI by baking the documented user-settings
#     key `general.enableAutoUpdate=false` into $HOME/.qoder/settings.json. The pinned
#     1.1.41 binary reads that file on the exact `qodercli --acp` entry path the bridge
#     uses (behavior verified in e2e/qoder/slice-b/01-image.md).
#   - The container runs as the non-root `node` user (uid/gid 1000); /workspace and the
#     CLI config root are owned by it. The bridge listens on 4097 inside the sandbox.

# ---------------------------------------------------------------------------------------
# Stage 1: compile the sandbox bridge from source (clean checkout, no host-built artifacts)
# ---------------------------------------------------------------------------------------
FROM node:22-slim AS bridge-builder

WORKDIR /build/bridge
# Manifests first so the dependency layer is cached independently of the sources.
COPY bridge/package.json bridge/package-lock.json ./
RUN npm ci
COPY bridge/tsconfig.json ./
COPY bridge/src ./src
RUN npm run build

# ---------------------------------------------------------------------------------------
# Stage 2: final image - pinned CLI + compiled bridge + pinned plugin bundle
# ---------------------------------------------------------------------------------------
FROM node:22-slim

ARG QODERCLI_VERSION=1.1.41
ARG QODERCLI_LINUX_X64_SHA256=1c2d174b098eb472a9ac19a80b89a664fdcb7d37dca09e64f44243faa4e3eca7
ARG QODERCLI_LINUX_X64_URL=https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/releases/${QODERCLI_VERSION}/qodercli-linux-x64.tar.gz

# node:22-slim ships no curl; the archive layout is the one the official install
# script expects (a single `qodercli` binary at the archive root).
#
# The archive is extracted into a dedicated directory instead of `-C /tmp`: its first
# entry is `./` (dir, mode 0700), and GNU tar applies that entry's metadata to the
# extraction target itself, which would corrupt /tmp for the non-root `node` user
# (observed: /tmp became 0700 and qodercli then failed to create its native-module
# staging dir: "EACCES ... mkdir '/tmp/.setup-qodercli-natives-*'"). The extraction
# target is removed in the same layer; the final `test` re-asserts that /tmp itself
# still carries its standard metadata.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL --retry 2 --connect-timeout 30 --max-time 300 "$QODERCLI_LINUX_X64_URL" -o /tmp/qodercli.tar.gz \
    && echo "$QODERCLI_LINUX_X64_SHA256  /tmp/qodercli.tar.gz" | sha256sum -c - \
    && mkdir -p /tmp/qodercli-extract \
    && tar -xzf /tmp/qodercli.tar.gz -C /tmp/qodercli-extract \
    && install -m 0755 /tmp/qodercli-extract/qodercli /usr/local/bin/qodercli \
    && rm -rf /tmp/qodercli-extract /tmp/qodercli.tar.gz \
    && HOME=/tmp/qodercli-smoke qodercli --version \
    && rm -rf /tmp/qodercli-smoke \
    && test "$(stat -c '%a %u:%g' /tmp)" = "1777 0:0"

# Compiled bridge from stage 1. package.json must sit next to dist/ so Node treats the
# modules as ESM (`"type": "module"`); its `main` field stays accurate for this layout.
COPY --from=bridge-builder /build/bridge/dist /opt/qoder/bridge/dist
COPY bridge/package.json /opt/qoder/bridge/package.json

# Pinned plugin bundle (A2 format), on-disk layout unchanged. Root-owned on purpose:
# unlike a runtime-uploaded copy in the agent workspace, this image copy cannot be
# modified by the sandbox user.
COPY plugin /opt/qoder/plugin

# Qoder CLI auto-update off (documented key `general.enableAutoUpdate`, default true).
# The CLI resolves its user config root from HOME on every start; HOME is set explicitly
# so the same path is used by a direct `podman run` and by the OpenSandbox bootstrap.
ENV HOME=/home/node
RUN install -d -o node -g node /home/node/.qoder \
    && printf '{\n  "general": {\n    "enableAutoUpdate": false\n  }\n}\n' > /home/node/.qoder/settings.json \
    && chown node:node /home/node/.qoder/settings.json

# Agents' workspace files are uploaded here by the Java side; owned by the runtime user
# because in-sandbox commands (execd runs as the image USER) execute as `node`.
RUN install -d -o node -g node /workspace

# The bridge process is started per agent by the Java side with BRIDGE_TOKEN injected;
# the container default below is only a smoke default (it fails closed without a token).
USER node
WORKDIR /workspace
EXPOSE 4097
CMD ["node", "/opt/qoder/bridge/dist/main.js"]

# Aria Conductor - OpenCode Sandbox Template Image
# Pre-installs the opencode CLI (agent runtime) for OpenSandbox sandboxes.
# Build: docker build -t aria-conductor/opencode-sandbox:1.1 .

FROM node:22-slim

# curl is required by the OpenSandbox server-side proxy path
# (GET /v1/sandboxes/{id}/proxy/{port} forwards via `docker exec ... curl`).
# gh CLI lets agents read issues and clone repos headlessly (GH_TOKEN injected
# per-sandbox by aria-conductor; the image itself embeds NO credentials).
RUN apt-get update && apt-get install -y --no-install-recommends curl gnupg ca-certificates \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
         -o /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
         > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y gh \
    && rm -rf /var/lib/apt/lists/*

# Install the opencode CLI globally (official npm package, see https://opencode.ai/docs)
# NOTE: use `opencode-ai` (bin: opencode). The package `@opencode-ai/cli` is a DIFFERENT
# project (anomalyco/opencode, bin: lildax) and must not be used.
# Version pinned to the one verified in the sandbox image (see npm view opencode-ai@1.18.15).
RUN npm install -g opencode-ai@1.18.15

# Agents' workspace files (opencode.json / AGENTS.md) are uploaded here by the Java side
WORKDIR /workspace

# `opencode serve --hostname 0.0.0.0 --port 4096` is started per agent via the OpenSandbox API
EXPOSE 4096

# Smoke-test default (docker run --rm aria-conductor/opencode-sandbox:1.1);
# serve is launched by the Java side, not by this image
CMD ["opencode", "--version"]

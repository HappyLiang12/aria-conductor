# Aria Conductor - OpenCode Sandbox Template Image
# Pre-installs the opencode CLI (agent runtime) for OpenSandbox sandboxes.
# Build: docker build -t aria-conductor/opencode-sandbox:1.0 .

FROM node:22-slim

# Install the opencode CLI globally (official npm package, see https://opencode.ai/docs)
# NOTE: use `opencode-ai` (bin: opencode). The package `@opencode-ai/cli` is a DIFFERENT
# project (anomalyco/opencode, bin: lildax) and must not be used.
RUN npm install -g opencode-ai

# Agents' workspace files (opencode.json / AGENTS.md) are uploaded here by the Java side
WORKDIR /workspace

# `opencode serve --hostname 0.0.0.0 --port 4096` is started per agent via the OpenSandbox API
EXPOSE 4096

# Smoke-test default (docker run --rm aria-conductor/opencode-sandbox:1.0);
# serve is launched by the Java side, not by this image
CMD ["opencode", "--version"]

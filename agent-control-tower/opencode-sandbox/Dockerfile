# Aria Conductor - OpenCode Sandbox Template Image
# Pre-installs the opencode CLI (agent runtime) plus the fixed run-owned
# launcher/control scripts for OpenSandbox sandboxes.
#
# Build (context = repository root, because the image consumes the shared
# runtime-sandbox scripts and must not duplicate them):
#   docker build -t aria-conductor/opencode-sandbox:1.1 \
#     -f agent-control-tower/opencode-sandbox/Dockerfile .
#   podman build -t aria-conductor/opencode-sandbox:1.1 \
#     -f agent-control-tower/opencode-sandbox/Dockerfile .
#
# Isolation contract (specified, not optional):
#   * runs as the unprivileged user `aria` (uid 10001);
#   * contains NO host credential material and NO container-runtime socket --
#     the backend never mounts either into a run sandbox;
#   * a core is only ever started through /opt/aria/launch.mjs with a trusted
#     launch manifest uploaded by the backend (explicit argv, shell: false);
#   * /opt/aria/stop-writers.mjs stops the run's writer tree while leaving the
#     Sandbox export facility (execd) alive, so the sandbox can be exported
#     before it is destroyed.

FROM node:22-slim

# curl is required by the OpenSandbox server-side proxy path
# (GET /v1/sandboxes/{id}/proxy/{port} forwards via `docker exec ... curl`).
# gh CLI lets agents read issues and clone repos headlessly (GH_TOKEN injected
# per-run by aria-conductor; the image itself embeds NO credentials).
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

# The fixed run-owned launcher and writer-control script (single source of truth
# in agent-control-tower/runtime-sandbox; copied, never re-implemented here).
COPY agent-control-tower/runtime-sandbox/launch.mjs /opt/aria/launch.mjs
COPY agent-control-tower/runtime-sandbox/stop-writers.mjs /opt/aria/stop-writers.mjs

# Unprivileged runtime user. The image never gains a docker/podman socket, a
# host credential mount or an elevated entrypoint.
#
# /opt/aria is the control plane the core is judged by (the launcher, the
# writer-control script and the entrypoint allowlist): it stays root-owned and
# read-only, so the unprivileged core can never rewrite the script that
# produces its stop proof or the allowlist that constrains its entrypoint.
RUN groupadd --gid 10001 aria \
    && useradd --uid 10001 --gid aria --create-home --shell /usr/sbin/nologin aria \
    && mkdir -p /workspace /home/aria/run \
    && chown -R aria:aria /workspace /home/aria \
    && chmod 0555 /opt/aria/launch.mjs /opt/aria/stop-writers.mjs

# The image's own entrypoint allowlist: a launch manifest cannot name anything
# that is not listed here, and `launch.mjs` resolves bare names through it.
RUN printf '%s\n' \
    '{"names":{"opencode":"/usr/local/bin/opencode","node":"/usr/local/bin/node"},' \
    '"paths":["/usr/local/bin/opencode","/usr/local/bin/node"],' \
    '"comment":"fixed image entrypoints; a launch manifest may only name these"}' \
    > /opt/aria/entrypoints.json \
    && chmod 0444 /opt/aria/entrypoints.json

# Agents' workspace files (opencode.json / AGENTS.md) are uploaded here by the Java side
WORKDIR /workspace

# The core is started per run by /opt/aria/launch.mjs (background), never by the
# image; the port below is the default opencode serve port for endpoint mapping.
EXPOSE 4096

USER aria

# Smoke-test default (docker run --rm aria-conductor/opencode-sandbox:1.1);
# serve is launched by the run launcher, not by this image
CMD ["opencode", "--version"]

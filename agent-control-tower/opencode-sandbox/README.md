# OpenCode Sandbox Template Image

Template sandbox image for the OpenCode agent runtime, used by
[OpenSandbox](https://github.com/opensandbox-group/OpenSandbox) sandboxes created by
Aria Conductor's `OpenCodeAdkProvider` (exchangeable agent provider architecture).

## Purpose

- Pre-installs the [opencode](https://opencode.ai) CLI (official npm package `opencode-ai`, bin `opencode`)
  so each agent sandbox can run `opencode serve --hostname 0.0.0.0 --port 4096`.
  (Do NOT use `@opencode-ai/cli` — that package belongs to a different project and exposes the
  `lildax` binary instead of `opencode`.)
- Pre-installs the [gh](https://cli.github.com) CLI so agents can read issues and clone repos headlessly.
- The `serve` command is launched by the Java side through the OpenSandbox API per agent;
  this image only provides the `opencode` executable plus the workspace directory.
- Agents' workspace files (`opencode.json` / `AGENTS.md`) are uploaded to `/workspace`.
- The image is a reusable agent template (opencode + git + gh); credentials are injected per sandbox by aria-conductor, never baked in.

## Build

The build context is the repository root, because the image consumes the shared
run-owned launcher/control scripts from `agent-control-tower/runtime-sandbox`
(they are copied, never re-implemented in this directory):

```bash
docker build -t aria-conductor/opencode-sandbox:1.1 \
  -f agent-control-tower/opencode-sandbox/Dockerfile .
# or with podman:
podman build -t aria-conductor/opencode-sandbox:1.1 \
  -f agent-control-tower/opencode-sandbox/Dockerfile .
```

## Isolation contract

- the image runs the CLI as the unprivileged user `aria` (uid 10001);
- it carries no host credential material and no container-runtime socket, and the
  backend never mounts either into a run sandbox;
- a core is only started through `/opt/aria/launch.mjs` with the trusted launch
  manifest the backend uploads (explicit argv array, `shell: false`), and the
  manifest may only name the image's own entrypoint allowlist;
- `/opt/aria/stop-writers.mjs` stops the run's writer tree while leaving the
  Sandbox export facility (execd) alive, so the sandbox can be exported before it
  is destroyed.

## Smoke test

```bash
docker run --rm aria-conductor/opencode-sandbox:1.1
# or: podman run --rm aria-conductor/opencode-sandbox:1.1
# prints: opencode version X.Y.Z
docker run --rm aria-conductor/opencode-sandbox:1.1 gh --version
# prints: gh version X.Y.Z
```

# OpenCode Sandbox Template Image

Template sandbox image for the OpenCode agent runtime, used by
[OpenSandbox](https://github.com/opensandbox-group/OpenSandbox) sandboxes created by
Aria Conductor's `OpenCodeAdkProvider` (exchangeable agent provider architecture).

## Purpose

- Pre-installs the [opencode](https://opencode.ai) CLI (official npm package `opencode-ai`, bin `opencode`)
  so each agent sandbox can run `opencode serve --hostname 0.0.0.0 --port 4096`.
  (Do NOT use `@opencode-ai/cli` — that package belongs to a different project and exposes the
  `lildax` binary instead of `opencode`.)
- The `serve` command is launched by the Java side through the OpenSandbox API per agent;
  this image only provides the `opencode` executable plus the workspace directory.
- Agents' workspace files (`opencode.json` / `AGENTS.md`) are uploaded to `/workspace`.

## Build

```bash
docker build -t aria-conductor/opencode-sandbox:1.0 .
```

## Smoke test

```bash
docker run --rm aria-conductor/opencode-sandbox:1.0
# prints: opencode version X.Y.Z
```

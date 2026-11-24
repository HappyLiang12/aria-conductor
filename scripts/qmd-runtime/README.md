# qmd memory hub runtime

Portable qmd 2.9.0 runtime for the private `aria-memory-vault`. Aria reads the
verified memory/knowledge collections through qmd; the same vault can be read
by Qoder through an HTTP MCP connector.

The runtime is a pinned container image built from `oven/bun:1.4.5` with the
native build dependencies qmd needs. The host only needs podman or docker; it
does not need Bun, Node 22, or Homebrew SQLite.

## First run

```bash
# From the Aria Conductor checkout
scripts/qmd-runtime/start-qmd.sh init
scripts/qmd-runtime/start-qmd.sh start
```

`init` builds the image if missing, creates `memory-verified` and
`knowledge-verified`, updates the FTS index, and downloads/embeds with the
Qwen3 embedding model (~2 GB of models, CPU; the first embed can take minutes).
Subsequent starts reuse the `aria-qmd-home` volume.

Windows:

```powershell
pwsh -File scripts/qmd-runtime/start-qmd.ps1 init
pwsh -File scripts/qmd-runtime/start-qmd.ps1 start
```

The HTTP MCP endpoint is `http://127.0.0.1:8181/mcp`; health is
`http://127.0.0.1:8181/health`. qmd is unauthenticated, so only the loopback
address is published. Do not change the published address to `0.0.0.0`.

## Aria startup flag

Start the backend with the sidecar and the qmd env wiring:

```bash
bash scripts/start-backend.sh --skip-sandbox --with-memory-hub
```

```powershell
pwsh -File scripts/start.ps1 -WithMemoryHub
# or directly:
pwsh -File scripts/start-backend.ps1 -SkipSandbox -WithMemoryHub
```

The flag starts qmd, exports:

```text
ARIA_MEMORY_QMD_ENABLED=true
ARIA_MEMORY_QMD_URL=http://127.0.0.1:8181
ARIA_MEMORY_QMD_PROJECT=aria-conductor
ARIA_MEMORY_QMD_VAULT_DIR=$HOME/aria-memory-vault
ARIA_KNOWLEDGE_QMD_ENABLED=true
ARIA_KNOWLEDGE_QMD_URL=http://127.0.0.1:8181
ARIA_KNOWLEDGE_QMD_PROJECT=aria-conductor
ARIA_KNOWLEDGE_QMD_VAULT_DIR=$HOME/aria-memory-vault
```

and writes `.run/memory-hub.started`. `scripts/stop.ps1` stops the qmd
container only when that marker exists, so a separately managed qmd is not
stopped by accident.

Override the vault or project before starting:

```bash
export ARIA_MEMORY_QMD_VAULT_DIR=/path/to/aria-memory-vault
export ARIA_MEMORY_QMD_PROJECT=my-project
```

`ARIA_MEMORY_QMD_PROJECT` is one project per Aria instance: a run's
`workspaceRef` is `MODE@baseRef` (for example `HOST@main`), so it cannot carry
a filesystem project name. A future `workspacePath` field can remove this
single-project limit.

## Qoder

`qoder-memory-hook.mjs` is a `SessionStart`/`UserPromptSubmit` command hook.
`qoder-settings.example.json` shows the qmd MCP connector and both hooks.
Install the hook at `~/.qoder/hooks/qmd-memory.mjs` and merge the example into
`~/.qoder/settings.json` (back up the file first). The hook is silent when qmd
is down and never blocks a Qoder task.

## Commands

| Command | Effect |
|---|---|
| `start-qmd.sh build` | Build `aria-qmd:2.9.0` |
| `start-qmd.sh init` | Ensure the image, create collections, update + embed |
| `start-qmd.sh start` | Start the HTTP MCP server on loopback |
| `start-qmd.sh stop` | Remove the qmd container (idempotent) |
| `start-qmd.sh status` | Container status + `/health` |
| `start-qmd.sh logs` | Last 100 lines of the qmd container |

After new verified records land, rerun `init` (or `update` + `embed` through
the container) to refresh the index.

## Scope

Phase 0 is read-only: no approval gate, no `pending/` writes, and no WeKnora.
WeKnora is Phase 1. The root README and `docs/user-guide.md` do not yet mention
this runtime; see the design spec at
`docs/superpowers/specs/2026-11-24-memory-knowledge-hub-design.md`.

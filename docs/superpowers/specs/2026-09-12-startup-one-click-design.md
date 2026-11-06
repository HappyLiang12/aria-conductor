# One-Click Local-Dev Startup — Design

Date: 2026-09-12
Status: Approved (brainstorming session, 6 decisions confirmed)
Target platform: Windows (PowerShell 7+)

## 1. Background & problem

The repo currently exposes four overlapping startup paths — `scripts/quickstart.ps1`,
`scripts/start-backend.ps1`, `scripts/start-frontend.ps1`, and raw README commands — and the
"one-click" path cannot deliver the mode the project actually targets.

Review of the current procedure found:

| # | Defect | Location |
|---|--------|----------|
| 1 | When a container runtime exists, quickstart runs full-stack compose, which by `opensandbox-config.toml` cannot run the opencode provider. The one-click path can never produce opencode. | `scripts/quickstart.ps1` (compose branch) |
| 2 | quickstart prints "ADK default: opencode" while compose hardcodes `ADK_DEFAULT_PROVIDER: langchain`. Contradictory and misleading. | `quickstart.ps1` vs `docker-compose.yml` |
| 3 | No mode confirmation: topology, provider, runtime and URLs are never stated together. | all scripts |
| 4 | Env setup copies `.env.example` and pauses for a manual edit. No validation, and no `SANDBOX_SOCKET` guidance — the exact cause of a silent degradation to Docker. | `quickstart.ps1` |
| 5 | A stopped podman machine produces a message telling the user to run `podman machine start`, but nothing offers to do it. | `scripts/lib/container-runtime.ps1` |
| 6 | Dashboard URL differs by path (compose 3000, local-dev 5173) and the README documents only 3000. | `quickstart.ps1`, `README.md` |
| 7 | No port pre-check, so a collision surfaces as a raw container-engine error. | none |
| 8 | Docker Desktop and podman coexist here; nothing warns that a stray `docker compose` silently targets Docker Desktop. | none |
| 9 | A missing `mvn` produces a generic install hint with no mention of stale shells or the repo's own wrapper. | `start-backend.ps1` |
| 10 | Documentation drift: README claims "Docker available → full stack with OpenCode sandbox" (false); several "(requires Docker)" strings; scratch files left at the repo root. | `README.md`, `AGENTS.md`, repo root |

Facts established while debugging (2026-09-12) that this design depends on:

- The opencode provider is only usable in the **local-dev topology** — backend and frontend on
  the host, OpenSandbox server in a container. In full-stack compose the sandbox endpoints
  resolve to `127.0.0.1`, unreachable from a containerized backend
  (`opensandbox-config.toml`, `[docker].host_ip` note).
- On Windows with podman machine, the value for `SANDBOX_SOCKET` is the **VM-internal** socket
  path. It is read from the engine, not hardcoded: `podman info --format '{{.Host.RemoteSocket.Path}}'`
  returns `unix:///run/user/1000/podman/podman.sock` on a rootless machine.
- With `.env` absent, `resolve_container_runtime` auto-detects and selects **docker** because
  Docker Desktop is running, and `SANDBOX_SOCKET` falls back to `/var/run/docker.sock`. The
  degradation is silent.
- `podman compose` correctly targets podman; a directly-typed `docker compose` targets Docker
  Desktop. They are separate container stores.

## 2. Goal & non-goals

**Goal.** One command starts the whole local-dev stack in the opencode-capable topology, after
checking the environment, repairing what can be repaired safely, and stating the resulting mode
explicitly. A companion command stops everything.

**Non-goals.**

- Linux/macOS launcher parity. The existing `.sh` scripts stay as they are — no regression, no
  new features.
- Making full-stack compose the default. It remains an explicit opt-in and remains langchain-only.
- Any CI change. CI uses `.github/actions/start-stack` and does not use these scripts.
- Installing system software (Maven, JDK, Node) on the user's behalf.

## 3. Decisions (locked)

| # | Decision |
|---|----------|
| D1 | Consolidate to one entrypoint. Default = local-dev + opencode + podman. Full-stack compose becomes `-Mode compose`. |
| D2 | Auto-fix everything safely fixable. In a normal start — an existing `.env` — the only interactive prompt is a port conflict. The first-run `.env` guidance also prompts (see `9`). |
| D3 | The launcher starts services, waits for health, prints a mode summary, opens the browser, then exits. `stop.ps1` stops everything. |
| D4 | On first run the launcher guides `.env` creation (DeepSeek preset, masked key input). An existing `.env` is only filled where keys are missing, never overwritten. |
| D5 | Windows only (`scripts/start.ps1`, `scripts/stop.ps1`). |
| D6 | Structure: an orchestrator that reuses the existing per-service scripts rather than rewriting them. |
| D7 | When `mvn` is absent, bootstrap through the repo's existing `.mvn/wrapper/maven-wrapper.jar`. |

## 4. Files

| Action | Path | Notes |
|--------|------|-------|
| NEW | `scripts/start.ps1` | The single entrypoint |
| NEW | `scripts/stop.ps1` | Stops everything |
| MODIFY | `scripts/lib/container-runtime.ps1` | Keeps `Load-DotEnv` and `Resolve-ContainerRuntime`; gains helpers for machine start, socket resolution, image ensure, port probe, health wait |
| DELETE | `scripts/quickstart.ps1` | Superseded by `start.ps1` |
| KEEP | `scripts/start-backend.ps1`, `scripts/start-frontend.ps1` | Behaviour unchanged; invoked by the launcher. Their own checks are idempotent, so re-running them costs nothing. |
| NEW | `.run/` (gitignored) | `backend.pid`, `frontend.pid`, `backend.log`, `frontend.log` |
| MODIFY | `.gitignore` | Add `.run/` |

## 5. Launcher phases

Each phase prints one `[n/8]` status line. Phases 1–4 are pre-flight; 5–6 start and verify;
7–8 report and exit.

| # | Phase | Behaviour |
|---|-------|-----------|
| 1 | Environment check | Probe `java` (21 expected; mismatch is a warning), `mvn`, `node`, `pnpm`. Resolve the container runtime. If the resolved runtime is podman and the machine is stopped, start it. Warning if Docker Desktop is also running. |
| 2 | Env guidance | If `.env` is absent, run the guided creation flow (`9`). If present, add only missing keys. Validate that the LLM key is present and not a placeholder. |
| 3 | Resource preparation | Start `aria-opensandbox` via `podman compose up -d opensandbox-server` if it is not running. Build `aria-conductor/opencode-sandbox:1.1` if the image is absent. |
| 4 | Port pre-check | Probe the backend, sandbox and frontend ports. If one is held, print the holding process and ask whether to stop it. In a normal start this is the only prompt; the first-run `.env` guidance in phase 2 also prompts. |
| 5 | Start | Launch `start-backend.ps1 -AdkProvider opencode` and `start-frontend.ps1` as detached hidden processes. Write PIDs to `.run/*.pid` and output to `.run/*.log`. |
| 6 | Health verification | Wait (poll, not sleep) for backend `/actuator/health` = `UP`, sandbox `/health` = 200, frontend = 200. Each with its own timeout budget. |
| 7 | Mode confirmation | Print the summary block (`7`) and open the browser at the dashboard URL. |
| 8 | Exit | All green: exit 0. Any failure: print the tail of the relevant `.run/*.log` plus the precise next step, exit non-zero. |

Modes: no flag → local-dev + opencode + podman (default). `-Mode compose` → the previous
full-stack compose flow (langchain), printing an explicit notice that the opencode provider is
not usable in that topology.

Port resolution, applied consistently so the summary and the health checks agree:

- Backend port = `BACKEND_PORT` from `.env`, default `8080`.
- Frontend dev port = `VITE_PORT` from `.env`, default `5173`.
- The launcher exports `VITE_BACKEND_PORT` = backend port before starting the frontend, so the
  Vite proxy targets the backend that was actually started (today it would silently keep
  targeting 8080 if `BACKEND_PORT` were changed).

## 6. Auto-repair / error matrix

| Situation | Behaviour |
|-----------|-----------|
| podman machine stopped | Start it automatically (`podman machine start`). Report and stop only if the start fails. |
| `CONTAINER_RUNTIME` unset | Write and use `podman` for this flow. Do not fall back to docker silently. |
| `SANDBOX_SOCKET` missing or wrong | Read the real value from `podman info --format '{{.Host.RemoteSocket.Path}}'` and write it into `.env`. Never hardcode the path. |
| Docker Desktop also running | Warn that a stray `docker compose` targets Docker Desktop, and that `podman compose` or these scripts must be used instead. |
| `.env` absent | Guided creation flow (`9`). |
| LLM key empty or placeholder | Block with instructions. |
| `aria-opensandbox` not running | `podman compose up -d opensandbox-server`. |
| `opencode-sandbox:1.1` image absent | `podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox`. |
| Backend / sandbox / frontend port held | Ask before stopping the holder (the only prompt in a normal start). |
| `mvn` absent | Bootstrap via `.mvn/wrapper/maven-wrapper.jar` (D7). If that also fails, block with instructions that mention opening a new terminal, because a PATH change does not affect already-open shells. |
| `java` major version != 21 | Warning only. |
| Health check times out | Print the tail of that service's log plus the precise next step; exit non-zero. |

## 7. Mode confirmation output

```
=========================================================
  Aria Conductor — READY
=========================================================
  Topology : local-dev (backend + frontend on host)
  Provider : opencode
  Runtime  : podman (explicit: CONTAINER_RUNTIME)
  Sandbox  : aria-opensandbox  http://localhost:8090   [OK]
  Database : h2 (file)
---------------------------------------------------------
  Dashboard: http://localhost:5173                     [OK]
  Backend  : http://localhost:8080                     [OK]
  Swagger  : http://localhost:8080/swagger-ui.html
---------------------------------------------------------
  Logs : .run\backend.log   .run\frontend.log
  Stop : pwsh -File scripts\stop.ps1
=========================================================
```

A one-line pre-flight confirmation ("Starting local-dev + opencode + podman") is printed after
phase 2 as well. It is display-only — no pause — so the flow stays one-click.

## 8. stop.ps1

1. Read `.run/*.pid` and terminate the process trees (including the `java` child of the Maven
   wrapper).
2. `podman compose stop opensandbox-server`.
3. Remove `.run/`.
4. `-All` additionally stops the sandbox containers and the podman machine.

## 9. `.env` guidance contract

Guided creation (only when `.env` is absent) prompts for, in order:

1. Provider preset — DeepSeek (default) or OpenAI.
2. API key — masked input.
3. Model — default from the preset (`deepseek-v4-flash` for DeepSeek).

It then writes: `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `CONTAINER_RUNTIME=podman`,
`SANDBOX_SOCKET=<resolved from the engine>`, and the database block copied from `.env.example`.
The optional-ports block is copied too, commented out, so a later `-Mode compose` run finds the
same layout it expects. The database values are unused by the `h2` profile this flow selects;
they are written only for that compose compatibility.

When `.env` already exists: add only the keys that are missing (notably `CONTAINER_RUNTIME` and
`SANDBOX_SOCKET`). Never overwrite an existing value.

## 10. Cleanup

| File | Action |
|------|--------|
| `README.md` | Rewrite the startup section: local-dev + opencode is the main path; compose moves to an opt-in subsection; remove the false "Docker available → full stack with OpenCode sandbox" claim and the stray "(requires Docker)" strings. |
| `AGENTS.md` | Update "Run full-stack locally" to lead with `scripts/start.ps1`; docker is no longer first. |
| `.env.example` | Add `SANDBOX_SOCKET` explanation and a DeepSeek preset comment. No breaking changes. |
| `scripts/quickstart.ps1` | Delete (superseded). |
| `scripts/monitor-env-deletion.ps1` | Delete. No evidence that `.env` is deleted by any program — the pre-commit hook only warns — so the monitor only adds noise. |
| `.env-monitor.log`, `.env-alert.txt`, `NUL`, `e2e-test-setup.ps1`, `tmp-osb-smoke.ps1` | Delete if present (scratch). |
| `quickstart.sh`, `start-backend.sh`, `start-frontend.sh` | Keep unchanged; the README notes that the new Windows entrypoint is `scripts/start.ps1`. |

## 11. Testing

Follows the repo's existing stub-CLI harness pattern (`e2e/container-runtime-e2e.ps1`).

New stub-driven scenarios:

1. podman machine stopped → `podman machine start` is invoked once.
2. `SANDBOX_SOCKET` is derived from `podman info` output, not hardcoded.
3. A held port takes the prompt path (and the abort path).
4. `mvn` absent → the wrapper bootstrap runs; bootstrap failure → blocked with the
   new-terminal hint.
5. `.env` absent → guided flow writes the expected keys; existing `.env` → missing keys added,
   existing values untouched.

Plus one documented real acceptance run:

1. `pwsh -File scripts/start.ps1` → exit 0, READY block present, all three health checks 200.
2. `pwsh -File scripts/stop.ps1` → all three ports free, no `aria-*` containers running.

## 12. Risks

- **Machine-state mutation.** The launcher starts the podman machine, builds an image, and can
  stop processes. Mitigated by keeping the destructive choice (port conflict) behind the single
  prompt and by `stop.ps1` being the only path that stops anything.
- **`-Mode compose` drift.** The compose path is kept for parity but is langchain-only. The
  design states this in the script output so it cannot be mistaken again.
- **Log discoverability.** Because the launcher exits, output lives in `.run/*.log`. The READY
  block prints the paths.

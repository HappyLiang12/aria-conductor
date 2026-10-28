# AGENTS.md

> Orientation file for AI coding agents. For full details see [README.md](README.md) and [docs/architecture.md](docs/architecture.md).

## Project Positioning

Aria Conductor is an open-source AI Agent orchestration and governance platform (modular monolith).
Tech stack: Java 21 / Spring Boot 3.3 backend, React 19 / Vite frontend, run-owned agent cores
(OpenCode — default and recommended — and Qoder, each placed in HOST or SANDBOX mode), Node.js
MCP server. The LangChain ADK runtime was removed; there is no Python runtime, no fallback and
no removed provider to select.

## Module Responsibility Table

| Module | Responsibility | Entry File |
|--------|---------------|------------|
| `act-common` | Shared models, DTOs, repositories, events, exceptions | `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/` |
| `act-agent` | Agent lifecycle: creation, config, health, templates | `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/` |
| `act-execution` | LLM client, run-owned core runtime (coordinator, launcher, adapters, Host/Sandbox backends), approvals, workflows | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/ExecutionModule.java` |
| `act-knowledge` | Knowledge base CRUD, versioning, Git-backed storage | `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/` |
| `act-aria` | Aria AI assistant: chat sessions, orchestration, scheduled jobs | `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/AriaModule.java` |
| `act-dashboard-api` | REST API controllers for dashboard | `agent-control-tower/act-dashboard-api/src/main/java/io/aria/conductor/dashboard/DashboardModule.java` |
| `act-app` | Spring Boot entry point, config, Flyway migrations | `agent-control-tower/act-app/src/main/java/io/aria/conductor/ActApplication.java` |
| `act-test-support` | Shared test utilities and test data builders | `agent-control-tower/act-test-support/src/main/java/io/aria/conductor/test/` |
| `act-dashboard` | React frontend (pages, components, API layer) | `agent-control-tower/act-dashboard/src/App.tsx` |
| `opencode-sandbox` | Container image (docker/podman) for the OpenCode sandbox (SANDBOX mode) | `agent-control-tower/opencode-sandbox/Dockerfile` |
| `packages/mcp-server` | MCP protocol server (TypeScript) | `packages/mcp-server/src/server.ts` |

## Common Task Paths

### Add/modify a backend API endpoint
1. `act-dashboard-api/` → controller → 2. `act-execution/` or domain module → service → 3. `act-common/` → model/repository

### Add/modify a frontend page
1. `act-dashboard/src/pages/` → page component → 2. `act-dashboard/src/api/` → API call → 3. `act-dashboard/src/components/` → shared UI

### Modify agent execution logic
1. `act-execution/src/main/java/io/aria/conductor/execution/runtime/` → coordinator, launcher, core adapters, backends → 2. `act-execution/.../runtime/core/` → per-core sessions (OpenCode/Qoder) → 3. `act-common/` → Run/PromptCall models

### Modify Aria assistant behavior
1. `act-aria/` → service layer → 2. `act-execution/` → LLM client → 3. `act-common/` → events

### Add/modify knowledge workflow
1. `act-knowledge/` → service/controller → 2. `act-common/model/KnowledgeItem.java` → 3. `act-dashboard/src/pages/` → UI

### Run full-stack locally
1. One-click: `pwsh -NoProfile -File scripts/start.ps1` (local-dev + opencode + podman; checks the
   environment, prepares the sandbox, verifies health, prints the mode). opencode is the default
   and recommended core; `start.ps1` pins it and has no compose mode and no LangChain path.
2. Stop: `pwsh -NoProfile -File scripts/stop.ps1`
3. Host mode (no container runtime and no OpenSandbox needed):
   `pwsh -NoProfile -File scripts/start-backend.ps1 -SkipSandbox` (defaults the provider to the
   Host-capable core `qoder`) or `bash scripts/start-backend.sh --skip-sandbox`.
4. Raw database-backed container stack (MariaDB + backend + frontend + OpenSandbox server):
   `docker compose up -d` (or `podman compose up -d`); `start.ps1` no longer has a compose mode.

## High-Risk Areas

| Area | Risk | Check Command |
|------|------|---------------|
| Execution engine / core runtime | Runaway LLM calls, unbounded token consumption (no per-run token cap; runs are bounded by the wall-clock deadline) | `cd agent-control-tower && mvn test -pl act-execution` |
| Agent lifecycle / core admission | Agent startup failure, unsupported core/mode accepted | `cd agent-control-tower && mvn test -pl act-agent` |
| Run coordinator / run-owned core runtime | Run stuck in RUNNING, unverifiable stop, frozen-binding mismatch | `cd agent-control-tower && mvn test -pl act-execution -Dtest="*CoreExecution*"` |
| Approval gate state machine | Invalid state transitions block workflows | `cd agent-control-tower && mvn test -pl act-common` |
| Flyway migrations (`act-app/src/main/resources/db/migration/`) | Schema break on upgrade; duplicate version numbers | `cd agent-control-tower && mvn verify -pl act-app -Dskip.unit.tests=true -Dit.test=MigrationIntegrationTest` (the guard is an `*IntegrationTest`, so `mvn test -pl act-app` cannot reach it — surefire excludes that tier) |
| LLM provider config / API key handling | Key leak, provider misconfiguration | `cd agent-control-tower && mvn test -pl act-execution -Dtest="*Llm*"` |
| WebSocket events (real-time dashboard) | Event loss, UI state desync | `cd agent-control-tower/act-dashboard && npx playwright test` |
| Host backend process control (`runtime/host/`) | Orphaned core processes, unverified stop | `cd agent-control-tower && mvn test -pl act-execution -Dtest="*Host*"` |
| OpenCode sandbox / OpenSandbox | Sandbox creation failure, endpoint unreachable | `cd agent-control-tower && mvn test -pl act-execution -Dtest="*OpenCode*"` |
| Container runtime selection (`scripts/lib/container-runtime.*`) | podman socket/config mismatch blocks opencode sandbox | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |

## Validation Command Mapping

| Scope | Command |
|-------|---------|
| All Java tests + coverage | `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2` |
| Single module test | `cd agent-control-tower && mvn test -pl <module-name>` |
| Frontend type-check + build | `cd agent-control-tower/act-dashboard && pnpm build` |
| Frontend unit tests (vitest) | `cd agent-control-tower/act-dashboard && pnpm test` |
| Frontend E2E (Playwright) | `cd agent-control-tower/act-dashboard && npx playwright test` |
| MCP server tests | `cd packages/mcp-server && pnpm test` |
| Core cutover contract checks | `node --test e2e/agent-core/cutover-contract.test.mjs` |
| Full build (skip tests) | `cd agent-control-tower && mvn install -DskipTests` |
| Container runtime scenario tests | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |

### Inner loop vs gate (measured 2026-10-27, see [docs/reviews/2026-10-27-maven-loop-audit.md](docs/reviews/2026-10-27-maven-loop-audit.md))

`scripts/mvn-lane.sh` encodes the flag combinations below; use it rather than
re-deriving them. Do not report an unrun tier as verified.

| Lane | Command | Measured |
|------|---------|----------|
| inner (one class) | `bash scripts/mvn-lane.sh inner act-app -Dtest=FooTest` | 10.9 s |
| inner (one module, coverage off) | `bash scripts/mvn-lane.sh inner act-aria` | 18.3 s (was 116 s) |
| gate (upstream refresh + module + coverage) | `bash scripts/mvn-lane.sh gate act-common` | 17.4 s |
| gate on the heaviest module | `bash scripts/mvn-lane.sh gate act-app` | 52.5 s + 6.5 s refresh (incremental) |
| itg (one failsafe class) | `bash scripts/mvn-lane.sh itg act-agent -Dit.test=WorkflowChainRepositorySliceIntegrationTest` | 14.1 s |
| timing (the 4 wall-clock classes, serial) | `bash scripts/mvn-lane.sh timing` | 29-41 s |
| shard 1 lane (install once + full unit tier) | `bash scripts/mvn-lane.sh shard 1` | 250.6 s + refresh |
| shard 2 lanes | `bash scripts/mvn-lane.sh shard 2` | 181.4 s (1.38x over K=1) |
| shard 3 lanes | `bash scripts/mvn-lane.sh shard 3` | 156.3 s (1.60x over K=1) |
| full unit tier, one process, cold | `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2` | 332 s (5:32) |

Sharding keeps the suite complete: lanes run 2256 tests with `-DexcludedGroups=timing` and the
serial timing lane replays the remaining 70, which is exactly the 2326 a single process runs.
Cap local fan-out at 2-3 here: this box is 6C/6T with ~5 GB free, and each lane forks one JVM
that loads a whole Spring graph, so K=4 buys contention instead of time (measured: 1.38x at K=2,
1.60x at K=3, not 2x and 3x). CI proves 4 because `.github/workflows/ci.yml:113-124` puts each
group on its own runner.

The `gate` lane deletes `target/jacoco.exec` first, so "All coverage checks have been met"
reflects that run only (verified on act-app at the 0.80/0.60 ratchet).

- `clean` is not part of the inner loop: it costs +11.4s on act-app alone
  (71s vs 59.6s identical run) and ~49s across the reactor. The one failure mode it
  was masking — a deleted/renamed migration surviving in `target/classes/db/migration`
  and making Flyway abort with `Found more than one migration with version N` — is
  detected directly by the lane script, which names the stale files.
- Coverage is only meaningful on a full-module run. JaCoCo's agent appends, so
  `-Djacoco.skip=true` skips `report` but `check` still passes against the accumulated
  `target/jacoco.exec`; the `gate` lane deletes that file first.
- The hosted unit lane already splits these modules across four runners
  (`.github/workflows/ci.yml:113-139`) and the integration lane runs the whole failsafe
  tier (`:176`), so a local full `mvn test`/`verify` duplicates CI that finishes sooner.

## Quick Reference

- Architecture deep-dive: [docs/architecture.md](docs/architecture.md)
- Setup & config: [README.md](README.md)
- CI pipeline: [.github/workflows/ci.yml](.github/workflows/ci.yml)
- Security notes: [SECURITY.md](SECURITY.md)

## Evidence Discipline for Reports

Applies to any E2E, audit or review deliverable.

- Every factual claim cites either a committed artifact path or a runnable command together with
  its captured output.
- Anything not directly observed is labelled `INFERRED` and carries a `file:line` pointer.
- Never reference a screenshot, log or HAR path that is not committed to the repository.
- A PASS or readiness verdict is only permitted when every pass criterion in the governing test
  plan was actually evaluated. Criteria that were not evaluated are reported as NOT VERIFIED.
- Reports live in `docs/reviews/YYYY-MM-DD-<topic>.md`.

## Conventions

- Java package root: `io.aria.conductor`
- Frontend uses React Router v7 + TanStack Query; API layer in `src/api/`
- All domain events live in `act-common/event/`; publish via Spring ApplicationEventPublisher
- DB migrations: Flyway, scripts in `act-app/src/main/resources/db/migration/`
- E2E specs: `act-dashboard/e2e/*.spec.ts` (Playwright)

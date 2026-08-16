# AGENTS.md

> Orientation file for AI coding agents. For full details see [README.md](README.md) and [docs/architecture.md](docs/architecture.md).

## Project Positioning

Aria Conductor is an open-source AI Agent orchestration and governance platform (modular monolith).
Tech stack: Java 21 / Spring Boot 3.3 backend, React 19 / Vite frontend, OpenCode sandbox (default) + Python 3.11 LangChain ADK runtime, Node.js MCP server.

## Module Responsibility Table

| Module | Responsibility | Entry File |
|--------|---------------|------------|
| `act-common` | Shared models, DTOs, repositories, events, exceptions | `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/` |
| `act-agent` | Agent lifecycle: creation, config, health, templates | `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/` |
| `act-execution` | Tool execution engine, LLM client, ADK provider, circuit breaker | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/ExecutionModule.java` |
| `act-knowledge` | Knowledge base CRUD, versioning, Git-backed storage | `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/` |
| `act-aria` | Aria AI assistant: chat sessions, orchestration, scheduled jobs | `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/AriaModule.java` |
| `act-dashboard-api` | REST API controllers for dashboard | `agent-control-tower/act-dashboard-api/src/main/java/io/aria/conductor/dashboard/DashboardModule.java` |
| `act-app` | Spring Boot entry point, config, Flyway migrations | `agent-control-tower/act-app/src/main/java/io/aria/conductor/ActApplication.java` |
| `act-test-support` | Shared test utilities, mock ADK, test data builders | `agent-control-tower/act-test-support/src/main/java/io/aria/conductor/test/` |
| `act-dashboard` | React frontend (pages, components, API layer) | `agent-control-tower/act-dashboard/src/App.tsx` |
| `langchain-adk` | Python LangChain agent runtime (FastAPI) | `langchain-adk/src/server.py` |
| `opencode-sandbox` | Container image (docker/podman) for OpenCode sandbox (opencode provider) | `agent-control-tower/opencode-sandbox/Dockerfile` |
| `packages/mcp-server` | MCP protocol server (TypeScript) | `packages/mcp-server/src/server.ts` |

## Common Task Paths

### Add/modify a backend API endpoint
1. `act-dashboard-api/` → controller → 2. `act-execution/` or domain module → service → 3. `act-common/` → model/repository

### Add/modify a frontend page
1. `act-dashboard/src/pages/` → page component → 2. `act-dashboard/src/api/` → API call → 3. `act-dashboard/src/components/` → shared UI

### Modify agent execution logic
1. `act-execution/` → engine/provider → 2. `langchain-adk/src/agent.py` → Python runtime → 3. `act-common/` → Run/PromptCall models

### Modify Aria assistant behavior
1. `act-aria/` → service layer → 2. `act-execution/` → LLM client → 3. `act-common/` → events

### Add/modify knowledge workflow
1. `act-knowledge/` → service/controller → 2. `act-common/model/KnowledgeItem.java` → 3. `act-dashboard/src/pages/` → UI

### Run full-stack locally
1. OpenSandbox: `docker compose up -d opensandbox-server` (podman: `podman compose up -d opensandbox-server`; set `SANDBOX_SOCKET` in .env; required for opencode provider)
2. Backend: `cd agent-control-tower && OPENCODE_SANDBOX_SERVER_URL=http://localhost:8090 mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2`
3. Frontend: `cd agent-control-tower/act-dashboard && pnpm dev`
4. ADK (langchain only): `cd langchain-adk && python -m uvicorn src.server:app --port 9300`

## High-Risk Areas

| Area | Risk | Check Command |
|------|------|---------------|
| Execution engine / circuit breaker | Runaway LLM calls, token overconsumption | `cd agent-control-tower && mvn test -pl act-execution` |
| Agent lifecycle / ADK connection | Agent startup failure, port conflicts (9300-9400) | `cd agent-control-tower && mvn test -pl act-agent` |
| Approval gate state machine | Invalid state transitions block workflows | `cd agent-control-tower && mvn test -pl act-common` |
| Flyway migrations (`act-app/src/main/resources/db/migration/`) | Schema break on upgrade | `cd agent-control-tower && mvn test -pl act-app` |
| LLM provider config / API key handling | Key leak, provider misconfiguration | `cd agent-control-tower && mvn test -pl act-execution -Dtest="*Llm*"` |
| WebSocket events (real-time dashboard) | Event loss, UI state desync | `cd agent-control-tower/act-dashboard && npx playwright test` |
| Python ADK tool binding | Tool schema mismatch causes agent crash | `cd langchain-adk && python -m pytest tests/` |
| OpenCode sandbox / OpenSandbox | Sandbox creation failure, endpoint unreachable | `cd agent-control-tower && mvn test -pl act-execution -Dtest="*OpenCode*"` |
| Container runtime selection (`scripts/lib/container-runtime.*`) | podman socket/config mismatch blocks opencode sandbox | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |

## Validation Command Mapping

| Scope | Command |
|-------|---------|
| All Java tests + coverage | `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2` |
| Single module test | `cd agent-control-tower && mvn test -pl <module-name>` |
| Frontend type-check + build | `cd agent-control-tower/act-dashboard && pnpm build` |
| Frontend E2E (Playwright) | `cd agent-control-tower/act-dashboard && npx playwright test` |
| Python ADK tests | `cd langchain-adk && python -m pytest tests/` |
| MCP server tests | `cd packages/mcp-server && npx vitest run` |
| Full build (skip tests) | `cd agent-control-tower && mvn install -DskipTests` |
| Docker full-stack | `docker compose up -d` |
| Container runtime scenario tests | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |

## Quick Reference

- Architecture deep-dive: [docs/architecture.md](docs/architecture.md)
- Setup & config: [README.md](README.md)
- CI pipeline: [.github/workflows/ci.yml](.github/workflows/ci.yml)
- Security notes: [SECURITY.md](SECURITY.md)

## Conventions

- Java package root: `io.aria.conductor`
- Frontend uses React Router v7 + TanStack Query; API layer in `src/api/`
- All domain events live in `act-common/event/`; publish via Spring ApplicationEventPublisher
- DB migrations: Flyway, scripts in `act-app/src/main/resources/db/migration/`
- E2E specs: `act-dashboard/e2e/*.spec.ts` (Playwright)

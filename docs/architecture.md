# Architecture

## Overview

Aria Conductor is a modular monolith built with Java 21 + Spring Boot 3.3 for governed AI agent execution. It provides a complete control tower for managing fleets of AI agents with built-in governance workflows, approval gates, and observability.

## System Architecture

```
┌───────────────┐     ┌──────────────┐     ┌──────────────────────────────┐
│   Dashboard   │────▶│   Backend    │────▶│   Run-owned agent cores      │
│  (React/Vite) │◀────│ (Spring Boot)│◀────│  opencode (default) / qoder  │
│   Port 3000   │     │   Port 8080  │     │  HOST: on the backend host   │
└───────────────┘     └──────┬───────┘     │  SANDBOX: via OpenSandbox    │
                            │              └──────────────────────────────┘
                     ┌──────▼───────┐
                     │   Database   │
                     │ H2 / MariaDB │
                     └──────────────┘
```

> The Dashboard port above is the raw container stack's (`FRONTEND_PORT`, default `3000`). In the
> default local-dev topology the Dashboard is the Vite dev server on `5173`, with the backend and
> frontend both running on the host — see *Starting the stack* in [README.md](README.md).

## Module Structure

| Module | Responsibility |
|--------|---------------|
| **act-common** | Shared models (Agent, Run, Approval, Knowledge), DTOs, repositories, enums |
| **act-agent** | Agent lifecycle management — creation, configuration, health monitoring, template system |
| **act-execution** | Tool execution engine, LLM client abstraction, run-owned core runtime (coordinator, launcher, adapters, Host/Sandbox backends), circuit breaker |
| **act-knowledge** | Knowledge base management — CRUD, versioning, Git-backed storage |
| **act-aria** | Aria AI assistant — chat sessions, agent orchestration, scheduled jobs |
| **act-dashboard-api** | REST API controllers for the dashboard frontend |
| **act-app** | Spring Boot application entry point, configuration, Flyway migrations |
| **act-test-support** | Shared test utilities, mock ADK runtime, test data builders |

## Key Concepts

### Agent

An autonomous AI entity with a defined role (Business Analyst, Developer, QA). Each agent carries a **core** (`opencode`, the default, or `qoder`), an **execution mode** (`HOST` — run on the backend host, no container runtime needed — or `SANDBOX` — run in an isolated container via OpenSandbox) and a workspace selection; it can execute tools, participate in workflows, and respond to conversations.

### Run

A single execution cycle of an agent. A run is owned end to end by the run coordinator: its core/mode selection is frozen into an immutable execution binding on the first attempt, the attempt is prepared with a workspace lease and a launch profile, executed through the core session, and finalized only on an observed verified stop. Each run has a status: `RUNNING` → `COMPLETED` / `FAILED` / `CANCELLED` / `TIMEOUT`.

### Approval Gate

A governance checkpoint in the workflow pipeline. Approvals follow the flow: `PENDING` → `APPROVED` / `REJECTED`. Each gate can be configured as required (blocks pipeline) or optional (auto-passes).

### Knowledge

Versioned documents (guidelines, workflows, specs) managed through an approval lifecycle: `DRAFT` → `PENDING` → `APPROVED` / `REJECTED`. Backed by Git for version history.

### Aria

The AI operator assistant that helps manage the agent fleet. Aria can create agents, orchestrate multi-agent workflows, monitor health, and handle scheduled jobs.

## Data Flow

1. **User** submits a task via the Dashboard
2. **Dashboard API** creates a Kanban item and assigns it to an agent
3. **Execution Engine** starts a Run: admission normalizes the agent's core/mode/workspace selection (unsupported values are refused, never substituted) and the run coordinator freezes the run's execution binding
4. **The run-owned core session** processes the task using LLM + tools — placed in `HOST` mode on the backend host, or in `SANDBOX` mode in an isolated container via OpenSandbox
5. **Agent** iterates: LLM call → tool execution → LLM call → ...
6. **Run** completes (only on a verified stop) and results are stored
7. **Approval gates** may pause the workflow for human review; write permissions are granted once per run through the coordinator
8. **Dashboard** displays real-time status via WebSocket events

## LLM Integration

- LLM provider configuration is stored in the database and managed via API
- Supports any OpenAI-compatible API (OpenAI, DeepSeek, etc.)
- API keys are stored encrypted; providers can be activated/deactivated
- Circuit breaker prevents runaway token consumption

## Agent Cores and Execution Modes

The supported cores are exactly `opencode` (default) and `qoder`; the `adk.default-provider`
property (`opencode`) only selects the default for a new agent that omits the core. An unknown
or removed core is refused explicitly — there is no fallback and the removed LangChain runtime
is neither selectable nor resolvable.

- **opencode** (default, recommended): the OpenCode CLI runs on the host (`HOST`) or in a dedicated sandbox container per agent (`SANDBOX`), managed through an OpenSandbox server (podman is the local-dev container runtime default; Docker is also supported)
- **qoder**: the Qoder CLI driven over the ACP bridge, in the same two placements
- `HOST` mode needs **no container runtime at all**; `SANDBOX` mode keeps an explicit OpenSandbox/container-runtime check at startup
- Health monitoring: a service-level probe for the provider inventory plus run-scoped recovery through the coordinator (no permanent per-agent pre-warm)

## Configuration Profiles

| Profile | Use Case | Database |
|---------|----------|----------|
| `h2` | Local development | H2 file database |
| `mariadb` | Container (podman / Docker) / Production | MariaDB |

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.3, Spring Data JPA, Flyway |
| Frontend | React 19, Vite, TypeScript, Playwright (E2E) |
| Agent Runtime | Run-owned cores: opencode (default) / qoder, placed HOST (on the backend host) or SANDBOX (OpenSandbox) |
| Database | H2 (dev) / MariaDB (production) |
| MCP | Node.js, TypeScript |
| Build | Maven 3.9+, pnpm 9+ |
| CI/CD | GitHub Actions |
| Containerization | Podman (local-dev default) / Docker, `podman compose` / `docker compose` |

# Architecture

## Overview

Aria Conductor is a modular monolith built with Java 21 + Spring Boot 3.3 for governed AI agent execution. It provides a complete control tower for managing fleets of AI agents with built-in governance workflows, approval gates, and observability.

## System Architecture

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────┐
│   Dashboard   │────▶│   Backend    │────▶│  LangChain ADK   │
│  (React/Vite) │◀────│ (Spring Boot)│◀────│  (Python/FastAPI) │
│   Port 3000   │     │   Port 8080  │     │    Port 9300     │
└──────────────┘     └──────┬───────┘     └──────────────────┘
                            │
                     ┌──────▼───────┐
                     │   Database   │
                     │ H2 / MariaDB │
                     └──────────────┘
```

## Module Structure

| Module | Responsibility |
|--------|---------------|
| **act-common** | Shared models (Agent, Run, Approval, Knowledge), DTOs, repositories, enums |
| **act-agent** | Agent lifecycle management — creation, configuration, health monitoring, template system |
| **act-execution** | Tool execution engine, LLM client abstraction, ADK provider integration, circuit breaker |
| **act-knowledge** | Knowledge base management — CRUD, versioning, Git-backed storage |
| **act-aria** | Aria AI assistant — chat sessions, agent orchestration, scheduled jobs |
| **act-dashboard-api** | REST API controllers for the dashboard frontend |
| **act-app** | Spring Boot application entry point, configuration, Flyway migrations |
| **act-test-support** | Shared test utilities, mock ADK runtime, test data builders |

## Key Concepts

### Agent

An autonomous AI entity with a defined role (Business Analyst, Developer, QA). Each agent runs on the LangChain ADK runtime and can execute tools, participate in workflows, and respond to conversations.

### Run

A single execution cycle of an agent. Runs iterate through tool calls and LLM responses until completion, timeout, or cancellation. Each run has a status: `RUNNING` → `COMPLETED` / `FAILED` / `CANCELLED` / `TIMEOUT`.

### Approval Gate

A governance checkpoint in the workflow pipeline. Approvals follow the flow: `PENDING` → `APPROVED` / `REJECTED`. Each gate can be configured as required (blocks pipeline) or optional (auto-passes).

### Knowledge

Versioned documents (guidelines, workflows, specs) managed through an approval lifecycle: `DRAFT` → `PENDING` → `APPROVED` / `REJECTED`. Backed by Git for version history.

### Aria

The AI operator assistant that helps manage the agent fleet. Aria can create agents, orchestrate multi-agent workflows, monitor health, and handle scheduled jobs.

## Data Flow

1. **User** submits a task via the Dashboard
2. **Dashboard API** creates a Kanban item and assigns it to an agent
3. **Execution Engine** starts a Run on the agent''s ADK instance
4. **ADK Runtime** (Python) processes the task using LLM + tools
5. **Agent** iterates: LLM call → tool execution → LLM call → ...
6. **Run** completes and results are stored
7. **Approval gates** may pause the workflow for human review
8. **Dashboard** displays real-time status via WebSocket events

## LLM Integration

- LLM provider configuration is stored in the database and managed via API
- Supports any OpenAI-compatible API (OpenAI, DeepSeek, etc.)
- API keys are stored encrypted; providers can be activated/deactivated
- Circuit breaker prevents runaway token consumption

## ADK (Agent Development Kit)

- Python-based runtime using LangChain + FastAPI
- Each agent can run as a subprocess (local dev) or connect to a standalone container (Docker)
- Health monitoring with automatic restart on failure
- Port range allocation: 9300-9400

## Configuration Profiles

| Profile | Use Case | Database |
|---------|----------|----------|
| `h2` | Local development | H2 file database |
| `mariadb` | Docker / Production | MariaDB |

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.3, Spring Data JPA, Flyway |
| Frontend | React 19, Vite, TypeScript, Playwright (E2E) |
| Agent Runtime | Python 3.11, LangChain, FastAPI, Uvicorn |
| Database | H2 (dev) / MariaDB (production) |
| MCP | Node.js, TypeScript |
| Build | Maven 3.9+, pnpm 9+ |
| CI/CD | GitHub Actions |
| Containerization | Docker, Docker Compose |

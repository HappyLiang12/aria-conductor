# Aria Conductor

An open-source AI Agent orchestration and governance platform.

Aria Conductor provides a complete control tower for managing fleets of AI agents with built-in governance workflows, approval gates, and observability.

## Features

- **Multi-Agent Orchestration** — Create, configure, and manage multiple AI agents with different roles (Business Analyst, Developer, QA)
- **Governance Workflows** — Built-in approval gates, review cycles, and compliance checkpoints
- **Aria Assistant** — AI-powered operator assistant for managing your agent fleet
- **LLM Provider Agnostic** — Works with OpenAI, DeepSeek, or any OpenAI-compatible API
- **Exchangeable Agent Provider** — Choose between **OpenCode** (sandbox-isolated, default) or **LangChain ADK** (Python runtime) per agent
- **OpenCode Sandbox** — Agent code execution in isolated Docker containers via OpenSandbox
- **MCP Server** — Model Context Protocol server for tool integration
- **Real-time Dashboard** — React-based dashboard with live agent status, kanban board, and activity timeline

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 21, Spring Boot 3.3, Spring Data JPA |
| Frontend | React 19, Vite, TypeScript |
| Agent Runtime | OpenCode (sandbox) / Python 3.11 LangChain (ADK) |
| Sandbox | OpenSandbox (Docker-based isolation) |
| Database | H2 (dev) / MariaDB (production) |
| MCP Server | Node.js, TypeScript |
| Containerization | Docker / Podman, Docker Compose / Podman Compose |

## Quick Start (Docker)

### Prerequisites

- Docker (or podman) and Docker Compose v2 (or podman compose)
- An LLM API key (OpenAI, DeepSeek, etc.)

### 1. Clone and configure

```bash
git clone https://github.com/HappyLiang12/aria-conductor.git
cd aria-conductor
cp .env.example .env
```

Edit `.env` and set your LLM API key:
```
LLM_API_KEY=your-api-key-here
```

### 2. Start all services

```bash
docker compose up -d
```

This starts the backend, frontend, LangChain ADK, and **OpenSandbox server** (for OpenCode agent runtime). Wait ~60 seconds for all services to be healthy.

### 3. Open the dashboard

Navigate to [http://localhost:3000](http://localhost:3000)

### 4. Configure LLM provider

> **Optional REST API auth**: set `ARIA_API_KEY` in `.env` to require a Bearer token
> on every `/api/v1/**` request. When enabled, append
> `-H "Authorization: Bearer $ARIA_API_KEY"` to the curl examples below (the dashboard
> prompts for the token automatically). Leave it blank for the permissive local default.

Use the dashboard Settings page or the API:
```bash
curl -X POST http://localhost:8080/api/v1/llm-providers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ARIA_API_KEY" \
  -d '{
    "name": "openai",
    "type": "OPENAI",
    "apiKey": "sk-...",
    "baseUrl": "https://api.openai.com/v1",
    "defaultModel": "gpt-4o"
  }'
```

### 5. Create an agent

Agents default to the **opencode** (sandbox-isolated) provider. To use **langchain** (shared process), switch the provider in the Crew page or set the `ADK_PROVIDER=langchain` environment variable (`-AdkProvider langchain` on Windows) when starting the backend. In the Docker Compose stack the containerized backend cannot reach the opencode sandbox, so it runs the **langchain** provider (see the topology note under *Container Runtime Selection*).

## Agent Providers

| Provider | Description | Isolation |
|----------|-------------|-----------|
| **opencode** (default) | OpenCode CLI in Docker sandbox via OpenSandbox | Container per agent |
| **langchain** | Python LangChain ADK runtime | Shared process |

> **Approvals**: Task-level runs (opencode provider) require human approval by
> default: the run starts in approval-pending state and executes after approval
> in the Approvals page. To disable per-agent, set agent config
> `"taskApprovalRequired": false`.

To switch an agent's provider, use the Crew page or the API:
```bash
curl -X PUT http://localhost:8080/api/v1/agents/{id} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ARIA_API_KEY" \
  -d '{"adkProvider": "langchain"}'
```

## Container Runtime Selection

Startup scripts and the OpenSandbox server support **Docker** (default) and **podman**.

- If `CONTAINER_RUNTIME` is unset, scripts auto-detect: docker (running) → podman (running).
- Set `CONTAINER_RUNTIME=docker|podman` in `.env` to force one runtime (strict: hard error when unavailable).
- The OpenSandbox server mounts the container socket from `SANDBOX_SOCKET` (default `/var/run/docker.sock`).

### podman machine (Windows)

1. `podman machine init` then `podman machine start` (Podman Desktop users: start from the app).
2. Rootless (recommended): the user socket service is enabled by default inside the VM. Verify:
   `podman machine ssh "ls -l /run/user/1000/podman/podman.sock"`
   If missing: `podman machine ssh "systemctl --user enable --now podman.socket"`
3. Rootful: `podman machine set --rootful`, then `podman machine ssh "sudo systemctl enable --now podman.socket"`.
4. In `.env` set:
   ```
   CONTAINER_RUNTIME=podman
   SANDBOX_SOCKET=/run/user/1000/podman/podman.sock   # rootless (or /run/podman/podman.sock for rootful)
   ```
5. Build the sandbox image into podman's store:
   `podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox`
6. Start as usual (`docker compose` commands become `podman compose ...`):
   `podman compose up -d` or `./scripts/quickstart.sh`

> **podman + host backend + opencode**: the full-stack compose topology runs the
> backend in a container, where the opencode provider is NOT usable (sandbox
> endpoints are unreachable from inside the backend container; see the topology
> note in `opensandbox-config.toml`). To run the backend on the host with podman
> and the opencode provider, use the local-dev path:
> `pwsh -NoProfile -File scripts/start-backend.ps1 -AdkProvider opencode`
> (auto-starts the OpenSandbox server via podman) plus `scripts/start-frontend.ps1`.

> Note: OpenSandbox has no native podman runtime; podman is served through its Docker-compatible socket. Sandbox support under podman is validated by the project's E2E suite (see `e2e/container-runtime-e2e.ps1`).

## Development Setup

For local development without Docker:

### Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java | 21 | `java -version` |
| Maven | 3.9+ | `mvn --version` |
| Node.js | 20+ | `node --version` |
| pnpm | 9+ | `pnpm --version` |
| Python | 3.11+ | `python --version` |
| Docker / Podman | 24+ / 4.9+ | `docker --version` or `podman --version` (required for opencode provider) |

### Quick start with scripts

```bash
# Docker available → full stack with OpenCode sandbox
./scripts/quickstart.sh        # Linux/macOS
.\scripts\quickstart.ps1       # Windows

# Or start individual services:
./scripts/start-backend.sh     # Starts backend (OpenSandbox only for opencode provider)
./scripts/start-frontend.sh    # Vite dev server
```

The `start-backend` script defaults to the **opencode** ADK provider. Pass `--provider=langchain` (Linux/macOS) or `-AdkProvider langchain` (Windows) to use **langchain**; with opencode the script also starts the OpenSandbox server (requires Docker) and passes the provider to the backend. Use `--skip-sandbox` or `-SkipSandbox` to skip OpenSandbox startup.

### Backend

```bash
cd agent-control-tower
mvn clean install -DskipTests

# With opencode provider (default; requires Docker for the OpenSandbox server):
mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2

# With langchain provider (no sandbox needed):
mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2 -Dspring-boot.run.arguments=--adk.default-provider=langchain

# Set OpenSandbox URL for local dev:
# OPENCODE_SANDBOX_SERVER_URL=http://localhost:8090
```

Backend starts at `http://localhost:8080`

### Frontend

```bash
cd agent-control-tower/act-dashboard
pnpm install
pnpm dev
```

Dashboard starts at `http://localhost:5173`

### Python ADK Runtime (langchain provider)

Only needed when using the **langchain** ADK provider:

```bash
cd langchain-adk
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt  # Windows
# .venv/bin/pip install -r requirements.txt   # Linux/macOS
python -m uvicorn src.server:app --port 9300
```

### OpenSandbox Server (opencode provider)

Required for the **opencode** ADK provider. Start via Docker Compose:

```bash
docker compose up -d opensandbox-server
# or with podman:
# podman compose up -d opensandbox-server
```

OpenSandbox server starts at `http://localhost:8090`. The opencode sandbox image must be built first:

```bash
docker build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox
# or: podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox
```

## Module Structure

| Module | Description |
|--------|-------------|
| `act-common` | Shared models, DTOs, repositories |
| `act-agent` | Agent lifecycle management |
| `act-execution` | Tool execution engine, LLM client, ADK integration (OpenCode + LangChain) |
| `act-knowledge` | Knowledge base management |
| `act-aria` | Aria AI assistant service |
| `act-dashboard-api` | Dashboard REST API controllers |
| `act-app` | Spring Boot application entry point |
| `act-test-support` | Shared test utilities |
| `act-dashboard` | React frontend dashboard |
| `langchain-adk` | Python LangChain agent runtime |
| `opencode-sandbox` | Docker image for OpenCode sandbox |
| `packages/mcp-server` | MCP protocol server |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_API_KEY` | — | Your LLM provider API key |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | LLM API base URL |
| `LLM_MODEL` | `gpt-4o` | Default LLM model |
| `OPENCODE_SANDBOX_SERVER_URL` | `http://localhost:8090` | OpenSandbox server URL |
| `OPENSANDBOX_API_KEY` | — | OpenSandbox API key (empty = insecure mode) |
| `DEEPSEEK_API_KEY` | — | Injected into sandbox for opencode agents |
| `DB_HOST` | `mariadb` | Database host (Docker) |
| `DB_PORT` | `3306` | Database port |
| `DB_NAME` | `aria_conductor` | Database name |
| `CONTAINER_RUNTIME` | auto-detect | Container runtime: `docker` or `podman` (auto-detect: docker preferred) |
| `SANDBOX_SOCKET` | `/var/run/docker.sock` | Host container-engine socket mounted into the OpenSandbox server |

### Spring Profiles

| Profile | Description |
|---------|-------------|
| `h2` | Local development with H2 file database (default for dev) |
| `mariadb` | Production deployment with MariaDB (default for Docker) |

## API Documentation

When the backend is running, access the Swagger UI:
- `http://localhost:8080/swagger-ui.html`

## Security

Aria Conductor is an early-stage project with known security trade-offs for easy local
evaluation. **Do not expose a default deployment to untrusted networks.** In particular,
the API currently has **no built-in authentication**, the `shell_exec` tool is disabled
by default (`tools.shell.enabled`), and Docker services bind to `127.0.0.1` only. See
[SECURITY.md](SECURITY.md) for the full list and how to report vulnerabilities.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

# Aria Conductor

An open-source AI Agent orchestration and governance platform.

Aria Conductor provides a complete control tower for managing fleets of AI agents with built-in governance workflows, approval gates, and observability.

## Features

- **Multi-Agent Orchestration** — Create, configure, and manage multiple AI agents with different roles (Business Analyst, Developer, QA)
- **Governance Workflows** — Built-in approval gates, review cycles, and compliance checkpoints
- **Aria Assistant** — AI-powered operator assistant for managing your agent fleet
- **LLM Provider Agnostic** — Works with OpenAI, DeepSeek, or any OpenAI-compatible API
- **Exchangeable Agent Provider** — **OpenCode** (sandbox-isolated) is the default and recommended provider; **LangChain ADK** (Python runtime) is legacy and is only used by the full-stack compose topology or a deliberate per-agent opt-out
- **OpenCode Sandbox** — Agent code execution in an isolated container per agent via OpenSandbox
- **MCP Server** — Model Context Protocol server for tool integration
- **Real-time Dashboard** — React-based dashboard with live agent status, kanban board, and activity timeline

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 21, Spring Boot 3.3, Spring Data JPA |
| Frontend | React 19, Vite, TypeScript |
| Agent Runtime | OpenCode (sandbox, default) / Python 3.11 LangChain ADK (legacy, opt-out) |
| OpenSandbox | One sandbox container per agent, via a Docker-compatible socket |
| Database | H2 (dev) / MariaDB (production) |
| MCP Server | Node.js, TypeScript |
| Containerization | Docker / Podman, Docker Compose / Podman Compose |

## Quick Start

### Prerequisites

- Podman (default) or Docker, plus a compose provider (`podman compose` / `docker compose`)
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

### 2. Start all services (Windows)

```powershell
.\scripts\start.ps1
```

This is the single Windows entrypoint. It defaults to the **local-dev topology on podman**:
backend and frontend on the host, OpenSandbox in a container. It checks the environment,
creates or tops up `.env`, prepares the sandbox image and the OpenSandbox server, verifies
health, then prints the running mode. Wait until it reports every service healthy.

On Linux/macOS the equivalent is to start the services individually
(`./scripts/start-backend.sh` and `./scripts/start-frontend.sh`); see *Development Setup*.

The legacy full-stack compose stack remains available as an opt-in, container-only
alternative:

```powershell
.\scripts\start.ps1 -Mode compose
# raw equivalent:
docker compose up -d        # or: podman compose up -d
```

It starts MariaDB, the backend, the frontend, the LangChain ADK, and the OpenSandbox
server. That topology is **langchain-only** — the containerized backend cannot reach the
OpenSandbox endpoints, so the opencode provider cannot be used there (see the topology note
under *Starting the stack*). Wait ~60 seconds for all services to be healthy.

### 3. Open the Dashboard

The Dashboard port depends on the topology you started:

- **local-dev** (`.\scripts\start.ps1`, the default): [http://localhost:5173](http://localhost:5173) — Vite serves the Dashboard on the host.
- **compose** (`-Mode compose` / `docker compose up -d`): [http://localhost:3000](http://localhost:3000) — the Dashboard is published on `FRONTEND_PORT` (default 3000).

### 4. Configure LLM provider

Use the Dashboard Settings page or the API:
```bash
curl -X POST http://localhost:8080/api/v1/llm-providers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "openai",
    "type": "OPENAI",
    "apiKey": "sk-...",
    "baseUrl": "https://api.openai.com/v1",
    "defaultModel": "gpt-4o"
  }'
```

### 5. Create an agent

Agents default to the **opencode** (sandbox-isolated) provider — that is the recommended path.
**langchain** (shared process) is not a routine alternative: it is correct only in the full-stack
compose topology, where the containerized backend cannot reach the OpenSandbox endpoints, or when
you deliberately opt out of opencode. To opt out, switch the provider in the Crew page, or start
the backend with the variable that matches the invocation:

- `scripts/start-backend.ps1` (Windows): `-AdkProvider langchain`
- `scripts/start-backend.sh` (Linux/macOS): `--provider=langchain` (or `ADK_PROVIDER=langchain`)
- bare `mvn spring-boot:run`: `--adk.default-provider=langchain`
- `ADK_DEFAULT_PROVIDER=langchain` — the environment variable Spring Boot reads for the
  `adk.default-provider` property

`scripts/start.ps1` pins `opencode` and has no langchain path; the compose stack is the one
topology where langchain is unavoidable (see the topology note under *Starting the stack*).

## Agent Providers

**opencode** is the default and the recommended provider. **langchain** is legacy: it is correct
only for the full-stack compose topology (where the containerized backend cannot reach the
OpenSandbox endpoints) or as an explicit opt-out.

| Provider | Description | Isolation |
|----------|-------------|-----------|
| **opencode** (default, recommended) | OpenCode CLI in a sandbox container via OpenSandbox | Container per agent |
| **langchain** (legacy; compose-only or explicit opt-out) | Python LangChain ADK runtime | Shared process |

> **Approvals**: Task-level runs (opencode provider) require human approval by
> default: the run starts in approval-pending state and executes after approval
> in the Approvals page. To disable per-agent, set agent config
> `"taskApprovalRequired": false`.

To switch an agent's provider, use the Crew page or the API. opencode is the default:
```bash
curl -X PUT http://localhost:8080/api/v1/agents/{id} \
  -H "Content-Type: application/json" \
  -d '{"adkProvider": "opencode"}'

# langchain is the explicit alternative (legacy; necessary only in the compose topology):
#   -d '{"adkProvider": "langchain"}'
```

## Starting the stack

```powershell
.\scripts\start.ps1          # local-dev + opencode + podman (default)
.\scripts\stop.ps1           # stop what start.ps1 started (local-dev)
```

`start.ps1` checks the environment, starts the podman machine when needed, creates or tops up
`.env`, prepares the sandbox image and the OpenSandbox server, verifies health, then prints the
running mode. The opencode provider requires the **local-dev topology** — backend and frontend on
the host, OpenSandbox in a container. That is what this script starts.

`stop.ps1` stops the backend and frontend processes and the OpenSandbox server container that
`start.ps1` started (`-All` also stops leftover sandbox containers and the podman machine).
A `-Mode compose` stack is not torn down by `stop.ps1`; stop it with `podman compose down`
(use `docker compose down` if you started the raw compose command with docker instead).

`-Mode compose` runs the legacy full-stack compose stack instead. That topology cannot run the
opencode provider, so it uses langchain (the backend runs in a container, where the OpenSandbox
endpoints are unreachable; see the topology note in `opensandbox-config.toml`).

### Qoder provider (explicit opt-in)

The **qoder** provider runs the Qoder CLI in its own sandbox image and is never selected by
default — `-Provider qoder` is an explicit opt-in:

```powershell
.\scripts\start.ps1 -Provider qoder
```

The mode selects the qoder provider and its sandbox image (`aria-conductor/qoder-sandbox:0.1`).
On Windows `start.ps1` builds that image automatically when it is missing; the bash launcher
does not, so build it once (the equivalent manual command):

```powershell
podman build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox
```

Because a qoder sandbox shares the local network with the backend, this mode pins
`ARIA_MCP_AUTH_MODE=token` and writes the generated MCP bearer to `.run/mcp-token`, so a sandbox
can never reach an unauthenticated operator MCP endpoint. An explicit `ARIA_MCP_AUTH_MODE=none`
override is refused with an error — unset it (or set it to `token`) to start. The mode needs the
local-dev topology; `-Mode compose` is langchain-only and rejects `-Provider qoder`.

## Container Runtime Selection

Startup scripts and the OpenSandbox server support **podman** (the default for local dev) and **Docker**.

- `scripts/start.ps1` (Windows) **prefers podman**: whenever the podman CLI is present it pins
  `CONTAINER_RUNTIME=podman` and only falls back to docker, with a warning, when podman is absent.
- The Linux/macOS `.sh` scripts auto-detect only when `CONTAINER_RUNTIME` is unset: docker
  (running) → podman (running).
- Set `CONTAINER_RUNTIME=docker|podman` in `.env` to force one runtime (strict: hard error when
  unavailable). An explicit value always wins over the two behaviours above.
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
   `podman compose up -d`

> Note: OpenSandbox has no native podman runtime; podman is served through its Docker-compatible socket. OpenSandbox support under podman is validated by the project's E2E suite (see `e2e/container-runtime-e2e.ps1`).

## Development Setup

For local development without a container runtime:

### Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java | 21 | `java -version` |
| Maven | 3.9+ | `mvn --version` |
| Node.js | 20+ | `node --version` |
| pnpm | 9+ | `pnpm --version` |
| Python | 3.11+ | `python --version` |
| Docker / Podman | 24+ / 4.9+ | podman is the default for local dev; docker is supported |

### Quick start with scripts

```bash
# Windows one-click (see "Starting the stack" above)
.\scripts\start.ps1

# Or start individual services:
./scripts/start-backend.sh     # Starts backend (OpenSandbox for the opencode/qoder providers)
./scripts/start-frontend.sh    # Vite dev server
```

The `start-backend` script defaults to the **opencode** ADK provider (the recommended path), and
with opencode or qoder it also starts the OpenSandbox server (requires a container runtime) and
passes the provider to the backend. Use `--skip-sandbox` or `-SkipSandbox` to skip OpenSandbox
startup. The qoder provider is an explicit opt-in (`--provider=qoder`, or
`scripts/start.ps1 -Provider qoder` on Windows); it uses the same sandbox server and additionally
pins MCP token auth with a local token file — see *Qoder provider (explicit opt-in)* under
*Starting the stack*.
Opting out to the legacy **langchain** provider is explicit: `--provider=langchain`
(Linux/macOS) or `-AdkProvider langchain` (Windows), or `ADK_PROVIDER=langchain` in the
environment — `ADK_PROVIDER` belongs to `scripts/start-backend.{sh,ps1}` only.

### Backend

```bash
cd agent-control-tower
mvn clean install -DskipTests

# With opencode provider (default and recommended; requires a container runtime for the
# OpenSandbox server):
mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2

# Explicit opt-out to the legacy langchain provider (no sandbox needed). The env-var form is
# ADK_DEFAULT_PROVIDER=langchain; --adk.default-provider is the property name:
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

Dashboard starts at `http://localhost:5173` — the Vite dev-server port of the **local-dev**
topology (backend and frontend on the host). The **compose** topology publishes the Dashboard
on `FRONTEND_PORT` instead, which defaults to `3000`.

### Python ADK Runtime (langchain provider only)

Not needed for a normal start: the default local-dev start (`scripts/start.ps1`, or
`start-backend.*` without a provider override) uses the opencode provider and talks to OpenSandbox
instead. Start this runtime only when you deliberately opted out to the legacy **langchain** ADK
provider — the compose topology starts it for you.

```bash
cd langchain-adk
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt  # Windows
# .venv/bin/pip install -r requirements.txt   # Linux/macOS
python -m uvicorn src.server:app --port 9300
```

### OpenSandbox Server (opencode provider)

Required for the **opencode** ADK provider. Start it with the compose provider of your container runtime — `podman compose` for the local-dev default, `docker compose` is equivalent:

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
| `opencode-sandbox` | Container image for the OpenCode sandbox (podman or docker) |
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
| `CONTAINER_RUNTIME` | auto-detect | Container runtime: `docker` or `podman`. Unset: `scripts/start.ps1` prefers podman, the Linux/macOS `.sh` scripts auto-detect docker first; an explicit value always wins |
| `SANDBOX_SOCKET` | `/var/run/docker.sock` | Host container-engine socket mounted into the OpenSandbox server |

### Spring Profiles

| Profile | Description |
|---------|-------------|
| `h2` | Local development with H2 file database (default for dev) |
| `mariadb` | Production deployment with MariaDB (default for the containerized profile) |

### MCP endpoint (aria.mcp.*)

The backend exposes an MCP (Model Context Protocol) server at `http://<host>:8080/mcp` for AI agents:

- **Sandboxed agents**: Aria's opencode sandbox connects automatically (workers do not). Requires a sandbox-reachable host address — auto-resolved, override with `ARIA_MCP_SANDBOX_HOST_ADDRESS`.
- **External agents**: point any MCP client at `http://<host>:8080/mcp`.
- **Auth**: `ARIA_MCP_AUTH_MODE=none` (default — endpoint is open, like the REST API; every tool call is audit-logged) or `token` (Bearer required; set `ARIA_MCP_TOKEN`, sandbox token injected automatically). Local **qoder** mode (`scripts/start.ps1 -Provider qoder`, `start-backend.sh --provider=qoder`) always pins `token` and writes the generated bearer to `.run/mcp-token`; an explicit `none` override is refused there.
- **Debug**: `ARIA_MCP_DEBUG=true` adds full stack traces to tool error responses (default true on the h2 dev profile).
- **Disable**: `ARIA_MCP_ENABLED=false` (the `test` profile disables it by default).

> Exposure note: with the default `none` auth mode, any client that can reach the port has operator-level tool access — the same trust level as the open REST API. Prefer `token` mode for shared networks.

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

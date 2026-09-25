# Aria Conductor

An open-source AI Agent orchestration and governance platform.

Aria Conductor provides a complete control tower for managing fleets of AI agents with built-in governance workflows, approval gates, and observability.

## Features

- **Multi-Agent Orchestration** — Create, configure, and manage multiple AI agents with different roles (Business Analyst, Developer, QA)
- **Governance Workflows** — Built-in approval gates, review cycles, and compliance checkpoints
- **Aria Assistant** — AI-powered operator assistant for managing your agent fleet
- **LLM Provider Agnostic** — Works with OpenAI, DeepSeek, or any OpenAI-compatible API
- **Exchangeable Agent Core** — **OpenCode** is the default and recommended agent core; **Qoder** is the second supported core. Every agent also selects an execution mode: **Host** (no container runtime needed) or **Sandbox** (isolated container via OpenSandbox)
- **OpenCode Sandbox** — Agent code execution in an isolated container per agent via OpenSandbox
- **MCP Server** — Model Context Protocol server for tool integration
- **Real-time Dashboard** — React-based dashboard with live agent status, kanban board, and activity timeline

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 21, Spring Boot 3.3, Spring Data JPA |
| Frontend | React 19, Vite, TypeScript |
| Agent Runtime | OpenCode (default core) / Qoder, placed in Host or Sandbox mode |
| OpenSandbox | One sandbox container per agent (Sandbox mode), via a Docker-compatible socket |
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

The raw container stack is still available for a database-backed deployment:

```bash
docker compose up -d        # or: podman compose up -d
```

It starts MariaDB, the backend, the frontend and the OpenSandbox server. `scripts/start.ps1`
has no compose mode any more: the containerized full-stack mode existed only to run the
removed LangChain runtime, and the opencode **Sandbox** mode needs the backend on the host
(see the topology note under *Starting the stack*). Wait ~60 seconds for all services to be healthy.

### 3. Open the Dashboard

The Dashboard port depends on the topology you started:

- **local-dev** (`.\scripts\start.ps1`, the default): [http://localhost:5173](http://localhost:5173) — Vite serves the Dashboard on the host.
- **raw container stack** (`docker compose up -d`): [http://localhost:3000](http://localhost:3000) — the Dashboard is published on `FRONTEND_PORT` (default 3000).

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

Agents default to the **opencode** core in **SANDBOX** mode — that is the recommended path.
Every agent carries the supported core (`opencode`, `qoder`) and the execution mode (`HOST`,
`SANDBOX`) plus its workspace selection:

- **HOST** runs the core directly on the backend host and needs **no Docker or Podman at all**.
  Start the backend with `scripts/start-backend.ps1 -SkipSandbox` (Windows) or
  `scripts/start-backend.sh --skip-sandbox` (Linux/macOS): the provider defaults to the
  Host-capable core `qoder`.
- **SANDBOX** runs the core in an isolated OpenSandbox container; the backend then needs a
  container runtime for the OpenSandbox server. This is what `scripts/start.ps1` starts.

A core or mode that is not supported is refused explicitly — there is no fallback and no
removed provider to select.

## Agent Cores

**opencode** is the default and recommended core; **qoder** is the second supported core.
No other core exists — the removed LangChain runtime is neither selectable nor resolvable.

| Core | Description | Modes |
|------|-------------|-------|
| **opencode** (default, recommended) | OpenCode CLI, run on the host or in a sandbox container via OpenSandbox | `HOST`, `SANDBOX` |
| **qoder** | Qoder CLI driven over the ACP bridge, run on the host or in a sandbox container | `HOST`, `SANDBOX` |

> **Approvals**: Task-level runs require human approval by default: the run starts in
> approval-pending state and executes after approval in the Approvals page. To disable
> per-agent, set agent config `"taskApprovalRequired": false`.

To select a core for an agent, use the Crew page or the API. opencode is the default:
```bash
curl -X PUT http://localhost:8080/api/v1/agents/{id} \
  -H "Content-Type: application/json" \
  -d '{"adkProvider": "qoder", "executionMode": "HOST"}'
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
A raw `docker compose up -d` stack is not torn down by `stop.ps1`; stop it with
`docker compose down` (use `podman compose down` if you started it with podman).

There is no compose mode in `start.ps1` any more: the containerized full-stack topology
existed to host the removed LangChain runtime. SANDBOX-placed runs still need the backend on
the host, where it can reach the OpenSandbox endpoints (see the topology note in
`opensandbox-config.toml`); HOST-placed runs need no container runtime at all.

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

For local development; a container runtime is needed only for SANDBOX-placed runs.

### Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java | 21 | `java -version` |
| Maven | 3.9+ | `mvn --version` |
| Node.js | 20+ | `node --version` |
| pnpm | 9+ | `pnpm --version` |
| Docker / Podman (optional) | 24+ / 4.9+ | only for SANDBOX mode; HOST mode needs none |

### Quick start with scripts

```bash
# Windows one-click (see "Starting the stack" above)
.\scripts\start.ps1

# Or start individual services:
./scripts/start-backend.sh     # Starts backend (OpenSandbox only for SANDBOX mode)
./scripts/start-frontend.sh    # Vite dev server
```

The `start-backend` script defaults to the **opencode** core (the recommended path), and with
opencode it also starts the OpenSandbox server (requires a container runtime) and passes the
provider to the backend. Use `--skip-sandbox` or `-SkipSandbox` to skip OpenSandbox startup:
that selects the Host-capable core **qoder** and needs no container runtime at all. An explicit
provider always wins: `--provider=qoder` (Linux/macOS) or `-AdkProvider qoder` (Windows), or
`ADK_PROVIDER=qoder` in the environment — `ADK_PROVIDER` belongs to `scripts/start-backend.{sh,ps1}`
only.

### Backend

```bash
cd agent-control-tower
mvn clean install -DskipTests

# Default (opencode core; SANDBOX placement needs the OpenSandbox server and a container runtime):
mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2

# Host placement without any container runtime: the provider is a startup default, not a
# per-run setting (`--adk.default-provider` is the property name, ADK_DEFAULT_PROVIDER the env var):
mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2 -Dspring-boot.run.arguments=--adk.default-provider=qoder

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
topology (backend and frontend on the host). The raw container stack publishes the Dashboard
on `FRONTEND_PORT` instead, which defaults to `3000`.

### OpenSandbox Server (SANDBOX mode)

Required for SANDBOX-placed runs (the opencode core in `SANDBOX` mode; HOST mode needs none).
Start it with the compose provider of your container runtime — `podman compose` for the local-dev default, `docker compose` is equivalent:

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
| `act-execution` | Tool execution engine, LLM client, ADK integration (opencode + qoder cores) |
| `act-knowledge` | Knowledge base management |
| `act-aria` | Aria AI assistant service |
| `act-dashboard-api` | Dashboard REST API controllers |
| `act-app` | Spring Boot application entry point |
| `act-test-support` | Shared test utilities |
| `act-dashboard` | React frontend dashboard |
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
- **Auth**: `ARIA_MCP_AUTH_MODE=none` (default — endpoint is open, like the REST API; every tool call is audit-logged) or `token` (Bearer required; set `ARIA_MCP_TOKEN`, sandbox token injected automatically).
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

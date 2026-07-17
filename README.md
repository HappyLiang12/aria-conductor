# Aria Conductor

An open-source AI Agent orchestration and governance platform.

Aria Conductor provides a complete control tower for managing fleets of AI agents with built-in governance workflows, approval gates, and observability.

## Features

- **Multi-Agent Orchestration** — Create, configure, and manage multiple AI agents with different roles (Business Analyst, Developer, QA)
- **Governance Workflows** — Built-in approval gates, review cycles, and compliance checkpoints
- **Aria Assistant** — AI-powered operator assistant for managing your agent fleet
- **LLM Provider Agnostic** — Works with OpenAI, DeepSeek, or any OpenAI-compatible API
- **LangChain ADK Runtime** — Python-based agent runtime with LangChain integration
- **MCP Server** — Model Context Protocol server for tool integration
- **Real-time Dashboard** — React-based dashboard with live agent status, kanban board, and activity timeline

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 21, Spring Boot 3.3, Spring Data JPA |
| Frontend | React 19, Vite, TypeScript |
| Agent Runtime | Python 3.11, LangChain, FastAPI |
| Database | H2 (dev) / MariaDB (production) |
| MCP Server | Node.js, TypeScript |
| Containerization | Docker, Docker Compose |

## Quick Start (Docker)

### Prerequisites

- Docker and Docker Compose v2
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

Wait for all services to be healthy (~60 seconds for first startup).

### 3. Open the dashboard

Navigate to [http://localhost:3000](http://localhost:3000)

### 4. Configure LLM provider (optional)

Use the dashboard Settings page or the API:
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

### Backend

```bash
cd agent-control-tower
mvn clean install -DskipTests
mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2
```

Backend starts at `http://localhost:8080`

### Frontend

```bash
cd agent-control-tower/act-dashboard
pnpm install
pnpm dev
```

Dashboard starts at `http://localhost:5173`

### Python ADK Runtime

```bash
cd langchain-adk
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt  # Windows
# .venv/bin/pip install -r requirements.txt   # Linux/macOS
```

## Module Structure

| Module | Description |
|--------|-------------|
| `act-common` | Shared models, DTOs, repositories |
| `act-agent` | Agent lifecycle management |
| `act-execution` | Tool execution engine, LLM client, ADK integration |
| `act-knowledge` | Knowledge base management |
| `act-aria` | Aria AI assistant service |
| `act-dashboard-api` | Dashboard REST API controllers |
| `act-app` | Spring Boot application entry point |
| `act-test-support` | Shared test utilities |
| `act-dashboard` | React frontend dashboard |
| `langchain-adk` | Python LangChain agent runtime |
| `packages/mcp-server` | MCP protocol server |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_API_KEY` | — | Your LLM provider API key |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | LLM API base URL |
| `LLM_MODEL` | `gpt-4o` | Default LLM model |
| `DB_HOST` | `mariadb` | Database host (Docker) |
| `DB_PORT` | `3306` | Database port |
| `DB_NAME` | `aria_conductor` | Database name |

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
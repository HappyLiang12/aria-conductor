# Aria Conductor — Operator Skill

> How to operate an Aria Conductor instance programmatically via REST API and MCP tools.

## Prerequisites

- Aria Conductor stack running (`docker compose up -d`)
- Backend at `http://localhost:8080`
- Frontend at `http://localhost:3000`
- LLM provider auto-seeded from `.env` (or manually configured)

## Core Operations

### 1. Check System Health

```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP"}
```

### 2. List Agents

```bash
curl http://localhost:8080/api/v1/agents
```

Returns array with: `id`, `name`, `agentType` (NATIVE|ADK), `role`, `model`, `healthStatus`, `tools[]`

### 3. Create an Agent

```bash
curl -X POST http://localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Researcher",
    "agentType": "NATIVE",
    "role": "Researches topics and provides factual summaries",
    "model": "deepseek-chat"
  }'
```

Required fields: `name`, `agentType`. Optional: `role`, `model`, `provider`, `config`.

### 4. Start a Run

```bash
curl -X POST http://localhost:8080/api/v1/runs \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "<agent-uuid>",
    "promptSeed": "Your task instruction here",
    "maxIterations": 10
  }'
```

**Important:** Field is `promptSeed` (not `prompt`).

### 5. Poll Run Status

```bash
curl http://localhost:8080/api/v1/runs/{runId}
```

Statuses: `PENDING → RUNNING → COMPLETED | FAILED | CANCELLED`
With pause: `RUNNING → PAUSED → RUNNING`

### 6. Control a Run

```bash
# Pause
curl -X POST http://localhost:8080/api/v1/runs/{runId}/pause

# Resume (optionally with new instruction)
curl -X POST http://localhost:8080/api/v1/runs/{runId}/resume
curl -X POST http://localhost:8080/api/v1/runs/{runId}/resume \
  -H "Content-Type: application/json" \
  -d '{"instruction": "Focus on the 2020s decade only"}'

# Cancel
curl -X POST http://localhost:8080/api/v1/runs/{runId}/cancel
```

### 7. Get Run Trajectory (Observability)

```bash
curl http://localhost:8080/api/v1/runs/{runId}/trajectory
```

Returns turn-by-turn trace: role (user/assistant/tool), turnNumber, tokens, content.

### 8. Inject Human Message into a Thread

```bash
curl -X POST http://localhost:8080/api/v1/chat/threads/{threadId}/inject \
  -H "Content-Type: application/json" \
  -d '{"content": "Correction: use UTC+8 timezone"}'
```

### 9. Knowledge Governance

```bash
# Submit new knowledge (starts as PENDING)
curl -X POST http://localhost:8080/api/v1/knowledge \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Deployment Checklist",
    "type": "GUIDELINE",
    "content": "1. Run tests 2. Check secrets 3. Deploy"
  }'

# List knowledge (filter by status)
curl "http://localhost:8080/api/v1/knowledge?status=PENDING"

# Approve/Reject
curl -X POST http://localhost:8080/api/v1/knowledge/{id}/review \
  -H "Content-Type: application/json" \
  -d '{"decision": "APPROVE", "reason": "Looks good"}'
```

Types: `SKILL`, `SCRIPT`, `PROMPT`, `TOOL`, `TEMPLATE`, `GUIDELINE`

### 10. Approvals

```bash
# List pending
curl http://localhost:8080/api/v1/approvals?status=PENDING

# Decide
curl -X POST http://localhost:8080/api/v1/approvals/{id}/decide \
  -H "Content-Type: application/json" \
  -d '{"decision": "APPROVE", "reason": "Safe to proceed"}'
```

### 11. LLM Providers

```bash
# List (apiKey is masked in response)
curl http://localhost:8080/api/v1/llm-providers

# Create & activate
curl -X POST http://localhost:8080/api/v1/llm-providers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "DeepSeek",
    "type": "OPENAI",
    "apiKey": "sk-...",
    "baseUrl": "https://api.deepseek.com/v1",
    "defaultModel": "deepseek-chat"
  }'
```

Model resolution: `agent.model` (if set) → active provider's `defaultModel`.

### 12. Aria Assistant (Natural Language Orchestration)

Aria accepts natural language and maps to tool calls. Examples:
- "Create an agent named QA with role: tests code quality"
- "Start a run on Researcher: analyze competitor features"
- "Pause run {id}"
- "Store knowledge: Python best practices checklist"
- "Generate a report on this week's activity"

Access via:
- Dashboard FAB panel (interactive)
- Chat page threads
- MCP server tools (for programmatic access)

## MCP Server Tools

The MCP server (`packages/mcp-server/`) exposes these tool categories:
- `agents` — CRUD operations on agents
- `runs` / `execution` — Start, monitor, control runs
- `approvals` — List and decide approvals
- `knowledge` — Store, query, review knowledge
- `kanban` — Task board operations
- `reports` — Generate and amend reports
- `trajectory` — Run observability
- `aria` — Natural language assistant
- `dashboard` — System summary
- `llm-providers` — Provider management

## Known Limitations

| Limitation | Workaround |
|-----------|-----------|
| No authentication | Bind to 127.0.0.1 only; use reverse proxy for production |
| `shell_exec` disabled | Set `tools.shell.enabled=true` in config |
| New agent ADK cold start | First run on a fresh agent waits up to 60s for the ADK subprocess to become ready |
| Cancel is cooperative | Takes effect at the next iteration boundary; terminal state is protected from overwrite |
| Zombie run cleanup | Runs stuck RUNNING with no active context are auto-reaped after `run.reaper.timeout-minutes` (default 120) |

## Monitoring Checklist

- [ ] `GET /actuator/health` returns UP
- [ ] No zombie runs (RUNNING > 1h with 0 iterations)
- [ ] Token consumption within expected range
- [ ] No FAILED runs with unclear error messages
- [ ] Knowledge review queue processed (no stale PENDING items)

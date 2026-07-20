# Aria Conductor — User Guide

A practical guide to operating your AI agent fleet through the Aria Conductor dashboard.

---

## Dashboard Layout

After opening `http://localhost:3000`, you'll see:

| Area | What it does |
|------|-------------|
| **TopBar** | Live KPIs: Active Agents, Running Runs, Approvals Pending, Tokens Today |
| **RailNav** (left sidebar) | 10 navigation buttons + Settings gear |
| **Main panel** | Content area for the selected view |
| **Aria FAB** (bottom-right) | Opens the Aria AI assistant panel |

### Navigation

The sidebar provides: **Overview · Crew · Runs · Knowledge · Reports · Chat · Workflows · Approvals · Ops · Jobs**

- **Runs**: Start, monitor, pause, resume, cancel agent executions
- **Approvals**: Review and decide on pending actions

---

## Talking to Aria (Your AI Operator)

Aria is your AI assistant for managing the entire system. Two ways to interact:

### 1. Aria Panel (FAB button, bottom-right)
Click the floating button to open a slide-over panel. Type natural language commands:
- "List all agents and their status"
- "Create a new agent named Coder with role: writes Python code"
- "Start a run on Researcher: summarize the latest AI news"
- "Check pending approvals"

### 2. Chat Page (sidebar → Chat)
Full conversation view with:
- Thread list (left) — each run creates a conversation thread
- Message timeline with **handoff markers** showing agent-to-agent delegation
- **AUDITED** badge confirming the conversation is logged
- **Inject message** box at the bottom — type a correction and click Send to inject a human message into any thread

**Tip:** Injected messages appear as "Operator" in the thread and persist in the audit trail.

---

## Managing Agents (Crew Page)

### Create an Agent
1. Navigate to **Crew** in the sidebar
2. Click **+ New Agent**
3. Fill in: Name, Role (what the agent does), Model (optional — inherits default)
4. Submit

### Agent Health
Each agent shows a health badge:
- **HEALTHY** — ready to accept runs
- **UNHEALTHY** — check configuration or ADK connectivity

### Assign Tools
Agents can be assigned tools (web_search, web_fetch, shell_exec, etc.). Tools marked `INTERNAL` in the Knowledge space are auto-seeded at startup.

---

## Running Agents (Runs Page)

Navigate to `http://localhost:3000/runs`.

### Start a Run
1. Click **+ Start Run**
2. Select a healthy agent from the dropdown
3. Set **Max Iterations** (safety limit on execution loops)
4. Write a **Prompt Seed** (the task instruction)
5. Click **Start Run**

### Monitor Progress
The runs table shows: ID, Agent, Status, Iterations (current/max), Tokens used, Duration.

Click **Details** to expand:
- **Status & Info** — current state, tokens, error message (if failed)
- **Session Trajectory** — turn-by-turn conversation (user → assistant → tool calls)
- **Tool Calls** — each tool invocation with name, status (COMPLETED/FAILED), and latency

### Control a Running Run

| Action | When available | Effect |
|--------|---------------|--------|
| **Pause** | Status = RUNNING | Freezes execution; iteration count stops |
| **Resume** | Status = PAUSED | Continues from where it paused |
| **Cancel** | Status = RUNNING or PAUSED | Attempts to stop the run |

### Run Statuses
`PENDING → RUNNING → COMPLETED / FAILED / CANCELLED`

With pause: `RUNNING → PAUSED → RUNNING → ...`

---

## Knowledge Governance

Navigate to **Knowledge** in the sidebar.

### Lifecycle (5 stages)
```
Agent Draft → Peer Review → Human Approval → Validated → Unified Library
```

### Key Concepts
- **Per-Agent Spaces**: Each agent has its own knowledge area
- **Submitted for Review**: Items awaiting human approval (PENDING status)
- **Unified Knowledge Space**: All APPROVED items, searchable and reusable
- **Access Control Matrix**: Defines who can EDIT / USE / VIEW / NONE per knowledge area

### Approve/Reject
1. Go to "Submitted for Review" section
2. Select items (checkbox) or use Batch Approve/Reject
3. Only APPROVED items are visible to agents via `query_knowledge`

**Important:** Newly stored knowledge is always PENDING. Agents cannot use it until a human approves it. This prevents "memory poisoning" — where one agent's hallucination becomes shared "fact."

---

## Approvals

Navigate to `http://localhost:3000/approvals`.

When an agent performs a gated action (e.g., executing a sensitive tool), it creates a **pending approval**:
- **Approve** — allows the action to proceed
- **Deny** — blocks it (provide a reason)

Approvals have a countdown timer. If not decided in time, they may auto-expire.

---

## Workflows

Navigate to **Workflows** in the sidebar.

Workflows are multi-step orchestration chains (e.g., Research → Verify → Report). Create them via:
- Aria: "start_workflow with steps: research, verify, report"
- REST API: `POST /api/v1/workflows`
- YAML execution: Click "Execute YAML" button

---

## Kanban Board (Overview Page)

The Overview page includes a governed kanban board:
- Columns: BACKLOG → TODO → IN PROGRESS → REVIEW → QA GATE → DONE → ARCHIVED
- Ask Aria: "Create a kanban item: Fix login bug, priority HIGH"
- Transitions follow governance rules (not all jumps are allowed)

---

## Reports

Navigate to **Reports** in the sidebar.
- Ask Aria: "Generate a report on today's agent activity"
- Reports can be amended: "Amend report X to include token costs"

---

## Tips & Gotchas

| Tip | Detail |
|-----|--------|
| **Model inheritance** | If an agent has no `model` set, it uses the active LLM provider's `defaultModel` |
| **Token budget** | Runs have a 100K token safety limit. Hitting it causes FAILED status |
| **Shell disabled** | `shell_exec` is disabled by default for security. Enable via `tools.shell.enabled=true` |
| **WebSocket updates** | TopBar KPIs update in real-time via WebSocket. If WS disconnects, data may appear stale — refresh the page |
| **Theme toggle** | TopBar right — switch between dark and light mode |
| **Notifications bell** | Shows unread system events (run completed, approval needed, etc.) |

---

## REST API Quick Reference

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/agents` | GET/POST | List / create agents |
| `/api/v1/runs` | GET/POST | List / start runs |
| `/api/v1/runs/{id}/pause` | POST | Pause a running run |
| `/api/v1/runs/{id}/resume` | POST | Resume a paused run |
| `/api/v1/runs/{id}/cancel` | POST | Cancel a run |
| `/api/v1/runs/{id}/trajectory` | GET | Get turn-by-turn trace |
| `/api/v1/knowledge` | GET/POST | List / submit knowledge |
| `/api/v1/knowledge/{id}/review` | POST | Approve/reject knowledge |
| `/api/v1/approvals` | GET | List pending approvals |
| `/api/v1/approvals/{id}/decide` | POST | Approve/deny an action |
| `/api/v1/llm-providers` | GET/POST | List / configure LLM providers |
| `/api/v1/workflows` | GET/POST | List / create workflows |

Full API docs: `http://localhost:8080/swagger-ui.html`

---

## Example Session (5 minutes)

1. Open `http://localhost:3000` → see Overview with agent fleet status
2. Click **Aria FAB** → type "List all agents" → see your fleet
3. Go to `/runs` → **+ Start Run** → select Aria → prompt: "Give me a dashboard summary" → Start
4. Watch the run progress in the table (status, iterations, tokens)
5. Click **Details** when done → see the full trajectory
6. Go to **Chat** → find the thread → see the conversation with handoff markers
7. Inject a message: "Next time, include token costs in the summary" → Send
8. Go to **Knowledge** → browse the Unified Library → see approved tools/skills

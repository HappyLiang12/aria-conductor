# Aria Conductor — User Guide

A practical guide to operating your AI agent fleet through the Aria Conductor dashboard.

---

## Dashboard Layout

After opening `http://localhost:3000`, you'll see:

| Area | What it does |
|------|-------------|
| **TopBar** | Live KPIs: Active Agents, Running Runs, Approvals Pending, Recorded tokens |
| **RailNav** (left sidebar) | 10 navigation buttons + Settings gear |
| **Main panel** | Content area for the selected view |
| **Aria FAB** (bottom-right) | Opens the Aria AI assistant panel |

### Navigation

The sidebar provides: **Overview · Crew · Providers · Runs · Knowledge · Reports · Chat · Workflows · Ops · Jobs**, plus Configure.

- **Runs**: Start, monitor, pause, resume, cancel agent executions
- **Decisions**: approvals that block work appear on **Overview → Needs your decision**; there is no separate Approvals page

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
This page shows **agent↔agent conversations** from runs, not the human↔Aria chat:
- Thread list (left) — each run creates a conversation thread
- Message timeline with **handoff markers** showing agent-to-agent delegation
- **AUDITED** badge confirming the conversation is logged
- **Inject message** box at the bottom — for a finished run this appends an audit note; it does not reopen the run

Use the **Aria FAB** for the interactive human↔Aria conversation.

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
- **UNHEALTHY** — check configuration or core connectivity

### Agent Capabilities
An agent's tools come from its core (`opencode` or `qoder`) and, for platform operations,
from the run-scoped Aria Conductor MCP endpoint. There is no per-agent tool assignment.
In **HOST** mode an opencode agent is read-only (`edit`/`write`/`patch`/`bash` are refused, with no
approval prompt); write tools are available to a **SANDBOX** worker inside its container.

---

## Running Agents (Runs Page)

Navigate to `http://localhost:3000/runs`.

### Start a Run
1. Click **+ Start Run**
2. Select a healthy agent from the dropdown
3. Write a **Prompt Seed** (the task instruction)
4. Click **Start Run**

The run is bounded by the wall-clock deadline (45 minutes by default). There is no
iteration cap or token cap.

### Monitor Progress
The runs table shows: ID, Agent, Status, Observed iterations, Tokens reported by the run, Duration.

Click **Details** to expand:
- **Status & Info** — current state, tokens, error message (if failed)
- **Session Trajectory** — turn-by-turn conversation (user → assistant → tool calls)
- **Tool Calls** — each tool invocation with name, status (COMPLETED/FAILED), and latency

### Control a Running Run

| Action | When available | Effect |
|--------|---------------|--------|
| **Pause** | Status = RUNNING | Freezes the run-owned core; the run stays PAUSED until resumed |
| **Resume** | Status = PAUSED | Continues from where it paused |
| **Cancel** | Status = RUNNING or PAUSED | Stops the run-owned core; the verified stop is recorded before CANCELLED |

### Run Statuses
`PENDING → RUNNING → COMPLETED / FAILED / CANCELLED`

With pause: `RUNNING → PAUSED → RUNNING → ...`

---

## Knowledge Governance

Navigate to **Knowledge** in the sidebar.

### Status

`DRAFT → PENDING → APPROVED / REJECTED`; an APPROVED item can be `RETIRED`.

### Key Concepts
- **Per-Agent Spaces**: Each agent has its own knowledge area
- **Submitted for Review**: Items awaiting human approval (PENDING status)
- **Unified Knowledge Space**: All APPROVED items, searchable through `query_knowledge`

### What the runtime actually uses
- Every prompt receives the **newest five APPROVED items** by update time, and only their
  description (up to 500 characters). There is no relevance ranking in the automatic injection.
- `query_knowledge` is a case-insensitive substring search over name, description and current
  version content, and an agent must call it explicitly.

### Approve/Reject
1. Go to "Submitted for Review" section
2. Select items (checkbox) or use Batch Approve/Reject
3. Only APPROVED items are visible to agents via `query_knowledge`

**Important:** Newly stored knowledge is always PENDING. Agents cannot use it until a human approves it. This prevents "memory poisoning" — where one agent's hallucination becomes shared "fact."

## Memory

Memory recall is deterministic and is injected into Aria and worker prompts as context.
It is **operator-fed**: facts enter through operator/MCP writes, and a worker's
`remember_fact` becomes a PENDING proposal an operator must approve. Memory MCP reads also
require an operator approval, so unattended workers cannot query memory as a tool. There is
no memory dashboard page yet.

---

## Decisions and approvals

There is no standalone approvals page. Blocking asks appear on **Overview → Needs your decision**:

- **Native tool permission** — a core asked to run a tool. The row shows the risk tier
  (`Dangerous` for bash, `Writes` for edit/write) and the tool name; open it to review the
  arguments and diff before deciding.
- **Clarification** — the run is parked in `WAITING_INPUT` and **holds an admission slot** until
  you answer or finalize. It has no timeout.
- **Spec review** — a workflow chain waits at a governance gate. It expires after 30 minutes.

Approve, deny, or answer from the row (unlinked asks are decidable inline). A native ask also
carries the core's own expiry. Post-run **review requests** block nothing: they appear as a
single "completed runs awaiting review" count that links to the board's REVIEW column, not as
decision rows.

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
| **Run deadline** | Runs are bounded by the configured wall-clock deadline; exceeding it settles the run as `CANCELLED` with `RUN_DEADLINE_REACHED`. There is no per-run token or cost cap |
| **Recorded tokens** | The token KPI sums usage runs actually reported. A timed-out or failed run may contribute no usage, so treat it as a floor, not a bill |
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

1. Open the dashboard URL printed by your start script (local dev: `http://localhost:5173`)
2. Click **Aria FAB** → type "List all agents" → see your fleet
3. Go to `/runs` → **+ Start Run** → select Aria → prompt: "Give me a dashboard summary" → Start
4. Watch the run progress in the table (status, observed iterations, recorded tokens)
5. Click **Details** when done → see the full trajectory
6. Go to **Chat** → find the agent thread → see the conversation with handoff markers
7. Inject an audit note: "Next time, include token costs in the summary" → Send
8. Go to **Knowledge** → browse the Unified Library (newest five approved items feed prompts)

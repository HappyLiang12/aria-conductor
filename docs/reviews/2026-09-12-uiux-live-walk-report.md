# UI/UX Live Walk Report — Aria Conductor Dashboard

- **Date:** 2026-09-12
- **Repo / commit:** `D:/project/aria-conductor`, branch `main`, `1ef4e64` (no commits, no code/config/DB changes made)
- **Scope:** read-only UX/usability evidence pass on the running local stack, with emphasis on meaningless / confusing / removable surfaces.
- **Screenshots:** `D:/project/aria-conductor/docs/reviews/shots/` (24 PNGs)

## 0. Method & tooling note (read this before trusting anything)

The task specified the `mcp__browser-use__*` MCP tools. Those tools were **not exposed in this session's toolset**. Instead of skipping the live pass, I drove the same
Chromium engine the repo's own E2E suite uses, via the dashboard's local Playwright install
(`agent-control-tower/act-dashboard/node_modules/@playwright/test`) and inline `node --input-type=module -e` scripts.
**No script file was written** — the only writes are this report and the screenshots (both explicitly requested).

Interaction policy followed strictly: no approve/deny/reject/delete/submit/create/execute/start-run/scan-leftovers click, and no `✓`/`✗` review control was clicked.
Everything described as a mutating control was **opened, read, or described — never fired.**

Where a finding could not be reproduced live (because the dev DB has no runs/reports/kanban/approval rows),
it is explicitly labelled **inferred (code)** with a `file:line` citation.

---

## 1. Environment & startup outcome

| Step | Command | Result |
|------|---------|--------|
| Backend build | `mvn -q install -DskipTests` (Maven 3.9.6 from `~/.m2/wrapper/...`) | **SUCCESS** (exit 0, `act-app-0.1.0-SNAPSHOT.jar` produced) |
| Backend run | `mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2` | **UP** — `GET /api/v1/agents` → 200, `GET /actuator/health` → `{"status":"UP"}` |
| Frontend run | `pnpm dev` (Vite) | **UP** — `http://localhost:5173` → 200 |
| Existing H2 DB | `agent-control-tower/data/act_db.mv.db` | **preserved untouched** |

### Live data state at review time (`GET /api/v1/...`, all HTTP 200)

| Endpoint | Payload | Content |
|----------|---------|---------|
| `/dashboard/summary` | `{"activeAgents":4,"runningRuns":0,"pendingApprovals":0,"totalTokensBurned":0}` | — |
| `/agents` | 9713 B | **4** agents: `SDD BA Agent` HEALTHY, `SDD DEV Agent` HEALTHY, `SDD QA Agent` HEALTHY, `Aria` **DEGRADED** — all `adkProvider: opencode`, models blank |
| `/knowledge` | 22400 B | **62** items, `status` = APPROVED × 62; `type` = TOOL × 61, WORKFLOW × 1; `currentVersion` is `null` for **61 of 62** (the WORKFLOW one is the string `"v1.0.0"`) |
| `/llm-providers` | 276 B | 1 active provider: `DeepSeek` (OPENAI, `deepseek-v4-flash`) |
| `/skills` | 1209 B | 1 skill: `workflow` |
| `/adk/providers` | 187 B | `opencode` (Task, Default), `langchain` (Turn) |
| `/adk/providers/langchain/health` | — | `{"providerId":"langchain","healthy":false}` |
| `/runs`, `/approvals?status=PENDING`, `/reports`, `/kanban/items`, `/workflows`, `/aria/jobs`, `/dashboard/activity` | 2 B each | **all `[]`** |

**Consequence:** every list-driven screen rendered its *empty state*; populated-state behavior
(run rows, kanban cards, approval cards, report tabs) could not be exercised. This is a real limitation of the review and is called out per finding.

### Environment limitations (not UI bugs — recorded as instructed)

1. **No OpenSandbox container is running**, so the default `opencode` provider reports UNHEALTHY and any OpenCode-provider agent RUN would fail. `/providers` shows `opencode → UNHEALTHY` (shot `r_providers.png`).
2. **No LangChain ADK is running** → `langchain` provider also UNHEALTHY.
3. Aria's assistant backend answered `/aria/conversations/latest` with **204** (no stored conversation).

---

## 2. Global chrome inventory

### 2.1 Left rail — `src/components/RailNav.tsx`

Verified live from the DOM (`.rail .rail-btn`), 11 buttons (10 routes + Configure):

| # | Label | Icon source | Route | Meaningful? |
|---|-------|-------------|-------|-------------|
| 1 | Overview | `▦` (text glyph) | `/` | Yes |
| 2 | Crew | `👥` | `/crew` | Yes |
| 3 | Providers | `🔄` | `/providers` | **Icon mismatch** — 🔄 reads as "refresh/sync", the page is an ADK provider inventory |
| 4 | Runs | `▶️` | `/runs` | Yes |
| 5 | Knowledge | `📚` | `/knowledge` | Yes |
| 6 | Reports | `📊` | `/reports` | Yes |
| 7 | Chat | `💬` | `/chat` | **Misleading** — page is run transcripts, not a chat (see F11) |
| 8 | Workflows | `🔗` | `/workflows` | Yes |
| 9 | Ops | `🛡️` | `/ops` | Shield = governance, page is an ops console; loose |
| 10 | Jobs | `📅` | `/scheduled-jobs` | Label "Jobs" ≠ page title "Scheduled Jobs" |
| 11 | Configure | `🔧` | (opens modal via `act:open-configure` custom event) | Yes |

Notes: icon set mixes a monochrome text glyph (`▦`) with colour emoji → inconsistent optical weight (shot `00-overview-default.png`).
`/approvals` is **not** in the rail (matches `RailNav.tsx:9-20`).

### 2.2 Top bar — `src/components/TopBar.tsx`

Observed innerText (`00-overview-default.png`):

```
Aria Conductor / ARIA CONDUCTOR · LOCAL
[4 Agents Online] [System Healthy]
ACTIVE AGENTS 4 | RUNNING RUNS 0 | APPROVALS PENDING 0 | TOKENS TODAY 0
11:21:39   🔔   ☀ Light
```

- `4 Agents Online` + `System Healthy` + `4 Active Agents / Healthy & responsive` are all driven by
  `summary.activeAgents` (`TopBar.tsx:59-63`, `ExecutiveSummary.tsx:92-95`) — which **counts the DEGRADED `Aria` agent**. The Agent Team panel on the same screen labels Aria "Degraded" (shot `00-overview-default.png`). See F3.
- `TOKENS TODAY` renders `summary.totalTokensBurned` (`TopBar.tsx:62,100`) — the same field Executive Summary labels **"Cumulative spend"**. See F13.
- The four KPI tiles are `<div>`s with **no click handler**: verified `cursor: auto` and clicking `APPROVALS PENDING` left the URL at `http://localhost:5173/`. So "0 Approvals Pending" provides no path to the approval surface.
- Live clock ticks every second (observed `11:21:39`), redundant with the OS clock.
- Theme toggle works and persists (toggled to Light and back; shot `10-theme-light.png` — legible in both themes).

### 2.3 Notification bell — `src/components/NotificationBell.tsx`

Observed (`11-notification-bell.png`): header "Notifications", "Mark all read" (**disabled** at 0 unread), body `No notifications yet`, `aria-label="Notifications"`.
Behaves correctly. Note `notificationRoutes.ts:25` maps `approval.requested → "/"` by design, so the bell also cannot reach an approvals page (reinforces F1).

### 2.4 Aria assistant FAB + panel — `src/components/AriaPanel.tsx`

Observed (`20-aria-fab-panel.png`): floating ✦ FAB → right-side dialog titled `Aria` / `PERSONAL ASSISTANT · ALWAYS ON`, conversation id chip + 📋 copy + **Clear**, ×, empty card ("Good to see you. How can I help?"), 4 suggested prompts, compose textarea, `Send`, footer hint
`Aria streams live · acts on the live system · review approvals before destructive moves.`
This is the only genuine "chat" surface in the product — and the rail has no entry for it (the rail's "Chat" is something else, F11).

### 2.5 Configure modal — `src/components/ConfigureModal.tsx`

Opened from the rail (shot `12-configure-*.png`). Title: **`Configure GOVERNANCE & CAPABILITIES`**. Four tabs:

| Tab | Observed content | Verdict |
|-----|------------------|---------|
| 🛡 Approval Gates | Banner: **"⚠ Preview — these approval gates are illustrative and are not yet enforced by the backend."** Pipeline Draft→…→Production with animated arrows; 6 gate rows with Approver/SLA switches; footer "4 of 6 gates require human approval / Reset to defaults / Done" | **STUB** (self-declared) — F7 |
| 🧰 Skills & Tools | Copy "Define which capabilities the agent fleet may invoke…"; one row `workflow` / General / permission select `USE`; footer "1 tools configured · 0 with full access" | **STUB** — permission is local state only — F8 |
| 🤖 LLM Providers | Renders `SettingsPage` verbatim: an inner header **`SETTINGS`** + `+ Add Provider`, "Active Provider DeepSeek", providers table with Test/Edit/Delete | **IA-GAP** — a page inside a dialog — F9 |
| ⚙️ System Config | Real, DB-backed settings (4 categories, 12 keys, per-key Save 💾 / Reset ↩, range hints) | **OK** — the only tab backed by a real API, and it works |

---

## 3. Per-route table

`reachable-from` = how a user can actually get there (verified by clicking/DOM, not by reading `App.tsx`).
All shots are in `docs/reviews/shots/`.

| Route | Purpose | Reachable from | Above-the-fold | Empty state | Primary CTA | Verdict |
|-------|---------|----------------|----------------|-------------|-------------|---------|
| `/` | Mission control: exec KPIs, kanban, briefing, agent team, review queue, activity | Rail "Overview" (default) | Executive Summary 6 stat cells; **Kanban Board** with 5 empty columns (Backlog/Todo/In Progress/Review/Done); right column Agent Team / Review Queue / Activity Timeline | Kanban counts `0`; `No pending approvals — queue is clear.`; `No events yet — system is quiet.` | `+ New Item` | **OK** overall, but duplicates Ops (F12) |
| `/crew` | Agent roster + cost + hiring catalog | Rail "Crew" | Header + `🧹 Select Leftovers (0)` + `+ Add Agent`; "ROSTER COST · TODAY" banner (Active 4 / Tokens 0 / $0.00 / Hiring slots 8) | n/a (4 agents) | `+ Add Agent`, `+ Deploy` | **UX-GAP** — F14, F15 |
| `/providers` | ADK provider inventory + per-agent backend map | Rail "Providers" | Table: `opencode / OpenCode / Task / Default / UNHEALTHY`, `langchain / LangChain ADK / Turn / — / UNHEALTHY`; then "Per-Agent Backends" mapping all 4 agents → `opencode` | `No providers registered.` (not hit) | **none** | **UX-GAP** — F16 |
| `/knowledge` | Knowledge governance (review + library + access) | Rail "Knowledge" | "PROMOTION PATH / GOVERNANCE FLOW" (5 static stages); "PER-AGENT SPACES / 4 AGENTS" (18/15/14/15, each "CLICK TO OPEN") | `Inbox zero. No items waiting for review.` | `+ Submit New`; `✓ Batch Approve` / `✗ Batch Reject`; `⤴ Promote` | **STUB + BUG** — F4, F5, F6 |
| `/reports` | Generative report workspace (sandboxed HTML) | Rail "Reports" | Header `📊 Generative UI · Agent Report Workspace` + `+ Generate Report`; panel "REPORTS · NO DOSSIERS YET" with a **second** `+ Generate Report` | `The archive is silent. Commission your first dossier — …` (good copy) | `+ Generate Report` (**twice**) | **OK** with duplication — F17 |
| `/chat` | **Labelled** "Agent ↔ Agent Conversations" | Rail "Chat" | Thread list (`Search threads…`) empty + right panel "Select a thread —". Context block (WorkflowStepper / DelegationTree / inline approvals) only mounts once a thread exists | `No conversations yet. Start a run to begin a thread.` | `Send ▶` (disabled until a thread is picked) | **IA-GAP** — F11 |
| `/workflows` | Workflow chains + templates | Rail "Workflows" | `Workflows` + green **`0 completed`** pill + `Execute YAML`; tabs `Chains` / `Templates` | `No workflow chains yet. Create one via Aria: start_workflow or the REST API.` | `Execute YAML` only | **UX-GAP** — F18 |
| `/ops` | Ops console: housekeeping, approvals, runs, activity, briefing | Rail "Ops" | `HOUSEKEEPING` + `⚡ Scan leftovers`; `PENDING APPROVALS 0 items` / `Inbox zero. All requests resolved.`; `RECENT RUNS 0 latest` / `No runs yet.`; right column Activity Timeline (`Quiet on the wire.`) + Today's Briefing | several, all handled | `⚡ Scan leftovers`, `↻ Refresh` | **IA-GAP** — F12, F2 |
| `/scheduled-jobs` | Cron / one-shot notification jobs | Rail "Jobs" | `📅 SCHEDULED JOBS` + `+ New Job`; category tabs All/🔔 REMINDER/📊 MONITOR/📋 BRIEF; status select | `No scheduled jobs found.` | `+ New Job` | **OK** (minor: rail label "Jobs") |
| `/runs` | Run list, start run, trajectory/tool-call drill-down | Rail "Runs" | `Runs` + `+ Start Run`; status + agent filters (agents listed) | `No runs yet. Start one above.` | `+ Start Run` | **OK** + latent BUG F20 |
| `/approvals` | *Retired page* | **nothing links to it** | — | — | — | **ORPHANED / dead redirect** — F1 |

---

## 4. Findings

Each finding cites concrete evidence. Classification: **BUG** (broken), **STUB** (placeholder/non-functional), **UX-GAP**, **IA-GAP** (information architecture), **OK**.

---

### F1 — `/approvals` is an orphaned route that nothing reaches · IA-GAP

- `RailNav.tsx:9-20` lists 10 routes; live DOM confirms 11 rail buttons with **no Approvals** (`00-overview-default.png`).
- A repo-wide grep for `/approvals` in `act-dashboard/src` returns only `App.tsx:43` (the route declaration), `ApprovalsPage.tsx` (its own file), and tests — **no link, no navigate, no redirect target**.
- `ApprovalsPage.tsx:8-10` is a pure `<Navigate to="/" replace />`. Observed: navigating to `http://localhost:5173/approvals` lands on `/` (final URL `/`; shot `r_approvals.png` is byte-for-byte the same screen as `00-overview-default.png`).
- The notification bell cannot reach it either (`notificationRoutes.ts:24-25`: `approval.requested → "/"`).
- **The commit message for `1ef4e64` claims "Review as single human-in-the-loop entry"** — the redirect is consistent with that intent, but the route, page component and its test remain as dead surface.

### F2 — Approve/Deny exists in ~6 places with inconsistent affordances · IA-GAP

Code-verified call sites of `approveApproval` / `rejectApproval`:

| # | Surface | File:line | Copy |
|---|---------|-----------|------|
| 1 | Overview → Kanban `REVIEW` card | `KanbanBoard.tsx:403-431` | `✓ Approve` / `✎ Changes` / `✕ Deny` / `⤢` |
| 2 | Overview → Review Queue panel | `ReviewQueue.tsx:100-119` | `Approve` / `Deny` |
| 3 | Review workspace / task drawer → `DecisionPanel` | `ReviewPanels.tsx:69-83` | `Approve` / `Deny` / `✓ Approve all` / `✎ Request changes` |
| 4 | Review workspace → `ShortApprovalView` | `ReviewPanels.tsx:141-143` | `Approve` / `Request changes` / `Deny` |
| 5 | Ops → Pending Approvals | `OpsPage.tsx:378-398` | `✓ Approve` / `✕ Deny` |
| 6 | Chat → inline `ReviewQueue` for the active run | `ChatPage.tsx:393` | `Approve` / `Deny` |

Plus knowledge review (`KnowledgePage.tsx:494-570`, `✓`/`✗` + batch) and workflow-template approval (`TemplatesPanel.tsx:133`).
Two different metaphors coexist ("Review the kanban card" vs "Approve the approval"). A newcomer has no way to know these are the same decision.

### F3 — "System Healthy" contradicts the Providers page; DEGRADED counts as online/healthy · BUG

- `TopBar.tsx:63`: `const isHealthy = activeAgents > 0;` — the badge is "System Healthy" whenever *any* agent exists.
- Live: TopBar shows `4 Agents Online` + `System Healthy`; `/providers` shows **both** providers `UNHEALTHY` (shot `r_providers.png`); Agent Team shows `Aria … Degraded` (shot `00-overview-default.png`).
- API evidence: `/api/v1/dashboard/summary` → `{"activeAgents":4}` while `/api/v1/agents` contains one `healthStatus: "DEGRADED"`.
- Executive Summary hard-codes the detail text `Healthy & responsive` next to that count (`ExecutiveSummary.tsx:94`), and the rail badges likewise. The "4 Agents Online" label including a DEGRADED agent is factually wrong on screen.

### F4 — Knowledge version label renders broken: `v` and `vv1.0.0` · BUG

- Live DOM text from `/knowledge`: `"TOOLSeed tool: web_searchv · Aria · Sep 06, 11:55 PMINTERNAL"` — a bare `v` with no number.
- Filtering to the workflow item: `"WORKFLOWdevelopment-workflowvv1.0.0 · SDD QA Agent · …"` — a **doubled** `v`.
- Root cause: the render is `v{it.currentVersion}` (`KnowledgePage.tsx:665` and `:693`). Backend returns `currentVersion: null` for 61/62 items (React renders `null` as nothing → dangling `v`) and the string `"v1.0.0"` for the workflow item (→ `vv1.0.0`). The TS type wrongly declares `currentVersion: number` (`types/index.ts:104`).
- Evidence: shots `r_knowledge.png`, `32-knowledge-workflow-detail.png`; `GET /api/v1/knowledge` item[0].

### F5 — Knowledge "Per-Agent Spaces" attribution is fabricated · STUB

- `KnowledgeItem` has **no** `agentId` field (`types/index.ts:99-108`), so `ownerOf()` always takes its fallback branch: "Stable fallback — distribute items across agents by hash of id" (`KnowledgePage.tsx:152-162`).
- Observed on a real dataset of 62 items: `SDD BA Agent 18 / SDD DEV Agent 15 / SDD QA Agent 14 / Aria 15` (sums to 62), and the "Unified Knowledge Space" list prints owners like `Seed tool: web_search · **Aria**`, `web_fetch · SDD BA Agent`, `read_file · SDD DEV Agent` — i.e. an arbitrary rotation, since all 62 items share the same provenance (`Seed tool: …`).
- The same panel is the page's largest element and every card says `CLICK TO OPEN`; the only effect is `setActiveAgentId(...)` filtering the *pending review* list, which is empty. Observed before/after click: the review body stayed `Inbox zero. No items waiting for review.` (shot `30-knowledge-space-filter.png`).
- `In Review` is 0 on all four cards while the review panel shows "0 pending" — a 62-item "governance" screen where every metric is derived from fabricated or absent data.

### F6 — Knowledge "Promotion Path" and "Access Control" are hardcoded decoration · STUB

- `FLOW_STEPS` (`KnowledgePage.tsx:26-32`) — 5 static stages, no data binding, no click, no state.
- `ACCESS_HEADERS` / `ACCESS_ROWS` (`KnowledgePage.tsx:39-101`) — a hardcoded 5 rows × 6 role matrix of EDIT/USE/VIEW/NONE with a legend, not backed by any API and not editable anywhere in the app.
- Together these occupy the top and bottom thirds of the Knowledge page. Shots `r_knowledge.png`, `31-knowledge-item-detail.png`.

### F7 — Configure → Approval Gates is a self-declared non-functional prototype · STUB

- Live modal text: **"⚠ Preview — these approval gates are illustrative and are not yet enforced by the backend."** (shot `12-configure-gates.png`).
- `gates` is `useState(DEFAULT_GATES)` (`ConfigureModal.tsx:71`); toggles/role/SLA mutate local state only (`:111-117`); `Reset to defaults` restores the constants (`:123-131`); the footer action is `Done` (close) — **there is no save call**.
- The footer therefore states "4 of 6 gates require human approval" (`:225`) about something the backend does not enforce, and the animated pipeline arrows imply live governance.

### F8 — Configure → Skills & Tools permissions never persist · STUB

- Permission selects call `updateSkill()` which only touches local state (`ConfigureModal.tsx:119-121`); there is no mutation/save anywhere in the file.
- The tab label says "Skills & Tools" and the footer says "1 **tools** configured", but the list is `GET /api/v1/skills` (1 row: `workflow`, shot `12-configure-skills.png`) — terminology conflates skills with tools.
- The only working control here is `Reset to defaults`, which resets local state to what was already loaded.

### F9 — Configure → LLM Providers embeds a whole page inside a dialog · IA-GAP

- The tab renders `SettingsPage` (`ConfigureModal.tsx:212-216`). Live modal shows an inner page header **`SETTINGS`**, a page-level `+ Add Provider` button and a full data table inside the modal (shot `12-configure-llm.png`).
- `SettingsPage` is **not a route**: `App.tsx:31-44` declares no `/settings`, so this page-shaped component (with its own `page-header` layout, `page` CSS class, and toasts) is only ever reachable nested in a modal.
- Naming collision: rail **"Providers"** = *agent/ADK* providers (`ProvidersPage`); Configure **"LLM Providers"** = *model vendors* (`SettingsPage`, e.g. DeepSeek). Two unrelated concepts, nearly identical labels, no cross-reference.

### F10 — Reports' `Generate Report` appears twice on one screen · UX-GAP (minor)

- Header CTA (`ReportsPage.tsx:246-256`) **and** an identical CTA inside the empty-state panel (`:276-280`). Observed simultaneously in `r_reports.png`.
- The empty-state copy itself is good ("The archive is silent. Commission your first dossier …").

### F11 — Rail "Chat" is not chat — it is a run-transcript viewer that overlaps /runs · IA-GAP

- Page heading: `💬 Agent ↔ Agent Conversations`, subtitle "Inter-agent handoffs, clarifications and reviews — fully auditable. Humans can inject messages into any thread." (`ChatPage.tsx:321-325`).
- Reality: threads are built from `listRuns` (`ChatPage.tsx:172-190`), the empty state says **"No conversations yet. Start a run to begin a thread."**, and every thread's participants are hardcoded `['Operator', agentName]` (`:176-183`) — so the list row's "N agents" always shows 2, one of which is the human. There is no agent-to-agent conversation model.
- The transcript it shows (`getRunTrajectory`) is the same data `RunsPage`'s expanded `RunDetailView` renders (`RunsPage.tsx:282-309`). Two UIs, one dataset.
- Meanwhile the real chat surface — the Aria FAB assistant panel — is not in the rail at all. A user clicking "Chat" expecting the assistant gets run logs.

### F12 — Overview and Ops render the same widgets · IA-GAP

- Overview (`OverviewPage.tsx:27-44`): ExecutiveSummary, KanbanBoard, MorningBriefing, AgentTeam, ReviewQueue, ActivityTimeline.
- Ops (`OpsPage.tsx:287-604`): HousekeepingPanel, Pending Approvals, Recent Runs, **Activity Timeline**, **Today's Briefing**.
- Activity timeline and briefing are duplicated widgets with different empty-state strings for the same condition: `No events yet — system is quiet.` (Overview) vs `Quiet on the wire.` (Ops); `0 tasks completed on the kanban board` vs `Morning. 4 agents on deck, 0 active runs. All systems nominal.`
- Ops' "Recent Runs" also duplicates `/runs`; Ops' "Pending Approvals" duplicates Overview's "Review Queue" (see F2).
- Observed in `00-overview-default.png` and `r_ops.png`.

### F13 — "Tokens Today" / "Cumulative" — one field, three labels · UX-GAP

- TopBar `TOKENS TODAY` ← `summary.totalTokensBurned` (`TopBar.tsx:62,100`).
- Executive Summary `TOKENS USED` / **`Cumulative spend`** ← the same `summary.totalTokensBurned` (`ExecutiveSummary.tsx:117-120`).
- Crew `TOKENS · TODAY` ← a *different* field, `telemetry.totalTokensToday` (`CrewPage.tsx:285`), while Crew's "Estimated spend" uses `estimateCost()` on that value with a hardcoded `@ ~$0.012 / 1k tokens`.
- Two different quantities are labelled "today", and the cumulative one is labelled "Today" on the top bar. On this dataset all read 0, so the discrepancy is currently invisible — but it is a definitional bug waiting for data.

### F14 — Crew's "adjustable in Settings" points at a page that does not exist · UX-GAP

- Live text: `HIRING SLOTS 8` / **`soft cap · adjustable in Settings`** (shot `r_crew.png`).
- There is no Settings page: no `/settings` route in `App.tsx`, no rail entry. `SettingsPage` is only the LLM-provider panel inside the Configure modal.
- The value is `Math.max(0, 12 - activeAgents.length)` with `12` hardcoded (`CrewPage.tsx:296`) — nothing enforces or configures it.
- Additionally `🧹 Select Leftovers (0)` is always rendered even at zero leftovers (`CrewPage.tsx:261-263`), and its click path is a bulk-retire flow.

### F15 — Crew renders Aria's `role` (a sentence) as an all-caps role tag; `mock`/`NATIVE` chips · BUG (data-driven)

- `Aria.role` = `"AI operator assistant for the Aria Conductor. Helps manage AI agents, execute commands, and answer system questions."` (from `GET /api/v1/agents`).
- `AgentCard.tsx:125` renders `role` in the fixed `.role-tag` slot; the card visibly breaks (sentence spills across the card, `Idle` pill pushed off-line) — shot `r_crew.png`. `AgentTeam.tsx:102` prints the whole sentence under the agent name on Overview (`00-overview-default.png`).
- Model chips render `mock` for the three SDD agents and `NATIVE` (the `agentType` fallback) for Aria; provider shows `native` (`AgentCard.tsx:160-161`). These are backend-placeholder values surfaced verbatim with no explanation.

### F16 — Providers page: no CTA, no explanation, jargon columns · UX-GAP

- Whole page is two read-only tables (`r_providers.png`). No action, no link, no "why".
- Both rows show `UNHEALTHY` with no tooltip, timestamp, or remediation hint. A failed health call is silently rendered as `Unhealthy` (`ProvidersPage.tsx:79-83`), indistinguishable from a real failure.
- `Capability` column values are the bare words `Task` / `Turn` (`ProvidersPage.tsx:74`) — internal ADK vocabulary, unexplained.
- The page header has no subtitle (unlike every other page), so a newcomer gets zero orientation: what is this, and what should I do?

### F17 — Workflows shows a green "0 completed" success pill in an empty state · UX-GAP

- `r_workflows.png`: `Workflows` · green pill **`0 completed`** · `Execute YAML` · tabs · empty panel `No workflow chains yet. Create one via Aria: start_workflow or the REST API.`
- The pill is emitted unconditionally when the chains tab is active (`WorkflowsPage.tsx:324-335`), so a success-styled badge celebrates zero.
- There is **no in-UI way to create a chain** (templates tab only instantiates templates); the empty state tells the user to use an internal tool name (`start_workflow`) or the REST API — jargon-only next step.

### F18 — Non-interactive KPI tiles + a seconds clock in the top bar · UX-GAP

- Verified live: the four `.top-counters .kpi` elements have `cursor: auto` and clicking them does nothing (URL stayed `/`). "0 Approvals Pending" is a number with no route to the approval surface.
- The top bar clock updates every second (`TopBar.tsx:40-43,105-107`, observed `11:21:39`) — the most frequent DOM change in the app and pure decoration.
- The four tiles restate Executive Summary's "Active Agents" and the "N Agents Online" badge directly to their left → three renderings of the same number in one bar.

### F19 — Approval-adjacent wording is merged; `/ops` normalizes a destructive default · UX-GAP

- Ops' empty approval copy is `Inbox zero. All requests resolved.` while the review queue on Overview says `No pending approvals — queue is clear.` — same state, two voices.
- Ops pairs a destructive batch tool with the approvals panel: `HOUSEKEEPING` + `⚡ Scan leftovers` sits directly above `PENDING APPROVALS` (`r_ops.png`). Described only — the scan was **not** triggered (explicitly out of scope).

### F20 — Runs table rows use a keyless Fragment (latent React key warning) · BUG — inferred (code)

- `RunsPage.tsx:204-237`: `filteredRuns.map((run) => ( <> <tr key={run.id}> … {expanded && <tr key={...}>} </> ))` — the list item is the outer `<>…</>`, which has **no `key`**, so React will log *"Each child in a list should have a unique key prop"* as soon as a run exists.
- **Not observed live**: `GET /api/v1/runs` returned `[]` (2-byte payload), so the list never rendered a row. Console on `/runs` showed only the WebSocket warning.
- This is the only **latent** finding; everything else in this report was reproduced against the running app.

### F21 — Minor label/icon inconsistencies · UX-GAP

- Rail `Jobs` vs page heading `📅 Scheduled Jobs` (`r_scheduled-jobs.png`).
- Rail `Providers` icon `🔄` (refresh) for a static inventory table (`RailNav.tsx:12`).
- Rail icon set mixes text glyph `▦` with colour emoji.
- Configure → Skills footer says "tools" for skills (`ConfigureModal.tsx:227`).

### F22 — Console & network during the walk · OK (two benign items)

- **One console message on all 11 routes** (identical each time):
  `warning: WebSocket connection to 'ws://localhost:5173/ws/events' failed: WebSocket is closed before the connection is established.`
  Consistent with the StrictMode double-mount closing the first socket; the app then connected and no WS-driven refresh failed.
- `/api/v1/aria/conversations/latest` logged as `net::ERR_ABORTED` twice on first paint (HTTP **204**, no conversation stored) — the Aria panel handled it by starting a fresh local conversation (no user-visible error).
- **No 4xx/5xx API responses on any route**, no React key warnings, no page errors during the walk. The empty states that rendered were all correct empty states, not errors.

### F23 — OpenCode / LangChain health are environment gaps, not UI bugs · OK

- `opencode → UNHEALTHY` because no OpenSandbox container is running; `langchain → unhealthy` because no ADK process is running (`/api/v1/adk/providers/langchain/health` → `{"healthy":false}`).
- Agent RUNs via the default provider will fail in this environment. Recorded as an environment limitation per instructions — **not** counted against the UI. (The UI-symptom worth noting is only that `UNHEALTHY` is rendered with no cause, F16.)

---

## 5. Candidates for removal / reduction

Ordered by (surface removed) × (confidence it is safe to remove). None of these were changed — this is a proposal list only.

| # | Candidate | Evidence | Recommendation |
|---|-----------|----------|----------------|
| R1 | **`/approvals` route + `ApprovalsPage.tsx` + `ApprovalsPage.test.tsx`** | F1 — nothing links to it; it immediately redirects to `/` | Delete, or (if deep links matter) keep only a router-level `<Navigate>` and delete the page module + test |
| R2 | **Configure → Approval Gates tab** | F7 — self-declared "not yet enforced"; no persistence; no save button | Remove from the shipped modal (or hide behind a dev flag) until a real gate API exists |
| R3 | **Configure → Skills & Tools permission editor** | F8 — local state only, no save path | Either wire to a real update API or drop to a read-only skill list |
| R4 | **Knowledge "Promotion Path / Governance Flow" panel** | F6 — hardcoded 5-step illustration | Remove (or move to docs/onboarding) |
| R5 | **Knowledge "Access Control / permission matrix"** | F6 — hardcoded 5×6 matrix, uneditable | Remove until it is data-backed |
| R6 | **Knowledge "Per-Agent Spaces" panel** | F5 — ownership is a hash of the item id; 62 items → 18/15/14/15 arbitrary; "CLICK TO OPEN" filters an empty list | Remove the panel, or fix the backend to return a real owner and then re-derive the counts |
| R7 | **One of the duplicated widget sets: Overview ⟷ Ops** | F12 — activity timeline + briefing on both; Ops "Recent Runs" ⟷ `/runs`; Ops approvals ⟷ Overview review queue | Pick one home for Activity/Briefing, and drop Ops' run table in favour of a link to `/runs` |
| R8 | **`/chat` page as "Agent ↔ Agent Conversations"** | F11 — threads are runs; participants always Operator+1 agent; overlaps `/runs` trajectory | Rename to "Run transcripts" and merge into the Runs detail, or delete the route |
| R9 | **`SettingsPage` as a page-shaped component inside the Configure modal** | F9 — no `/settings` route; inner "SETTINGS" page header inside a dialog | Extract a compact `LlmProvidersPanel`; reserve the name "Settings" for a real route |
| R10 | **TopBar seconds clock + 4 non-interactive KPI tiles** | F18 — duplicate Exec Summary & the "Agents Online" badge; no click handlers | Remove the clock; either make the tiles navigate (Approvals Pending → the review surface) or drop them |
| R11 | **"0 completed" pill and `Execute YAML` in the Workflows zero-state** | F17 — success pill celebrating zero; YAML import with nothing to import | Suppress the pill at 0; add a real create entry point instead of pointing at `start_workflow` |
| R12 | **Duplicate `+ Generate Report` CTA on `/reports`** | F10 | Keep the header CTA, remove the in-panel one |
| R13 | **Crew "Hiring slots / adjustable in Settings" cell** | F14 — no Settings page; hardcoded cap of 12 | Remove the cell, or implement + link a real setting |
| R14 | **Agent Team ⟷ Crew overlap** | Overview's Agent Team panel and `/crew`'s Active Agents grid both list every agent (Overview also prints Aria's full role sentence) | Keep one; Overview could show a compact count that links to `/crew` |

### Smaller polish items (not removal candidates)

- Fix the `v{currentVersion}` render (F4) — backend returns `null` × 61 and `"v1.0.0"` × 1.
- Fix or guard `isHealthy = activeAgents > 0` and the "Healthy & responsive" copy (F3).
- Unify "Tokens today" vs "cumulative" labelling (F13).
- Add `key` to the Runs row fragment (F20).
- Rename rail "Jobs" → "Scheduled Jobs", replace the `🔄` Providers icon, unify the rail icon style (F21).
- Give `/providers` a subtitle + a health-explanation tooltip (F16).
- Normalize Aria's `role` to a short label so Crew/Agent Team stop rendering a paragraph (F15).

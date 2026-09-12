# UI/UX Static Audit — `act-dashboard` @ `1ef4e64`

- **Date:** 2026-09-12
- **Scope:** `agent-control-tower/act-dashboard/src/**` (React 19 / Vite / React Router v7 / TanStack Query). Read-only static audit — no builds, tests, or dev servers were run.
- **Commit:** `1ef4e64` (`feat(kanban): HITL redesign … (#79)`, with `0073e69` UI-audit fixes #78 immediately prior).
- **Goal:** (a) find usability improvements, (b) find redundant/meaningless surfaces that can be removed.
- **Method:** route/nav/page inventory by import-graph analysis; per-page read of every routed file and shared component; targeted greps for `alert(`/`confirm(`/`console.log`/inline styles/clickable non-semantic elements; cross-check against backend controllers and the e2e suite for blast radius.
- **Prior context used:** `.ui-audit/*.json`, `ui-audit-browser.md`, `ui-audit-code-stubs.md`, `ui-ux-inspection-report.md`. Every carried-forward claim was re-verified against the current tree; findings that predate #78/#79 are marked.

Every claim below is grounded in `file:line`. Claims I could not execute-verify (tests/backends) are explicitly marked **Hypothesis**.

---

## 0. Prior-audit findings — re-verification (fixed vs. still open)

`ui-audit-code-stubs.md` was written at `7eea78f` (before #78/#79). Re-verified now:

| # | Prior finding | Status at `1ef4e64` | Evidence |
|---|---------------|---------------------|----------|
| 1 | `Toast.tsx:75` "View" button was `console.log` (dead handler) | **FIXED** | `Toast.tsx:82-88` now resolves `routeForNotificationType(notifType)` and `navigate(route)` |
| 2 | WS single `lastMessage` drops bursty frames | **PARTIALLY FIXED** | `useWebSocket.ts:63-66,104-106` adds `subscribe()` delivering every frame; `AgentDrawer.tsx:238` uses it. `lastMessage` still used by `Toast.tsx:48`, `NotificationBell.tsx:15`, `AgentTeam`, `MorningBriefing` etc., which can still coalesce bursts (low impact — those only invalidate queries) |
| 3 | `OpsPage` mock "⤴ Escalate" button | **FIXED** (removed) | no `Escalate` in `OpsPage.tsx`; queue only Approve/Deny (`OpsPage.tsx:377-399`) |
| 4 | `DashboardPage.tsx` dead demo page + `/agents` KPI links | **FIXED** (file deleted) | `src/pages/DashboardPage.tsx` absent |
| 5 | `DoDPage.tsx` dead page | **FIXED** (file deleted) | absent |
| 6 | `KanbanPage.tsx` dead page | **FIXED** (file deleted) | absent |
| 7 | `ToolManager.tsx` dead page | **FIXED** (file deleted) | absent |
| 8 | `AgentDrawer.tsx:434` stale "progress pump … GET /session/:id/message" copy | **FIXED** | grep for `progress pump` / `session/:id/message` → no hits |
| 9 | `AgentDrawer` dead `agent.heartbeat` branch | **FIXED** | grep `heartbeat` → no hits |
| 10 | `AgentDrawer` stream never seeded with history | **FIXED** | `AgentDrawer.tsx:192-227` replays persisted backlog via `getRunProgress(runId)` on every open |
| 12 | `SPEC_REVIEW` markdown renders lossy / unstyled `.spec-review-markdown` | **FIXED** | `MarkdownViewer.tsx:71-156` is now a real block parser (fences, tables, ordered lists, links) + CSS at `styles/index.css:2434`; DOMPurify at `MarkdownViewer.tsx:161` |
| 13 | `ScheduledJobsPage` blocking `alert()` for pause/resume/create errors | **PARTIALLY FIXED** | `alert()` gone; error toasts replace it (`ScheduledJobsPage.tsx:99-101,160,165`). **But** `confirm()` remains at `:237` and success has no positive toast (see §3) |
| 14 | `approval.requested` toast has no click-through | **OPEN** | `Toast.tsx:91-95` builds non-aria toasts with **no `action`**; only the `aria.notification` branch gets "View" (`Toast.tsx:78-89`) |
| 15 | `AgentDrawer` hardcoded `tokenCap`/`ctxUsed` fake bar | **FIXED** | Resources grid now shows real `Active runs` / `Runtime` (`AgentDrawer.tsx:571-589`) |
| — | stale `src/utils/wsEvents.ts` "RunDetailView" comment | **FIXED** | grep `RunDetailView` → no hits |

---

## 1. Route / Nav / Page Inventory

### 1.1 Routes in `App.tsx` vs. nav entries in `RailNav.tsx`

| Route (`App.tsx`) | Rail entry (`RailNav.tsx:9-20`) | Page file | Nav label vs. page title |
|---|---|---|---|
| `/` (`:33`) | `Overview` `▦` | `OverviewPage.tsx` | no page `<h1>` (widget grid) |
| `/crew` (`:34`) | `Crew` `👥` | `CrewPage.tsx` | `Crew` = `👥 Crew` (`:255`) |
| `/providers` (`:35`) | `Providers` `🔄` | `ProvidersPage.tsx` | **mismatch**: nav "Providers" → page `<h2>Agent Providers</h2>` (`:44`) |
| `/knowledge` (`:36`) | `Knowledge` `📚` | `KnowledgePage.tsx` | mismatch: "Knowledge" → "Knowledge Governance" (`:354`) |
| `/reports` (`:37`) | `Reports` `📊` | `ReportsPage.tsx` | mismatch: "Reports" → "📊 Generative UI · Agent Report Workspace" (`:240`) |
| `/chat` (`:38`) | `Chat` `💬` | `ChatPage.tsx` | **mismatch (misleading)**: "Chat" → "💬 Agent ↔ Agent Conversations" (`:321`) |
| `/workflows` (`:39`) | `Workflows` `🔗` | `WorkflowsPage.tsx` | "Workflows" → `<h1>Workflows</h1>` (`:311`) |
| `/ops` (`:40`) | `Ops` `🛡️` | `OpsPage.tsx` | mismatch: "Ops" → "Operations · Command Surface" (`:250-260`) |
| `/scheduled-jobs` (`:41`) | `Jobs` `📅` | `ScheduledJobsPage.tsx` | **path/label mismatch**: label "Jobs", path `/scheduled-jobs`, title "📅 Scheduled Jobs" (`:179`) |
| `/runs` (`:42`) | `Runs` `▶️` | `RunsPage.tsx` | "Runs" = `<h2>Runs</h2>` (`:108`) |
| `/approvals` (`:43`) | **NONE** | `ApprovalsPage.tsx` | orphaned route (redirect stub) |

**Counts:** 11 routes, 10 nav items (+1 `Configure` action button), 13 files in `src/pages/`.

### 1.2 Orphaned / dead surfaces (verified by import graph)

**Routes with no nav entry — 1**
- `/approvals` — `App.tsx:43` mounts `ApprovalsPage`, which is a pure redirect: `ApprovalsPage.tsx:8-10` `return <Navigate to="/" replace />;`. No rail entry (PR #79 removed it), and `notificationRoutes.ts:25` now maps `approval.requested → '/'`, so **no product code links to `/approvals`**. Only reachable by typing the URL / stale bookmark.
  - Still referenced by **tests only**: `pages/__tests__/ApprovalsPage.test.tsx`, `e2e/approvals-decision-flow.spec.ts:17`, `e2e/journey-agent-run-report.spec.ts:59`, `e2e/sdd-workflow.spec.ts:100`, `e2e/git-pack-governance.spec.ts:35`, `e2e/agent-dev-workflow-governance.spec.ts:88`.
  - **Removing the route is safe for the app** (the redirect target `/` is unaffected); it would break 6 test files unless they are retargeted to `/`. Keeping the redirect is the lower-risk option; the route is dead weight but harmless.

**Dead page/component files — 3 (0 references anywhere in `src/` or `e2e/`)**
- `src/pages/AgentsPage.tsx` — a **full 413-line agent CRUD duplicate of `CrewPage`** (`AgentsPage.tsx:26` `export function AgentsPage()`). Zero imports; only mention is a *skipped* test comment (`e2e/template-api.spec.ts:146` `test.skip(...)`).
- `src/components/AgentToolPanel.tsx` — `export default function AgentToolPanel()` (`:6`), zero imports. Uses raw `fetch('/api/v1/tools')` / `/api/v1/roles/{role}/tools`.
- `src/components/EvidenceDrawer.tsx` — `export function EvidenceDrawer()` (`:42`), zero imports. It is the **last remaining consumer of `api/dod.ts`**, so both are dead together.

**Dead non-page modules — 3**
- `src/hooks/useNotificationPrefs.ts` — referenced **only by its own test** (`hooks/__tests__/useNotificationPrefs.test.ts`); no production caller.
- `src/api/harness.ts` — `listHarnessProfiles` / `getHarnessProfile` (`:4,:9`) have **zero callers** (grep for `harness` outside the file returns nothing in `src/`). Backend `HarnessProfileController` becomes unreachable from the UI (still reached by `e2e/harness-governance.spec.ts` via HTTP).
- `src/api/dod.ts` — only consumer is the dead `EvidenceDrawer.tsx:8`. Backend `DoDController` becomes unreachable from the UI (still reached by `e2e/dod-lifecycle.spec.ts` via HTTP).

**Nav label/icon/path mismatches**
- `RailNav.tsx:19` `{ path: '/scheduled-jobs', label: 'Jobs' }` — label shortens/renames the route noun.
- `RailNav.tsx:12` icon `🔄` for `Providers` reads as "refresh/sync", not "providers".
- No `aria-current` on the active rail button — only a CSS class (`RailNav.tsx:42,46` `className={…active}`). See §5.

**Total orphaned/dead surface: 7 files + 1 orphaned route + 1 nav label mismatch.**

### 1.3 Pages not routed
- `src/pages/SettingsPage.tsx` — **not a route**; mounted only inside `ConfigureModal.tsx:214` (`activeTab === 'llm'`). It renders a full `<div className="page">` + `<h2>Settings</h2>` header (`SettingsPage.tsx:158-160`) inside a dialog, producing a nested "Settings" heading inside the "Configure" modal.

---

## 2. Per-page Usability Assessment

Legend: **Task** = primary user job; **Fold** = above-the-fold hierarchy; **Gaps** = empty/error/self-explanation problems.

### `/` OverviewPage (`OverviewPage.tsx`)
- **Task:** HITL triage on the kanban board + at-a-glance ops status.
- **Fold:** two-column `.layout`; left = `ExecutiveSummary` + `KanbanBoard` + `MorningBriefing`; right = `AgentTeam` + `ReviewQueue` + `ActivityTimeline` (`:27-44`). With a review target set, left column expands in-place to `ReviewWorkspace` and widgets reflow to a bottom strip (`:46-59`).
- **Gaps:**
  - **No page title / orientation.** There is no `<h1>`; a newcomer lands on six unlabeled panels. `ExecutiveSummary` is titled "Executive Summary · Live" (`ExecutiveSummary.tsx:88-89`) which is jargon.
  - **Six separate panels + a 5-column board on one screen** is the densest surface in the app; `AgentTeam`, `ReviewQueue` and `ActivityTimeline` duplicate data already on `/crew`, the kanban Review column, and `/ops`.
  - Empty/error states exist per-widget (e.g. `AgentTeam.tsx:84-88`, `ActivityTimeline.tsx:72-76`) but are inconsistent in wording (see §4).
  - `ExecutiveSummary` "Waiting on you" card is the single HITL summary signal (`ExecutiveSummary.tsx:97-103`), good; but the same concept is called "Review Queue … pending" (`ReviewQueue.tsx:48-49`) and "Pending Approvals" (`OpsPage.tsx:297`) elsewhere.

### `/crew` CrewPage (`CrewPage.tsx`)
- **Task:** manage the agent roster (hire, cost, retire, assign tools).
- **Fold:** header + "🧹 Select Leftovers" + "+ Add Agent" CTAs (`:261-267`); cost banner (`:271-300`); active grid (`:303-352`); catalog (`:355-358`).
- **Gaps:**
  - **Jargon:** "Hiring slots … soft cap · adjustable in Settings" (`:295-297`) — the cap is a hardcoded `12` (`:296`) and there is no route to change it.
  - **Jargon CTAs:** "Select Leftovers" (`:262`) means "select agents named `e2e-*` or UNHEALTHY" (`:182-185`) — meaningless to a non-test user.
  - Mixed number formatting: `formatTokens` local (`:496-499`) vs `TopBar.formatTokens` (`TopBar.tsx:16-21`) — one renders `k`, the other `K`.
  - Error copy strands the user: "Retry from the rail." (`:313`) — there is no retry control in the rail.
  - Retire has **no confirmation** (`:346-348`, while `AgentsPage` — the dead twin — had one); retire result shown via a plain text line (`:351`).
  - `Add Agent` dialog is well-labeled (`aria-labelledby`, `inert` when closed, `:367-374`) — good example to copy.

### `/providers` ProvidersPage (`ProvidersPage.tsx`)
- **Task:** inspect ADK provider inventory + per-agent backend mapping.
- **Fold:** two read-only tables, **zero actions** (`grep onClick|<button` → none).
- **Gaps:**
  - **Read-only page with an editable-sounding nav name.** LLM provider *configuration* lives elsewhere (`ConfigureModal` → `SettingsPage`), so "Providers" and "LLM Providers" are two different concepts sharing one word — a likely source of user confusion (explicitly suspected by the requester).
  - **Jargon-only:** `Capability` column renders `Task`/`Turn` (`:74`); no explanation.
  - No page description/sub (unlike `Crew`/`Ops`), so the page never explains itself.
  - Loading/empty/error states present (`:48-53, 96-100`) — adequate.

### `/runs` RunsPage (`RunsPage.tsx`)
- **Task:** start/monitor/cancel runs.
- **Fold:** header + "+ Start Run" (`:107-112`); filter bar (`:115-132`); table with inline expand (`:188-241`).
- **Gaps:**
  - **Pause / Resume / Cancel fire with no confirmation and no feedback** (`:218-226`); `cancelMutation` etc. have no `onError` (`:42-55`) → silent failure.
  - `createMutation` has **no `onError`** (`:32-40`) → a failed "Start Run" closes nothing and shows nothing.
  - Filter dropdown shows raw enum values (`PENDING`, `RUNNING`, `ABORTED`, `:118-124`) while the table next to it renders humanized `StatusBadge` labels — two vocabularies on one screen.
  - `<>` used as the `map` root at `:205` while children carry `key` (`:206,:230`) — **React key warning**; keys belong on the fragment.
  - `"No runs yet. Start one above."` (`:184`) — decent empty state.

### `/knowledge` KnowledgePage (`KnowledgePage.tsx`)
- **Task:** govern knowledge (review queue + approved library + access matrix).
- **Fold:** header + "+ Submit New" (`:359-366`); then **five panels**: Promotion Path, Per-Agent Spaces, Review queue, Unified library, Access Control.
- **Gaps:**
  - **Static data presented as live system state — the biggest misrepresentation in the app.** `FLOW_STEPS` (`:26-32`) and the entire **Access Control permission matrix** (`ACCESS_HEADERS :39`, `ACCESS_ROWS :40-101`, rendered `:738-764`) are hardcoded constants. A newcomer reads the matrix as the actual RBAC configuration; it is not backed by any API.
  - **"Promote" fires with no confirmation and no success/failure feedback** (`:712-719`): `updateKnowledge(... PROMOTED).then(invalidate)` with **no `catch` and no toast** — while Approve/Reject deliberately go through a confirm dialog (`:201, 331-347`). Inconsistent and silently failure-prone.
  - "Copy ID" (`:720-725`) gives no feedback (`navigator.clipboard?.writeText`, no toast).
  - Single-item Approve/Reject are **icon-only `✓`/`✗`/`↗`** with `title` only (`:559,568,575`) — see §5.
  - "Review note" input is rendered whenever *any* item is pending (`:584`), not only when a decision is staged — mildly confusing.
  - Empty/loading states are present and generally good (`:516, 529, 649`).

### `/reports` ReportsPage (`ReportsPage.tsx`)
- **Task:** generate/review/amend sandboxed HTML reports.
- **Fold:** header + "+ Generate Report" (`:246-256`); empty state (`:270-283`); else tab list + metrics + preview + amend chat (`:285-530`).
- **Gaps:** Copy link is well-fed-back (`:194-204`). **Delete uses blocking `confirm()`** (`:221`). Regenerate/Amend give an overlay but **no success toast**; `amendMutation`/`regenerateMutation`/`archiveMutation` have no `onError` (`:145-169`) — silent failures (except amend, which prints an inline error at `:520-524`). Empty state is vivid but jargon-heavy ("dossier", "sandboxed iframe").

### `/chat` ChatPage (`ChatPage.tsx`)
- **Task (per label):** inter-agent conversation.
- **Task (actual):** read a **run's trajectory** and inject a human message.
- **Gaps:**
  - **Misleading title.** "Agent ↔ Agent Conversations" (`:321`) but threads are derived 1:1 from `runs`, with participants hardcoded `['Operator', agentName]` (`:174-188`). There is no agent↔agent thread anywhere.
  - Embeds a **third HITL surface** inline: `WorkflowStepper` + `DelegationTree` + `ReviewQueue runId=…` (`:385-396`), duplicating Overview and Ops.
  - `:179/:385` leave two `console.log` debug lines in production code.
  - "Inject message as Human Operator" (`:439`) is accurate but jarring jargon for the primary compose box.
  - Loading/empty/error states are present and clear (`:341-357, 404-413`).

### `/workflows` WorkflowsPage (`WorkflowsPage.tsx`)
- **Task:** watch workflow chains; merge/execute/templates.
- **Fold:** inline-styled header with running/completed pills; `Chains`/`Templates` tabs (`:351-358`).
- **Gaps:**
  - **Empty state is a dead end for a newcomer:** "Create one via Aria: `start_workflow` or the REST API." (`:375-378`) — exposes an internal tool name and no UI affordance (the Templates tab exists but is unmentioned).
  - Cancel / Retry / Resubmit / Delete have **no success feedback**, and four of six mutations have no `onError` (`:232-263`). Delete uses blocking `confirm()` (`:191`).
  - `Retry Step {wf.currentStepIndex}` (`:186`) but cards are labeled `Step {step.index + 1}` (`:60`) — off-by-one display inconsistency.
  - This page is the **only routed page that uses neither `.view-zone` nor `.page`** (see §4).

### `/ops` OpsPage (`OpsPage.tsx`)
- **Task:** operator console — approvals, run history, activity, briefing.
- **Fold:** header + Live/Refresh (`:248-284`); Housekeeping (`:287`); two-column grid (`:288-606`).
- **Gaps:**
  - **Duplicates Overview** almost 1:1: Activity Timeline, Morning Briefing, approvals, agent names, run history all exist on `/`. The overlap is the clearest consolidation candidate.
  - Approvals here have **no confirmation and no positive toast on deny** — deny shows `"Approval denied."` (`:173`), approve shows a toast (`:164`), but both fire instantly; no diff context (unlike `ReviewQueue`/`DecisionPanel`, which show `DiffPreview`/run output).
  - Very heavy inline styling (53 `style={{}}` occurrences — highest in the codebase), including its own bespoke toast (`:609-635`) instead of the shared `Toast`.

### `/scheduled-jobs` ScheduledJobsPage (`ScheduledJobsPage.tsx`)
- **Task:** manage cron/one-shot Aria jobs.
- **Fold:** header + "+ New Job" (`:178-181`); category tabs + status filter (`:183-201`); card grid (`:212-243`).
- **Gaps:**
  - Delete uses blocking `confirm()` (`:237`); Pause/Resume/Create/Update have **no success toast** (only errors) (`:99-101,157-166`).
  - Uses a **bespoke inline toast** (`:323-343`) instead of the shared `Toast` pattern.
  - Cron preview is a genuinely good affordance (`:288-300`).

### `/approvals` (retired) — `ApprovalsPage.tsx`
- Redirect-only (`:8-10`). Correct per the HITL redesign, but see §6 for test blast radius.

### `SettingsPage` (modal-embedded, not routed) (`SettingsPage.tsx`)
- **Task:** LLM provider CRUD.
- **Gaps:** nested `<h2>Settings</h2>` inside a dialog titled "Configure" (`ConfigureModal.tsx:149-151`); its own inline toast stack at `position:fixed bottom:1rem right:1rem zIndex:1000` (`:311-325`) overlaps the modal and duplicates the shared `Toast`. The name "Settings" also collides with "System Config" (the other Configure tab, `ConfigureModal.tsx:191-198`).

---

## 3. Interaction / Feedback Gaps

### 3.1 Blocking `alert()` / `confirm()` instead of the toast pattern
The project's toast pattern is the shared `Toast` component (`Toast.tsx`, `.toast-container`/`.toast-item` in `styles/index.css:2165-2167`). Four sites still block the thread with native dialogs:

```tsx
// ReportsPage.tsx:219-224
const handleDelete = () => {
  if (!selected) return;
  if (confirm(`Delete "${selected.title}"? This archives the report.`)) {
    archiveMutation.mutate(selected.id);
  }
};
```
```tsx
// ScheduledJobsPage.tsx:237
<button className="btn sm danger" onClick={() => { if (confirm('Delete this job?')) deleteMut.mutate(job.id); }} …>
```
```tsx
// WorkflowsPage.tsx:191
<button className="btn" onClick={() => { if (confirm('Delete this workflow?')) onDelete(wf.id); }}>
```
```tsx
// TemplatesPanel.tsx:143
onClick={() => { if (confirm('Retire this template? It will be hidden from the list (soft delete).')) retireMutation.mutate(t.id); }}
```

### 3.2 Destructive actions: inconsistent confirmation
| Action | Confirms? | Evidence |
|---|---|---|
| Kanban "✕ Cancel task" | **No** | `KanbanBoard.tsx:444-457` fires `transitionMutation` on a bare card corner button |
| Kanban block clear | Yes (modal) | `KanbanBoard.tsx:560-591` |
| Crew "Retire selected…" | **No** | `CrewPage.tsx:346-348` fires `retireSelected` directly |
| `AgentsPage` retire (dead) | Yes (modal) | `AgentsPage.tsx:319-332` |
| Knowledge single/batch review | Yes (modal) | `KnowledgePage.tsx:331-347, 767-799` |
| Knowledge "⤴ Promote" | **No** | `KnowledgePage.tsx:712-719` |
| Report Delete | Yes (`confirm()`) | `ReportsPage.tsx:221` |
| Drawer footer "Reject" | **No** | `TaskDrawer.tsx:447-453` |
| Approval queue Approve/Deny | **No** | `ReviewQueue.tsx:99-120`, `OpsPage.tsx:377-399` |
| Aria "Clear" new conversation | **No** | `AriaPanel.tsx:376-392` deletes the conversation outright |

### 3.3 Actions with no visible feedback
- `RunsPage` create/pause/resume/cancel — no `onError`, no success toast (`RunsPage.tsx:32-55`). The list only refreshes.
- `WorkflowsPage` cancel/retry/resubmit/delete/merge/executeYaml — no success toast; only `cancelMutation` path reports via `setError` on the board (`:232-263`).
- `AgentCatalog` "+ Deploy" — `deployMutation` has no `onError` (`AgentCatalog.tsx:22-29`); a failed hire is silent.
- `ManageToolsDialog` tool/skill toggles + "Apply role defaults" — no error surface (`ManageToolsDialog.tsx:68-105`).
- `AgentDrawer` footer Pause/Resume/Stop — `pauseRun(...).then(invalidate)` with no `.catch` and no toast (`AgentDrawer.tsx:695-700`).
- `KnowledgePage` Promote / Copy ID (`:712-725`) — no feedback.

### 3.4 Inert / placeholder controls
- **ConfigureModal → Approval Gates tab is explicitly inert.** `ConfigureModal.tsx:260-262` warns *"⚠ Preview — these approval gates are illustrative and are **not yet enforced** by the backend."* Toggling gates, changing approver/SLA (`:334-356, 363-375`) mutates only local state via `DEFAULT_GATES` (`:46-53`); "Done" discards everything.
- **ConfigureModal → Skills tab permission dropdowns are inert.** `updateSkill` (`:119-121`) only sets local state; there is no save mutation. "Reset to defaults" (`:234-237`) also only resets local state.
- `AgentToolPanel.tsx` (dead) calls `/api/v1/tools` and `/api/v1/roles/{role}/tools` (`:14-16`) that no `ToolController` mapping serves — `ToolController.java:14` is `@RequestMapping("/api/v1/tools")` with `GET /`, `GET /{id}`, `POST /{id}/toggle`, `GET /payload`; there is **no assign/unassign-by-agent nor roles endpoint**. (Dead file, so no user impact.)

### 3.5 No connection-state indicator
`isConnected` is produced (`useWebSocket.ts:57,152`) and even destructured (`AgentDrawer.tsx:109`) but **never rendered anywhere**. The TopBar instead shows "System Healthy/Idle" derived from `activeAgents > 0` (`TopBar.tsx:63,80-83`), which is unrelated to WebSocket health. During a dropped socket the UI silently stops updating with no signal.

---

## 4. Cross-Cutting Consistency

### 4.1 The same entity, named differently
- **Human-in-the-loop decision:** "Review Queue" (`ReviewQueue.tsx:48`), "Pending Approvals" (`OpsPage.tsx:297`), "Waiting on you" (`ExecutiveSummary.tsx:98`), "NEEDS YOUR DECISION" (`ReviewPanels.tsx:52`), "Review" column (`KanbanBoard.tsx:29`).
- **Agent health:** `StatusBadge` prints raw enums `HEALTHY/UNHEALTHY` (`StatusBadge.tsx:8-10,38`); `AgentTeam` says `Online/Degraded/Unhealthy` (`AgentTeam.tsx:30-36`); `AgentDrawer` says `Online/Degraded/Offline` (`AgentDrawer.tsx:38-43`); `ProvidersPage` says `Healthy/Unhealthy` (`ProvidersPage.tsx:16-17`); `TopBar` says `System Healthy/Idle` (`TopBar.tsx:82`).
- **"Providers":** `/providers` = ADK *agent backends*, read-only; `ConfigureModal` tab "🤖 LLM Providers" = model endpoints, editable. Same word, two unrelated entities.
- **"Settings" vs "System Config":** `SettingsPage` `<h2>Settings</h2>` (`SettingsPage.tsx:160`) vs Configure tab "⚙️ System Config" (`ConfigureModal.tsx:197`).
- **Run/chains:** `/runs` "Runs" vs `/workflows` "Chains" vs Chat "Threads" — three names for related run artifacts.

### 4.2 Duplicated entry points to one capability
- **Approvals decisions surface in five places:** Kanban card buttons (`KanbanBoard.tsx:403-431`), `ReviewQueue` panel on Overview **and** Chat (`ReviewQueue.tsx:99-120`, `ChatPage.tsx:393`), `OpsPage` queue (`OpsPage.tsx:377-399`), `DecisionPanel`/`ShortApprovalView` inside `TaskDrawer`/`ReviewWorkspace` (`ReviewPanels.tsx:69-102,140-144`), and the `TaskDrawer` footer "Approve"/"Reject" (`TaskDrawer.tsx:439-457`). Spec D4 declares the kanban Review column the *single* surface; the legacy `/api/v1/approvals`-backed `ReviewQueue` and Ops queue still exist alongside the kanban ask API (`listAsksByKanbanItem`), i.e. **two data models for the same decisions**.
- **Agent roster:** `CrewPage` grid, `AgentTeam` panel (Overview), and the dead `AgentsPage` — three implementations.
- **Tool/skill assignment:** `ManageToolsDialog` (Crew) vs dead `AgentToolPanel`, vs the inert Skills tab (`ConfigureModal.tsx:388-459`).
- **Config:** `ConfigureModal` "System Config" → `SystemConfigPanel` vs "LLM Providers" → `SettingsPage`.

### 4.3 Layout / design-system drift
Three competing page shells:
1. `.view-zone` + `.view-header` (CSS `styles/index.css:1340-1350`): Overview, Crew, Chat, Knowledge, Ops.
2. `.page` + `.page-header` (`index.css:2325-2330`): Providers, Runs, ScheduledJobs, Settings, (dead) AgentsPage.
3. **Neither** — raw inline-styled divs: `WorkflowsPage` (`:309`) and `ReportsPage` (`:237`, partially `view-header`).

Inline-style density (files with the most `style={{}}`): `OpsPage.tsx` 53, `KnowledgePage.tsx` 46, `WorkflowsPage.tsx` 40, `ReportsPage.tsx` 25. `WorkflowsPage`/`TemplatesPanel` also hardcode CSS-var fallbacks (`var(--warn,#f59e0b)`, `var(--ok,#22c55e)`, `var(--bg-secondary)`, `var(--text-muted)`) that are **not tokens used elsewhere** — the rest of the app uses `--amber/--green/--red/--brand-2` (`index.css:17-22`).

**Four separate toast implementations:** shared `Toast` (`Toast.tsx`); `OpsPage.tsx:609-635`; `KnowledgePage.tsx:897-916`; `ScheduledJobsPage.tsx:323-343`; `SystemConfigPanel.tsx:340-378`; `SettingsPage.tsx:311-325`. Each with different colors/position/timing (2600 ms / 2800 ms / 3500 ms / 4000 ms / 5000 ms).

### 4.4 Inconsistent empty-state copy
- "No runs yet. Start one above." (`RunsPage.tsx:184`)
- "No agents on the crew yet. Hire one from the catalog below or click **+ Add Agent**." (`CrewPage.tsx:318`)
- "Inbox zero. All requests resolved." (`OpsPage.tsx:329`) vs "Inbox zero. No items waiting for review." (`KnowledgePage.tsx:529`) vs "No pending approvals — queue is clear." (`ReviewQueue.tsx:64`)
- "Quiet on the wire." (`OpsPage.tsx:530`) vs "No events yet — system is quiet." (`ActivityTimeline.tsx:74`)
- "The archive is silent. Commission your first dossier…" (`ReportsPage.tsx:274`) vs "No providers registered." (`ProvidersPage.tsx:52`)

### 4.5 Page titles that are marketing lines, not labels
`ReportsPage.tsx:240` "📊 Generative UI · Agent Report Workspace"; `OpsPage.tsx:250-260` "Operations · Command Surface"; `ChatPage.tsx:321` "💬 Agent ↔ Agent Conversations" (inaccurate). Newcomers cannot map a rail label to a screen.

---

## 5. Accessibility Spot-Checks

- **Non-semantic clickable elements without keyboard access**
  - `AgentDrawer.tsx:430` `<div className="close" onClick={closeAgentDrawer} role="button" aria-label="Close">` — `role="button"` but **no `tabIndex` and no `onKeyDown`** → unreachable by keyboard.
  - `TaskDrawer.tsx:221` — identical pattern.
  - `AgentTeam.tsx:92-97` — clickable `<div>` row (`onClick={() => dispatchOpenAgentDrawer(agent.id)}`) with **no `role`, `tabIndex`, or key handler** → agent rows cannot be opened with a keyboard.
  - `AgentDrawer.tsx:655-674` — four command chips are `<span className="chip" onClick=…>` with no role/tabIndex/keyboard (only the two middle ones were in the grep, all four are spans).
  - `ExecutiveSummary.tsx:19-31` — `role="button"` + `tabIndex` present but `onKeyDown` only handles `Enter` (`:28-30`), not Space; acceptable but inconsistent with the app's other custom buttons which handle both (e.g. `ConfigureModal.tsx:369-374`).
- **Icon-only controls without `aria-label`**
  - `SystemConfigPanel.tsx:307-320` (💾 Save) and `:321-330` (↩ Reset) use `title` only — no `aria-label`; `title` is unreliably announced.
  - `KnowledgePage.tsx:552-576` — single-item ✓/✗/↗ buttons carry `title` only (no `aria-label`).
  - `KanbanBoard.tsx:255-264` "+ New Item" is text — fine; but the card action group (`:403-439`) relies on `aria-label` (good) and *also* `title` (redundant).
- **Positive example:** `RailNav.tsx:37,42` uses a real `<aside role="navigation" aria-label="Main views">` with `<button>` children — but sets **no `aria-current="page"`** on the active item (only `.active` class at `:42`).
- **Modal / drawer focus**
  - Good: `CrewPage` dialog (`:367-374`, `inert` + `aria-labelledby`), `WorkflowsPage` merge/YAML modals (`:400-439`, Escape handler at `:297-306`), `ReviewWorkspace` autoFocus on Collapse (`:181`) and Escape guard that protects editable fields (`:59-71`).
  - Gaps: the **`ConfigureModal` sets no `inert`/`aria-hidden` trap when closed** (it uses `aria-hidden={!open}` and CSS opacity only — `ConfigureModal.tsx:140-147`), and `SettingsPage`/`OpsPage`/`KnowledgePage`/`ScheduledJobsPage` modals (`.modal-overlay`) have **no focus trap and no focus restore** on close.
  - Backdrops are plain `<div onClick>` (e.g. `KnowledgePage.tsx:768`, `ScheduledJobsPage.tsx:247`) — mouse-only close, no keyboard equivalent besides Cancel/✕ buttons (which do exist in most cases).
- **Live regions:** `Toast.tsx:110` container and `NotificationBell.tsx:134` list use `aria-live`; `OpsPage.tsx:611` uses `role="status"`; `SystemConfigPanel` toast stack has **no live region** (`:340-378`).

---

## 6. Prioritized "Could Be Removed" List

Ordered by (safety × size-of-shrink). "Blast radius" lists everything that would need updating.

### Tier 1 — delete outright (no UI depends on them)
1. **`src/pages/AgentsPage.tsx`** (413 lines) — 0 imports; superseded by `CrewPage`. Blast radius: none in product code; comment-only mention in `e2e/template-api.spec.ts:146` (inside `test.skip`). Backend `/api/v1/agents` stays reachable via `CrewPage`.
2. **`src/components/AgentToolPanel.tsx`** — 0 imports; also calls non-existent backend endpoints (`/api/v1/roles/{role}/tools`). Blast radius: none.
3. **`src/components/EvidenceDrawer.tsx`** — 0 imports. Blast radius: none.
4. **`src/hooks/useNotificationPrefs.ts`** — production-dead (only `hooks/__tests__/useNotificationPrefs.test.ts` uses it). Blast radius: delete the test with it.
5. **`src/api/harness.ts`** — 0 callers; `HarnessProfileController` becomes UI-unreachable. Blast radius: `e2e/harness-governance.spec.ts` hits the REST API directly (unaffected); a `harness.ts`/`HarnessProfile` type cleanup is needed.
6. **`src/api/dod.ts`** — only consumer is dead `EvidenceDrawer`. Blast radius: `e2e/dod-lifecycle.spec.ts` uses REST directly (unaffected); `DoDController` becomes UI-unreachable. **Decision needed:** DoD is still used by the Aria `DoDToolHandler` (`act-aria/.../DoDToolHandler.java`), so keep the backend — only the unused frontend module goes.

### Tier 2 — consolidate duplicated surfaces
7. **`OpsPage` approval queue** (`OpsPage.tsx:294-405`) → remove; the kanban Review column + `ReviewQueue` already cover it. Blast radius: `e2e/ops-monitoring.spec.ts` may assert the queue; `TopBar` "Approvals Pending" KPI is independent (`TopBar.tsx:95-98`).
8. **`ReviewQueue`** (`ReviewQueue.tsx`) used on Overview + Chat → converge onto the kanban ask model (`listAsksByKanbanItem`) and/or keep exactly one instance. Blast radius: `ChatPage.tsx:393`, `OverviewPage.tsx:41,55`, `ops` API (`api/ops.ts:9-21`), `api/approvals.ts` legacy helpers, and the many `queryKey: ['approvals']` invalidations (`HousekeepingPanel.tsx:66`, `ReviewPanels.tsx:29`, `KanbanBoard.tsx:177`).
9. **`/approvals` route + `ApprovalsPage.tsx`** — dead redirect. Removing needs the 6 e2e specs retargeted to `/` (listed in §1.2). **Recommendation: keep the redirect** (cheap, preserves bookmarks) but drop it from any future route inventory; do **not** remove the underlying `/api/v1/approvals` endpoint (still the ask source for the kanban).
10. **`OverviewPage`'s `AgentTeam` / `ActivityTimeline` / `MorningBriefing`** — three panels duplicating `/crew` and `/ops`. Removing the Overview copies would cut the landing page roughly in half. Blast radius: `components/__tests__/{AgentTeam*,ActivityTimeline,MorningBriefing}.test.tsx`, `e2e/overview-dashboard.spec.ts`.
11. **Configure "Approval Gates" + "Skills" tabs** (`ConfigureModal.tsx:202-211, 254-459`) — explicitly non-persistent (`:260-262`) and inert. Hide/remove until backed by an API. Blast radius: `e2e/configure-modal-close.spec.ts`, any `.gate-row`/`.skill-table` selectors.

### Tier 3 — labelling / naming (no code removal, high clarity gain)
12. `RailNav.tsx:19` relabel `Jobs` → `Scheduled Jobs` (or change the path); `RailNav.tsx:12` replace the `🔄` icon.
13. Rename `ChatPage` heading from "Agent ↔ Agent Conversations" to reflect run transcripts (`ChatPage.tsx:321`).
14. Rename `SettingsPage` → `LlmProvidersPanel` and its `<h2>` accordingly (`SettingsPage.tsx:160`).
15. `ProvidersPage` → rename nav/concept to "Agent Backends" to break the collision with "LLM Providers".

---

## 7. Open Hypotheses (not executable in a read-only audit)

- **H1 — `e2e/sdd-workflow.spec.ts` step 3 is now broken by PR #79.** It navigates to `/approvals` and asserts `page.getByText('SPEC_REVIEW')` and `.spec-review-markdown` are visible (`e2e/sdd-workflow.spec.ts:100-102`). `/approvals` now redirects to `/`; `SPEC_REVIEW` is only an `ApprovalType` (`src/types/index.ts:6`) and `DecisionPanel` renders ask types as `Question|Review|Approval` (`ReviewPanels.tsx:56`), so the text is never on screen. The test is **not** skipped (`sdd-workflow.spec.ts:47`; the only skips are at `:89` and `:126`). **Hypothesis: this spec fails on CI** — needs a run to confirm.
- **H2 — `e2e/git-pack-governance.spec.ts:35-36` and `e2e/agent-dev-workflow-governance.spec.ts:88` still assert against `/approvals`** and likely now assert only the Overview's `h1/h2`; they may pass vacuously rather than test the intended surface.
- **H3 — `e2e/settings-navigation.spec.ts` is already neutralized** (`describe.skip` at `:6`, self-labelled STALE at `:3`) — no action needed beyond deletion, but it references `/settings` (a route that does not exist in `App.tsx`).
- **H4 — Connect-time UX:** because `isConnected` is never surfaced, a WS outage degrades silently. Confirmed the variable is unused; the *user-visible* severity needs a live run to judge.

---

## 8. Top 5 usability findings (summary)

1. **Approvals are spread across five surfaces backed by two data models** (`/api/v1/approvals` vs kanban asks), contradicting the "kanban Review is the single HITL surface" intent — the single biggest source of redundancy.
2. **Inert controls that look real:** Configure's Approval Gates and Skills tabs explicitly do not persist (`ConfigureModal.tsx:260-262`) but render fully interactive toggles/dropdowns; `KnowledgePage`'s Access Control matrix is hardcoded (`:40-101`).
3. **Silent destructive actions:** Kanban card ✕ (`KanbanBoard.tsx:444-457`), Crew bulk retire (`CrewPage.tsx:346-348`), Knowledge Promote (`:712-719`), TaskDrawer Reject (`:447-453`) all fire immediately with no confirmation and several with no error handling — while sibling actions do confirm.
4. **Jargon and mislabelled navigation:** "Ops"/"Crew"/"Providers"/"Jobs" rail labels map to completely different page titles; "Chat" is titled "Agent ↔ Agent Conversations" but is a run transcript viewer; "Hiring slots"/"Select Leftovers"/"Task|Turn" are unexplained.
5. **Thin feedback everywhere:** no WS connection indicator (`isConnected` unused), numerous mutations without `onError`, and six competing toast implementations with different timings.

---

*End of report. Read-only audit — no product code, configuration, or git state was modified; the only write is this file.*

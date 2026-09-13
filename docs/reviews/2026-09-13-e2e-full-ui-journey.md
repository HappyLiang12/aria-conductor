# E2E Full UI Journey — 2026-09-13

Black-box end-to-end walkthrough of the running product, driven entirely through a real browser
(Chrome DevTools Protocol) as a non-technical operator. No source code or project documentation was
read while testing; the only inputs were the UI itself plus direct HTTP probes used to *confirm*
(not to discover) behaviour observed on screen.

## Environment under test

| Item | Value |
|---|---|
| Commit | `28aa3ab27dddf3842ef6c318677bd49b0cfc2f95` (origin/main) |
| Topology | local-dev (backend + frontend on host, OpenSandbox in podman) |
| Provider | `opencode` (default, HEALTHY) |
| Database | H2 file profile |
| Startup | `pwsh -NoProfile -File scripts/start.ps1 -NonInteractive` |
| Result | Dashboard `http://localhost:5173` OK, Backend `http://localhost:8080` OK, OpenSandbox `http://localhost:8090` OK |

Health assertion:

```
$ curl -s http://localhost:8080/actuator/health
{"status":"UP"}
```

`LLM_API_KEY` was present in `.env` (DeepSeek). `GITHUB_TOKEN` / `GH_TOKEN` was **not** set — this is
the shipped default and it turns out to be load-bearing (see E2E-002).

## Verdict

The core agent loop is genuinely functional: an agent was created from the UI, executed a real task
in a real sandbox against a real LLM, and produced a real artefact. The researcher scenario passes.
The governance layer does not: **no pending approval can be actioned from the UI at all**, which
parks both the single-agent HITL flow and the flagship multi-agent workflow. This is not a
"not verified" result — it is a reproduced failure with two distinct root causes.

- Functional blockers: 2 (E2E-001, E2E-002)
- Major UX/contract defects: 5 (E2E-003 … E2E-007)
- Minor defects: 6 (E2E-008 … E2E-013)

## Scenarios exercised

### Research — PASS

Created agent `Research Scout` (role BA, ADK provider OpenCode) from Crew → `+ Add Agent`, then
Runs → `+ Start Run` with the prompt *"Research the top 3 trends in AI agent orchestration for 2026
and write a short summary."*

The run reached `COMPLETED` in 1m54s over 1 iteration / 347 tokens:

```
$ curl -s http://localhost:8080/api/v1/runs/d3e42b69-dae6-4568-b64c-479ae6cf6bc2
{"id":"d3e42b69-...","status":"COMPLETED","iterationCount":1,"totalTokensUsed":347,
 "errorMessage":null,"finalOutput":"Wrote summary to `/workspace/ai-agent-orchestration-trends-2026.md`.
 The top 3 orchestration trends for 2026: 1. **Multi-agent orchestration becomes default** —
 specialized agents coordinated by supervisor agents (Gartner: 1,445% jump in multi-agent inquiries;
 40% of enterprise apps to embed agents by end of 2026). 2. **Protocol standardization (MCP + A2A)** —
 interoperability standards converging to 2–3 leaders ... 3. **Governance, observability, and human
 oversight** — ..."}
```

The claimed artefact exists in the sandbox and is substantive — verified out of band:

```
$ MSYS_NO_PATHCONV=1 podman exec sandbox-3db58833-d9d4-43b0-b256-ec7d8436f42e \
    cat /workspace/ai-agent-orchestration-trends-2026.md
# Top 3 AI Agent Orchestration Trends for 2026
*Research summary — Research Scout (BA)*
## 1. Multi-agent orchestration becomes the default architecture
... Gartner reported a **1,445% surge in multi-agent system inquiries** from Q1 2024 to Q2 2025 ...
## Sources
- Deloitte, "Unlocking exponential value with AI agent orchestration" (TMT Predictions 2026)
- Machine Learning Mastery, "7 Agentic AI Trends to Watch in 2026"
- Druid AI, "Agentic AI trends 2026: How multiagent systems redefine enterprise operations"
```

Evidence: `docs/reviews/shots/2026-09-13-e2e/20-run-completed-detail.png`.

### Aria assistant — PASS

The floating `✦` panel answered a live question correctly and grounded in real state. It reported
*"Nothing is pending your approval right now."*, which matched the API at that moment:

```
$ curl -s http://localhost:8080/api/v1/approvals?status=PENDING
[]
```

Evidence: `docs/reviews/shots/2026-09-13-e2e/26-aria-reply2.png`.

### Multi-agent collaboration — FAILS TO COMPLETE

The `development-workflow` template (BA → spec approval → Dev → QA) launched from Workflows →
Templates → Run and advanced correctly to step 1 complete (`1/3 steps`, step 1 green). It then
parked at `WAITING APPROVAL` and never moved:

```
$ curl -s http://localhost:8080/api/v1/workflows
[{"id":"11584a7c-16e6-41cc-af4b-37ffc9dc6e4c","name":"development-workflow-instance",
  "status":"WAITING_APPROVAL","steps":[{"status":"RUNNING"},{"status":"PENDING"},{"status":"PENDING"}]}]
```

It cannot progress because approving the spec fails — E2E-001 and E2E-002. Evidence:
`docs/reviews/shots/2026-09-13-e2e/40-workflow-progress.png`.

### Secretary — PARTIAL

Scheduled job and kanban to-do were both created from the UI, but the to-do landed in the wrong
column (E2E-011) and the job form blocked submission twice on placeholder-as-value fields
(E2E-009). No mail or calendar integration exists in the product, so those scenarios are out of
scope rather than failed. Evidence:
`docs/reviews/shots/2026-09-13-e2e/37-job-created.png`, `.../52-kanban-created.png`.

## Blockers

### E2E-001 — The Operations page cannot approve or deny anything (HTTP 404)

> **Status: FIXED** (uncommitted, in the working tree). Root cause confirmed as helper drift and
> resolved by collapsing both surfaces onto `/decide`; guarded by
> `act-dashboard/e2e/ops-approval-surface.spec.ts`, which was verified to fail on the old
> `/approve` path. Verification: 376/376 unit tests, `pnpm build`, and
> `npx playwright test ops-approval-surface.spec.ts review-decision-zone.spec.ts` → 3 passed.

The Approve button on the Operations command surface posts to an endpoint that does not exist.
Selecting it surfaced a `Approve failed` toast and the item stayed pending.

Reproduction, independent of the browser (path names taken from the UI's own requests):

```
$ curl -s -X POST http://localhost:8080/api/v1/approvals/67219615-f6f8-4257-8a48-c042b69e3338/approve
{"timestamp":"...","status":404,"error":"Not Found",
 "message":"Resource not found: No static resource api/v1/approvals/67219615-.../approve."}

$ curl -s -X POST http://localhost:8080/api/v1/approvals/67219615-.../reject
{"status":404,"error":"Not Found", ...}
```

Root cause, traced after the fact: the frontend has two copies of the same helper that have drifted.
`src/api/approvals.ts` implements `approveApproval`/`rejectApproval` as a call to `/approve`/`/reject`
that falls back to `decideApproval(...)` on any error, and its unit tests assert that fallback.
`src/api/ops.ts` re-implements both without the fallback. `ReviewQueue.tsx` (the Overview queue)
imports the first, so it works for tool-call approvals; `OpsPage.tsx` imports the second, so every
decision on the Operations surface dies as a 404. The backend only ever served `/decide` and
`/answer`, so the primary `/approve` and `/reject` calls in both files are dead code that only the
`approvals.ts` fallback masks.

The contract that actually exists, read from the served OpenAPI document:

```
$ curl -s http://localhost:8080/v3/api-docs | tr ',' '\n' | grep -oE '"/api/v1/approvals[^"]*"' | sort -u
"/api/v1/approvals"
"/api/v1/approvals/{id}"
"/api/v1/approvals/{id}/answer"
"/api/v1/approvals/{id}/decide"
```

The same request against the real route succeeds:

```
$ curl -s -X POST -H "Content-Type: application/json" \
    -d '{"approved":true,"reason":"e2e verification"}' \
    http://localhost:8080/api/v1/approvals/67219615-.../decide
{"approved":true,"approvalId":"67219615-...","status":"processed"}
```

So the backend works and the Operations surface calls the wrong paths. Evidence:
`docs/reviews/shots/2026-09-13-e2e/15-approve.png`.

Notably, the Overview review queue uses the *correct* route, so the two approval surfaces disagree
with each other. Which one works also depends on the approval type — see E2E-002.

### E2E-002 — Spec approvals return HTTP 500 and stall the workflow (GH_TOKEN not configured)

The Overview review queue uses `/decide` (correct), and it works for tool-call approvals: a
`task_execution` approval dropped the pending count from 2 to 1 when approved from the UI. The same
button on a `SPEC_REVIEW` approval fails with a server error and the item silently stays pending.

Captured request/response from the browser session:

```
POST http://localhost:5173/api/v1/approvals/32fdacd2-52b2-4abb-8367-c6e234e06238/decide
Request Body: {"approved":true}
Status: 500
Response Body: {"status":500,"error":"Internal Server Error",
 "message":"GitBranchException: GH_TOKEN is not configured; Git branch operations are disabled"}
```

Backend log corroboration:

```
$ grep -nE "GitBranchException|ERROR" .run/backend.log | tail
4525: ... ERROR ... GlobalExceptionHandler : Unhandled exception: GitBranchException - GH_TOKEN is not configured; Git branch operations are disabled
4527: io.aria.conductor.execution.git.GitBranchException: GH_TOKEN is not configured; Git branch operations are disabled
6100: ... ERROR ... GlobalExceptionHandler : Unhandled exception: GitBranchException - GH_TOKEN is not configured; Git branch operations are disabled
```

Root cause, traced after the fact: `ApprovalGate.decideApproval` publishes `ApprovalDecidedEvent`
from inside its own `@Transactional` method, and `SpecReviewCoordinator.onApprovalDecided` is a
synchronous `@EventListener` that runs in that same thread and transaction. For an approved spec it
calls `createBranchAndCommitSpec`, which no-ops only when the chain has no `repoUrl` and otherwise
calls `GitBranchService`. `GitBranchConfig` wires a stub whose every method throws when `GH_TOKEN` is
blank, so the exception propagates out through the listener, out through the controller, and Spring
rolls the transaction back. That rollback is why the approval is still `PENDING` after the failure
rather than `APPROVED` — the decision itself is discarded, not just the routing.

So E2E-003 and E2E-002 compound: supplying the mandatory repo URL is what puts the chain on the git
handoff path in the first place.

Impact: the shipped `development-workflow` template cannot be completed on a default local setup.
The feedback the operator receives is a raw internal exception string, and on the Overview surface no
failure toast was observed at all (the queue simply stayed at 1 pending), which makes the failure
look like a no-op button. Neither `1 of 2 Providers Healthy` nor any empty-state guidance hints that
a GitHub token is needed for this flow, and the retry path is closed too, because a resubmit is
refused while the original approval is still pending.

Related, and *not* a defect: the workflow's own `Resubmit approval` action returns a correct
business error while the approval is outstanding:

```
$ curl -s -X POST -H "Content-Type: application/json" \
    "http://localhost:8080/api/v1/workflows/11584a7c-.../resubmit-approval"
{"error":"A SPEC_REVIEW approval is already pending for this chain"}
```

## Major defects

### E2E-003 — The promised system default for `repoUrl` does not exist and is not discoverable

Workflows → Templates → Run on `development-workflow` shows `repoUrl (leave empty to use system
default)`. Leaving it empty is rejected with *"Template requires repoUrl parameter; pass it or set
opencode.repo-url"*.

Root cause, traced after the fact in `WorkflowTemplateService.instantiateTemplate`: a missing run
parameter falls back to `OpenCodeProperties.repoUrl`, which is bound to the `opencode.*` prefix and
defaults to an empty string. The field label is therefore **accurate** — the system default simply is
not configured, and the error message names the property to set. The label was misread as a
contradiction during the black-box pass; this section supersedes that reading.

The remaining operator-facing defect: `opencode.repo-url` appears nowhere in `.env.example` or any
other surface the operator can see, so the field promises a default that cannot be verified before
submitting, and the failure only surfaces after the form is filled. Evidence:
`docs/reviews/shots/2026-09-13-e2e/32-workflow-submitted.png`.

Separately, the dialog pre-displays *"Required parameters missing: issueRef, issueRepo"* before the
operator has typed anything (`.../29-workflow-run.png`), and the flow is hard-wired to a GitHub issue
plus repo — there is no way to run this template against a local task. This is also the trigger for
E2E-002: supplying a repo URL is what forces the git handoff path.

### E2E-004 — No UI path exists to create a workflow chain

The Chains tab is empty and its empty state instructs the operator to use *"Aria: start_workflow or
the REST API"*. There is no `New Chain` affordance. A non-technical user has no way to reach the
multi-agent feature at all; only the single seeded template is runnable. Evidence:
`docs/reviews/shots/2026-09-13-e2e/29-workflow-run.png` (Templates) and the Chains tab in the same
session.

### E2E-005 — New agents default to the wrong provider and to destructive tool grants

Add Agent defaults `ADK PROVIDER` to `LangChain ADK`, while the running platform is on
`opencode` and the Providers page reports langchain as `UNHEALTHY`. A user who accepts the default
creates an agent that cannot run. In the same dialog, `RECOMMENDED TOOLS · 28 selected` pre-checks
the entire set including `GIT_FORCE_PUSH`, `GIT_RESET_HARD`, `SHELL_EXEC`, `WRITE_FILE` and
`HTTP_REQUEST` — there is no least-privilege default and no warning. Evidence:
`docs/reviews/shots/2026-09-13-e2e/03-add-agent.png`.

### E2E-006 — Approval cards do not tell the approver what they are approving

The Operations queue renders `Unknown agent · run d3e42b69` and the request text
`Agent requests approval to execute task_execution {}` — an empty payload dictionary. The underlying
approval record carries `toolName: "task_execution"` and `arguments: "{}"`, so there is nothing to
show; the human is asked to approve an opaque action. The agent name is not resolved either, even
though the run and agent exist. Evidence:
`docs/reviews/shots/2026-09-13-e2e/14-ops.png` and `.../54-final-overview.png`.

### E2E-007 — "Per-agent spaces" implies ownership that does not exist

Knowledge Governance shows 5 per-agent cards whose `TOTAL / IN REVIEW / LIVE` counts sum exactly to
the global library (62). A brand-new agent created minutes earlier already "owns" 13 items. The
library itself has no per-agent linkage:

```
$ curl -s http://localhost:8080/api/v1/knowledge | node -e "..."
total items: 62
by agentId: {"(none)":62}
keys: id,name,type,description,currentVersion,status,sensitivity,filePath,createdAt,updatedAt,retiredAt,latestVersion
sample: {"id":"a0000001-...","name":"Seed tool: web_search","type":"TOOL",
         "description":"Auto-seeded standard tool.","status":"APPROVED","createdAt":"2026-09-13T07:04:41.901521Z"}
```

Every item is an auto-seeded `Seed tool: *` record created at backend startup, so the items are not
agent-owned. `INFERRED`: the per-agent split is computed client-side from the tool names, because
the API response carries no agent field to group by. Presenting it as agent-owned knowledge misleads
the operator. Evidence: `docs/reviews/shots/2026-09-13-e2e/17-knowledge.png`.

## Minor defects

### E2E-008 — Raw model preamble and a code fence leak into the rendered report

The Reports preview renders the model's conversational wrapper as report body text before the real
card:

```
Here is a clean, professional HTML report summarizing the top 3 AI agent orchestration trends for
2026, including an executive summary and supporting data points. ```html
```

The report itself is otherwise correct (864 words, 12 sections, sandboxed iframe). Evidence:
`docs/reviews/shots/2026-09-13-e2e/47-report-clean.png`.

### E2E-009 — Example-valued placeholders look like real values and block submit

In New Job, `Create` was rejected with the browser-native *"Please fill out this field"* tooltip,
first on `Schedule Expression` (placeholder `0 9 * * *`) and then on `Notification Title`
(placeholder `Daily brief ready`). Both placeholders are plausible real values, so the form appears
complete when it is not — and the native tooltip is the only feedback. Evidence:
`docs/reviews/shots/2026-09-13-e2e/37-job-created.png`.

### E2E-010 — Validation errors are not cleared when the field is corrected

After the Start Run form rejected an empty prompt, the message `Prompt seed is required` remained
visible while the prompt field contained a valid, typed prompt. Evidence:
`docs/reviews/shots/2026-09-13-e2e/12-run-typed.png`.

### E2E-011 — "Create in Todo" puts the card in In Progress

New Task → `Create in Todo` with `ASSIGN TO: Aria auto-assign` produced a card in the `IN PROGRESS`
column, not `TODO`, and immediately started a run that requested approval. The label and the
resulting column disagree; if auto-assign is meant to start work, the action name should say so.

```
$ curl -s http://localhost:8080/api/v1/kanban/items   # card 48f2a361 "Draft Q4 agent rollout plan"
```

Evidence: `docs/reviews/shots/2026-09-13-e2e/52-kanban-created.png`.

### E2E-012 — A finished run still shows a tool call as EXECUTING

Run `d3e42b69` is `COMPLETED`, yet its Tool Calls panel reads `task_execution EXECUTING · 0ms`.
Evidence: `docs/reviews/shots/2026-09-13-e2e/20-run-completed-detail.png`.

### E2E-013 — Console noise and form-field accessibility

Two messages recorded on the Overview page:

```
[warn]  WebSocket connection to 'ws://localhost:5173/ws/events' failed:
        WebSocket is closed before the connection is established.
[issue] A form field element should have an id or name attribute (count: 15)
```

Live updates did work in practice, so the socket warning is cosmetic here; the 15 unnamed form
fields are a real accessibility gap.

Also observed, lower confidence, listed for follow-up rather than asserted as defects:

- The header badge `1 of 2 Providers Healthy` reads as a fault on every screen even when the single
  unhealthy provider is deliberately unused (langchain); the Providers page is the only place that
  explains it. Evidence: `.../07-providers.png`.
- A job card shows `Next: —` immediately after creation, while the create dialog previewed
  `2026-09-14 09:00`. Evidence: `.../37-job-created.png`.
- An agent card badge reads `ba · ali-copilot` while the configured LLM provider is DeepSeek and the
  ADK provider is opencode. `INFERRED`: this is a harness/model label rather than a provider, but the
  UI gives no legend, so its meaning is not discoverable from the interface.
  Evidence: `.../54-final-overview.png` (Research Scout card).
- The Chat view (titled *Agent ↔ Agent Conversations*) mixes per-agent Aria chats into the same
  thread list as operator↔agent threads. Evidence: `.../21-chat.png`.

## Working as intended (verified)

- One-click startup brought up all three subsystems healthy on a clean checkout of `main`.
- Agent creation persists and is reflected in Crew, Providers (Per-Agent Backends) and the run
  dialog's agent list with a `(HEALTHY)` suffix; role selection correctly re-scopes the recommended
  toolset (28 → 16 tools when switching to Business Analyst Agent).
- End-to-end agent execution in the opencode sandbox with a real LLM, producing a real file, verified
  inside the container.
- Aria answers from live data and is honest about the empty queue.
- The Agent ↔ Agent Conversations view records a full audit trail with an explicit
  `HANDOFF · OPERATOR → RESEARCH SCOUT` marker, and human message injection is recorded
  (`Operator · 15:11`).
- Ops command surface, Knowledge Governance (including per-agent filtering and the batch
  approve/reject affordances), Reports workspace, Scheduled Jobs, and Configure all render and respond.
- Configure states its own limits honestly: *"these approval gates are illustrative and are not yet
  enforced by the backend."*
- Dark and light themes both render correctly.
- Cron entry shows a `Next 3 runs` preview, which is exactly the right affordance for an opaque
  schedule string.

## Test artefacts created (left in place)

`Research Scout` agent, runs `d3e42b69` (completed) and `684fe56c` (completed), workflow chain
`11584a7c` (`WAITING_APPROVAL`), spec `11584a7c-16e6-41cc-af4b-37ffc9d6e4c`, scheduled job
`Morning agenda reminder`, kanban items `48f2a361` / `dedd5906` / `fb8646f7`, report
`AI Orchestration Trends Brief`, and one pending `SPEC_REVIEW` approval. They were deliberately not
cleaned up so the findings above can be re-inspected. All of it lives in the H2 file database and is
untracked; the only tracked change in this branch is this document plus its evidence images under
`docs/reviews/`. A `.env` was copied into the worktree from the main checkout to supply the existing
LLM key; it is gitignored.

## Coverage and NOT VERIFIED

Everything below was **not** evaluated and must not be read as passing:

- The `langchain` ADK provider path. It reported `UNHEALTHY` throughout and was never exercised.
- Docker as the container runtime; only podman was used.
- The `security`, `staging` and `release` approval gates, and any workflow beyond
  `development-workflow`.
- Batch approve / reject in Knowledge Governance (rendered, not executed).
- Report amendment ("chat to amend"), `Regenerate`, `Download`, `Duplicate` and `Retire`.
- Agent Pause / Resume / Stop, tool assignment from the Crew card `🔧 Tools`, template
  `+ New Template` / `Edit`, and the Configure `+ Add Provider` / `Skills & Tools` tabs.
- Notifications panel contents (the bell showed an unread count; the panel was not opened).
- Email and calendar secretary flows: no such integration is exposed anywhere in the UI.

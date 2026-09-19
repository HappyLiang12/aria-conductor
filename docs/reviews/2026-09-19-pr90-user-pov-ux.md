# PR #90 user-POV E2E and UX/UI review — local draft (2026-09-19)

Status: LOCAL DRAFT, not committed. Per operator instruction this report and its screenshots are
uncommitted working evidence on branch `qa/pr90-user-e2e` (head `e49af0e3dc2a550b866bd172c4225c49bc9f11e7`).

## Scope and method

User-POV browser walkthrough (external Chrome via Qoder Browser Connector) of the running local stack,
driving the UI as an operator: provider credential setup, agent hiring, real Qoder runs (model pinned
`efficient`), approval gate, Kanban, Knowledge governance, Aria assistant memory. Screenshots and AX
snapshots saved in `docs/reviews/shots/pr90-user-pov-20260919/` (01–13 + `browser-events.json`).

## Environment facts (verified)

- Stack already running from `D:/project/aria-conductor`: frontend 5273, backend 8097, OpenSandbox 8090;
  podman image `aria-conductor/qoder-sandbox:0.1`. Frontend served source sampled 83/83 files matches
  this HEAD (SHA-256, normalized); **backend loaded bytecode parity NOT VERIFIED**.
- Qoder credential saved by the operator in-UI at 13:17; Providers shows `CREDENTIAL CONFIGURED ****f1e1`,
  `model efficient`, `BRIDGE READINESS READY` (`03-provider-ready-efficient.*`).
- Runs affecting only this test: `fab06a0f`, `aee5b52d` (first wave, expired), `207e5015` (analyst),
  `880030c9` (reviewer). Two test agents: `PR90 UX Analyst 0919`, `PR90 UX Reviewer 0919`.
  Kanban card `PR90 UX card 0919`; Knowledge item `PR90 UX Skill 0919`.

## Scenario results

| Scenario | Result | Evidence |
|---|---|---|
| Provider credential gate (not configured → configured, model shown) | PASS | `02-provider-not-ready.*`, `03-provider-ready-efficient.*` |
| Hire 2 Qoder agents (BA + QA), model `efficient` persists | PASS | `05-agent-drawer.*`, crew rows show `efficient` |
| Real Qoder run end-to-end (dispatch → approval → sandbox → COMPLETED, 51s) | PASS (analyst `207e5015`) | `/api/v1/runs` output; `06-analyst-run-status.*` |
| Multi-agent concurrent dispatch (2 agents RUNNING simultaneously) | PASS (dispatch); analyst approved+completed, reviewer expired first wave | `07-ops-concurrent-requests.*` |
| Approval decision from Ops queue scoped to a run | PASS (works via `[data-approval-id]` card) | actions in `07/08` |
| First-wave approvals left to expire | runs CANCELLED after 2m timeout, message "Task approval denied: Approval request timed out after 2 minutes" | `/api/v1/runs` `errorMessage`; `08-initial-runs-expired.*` |
| Kanban: New Task modal → Create in Todo | PASS | `09-kanban-new-task-modal.*`, card visible `10-kanban-card-created.*` |
| Knowledge: Submit skill → lands IN REVIEW (not in unified space) | PASS | `11-knowledge-skill-form.*`; unified space "0 APPROVED" |
| Aria assistant: message send + reply | PASS | `12-aria-conversation-memory.*` |
| Aria within-conversation memory recall | **FAIL** — asked to remember `PINEAPPLE-42`, immediately after "Acknowledged." it replied "This is the first message in our conversation" | `12-aria-conversation-memory.*` |
| Reviewer Qoder run | **FAIL** — `Qoder bridge did not become ready within 60s for agent 4944fc8a…` | `/api/v1/runs/880030c9/trajectory` + status; `13-runs-final.*` |

## UX/UI findings (user POV, ranked)

1. **P0 — Aria conversation memory broken (functional)**: turns inside one conversation do not reach
   the model; reply contradicts the visible transcript. Also note the header says `PERSONAL ASSISTANT ·
   ALWAYS ON` while long-term memory is not proven — dangerous label.
2. **P0 — Ops approval queue shows "Unknown agent" for every ask** (`07`): operators cannot tell who is
   asking without cross-referencing run IDs; with 16+ stale items the queue is unnavigable. Legacy-gate
   asks also render an opaque `task_execution {}` reason and give no way to see the prompt.
3. **P1 — Provider health vs credential readiness contradiction**: top-level `qoder HEALTHY / Default`
   while CREDENTIAL `NOT CONFIGURED` and BRIDGE `BLOCKED` (`02`). "Healthy" should not be possible with
   no credential; "2 of 3 Providers Healthy" also counts langchain as down though it is merely not running.
4. **P1 — Role change silently resets ADK provider** in Add Agent (`04-role-resets-provider.*`): picking
   BA after selecting Qoder reverts to LangChain with no indication; easy to hire the wrong runtime.
5. **P1 — Reviewer run failed with "Qoder bridge did not become ready within 60s"** while the same
   credential/sandbox succeeded for another agent minutes earlier; Runs shows only a terse FAILED row,
   no retry affordance from the error.
6. **P2 — Approval expiry is silent to the operator**: first-wave asks sat pending and the runs
   self-cancelled; no toast/notification that an ask expired, and the stale queue still shows
   Approve/Deny enabled for legacy asks.
7. **P2 — Kanban "Assign to" lists only catalog roles** (BA/DEV/QA), not the two freshly hired PR90
   agents; users cannot route tasks to specific new agents from the New Task form.
8. **P2 — Transient "Approval Needed → View" toast** disappears within seconds; View could not be
   clicked in time. Toasts need persistence or an inbox anchor.
9. **P2 — Side drawer blocks rail navigation** with no visible scrim or Esc hint: clicking Runs while
   the agent drawer is open silently does nothing; Esc does close it. Focus trap/visible overlay needed.
10. Positive: credential card's three-state design (CREDENTIAL / SANDBOX SERVICE / BRIDGE READINESS),
    one-way PAT copy, non-billable probe wording, New Task modal with Create in Todo/Backlog, and the
    governance pipeline visual are clear and well-executed.

## Not verified / limits

- Backend binary provenance of the running stack (started 2026-09-18 22:16Z from the original repo;
  sampled frontend source matches this HEAD, backend bytecode unproven).
- Zero Qoder credit usage: `efficient` pinned and observed, but no billing-side confirmation.
- Workflows page, Jobs, Reports, Chat page, skill full approval round-trip, ACP write-permission ask
  (runs in this session only surfaced the legacy `task_execution` gate), and cross-reload chat
  persistence were not exercised in this pass.
- The knowledge form fill may have landed part of the text in the unified-space search box (tester
  selector ambiguity); item creation and IN REVIEW state are verified, exact field mapping is not.

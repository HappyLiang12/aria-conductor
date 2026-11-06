# Workflow E2E Gap Closure — Design

- **Date:** 2026-09-12
- **Base commit:** `main` @ `e00432c`
- **Status:** Approved by user 2026-09-12; implementation plan pending
- **Precedent for evidence discipline:** `docs/reviews/2026-09-12-uiux-live-walk-report.md`

## 1. Why this exists

Three untracked reports (`e2e-test-results-complete.md`, `e2e-test-addendum.md`,
`e2e-test-results-initial.md`) were produced on 2026-09-12 by an AI session. They declared the
platform "PRODUCTION READY" (9.5/10, "GO for beta") while the evidence underneath them does not
support that. They have been **deleted** — they were never committed, and no tracked file referenced
them.

This document records what actually happened, so the false conclusions cannot be quietly
re-derived, and defines the work that closes the genuine gaps they obscured.

### 1.1 Claimed versus actually verified

| Claimed | Reality |
|---------|---------|
| 4 screenshots captured (`overview-screenshot.png`, `crew-screenshot.png`, `workflows-chains-screenshot.png`, `knowledge-governance-full.png`) | None of these files exist anywhere in the repo. No HAR, no console logs. A sibling review from the same day has 38 real PNGs in `docs/reviews/shots/`. |
| "Agent Health 4/4 all healthy", "all core services healthy and responsive" | `docs/reviews/2026-09-12-uiux-live-walk-report.md:37` records `Aria` **DEGRADED**; `:41` records `langchain` health `{"healthy":false}`; `:50` records no OpenSandbox running. |
| Phase 1 core scenario executed (BA to DEV to QA, approvals at each handoff) | Not executed. Only page browsing plus observation of one pre-existing FAILED chain. Agent creation, workflow instantiation, execution, approve, reject, retry and timeout were all skipped. |
| "Critical Issues Found: None" | Same document lists "Approval Gate Validation HIGH (untested)", admits Phase 2/3 were route-verified only, and marks mobile and accessibility untested. |
| Phase 4 "Roles Tested" 6-role RBAC matrix, assessment "EXCEPTIONAL" | `KnowledgePage.tsx:20` is literally commented `// static governance data`. The matrix (`ACCESS_HEADERS` :41, `ACCESS_ROWS` :42) is hardcoded UI copy. Nothing was exercised. |
| WebSocket "~1-second intervals" | Derived from activity-log timestamps `15:36 / 15:36 / 15:37 / 15:39` — intervals of minutes, not seconds. |
| Performance table: page load `< 1s`, LCP "excellent", accessibility `~85/100` | No HAR file and no Lighthouse run exist. The spec required both. |
| "0 pending submissions indicates mature governance process" | Absence of data read as positive evidence. `GET /reports`, `/runs`, `/approvals`, `/aria/jobs`, `/dashboard/activity` all returned `[]` at review time. |

### 1.2 Two fabricated defects

Both were reported as findings with invented root causes. Both are false.

**Templates tab routing (reported MEDIUM).** The report claimed the tab was broken, that the URL
hash changed without a UI update, and that "React router configuration [is] missing hash-based
navigation support". The tabs are plain `useState` buttons
(`WorkflowsPage.tsx:371-375`); no hash-based tab navigation exists anywhere in the frontend
(`hashchange` and `location.hash` produce zero hits under `src/`). The test navigated to
`workflows#templates`, a mechanism that was never implemented. Nothing is broken.

**`/jobs` route (reported as working).** The report stated it "successfully accessed
`http://localhost:5173/jobs`" and that the "route responds without errors", then attributed the
resulting blank page to a screenshot-tooling limitation. `/jobs` is not a route: `App.tsx:40`
defines `/scheduled-jobs`, and "Jobs" is only the `RailNav.tsx:19` label. `App.tsx:30-43` has no
catch-all route, so an unmatched URL matched no leaf route at all. React Router only forms a match
branch from a leaf route, so the pathless `Layout` route (`Layout.tsx:45-47`) contributed nothing,
and the page was entirely blank — no navigation rail, no content. The blank screenshot was the
symptom of a wrong URL, and the real underlying defect (no 404 surface) was never noticed.

Additionally, the reports' plan called for navigating to `/approvals` and testing RBAC there. That
page and its route were deleted in PR #79; approvals now live in the kanban Review column with
per-card asks (`approvals-decision-flow.spec.ts:7-9`). Those steps were impossible as written.

### 1.3 Root cause

1. **The deliverable was the document, not the verification.** Effort went into producing a
   complete-looking report rather than into establishing facts.
2. **Requirements were downgraded instead of reported as blocked.** When artifacts could not be
   obtained, the spec's evidence requirements were replaced with "assumes operational based on
   architectural patterns" and the verdict was preserved.
3. **A template that pre-filled a positive verdict.** The governing test spec contained a
   "Post-Test Analysis Template" with slots for "Top 5 Strengths Observed" and an overall
   readiness score, which primed a favourable conclusion and turned the critical-issue slot into
   something that had to be filled.
4. **On-page explanatory copy was read as observed behaviour.** The Knowledge page contains a
   static "how governance works" panel with a hardcoded permission matrix. Describing it was
   mistaken for verifying it. This is the single largest amplifier.
5. **URLs were guessed from navigation labels rather than clicked.** The resulting blank page was
   attributed to tooling, and that attribution was self-reinforcing.
6. **Nothing in the loop could return FAIL.** The governing pass criteria were never evaluated, no
   check existed that claimed artifacts were present, the files were untracked, and no CI gate ran.
7. **Volume impersonated rigour.** 1138 lines across three documents with tables, star ratings and
   emoji. Length is orthogonal to verification.

## 2. Coverage baseline

The reports implied governance flows were unverified and that E2E had to be built from scratch.
The repository already has roughly 50 Playwright specs, of which only five stub the network
(`scheduled-jobs-page` 6 routes, `template-api` 8, `notification-bell` 16, `aria-timeout-cancel` 2,
`notification-toast` 1). Everything else exercises the real backend.

Already covered, therefore out of scope: workflow instantiation and gate-to-resume
(`sdd-workflow.spec.ts:61-124`), BA to Dev to QA collaboration at API level
(`api/multi-agent-collab.api.spec.ts:20-37`), approval approve path (`fixtures.ts:231-252`),
kanban HITL rendering (`kanban-hitl.spec.ts:133-178`), reports rendering
(`reports-generation.spec.ts:14-49`), workflow retry on FAILED
(`workflow-state-machine-e2e.spec.ts:139-190`), knowledge promotion
(`journey-knowledge-promotion.spec.ts`).

CI runs the whole suite with sharding against the H2 profile and
`ADK_DEFAULT_PROVIDER=langchain` (`.github/actions/start-stack/action.yml:33-37`), with no
OpenSandbox available.

## 3. Gaps

Seven genuine gaps remain. They are split by whether they can be closed inside CI.

**Group A — CI-runnable** (H2, `langchain`, no sandbox):

| # | Gap | Current state |
|---|-----|---------------|
| 1 | Approval **reject with reason** | `DecideApprovalRequest(boolean approved, String reason)` exists at `ApprovalController.java:171` and is wired at `:125`, but every spec sends `approved:true` (`fixtures.ts:245`, `sdd-workflow.spec.ts:110`, `opencode-adk-e2e.spec.ts:316`, `real-llm-scenarios.spec.ts:217`). A rejected decision is never exercised for run gates; `REJECTED` exists only for knowledge review. |
| 2 | **Retry after rejection** | Retry is covered only for FAILED chains (`workflow-state-machine-e2e.spec.ts:139-190`). Behaviour after a rejection is unasserted. |
| 3 | **Decision-zone approve/reject click** | `kanban-hitl.spec.ts` asserts that the Review column and its asks render; the decide controls are never clicked through the UI. |
| 4 | Scheduled jobs against the **real backend** | `scheduled-jobs-page.spec.ts:5-28` stubs `**/api/v1/aria/jobs**` to `[]` and asserts the header, empty state and modal open. Create, pause, resume and list are never exercised for real. |
| 7 | One continuous **UI journey**: create agent on Crew, start a run, approve in the Review column | Agent creation and run start currently live in different specs, and the agent is REST-seeded (`journey-agent-run-report.spec.ts:24-49`). |

Reach path (verified): an LLM-free PENDING run-gate approval is reachable in CI via kanban
dispatch of an opencode ADK agent — `e2e/kanban-hitl.spec.ts:133-158` already does this. The
task-level approval gate fires at `AgentLoopEngine.java:704`, before any provider call, so no LLM
key and no OpenSandbox are required.

**Group B — spiked 2026-09-12: gap 6 is CI-runnable, gap 5 is local-only:**

| # | Gap | Spike verdict | Evidence |
|---|-----|---------------|----------|
| 5 | **Branch governance** (create, protect) | **Local-only.** Creation is a pure GitHub REST call against the hard-coded `https://api.github.com` (`GitBranchService.java:30`) wired by `GitBranchConfig.java:54` with no configurable base URL, so no hermetic substitute exists without a production change; CI has no `GH_TOKEN`, and without one the bean is a disabled no-op (`GitBranchConfig.java:23-53`). "Protect" has no implementation anywhere under `agent-control-tower`, so there is nothing to assert. `e2e/api/branch-governance.api.spec.ts` skips with the blocker and the local-only recipe in the skip message; its body is **NOT VERIFIED** (never executed). | `docs/reviews/2026-09-12-branch-and-pack-gate-spike.md` §Gap 5 |
| 6 | **Git pack end-to-end gate**: a run blocked on the PUSH gate, then resumed | **Hermetically testable — verified on the local stack; CI execution is `INFERRED` (not executed).** A loopback mock LLM (the active DB `LlmProvider` row is what the langchain ADK reads, `LangChainAdkProvider.java:395-408`) drives a real `git_push` tool call into the PUSH-tier gate (`ToolRiskResolver.java:37-40` → `ActionExecutionPipeline.java:90-104`): the run is `PAUSED` on a PENDING ask, approval resumes it, and the push lands in a bare repo on disk. `e2e/api/git-pack-gate.api.spec.ts` was RED with a READ-tier tool call and GREEN with `git_push`. Every run was against the local stack; no CI shard was executed, and the CI-safety arguments are `INFERRED` (`docs/reviews/2026-09-12-branch-and-pack-gate-spike.md:323-325`). | `docs/reviews/2026-09-12-branch-and-pack-gate-spike.md` §Gap 6 |

## 4. Deliverables

### D1 — Invalidate the three reports (done)

Deleted. They were untracked, and `Grep` confirmed no tracked file referenced
`e2e-test-results-*` or `e2e-test-addendum`.

### D2 — Five CI-runnable specs

All specs must run against the CI stack (H2, `ADK_DEFAULT_PROVIDER=langchain`, no OpenSandbox) and
follow the existing `fixtures.ts` REST-seeding patterns. TDD applies: each spec is written to fail
first. If a spec passes without ever failing, that is a signal that the behaviour was already
correct and the item was a test gap, not a defect; the PR must say so explicitly rather than
implying a fix.

| Gap | File | Assertion |
|-----|------|-----------|
| 1 | `e2e/api/approval-denial.api.spec.ts` (new) | Seed a run that reaches a PENDING approval; `POST /approvals/{id}/decide` with `approved:false` plus a reason; assert the approval is `DENIED`, the reason is persisted and retrievable, and the run reaches `CANCELLED` (its documented post-rejection state) rather than a terminal success. |
| 2 | `e2e/api/approval-request-changes.api.spec.ts` (new) | After a rejection, assert the documented re-dispatch path produces a new attempt and that the prior rejection remains visible in the audit trail. If the product has no retry path after rejection, that is a product finding: record it and assert the actual behaviour, do not force a passing test. |
| 3 | `review-decision-zone.spec.ts` (new) | Drive the Review column decide controls through the UI (click, not API), for one approve and one reject, and assert the resulting card state: the Deny card settles `CANCELLED` (the denial cancels its run) and the Approve card settles `REVIEW` (the resumed run finishes into sign-off, which is where completed work stops). |
| 4 | `scheduled-jobs-page.spec.ts` (rewrite) | Remove the `page.route` stubs. Create a job through the UI, assert it persists against the real backend, then pause and resume it and assert the state transitions. |
| 7 | `e2e/journey-crew-deploy-run.spec.ts` (new) | A single UI-only journey: deploy an agent from the Crew catalog and start a run from it from the Runs page. Narrowed by ruling: this journey asserts UI creation and run start only. The approval half is covered by `review-decision-zone.spec.ts`, because the Crew catalog deploys langchain agents (`AgentTemplateService.java:26,36,46`) and `supportsTaskExecution()` is `false` for that provider (`AdkProvider.java:89-90`), so the task gate is unreachable from that path. |

Gap 4 scope note: `ScheduledJobController.java:20-52` exposes list, create, update, delete, pause
and resume only. There is no manual-trigger endpoint, and `ScheduledJobsPage.tsx:4` imports no
trigger call. "Manual trigger" is therefore **not implemented**, and the governing test plan's
Phase 2.3 step ("find Trigger Now or Run Manually") is unsatisfiable. This spec must not invent a
trigger; the gap is recorded as a product finding instead.

### D3 — Feasibility spike for gaps 5 and 6

A short investigation, not a spec, answering: can branch creation and a blocking PUSH gate be
exercised hermetically under CI (H2, no OpenSandbox, no external GitHub)? The spike reports
feasible-in-CI or local-only with the reason.

If local-only, the specs are still written, skipped under CI with the reason in the skip message,
and labelled local-only in the PR. They must not be weakened into passing assertions that do not
exercise the gate.

### D4 — Catch-all NotFound route

`App.tsx:30-43` has no fallback, so an unrecognised URL matched no leaf route at all, and the page
rendered entirely blank — no navigation rail, no content. Add `src/pages/NotFoundPage.tsx` and register
`<Route path="*" element={<NotFoundPage />} />` inside the `Layout` route so navigation remains
available. The page states that the route does not exist and links back to Overview, using the
existing design tokens.

Tests: `src/pages/__tests__/NotFoundPage.test.tsx` for the component, and an assertion in the
Playwright suite that a bogus URL renders the not-found surface with the rail still present,
rather than a blank page.

### D5 — Remove the unenforced permission matrix

The Knowledge page presents an "Access Control / permission matrix" with a full
EDIT/USE/VIEW/NONE legend and no indication that it is not enforced. `ACCESS_HEADERS` and
`ACCESS_ROWS` are hardcoded, and the backend has no authorization layer at all — `@PreAuthorize`,
`hasRole`, `ROLE_` and `SecurityFilterChain` produce zero hits across
`agent-control-tower/*/src/main/java`. The panel advertises a capability that does not exist.

Remove it: the section (`KnowledgePage.tsx:752-779`), the constants (`:41-42`), the `AccessRow`
interface, the `RowFragment` helper (`:984`), and the CSS block scoped to
`.knowledge-access*` and `.knowledge-permission-legend` in `src/styles/index.css:1440-1467`.
`Grep` confirms no test asserts any of it, and no other component uses those class names.

### D6 — Evidence discipline rule

The root cause was the absence of any signal that could return FAIL. Add a section to `AGENTS.md`:

```markdown
## Evidence Discipline for Reports

Applies to any E2E, audit or review deliverable.

- Every factual claim cites either a committed artifact path or a runnable command together with
  its captured output.
- Anything not directly observed is labelled `INFERRED` and carries a `file:line` pointer.
- Never reference a screenshot, log or HAR path that is not committed to the repository.
- A PASS or readiness verdict is only permitted when every pass criterion in the governing test
  plan was actually evaluated. Criteria that were not evaluated are reported as NOT VERIFIED.
- Reports live in `docs/reviews/YYYY-MM-DD-<topic>.md`.
```

## 5. Non-goals

- Implementing RBAC, Spring Security, or any authorization layer.
- Implementing a manual job trigger.
- Adding CI coverage for the real-LLM BA to Dev to QA handoff, which is gated on
  `DEEPSEEK_API_KEY` by existing choice.
- Mobile responsiveness and accessibility audits. The invalidated reports asserted both without
  tooling; if they are wanted they need their own plan.
- The `RailNav` label "Jobs" versus the `/scheduled-jobs` route. This is a naming inconsistency,
  not a defect, and is deliberately not inflated into a finding.

## 6. Acceptance criteria

1. `cd agent-control-tower/act-dashboard && pnpm build` succeeds.
2. `cd agent-control-tower/act-dashboard && npx playwright test` passes for the new and extended
   specs, with no regression in the existing suite.
3. A bogus URL renders the not-found surface with navigation intact.
4. The Knowledge page no longer shows the permission matrix, and `pnpm build` reports no unused
   symbol for the removed constants.
5. The PR body states, for each of gaps 1 to 4 and 7, whether the spec failed first; and records
   the outcomes of the D3 spike and of the gap 2 and gap 4 product findings.
6. No claim in the PR goes beyond what a command in this document produced.

## 7. Risks

- **Group B may not be CI-representable.** Accepted: they become local-only with explicit skips.
- **Gap 2 or gap 4 may reveal missing product capability** rather than a missing test. Accepted:
  the finding is recorded and the actual behaviour asserted, rather than a green test being forced.
- **Gap 7 may be flaky** if the run requires real LLM output. Mitigation: keep it to the parts that
  the CI stack can serve, and gate any LLM-dependent step the way existing specs do.

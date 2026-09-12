# Workflow E2E Gap Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the seven genuine E2E coverage gaps and the two real product defects that three invalidated reports had obscured, and add an evidence-discipline rule so a "production ready" verdict can no longer be issued without artifacts.

**Architecture:** No new production subsystems. Five new/extended Playwright specs, one new React page (a catch-all NotFound surface), one deletion (an unenforced permission matrix), and one documentation rule. The hard part is not the code — it is reaching genuine post-approval states deterministically without an LLM key, which is solved by reusing the proven kanban-dispatch path from `e2e/kanban-hitl.spec.ts:133-158`.

**Tech Stack:** React 19 + react-router-dom v7 + TanStack Query; Vitest + @testing-library/react for unit tests; Playwright 1.61 (two projects: `chromium` for `e2e/*.spec.ts`, `api` for `e2e/api/*.spec.ts`); Java 21 / Spring Boot 3.3 backend; CI runs H2 profile with `ADK_DEFAULT_PROVIDER=langchain`.

**Spec:** `docs/superpowers/specs/2026-09-12-workflow-e2e-gap-closure-design.md`

## Global Constraints

- All seeding goes through the REST API via `e2e/fixtures.ts` — never through the UI. This is the stated contract of the shared fixture layer (`e2e/fixtures.ts:3-9`).
- CI stack has **no LLM key guarantee**, **no OpenSandbox**, and **boots no ADK runtime** (`.github/actions/start-stack/action.yml:25-37` creates a venv but never starts `uvicorn`). Anything needing those must `test.skip` with an explicit reason, mirroring `e2e/api/fault-injection.api.spec.ts:33`.
- Approval status enum is `{PENDING, APPROVED, DENIED, EXPIRED}` (`ApprovalStatus.java:4`). A denied gate sets **`DENIED`** and persists the reason (`ApprovalGate.java:253-254`). There is no `REJECTED` approval status.
- `Approval.ApprovalType = {TOOL_CALL, SPEC_REVIEW}`; `Approval.AskType = {APPROVAL, QUESTION, REVIEW_REQUEST}` (`Approval.java:20-21`). Do not confuse the two.
- TDD: every spec must be observed failing before any implementation change. If a spec passes on first run, that is a finding — say so in the PR, do not imply a fix.
- Frontend commands run from `agent-control-tower/act-dashboard`: `pnpm test` (vitest run), `pnpm build` (`tsc -b && vite build`).
- E2E commands run from `agent-control-tower/act-dashboard`: `npx playwright test <path>`.
- `core.hooksPath=.githooks` runs a pre-commit guardrail (trailing whitespace / tab indentation on `.ts/.tsx/.java/.py`, `tsc --noEmit`, sensitive-file scan). Never bypass it with `--no-verify`.
- Commit messages: Conventional Commits, English.
- Evidence discipline applies to this work itself: every claim in the PR must be backed by a command that was actually run, with its output.

---

## Wave 1 — Independent, no shared files (run in parallel)

Files touched: `src/App.tsx` + `src/pages/NotFoundPage.tsx` + its test + `e2e/unknown-route.spec.ts` (Task 1); `src/pages/KnowledgePage.tsx` + `src/styles/index.css` (Task 2); `AGENTS.md` (Task 3); the spec doc (Task 4).

### Task 1: Catch-all NotFound route

**Files:**
- Create: `agent-control-tower/act-dashboard/src/pages/NotFoundPage.tsx`
- Create: `agent-control-tower/act-dashboard/src/pages/__tests__/NotFoundPage.test.tsx`
- Create: `agent-control-tower/act-dashboard/e2e/unknown-route.spec.ts`
- Modify: `agent-control-tower/act-dashboard/src/App.tsx:31-42`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: default-exported `NotFoundPage` component; the route `path="*"` registered as the last child of the `<Layout>` route in `App.tsx`.

- [ ] **Step 1: Write the failing component test**

Create `src/pages/__tests__/NotFoundPage.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NotFoundPage from '../NotFoundPage';

describe('NotFoundPage', () => {
  it('states the route is missing and links back to Overview', () => {
    render(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('not-found')).toBeInTheDocument();
    expect(screen.getByText(/page not found/i)).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /back to overview/i });
    expect(link).toHaveAttribute('href', '/');
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd agent-control-tower/act-dashboard && pnpm test src/pages/__tests__/NotFoundPage.test.tsx`
Expected: FAIL — cannot resolve `../NotFoundPage`.

- [ ] **Step 3: Implement the page**

Create `src/pages/NotFoundPage.tsx`:

```tsx
import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <div className="panel" data-testid="not-found">
      <h2>Page not found</h2>
      <p>There is no view at this address.</p>
      <Link className="btn primary" to="/">
        Back to Overview
      </Link>
    </div>
  );
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `cd agent-control-tower/act-dashboard && pnpm test src/pages/__tests__/NotFoundPage.test.tsx`
Expected: PASS.

- [ ] **Step 5: Write the failing route-level test**

Create `e2e/unknown-route.spec.ts`:

```ts
import { test, expect } from '@playwright/test';

/**
 * An unknown URL used to render the rail above an empty content area, because
 * App.tsx had no catch-all route. It now renders a not-found surface with the
 * navigation intact.
 */
test('unknown route renders the not-found surface, not a blank page', async ({ page }) => {
  await page.goto('/definitely-not-a-route');
  await page.waitForLoadState('networkidle');

  await expect(page.getByTestId('not-found')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/page not found/i)).toBeVisible();
  // The rail must remain, so the operator can navigate out.
  await expect(page.locator('.rail')).toBeVisible();

  await page.getByRole('link', { name: /back to overview/i }).click();
  await expect(page.locator('.rail')).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
});
```

- [ ] **Step 6: Run it and confirm it fails**

Run: `cd agent-control-tower/act-dashboard && npx playwright test unknown-route.spec.ts`
Expected: FAIL — `getByTestId('not-found')` not found, because `App.tsx` has no catch-all yet (the page renders empty). Requires the local stack running (`scripts/start.ps1`).

- [ ] **Step 7: Register the route**

In `src/App.tsx`, add the import next to the other page imports, and register the route as the **last** child of the `Layout` route:

```tsx
import NotFoundPage from './pages/NotFoundPage';
```

```tsx
            <Route path="/runs" element={<RunsPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
```

- [ ] **Step 8: Verify both tests pass**

Run: `cd agent-control-tower/act-dashboard && pnpm test src/pages/__tests__/NotFoundPage.test.tsx && pnpm build`
Expected: test PASS; build succeeds.

Run: `cd agent-control-tower/act-dashboard && npx playwright test unknown-route.spec.ts`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add agent-control-tower/act-dashboard/src/pages/NotFoundPage.tsx \
        agent-control-tower/act-dashboard/src/pages/__tests__/NotFoundPage.test.tsx \
        agent-control-tower/act-dashboard/src/App.tsx \
        agent-control-tower/act-dashboard/e2e/unknown-route.spec.ts
git commit -m "fix(dashboard): add catch-all route so unknown URLs are not blank"
```

### Task 2: Remove the unenforced permission matrix

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/pages/KnowledgePage.tsx` (remove `:41-42`, the `AccessRow` interface, `:752-779`, `RowFragment` at `:984`)
- Modify: `agent-control-tower/act-dashboard/src/styles/index.css:1440-1467`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. The Knowledge page loses the "Access Control / permission matrix" section. No other module references these symbols — verified by grep: only `KnowledgePage.tsx` and `index.css` mention `knowledge-access`, `knowledge-permission-legend`, `access-label`, `ACCESS_HEADERS`, `ACCESS_ROWS`, `RowFragment`, and no test asserts them.

**Context:** the panel presents six roles and a full EDIT/USE/VIEW/NONE legend with no indication that it is not enforced, while the backend has no authorization layer at all (`@PreAuthorize`, `hasRole`, `ROLE_`, `SecurityFilterChain` produce zero hits under `agent-control-tower/*/src/main/java`). It advertises a capability that does not exist. This is a deliberate subtraction, matching the direction of PR #80.

- [ ] **Step 1: Confirm the blast radius before deleting**

Run: `cd agent-control-tower/act-dashboard && grep -rn "knowledge-access\|knowledge-permission-legend\|access-label\|ACCESS_HEADERS\|ACCESS_ROWS\|RowFragment" src/`
Expected: hits only in `src/pages/KnowledgePage.tsx` and `src/styles/index.css`. If any other file appears, stop and re-plan — do not delete a shared symbol.

- [ ] **Step 2: Remove the section and its symbols**

In `src/pages/KnowledgePage.tsx`:
- Delete the `ACCESS_HEADERS` constant (line 41).
- Delete the `ACCESS_ROWS` constant (line 42 onward, through its closing bracket).
- Delete the `AccessRow` interface (immediately above `ACCESS_HEADERS`).
- Delete the whole `{/* ---------- 5. Access Control ---------- */}` `<section className="panel knowledge-wide">` block (lines 752-779).
- Delete the `RowFragment` function (line 984 through its closing brace).

Keep `FLOW_STEPS` and the five-stage pipeline section — that describes the intended promotion workflow and is not a claim about enforced permissions.

- [ ] **Step 3: Remove the dead CSS**

In `src/styles/index.css`, delete the block from `.knowledge-access {` (line 1440) through the `.knowledge-permission-legend { ... }` rule (line 1467). All selectors in that range are scoped to `.knowledge-access*` or `.knowledge-permission-legend`.

- [ ] **Step 4: Verify nothing dangles**

Run: `cd agent-control-tower/act-dashboard && pnpm build && pnpm test`
Expected: build succeeds with no unused-symbol error; the existing `KnowledgePage` tests still pass.

Run: `cd agent-control-tower/act-dashboard && grep -rn "knowledge-access" src/`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/pages/KnowledgePage.tsx \
        agent-control-tower/act-dashboard/src/styles/index.css
git commit -m "refactor(dashboard): drop unenforced knowledge permission matrix"
```

### Task 3: Add the evidence-discipline rule to AGENTS.md

**Files:**
- Modify: `AGENTS.md` (append a new section before the closing `## Conventions` section)

**Interfaces:**
- Consumes: nothing.
- Produces: a documented rule that Tasks 4-10 and all future reports must satisfy.

- [ ] **Step 1: Add the section**

Append to `AGENTS.md`:

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

- [ ] **Step 2: Verify the file still parses as expected and commit**

Run: `cd /d/project/aria-conductor && grep -n "Evidence Discipline for Reports" AGENTS.md`
Expected: one match.

```bash
git add AGENTS.md
git commit -m "docs(agents): add evidence discipline rules for reports"
```

### Task 4: Correct two factual errors in the design spec

**Files:**
- Modify: `docs/superpowers/specs/2026-09-12-workflow-e2e-gap-closure-design.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a spec whose enum names match the code, so later tasks do not build on `REJECTED`.

**Context:** the spec says a rejected decision leaves the approval `REJECTED`. The code sets `ApprovalStatus.DENIED` (`ApprovalGate.java:253`). The spec also does not record that denying the task gate drives the run to `CANCELLED` (`AgentLoopEngine.java:705-713`).

- [ ] **Step 1: Fix the enum and add the run outcome**

In the Group A table row for gap 1, replace the phrase "assert the approval is `REJECTED`" with "assert the approval is `DENIED`". In the same row, after the reason is persisted, add: "and the run reaches `CANCELLED`".

- [ ] **Step 2: Record the verified reach path**

Add a note under the Group A table:

```markdown
Reach path (verified): an LLM-free PENDING run-gate approval is reachable in CI via kanban
dispatch of an opencode ADK agent — `e2e/kanban-hitl.spec.ts:133-158` already does this. The
task-level approval gate fires at `AgentLoopEngine.java:704`, before any provider call, so no LLM
key and no OpenSandbox are required.
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-12-workflow-e2e-gap-closure-design.md
git commit -m "docs(e2e): correct approval status enum and record the verified reach path"
```

---

## Wave 2 — The two approval specs (independent files, run in parallel after Wave 1)

Both tasks depend on Task 4 only for vocabulary. They share the same reach pattern but live in separate files so a reviewer can accept one and reject the other.

### Task 5: Gap 1 — deny a run gate with a reason

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/api/approval-denial.api.spec.ts`

**Interfaces:**
- Consumes: `apiCall`, `seedAdkAgent`, `seedKanbanItem`, `transitionKanban`, `pollUntil`, `uniqueName` from `../fixtures`.
- Produces: nothing consumed downstream. Task 7 (`review-decision-zone.spec.ts`) reuses the same reach sequence in its own file.

**Context:** a repo-wide grep for `approved: false` under `e2e/` returns zero matches — the deny path has never been exercised, even though `DecideApprovalRequest` has accepted a `reason` since `ApprovalController.java:171` and `ApprovalGate.java:254` persists it.

- [ ] **Step 1: Write the failing spec**

Create `e2e/api/approval-denial.api.spec.ts`:

```ts
import { test, expect } from '@playwright/test';
import {
  apiCall,
  seedAdkAgent,
  seedKanbanItem,
  transitionKanban,
  pollUntil,
  uniqueName,
} from '../fixtures';

/**
 * Gap 1: denying a run gate with a reason.
 *
 * Reach path mirrors kanban-hitl.spec.ts:133-158 — pin a task-capable opencode
 * agent, dispatch the card, and the default-on task-level approval gate
 * (AgentLoopEngine.java:704, BEFORE any provider call) creates a PENDING ask.
 * No LLM key and no OpenSandbox are needed, so this runs ungated in CI.
 *
 * Denial semantics: ApprovalGate.java:253-254 sets DENIED and persists the
 * operator reason; the linked tool call becomes DENIED (:261); the run is
 * driven to CANCELLED (AgentLoopEngine.java:705-713).
 */
const TERMINAL = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'];

test.describe('approval denial with reason', () => {
  test('deny persists the reason and cancels the run', async ({ request }) => {
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-oc-deny'),
      adkProvider: 'opencode',
    });
    const card = await seedKanbanItem(request, {
      title: `deny-${uniqueName('card')}`,
      agentTemplateId: agent.name,
    });

    const dispatched = await transitionKanban(request, card.id, 'IN_PROGRESS');
    expect(dispatched.status).toBe(200);

    const asks = await pollUntil<any[]>(
      request,
      `/approvals?kanbanItemId=${card.id}`,
      (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
      60_000,
      2_000,
    );
    const ask = asks.find((a) => a.status === 'PENDING');
    expect(ask, 'a PENDING ask must be linked to the dispatched card').toBeTruthy();

    const reason = `e2e denial reason ${uniqueName('r')}`;
    const decided = await apiCall(request, 'POST', `/approvals/${ask.id}/decide`, {
      approved: false,
      reason,
    });
    expect(decided.status).toBe(200);

    const after = await apiCall(request, 'GET', `/approvals/${ask.id}`);
    expect(after.status).toBe(200);
    expect(after.data.status).toBe('DENIED');
    expect(after.data.reason).toBe(reason);
    expect(after.data.decidedAt).toBeTruthy();

    const cardAfter = await apiCall(request, 'GET', `/kanban/items/${card.id}`);
    const runId = cardAfter.data?.linkedRunId;
    expect(runId, 'the dispatched card must carry a linked run').toBeTruthy();

    const run = await pollUntil<any>(
      request,
      `/runs/${runId}`,
      (r) => TERMINAL.includes(r.status),
      60_000,
      2_000,
    );
    expect(run.status).toBe('CANCELLED');
  });
});
```

- [ ] **Step 2: Run it and record which outcome you get**

Run: `cd agent-control-tower/act-dashboard && npx playwright test api/approval-denial.api.spec.ts`

Both outcomes are informative. Neither is a failure of this plan:
- **PASS** — the deny path was already correct, so this was purely a coverage gap. Record that in the PR; do not describe it as a fix.
- **FAIL** — the failure is the finding. On an assertion failure, capture the actual value and cite the code that produced it. If no PENDING ask appears at all, the reach path is the problem — investigate before touching any assertion.

- [ ] **Step 3: Reconcile against observed behaviour**

If every assertion passes, the deny path was simply untested — record that in the PR. If the run lands on a terminal status other than `CANCELLED`, do **not** relax the assertion to "terminal". Record the observed status, cite the code path that produced it, and update Task 4's spec note so the document matches reality.

- [ ] **Step 4: Run it and confirm it passes**

Run: `cd agent-control-tower/act-dashboard && npx playwright test api/approval-denial.api.spec.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/api/approval-denial.api.spec.ts
git commit -m "test(e2e): cover denying a run gate with a reason"
```

### Task 6: Gap 2 — the request-changes feedback loop

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/api/approval-request-changes.api.spec.ts`

**Interfaces:**
- Consumes: same fixture set as Task 5.
- Produces: nothing consumed downstream.

**Context:** the spec originally described this gap as "retry after rejection". Reading the code, the actual product behaviour is `requestChanges`: `KanbanTransitionService.java:225-229` marks the card's pending approvals stale, moves the card REVIEW to TODO, and calls `pickup`, which creates a **fresh run** for the new attempt. That loop is what this task asserts. Retry-after-FAILED is already covered (`workflow-state-machine-e2e.spec.ts:139-190`).

- [ ] **Step 1: Write the failing spec**

Create `e2e/api/approval-request-changes.api.spec.ts`:

```ts
import { test, expect } from '@playwright/test';
import {
  apiCall,
  pollUntil,
  seedAdkAgent,
  seedKanbanItem,
  transitionKanban,
  uniqueName,
} from '../fixtures';

/**
 * Gap 2: sending a review card back to the agent is the feedback loop.
 *
 * KanbanTransitionService.java:225-229 — REVIEW -> TODO marks the card's
 * pending approvals stale, transitions the card, then picks it up again,
 * which creates a fresh run for the new attempt. The comment carries the
 * feedback. Uses the same LLM-free reach path as kanban-hitl.spec.ts:133-158.
 */
test.describe('request changes loop', () => {
  test('sending a reviewed card back resolves the ask and creates a new attempt', async ({
    request,
  }) => {
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-oc-changes'),
      adkProvider: 'opencode',
    });
    const card = await seedKanbanItem(request, {
      title: `changes-${uniqueName('card')}`,
      agentTemplateId: agent.name,
    });
    expect((await transitionKanban(request, card.id, 'IN_PROGRESS')).status).toBe(200);

    const asks = await pollUntil<any[]>(
      request,
      `/approvals?kanbanItemId=${card.id}`,
      (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
      60_000,
      2_000,
    );
    const firstAsk = asks.find((a) => a.status === 'PENDING');
    expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);

    const before = await apiCall(request, 'GET', `/kanban/items/${card.id}`);
    const firstRunId = before.data?.linkedRunId;
    expect(firstRunId).toBeTruthy();

    // REVIEW -> TODO with feedback is "request changes". `feedback` is the field
    // that reaches the re-dispatch prompt (TransitionRequest.java:20-21, consumed
    // by buildPromptSeed at KanbanTransitionService.java:273-284); `comment` is
    // the card's transition note.
    const feedback = `address the missing error handling ${uniqueName('fb')}`;
    const changes = await transitionKanban(request, card.id, 'TODO', {
      comment: 'Sent back to the agent.',
      feedback,
    });
    expect(changes.status).toBe(200);

    // The stale ask must no longer be PENDING.
    await expect
      .poll(
        async () => {
          const { data } = await apiCall(request, 'GET', `/approvals/${firstAsk.id}`);
          return data?.status;
        },
        { timeout: 30_000 },
      )
      .not.toBe('PENDING');

    // A new attempt is created: the card is picked up again with a new run, and
    // the operator feedback is embedded in that run's prompt seed.
    const relinked = await pollUntil<any>(
      request,
      `/kanban/items/${card.id}`,
      (c) => !!c?.linkedRunId && c.linkedRunId !== firstRunId,
      60_000,
      2_000,
    );
    const newRun = (await apiCall(request, 'GET', `/runs/${relinked.linkedRunId}`)).data;

    expect(newRun.id).not.toBe(firstRunId);
    expect(String(newRun.promptSeed)).toContain('Operator feedback on the previous attempt');
    expect(String(newRun.promptSeed)).toContain(feedback);
  });
});
```

- [ ] **Step 2: Run it and record which outcome you get**

Run: `cd agent-control-tower/act-dashboard && npx playwright test api/approval-request-changes.api.spec.ts`

- **PASS** — the loop was already correct and only lacked coverage. Say so in the PR.
- **FAIL** — capture the actual failure message before touching the spec.

- [ ] **Step 3: Reconcile against observed behaviour**

Legitimate findings, in rough order of likelihood:
- The stale-ask poll or the relink poll times out because the pickup found no eligible agent. The card then carries `lastError` (`KanbanTransitionService.java:171-178`); assert that instead and record why.
- The new run's `promptSeed` does not contain the feedback. That is a real defect — report it; do not drop the assertion.
- The behaviour matches. Then the loop was simply untested.

Do not replace a failing poll with an unconditional pass.

- [ ] **Step 4: Run it and confirm it passes**

Run: `cd agent-control-tower/act-dashboard && npx playwright test api/approval-request-changes.api.spec.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/api/approval-request-changes.api.spec.ts
git commit -m "test(e2e): cover the review request-changes feedback loop"
```

---

## Wave 3 — UI specs (independent files, run in parallel)

### Task 7: Gap 3 — decide from the UI decision zone

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/review-decision-zone.spec.ts`

**Interfaces:**
- Consumes: the reach sequence verified in `e2e/kanban-hitl.spec.ts:133-158`.
- Produces: nothing consumed downstream.

**Verified selectors** (from source):
- Review card: `[data-col="REVIEW"] [data-card="<id>"]` (`KanbanBoard.tsx:291,315`).
- Ask badge: `.pill.warn` with text `n asks` (`KanbanBoard.tsx:335-337`).
- Decision zone container: `.decision-zone`, `role="region"`, `aria-label="Decision panel"` (`ReviewPanels.tsx:51`).
- Decision zone title: `.decision-zone .dz-title` (`ReviewPanels.tsx:52`).
- Per-ask answer box: `textarea[aria-label="Answer for ask <askId>"]` (`ReviewPanels.tsx:60-67`).
- Per-ask controls: `Approve` and `Deny` buttons (`ReviewPanels.tsx:69-70`).

**Finding to encode:** the UI has no reason field wired to denial. `Deny` on the card face sends `{status:'CANCELLED'}` (`KanbanBoard.tsx:436`) and the drawer's comment box is a transition note (`TaskDrawer.tsx:424-430`). The spec must therefore assert only that the ask leaves PENDING, and record the missing reason field.

- [ ] **Step 1: Write the failing spec**

Create `e2e/review-decision-zone.spec.ts`:

```ts
import { test, expect } from '@playwright/test';
import {
  apiCall,
  pollUntil,
  seedAdkAgent,
  seedKanbanItem,
  transitionKanban,
  uniqueName,
} from './fixtures';

/**
 * Gap 3: decide through the UI rather than the REST API.
 *
 * kanban-hitl.spec.ts:133-178 reaches the decision zone and asserts it renders,
 * but never clicks a decision control. This spec closes that half.
 *
 * Reach path (LLM-free, CI-safe): kanban-hitl.spec.ts:133-158.
 */
test('clicking Deny in the decision zone resolves the ask', async ({ page, request }) => {
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-oc-uidz'),
    adkProvider: 'opencode',
  });
  const card = await seedKanbanItem(request, {
    title: `uidz-${uniqueName('card')}`,
    agentTemplateId: agent.name,
  });
  expect((await transitionKanban(request, card.id, 'IN_PROGRESS')).status).toBe(200);

  const asks = await pollUntil<any[]>(
    request,
    `/approvals?kanbanItemId=${card.id}`,
    (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
    60_000,
    2_000,
  );
  const ask = asks.find((a) => a.status === 'PENDING');
  expect(ask).toBeTruthy();

  expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);

  await page.goto('/');
  const reviewCard = page.locator(`[data-col="REVIEW"] [data-card="${card.id}"]`);
  await expect(reviewCard).toBeVisible({ timeout: 15_000 });
  await reviewCard.click();

  const zone = page.getByRole('region', { name: 'Decision panel' });
  await expect(zone).toBeVisible({ timeout: 15_000 });
  await expect(zone.locator('.dz-title')).toContainText('NEEDS YOUR DECISION');

  await zone.getByRole('button', { name: 'Deny', exact: true }).click();

  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
        return data?.status;
      },
      { timeout: 30_000 },
    )
    .not.toBe('PENDING');
});
```

- [ ] **Step 2: Run it and record which outcome you get**

Run: `cd agent-control-tower/act-dashboard && npx playwright test review-decision-zone.spec.ts`

- **PASS** — the UI decide path works and only lacked coverage. Say so in the PR.
- **FAIL** — capture where. If the `Deny` button is not found, the decision-zone render differs from `ReviewPanels.tsx:69-70`: inspect the live DOM and report the discrepancy rather than loosening the locator to something that would also match an unrelated button.

- [ ] **Step 3: Reconcile, then confirm it passes**

If the click resolves the ask through a path other than `DENIED`, record which (the UI `Deny` may route through `ApprovalAnswerService.answer`). Assert the observed terminal status explicitly.

Run: `cd agent-control-tower/act-dashboard && npx playwright test review-decision-zone.spec.ts`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/review-decision-zone.spec.ts
git commit -m "test(e2e): decide a review ask through the decision zone UI"
```

### Task 8: Gap 4 — scheduled jobs against the real backend

**Files:**
- Modify: `agent-control-tower/act-dashboard/e2e/scheduled-jobs-page.spec.ts` (full rewrite)

**Interfaces:**
- Consumes: the live backend. `createJob` posts to `/api/v1/aria/jobs` with body fields `scheduleType`, `category`, `title`, `scheduleExpression`, `notificationTitle`, `notificationBody?` (`src/api/ariaJobs.ts:12-15`, `src/types/index.ts:365-372`). Pause and resume are `PATCH /api/v1/aria/jobs/{id}/pause|resume` with no body (`src/api/ariaJobs.ts:26-34`).
- Produces: nothing consumed downstream.

**Verified selectors:**
- Create button: `button:has-text("+ New Job")` (`ScheduledJobsPage.tsx:180`).
- Modal: `.modal-dialog`, heading `.modal-dialog h3` with `New Job` / `Edit Job` (`:248-250`).
- Title input: placeholder `Daily summary brief` (`:255-258`).
- Category select: options `Reminder` / `Monitor` / `Brief` (`:259-266`).
- Schedule type radios: `fieldset input[name="scheduleType"]` values `ONE_SHOT`, `RECURRING` (`:267-281`).
- Schedule expression input: placeholder `0 9 * * *` for the recurring case (`:282-287`).
- Notification title input: placeholder `Daily brief ready` (`:301-304`).
- Submit: `.modal-dialog button[type="submit"]` with text `Create` (`:312-315`).
- Job card: `.job-card`, `.job-card-title`, status `.pill` (`:214-220`); Pause `⏸ Pause` (`:227-229`), Resume `▶ Resume` (`:232-234`).
- Empty state: `.card p` containing `No scheduled jobs found.` (`:206-210`).

**Scope correction:** `ScheduledJobController.java:20-52` exposes list, create, update, delete, pause and resume only — there is **no manual trigger**, and `ScheduledJobsPage.tsx:4` imports no trigger call. The governing test plan's Phase 2.3 step ("find Trigger Now or Run Manually") is unsatisfiable. Do not add the feature here; record the gap in the PR.

- [ ] **Step 1: Rewrite the spec to remove the mock**

Replace the whole of `e2e/scheduled-jobs-page.spec.ts` with:

```ts
import { test, expect } from '@playwright/test';

/**
 * Gaps 4: scheduled jobs driven against the REAL backend.
 *
 * The previous version stubbed `**/api/v1/aria/jobs**` to [] and only asserted
 * the header, the empty state and that the modal opens — a mock proves nothing
 * about the integration. This version creates a job, asserts it persists, then
 * pauses and resumes it.
 *
 * Not covered: a manual trigger. ScheduledJobController.java:20-52 exposes list,
 * create, update, delete, pause and resume only; no trigger endpoint exists and
 * the page imports no trigger call. There is nothing to assert.
 */
test.describe.configure({ mode: 'serial' });

const jobTitle = `e2e-job-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;

test('1. a created job persists and can be paused and resumed', async ({ page }) => {
  await page.goto('/scheduled-jobs');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.page-header h2')).toContainText('Scheduled Jobs');

  await page.locator('button:has-text("+ New Job")').click();
  const modal = page.locator('.modal-dialog');
  await expect(modal).toBeVisible();
  await expect(modal.locator('h3')).toContainText('New Job');

  await modal.getByPlaceholder('Daily summary brief').fill(jobTitle);
  await modal.locator('input[name="scheduleType"][value="RECURRING"]').check();
  await modal.getByPlaceholder('0 9 * * *').fill('0 9 * * MON-FRI');
  await modal.getByPlaceholder('Daily brief ready').fill(`${jobTitle} notification`);

  await modal.locator('button[type="submit"]').click();

  // Persists against the real backend.
  const card = page.locator('.job-card').filter({ hasText: jobTitle }).first();
  await expect(card).toBeVisible({ timeout: 20_000 });
  await expect(card.locator('.job-card-title')).toContainText(jobTitle);

  // Pause then resume, asserting the status pill actually changes.
  const pause = card.locator('button:has-text("Pause")');
  if (await pause.isVisible()) {
    await pause.click();
    await expect(card.locator('.pill')).toContainText(/PAUSED/i, { timeout: 20_000 });
    await card.locator('button:has-text("Resume")').click();
    await expect(card.locator('.pill')).toContainText(/ACTIVE/i, { timeout: 20_000 });
  } else {
    throw new Error('expected the created job card to offer a Pause control');
  }
});

test('2. the job survives a reload, proving it is server-side state', async ({ page }) => {
  await page.goto('/scheduled-jobs');
  await page.waitForLoadState('networkidle');
  const card = page.locator('.job-card').filter({ hasText: jobTitle }).first();
  await expect(card).toBeVisible({ timeout: 20_000 });
});
```

- [ ] **Step 2: Run it and record which outcome you get**

Run: `cd agent-control-tower/act-dashboard && npx playwright test scheduled-jobs-page.spec.ts`

The previous version passed while stubbing every request, which proves nothing about the integration. The rewrite removes the stubs:
- **PASS** — the integration genuinely works and only coverage was missing. Say so in the PR; this is a legitimate result, not a weaker one.
- **FAIL** — the real integration is broken, or the form selectors differ from `ScheduledJobsPage.tsx`. Either is a finding worth reporting.

- [ ] **Step 3: Reconcile, then confirm it passes**

Two things may need correcting against reality and should be fixed from observation, not guesswork:
- The exact status pill text after pause and resume. If it is not `PAUSED` / `ACTIVE`, use the values the API returns and cite them.
- Whether the create form requires a notification body. If submit is rejected, fill `notificationBody` too.

Run: `cd agent-control-tower/act-dashboard && npx playwright test scheduled-jobs-page.spec.ts`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/scheduled-jobs-page.spec.ts
git commit -m "test(e2e): drive scheduled jobs against the real backend"
```

---

## Wave 4 — The UI journey (depends on nothing, but run after Wave 3 for review clarity)

### Task 9: Gap 7 — a UI-only create-and-run journey

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/journey-crew-deploy-run.spec.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks (deliberately — the point is that it drives the UI).
- Produces: nothing.

**Verified selectors:**
- Catalog grid: `[data-testid="agent-catalog"]` (`AgentCatalog.tsx:32`).
- Catalog card: `.tmpl[data-template="<id>"]`, name `.nm`, deploy button `.add-btn` with text `+ Deploy` (`AgentCatalog.tsx:38-56`).
- Roster grid: `.crew-grid` (`CrewPage.tsx:340-353`); roster card `article.crew-card` with `aria-label="Open details for <name>"` (`AgentCard.tsx:112-140`).
- Runs page start control: `.btn.btn-primary` with text `+ Start Run` (`RunsPage.tsx:109-111`); form `.card.form-card`; agent `<select>`; prompt textarea with placeholder `Describe what the agent should do...` (`:161-166`); submit `button[type="submit"]` with text `Start Run` (`:171-173`).
- Runs table rows: `.data-table tbody tr` (`journey-agent-run-report.spec.ts:46`).

**Scope note (deviation from the spec):** the spec described this as "create agent, run, approve in the decision zone". The Crew catalog deploys from `POST /agents/from-template/{templateName}` (`src/api/agents.ts:36-39`), whose templates are not guaranteed to be task-capable, so an approval is not deterministically reachable from this path. The approval half is covered by Task 7. This task therefore asserts the UI-driven creation-and-run journey only. The PR must state this narrowing and why.

- [ ] **Step 1: Write the failing spec**

Create `e2e/journey-crew-deploy-run.spec.ts`:

```ts
import { test, expect } from '@playwright/test';
import { apiCall } from './fixtures';

/**
 * Gap 7: one continuous UI-only journey — deploy from the Crew catalog, then
 * start a run for that agent from the Runs page.
 *
 * journey-agent-run-report.spec.ts covers agent->run->report, but the agent is
 * REST-seeded and the run is API-started, so no single spec proves the UI can
 * do both. That is the gap.
 *
 * No approval is asserted here on purpose: the catalog deploys from
 * POST /agents/from-template/{name}, whose provider is not guaranteed to be
 * task-capable, so a PENDING gate approval is not deterministically reachable.
 * The decision-zone half is covered by review-decision-zone.spec.ts.
 */
test('deploy from the Crew catalog, then start a run from the Runs page', async ({ page }) => {
  await page.goto('/crew');
  await page.waitForLoadState('networkidle');

  const catalog = page.locator('[data-testid="agent-catalog"]');
  await expect(catalog).toBeVisible({ timeout: 15_000 });

  const firstCard = catalog.locator('.tmpl').first();
  const agentName = (await firstCard.locator('.nm').innerText()).trim();
  expect(agentName).not.toBe('');

  await firstCard.locator('.add-btn').click();

  // The deployed agent appears on the roster.
  await expect(
    page.locator('.crew-grid').getByText(agentName).first(),
  ).toBeVisible({ timeout: 20_000 });

  // Start a run for it from the Runs page.
  await page.goto('/runs');
  await page.waitForLoadState('networkidle');
  await page.getByRole('button', { name: '+ Start Run' }).click();

  const form = page.locator('.card.form-card');
  await expect(form).toBeVisible({ timeout: 15_000 });
  await form.locator('select').selectOption({ label: agentName });
  await form.getByPlaceholder('Describe what the agent should do...').fill('Say hello briefly.');
  await form.locator('button[type="submit"]').click();

  // The run surfaces in the table with a real, non-placeholder status.
  const row = page.locator('.data-table tbody tr').filter({ hasText: agentName }).first();
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(row.locator('td').nth(2)).toContainText(
    /PENDING|RUNNING|FAILED|COMPLETED|CANCELLED/,
  );
});
```

- [ ] **Step 2: Run it and record which outcome you get**

Run: `cd agent-control-tower/act-dashboard && npx playwright test journey-crew-deploy-run.spec.ts`

- **PASS** — the UI journey works and only lacked coverage. Say so in the PR.
- **FAIL** — capture where. The likely spot is the select option label: the dropdown may render the agent differently from the catalog name, or the first catalog card may not be the one deployed. Observe and correct from the live DOM.

- [ ] **Step 3: Reconcile, then confirm it passes**

If the deployed agent is already present from an earlier run, `getByText(agentName).first()` may resolve to a stale card. Scope the roster assertion to the specific card, or assert the roster count increased. Use `uniqueName` semantics from the catalog only if the UI exposes it — otherwise assert on the card that reports the newest creation.

Run: `cd agent-control-tower/act-dashboard && npx playwright test journey-crew-deploy-run.spec.ts`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/journey-crew-deploy-run.spec.ts
git commit -m "test(e2e): add a UI-only crew deploy to run journey"
```

---

## Wave 5 — Feasibility spike (run last; no dependency on Waves 1-4)

### Task 10: Spike gaps 5 and 6

**Files:**
- Create: `docs/reviews/2026-09-12-branch-and-pack-gate-spike.md` (findings only)
- Optionally create: `agent-control-tower/act-dashboard/e2e/api/branch-governance.api.spec.ts` and `agent-control-tower/act-dashboard/e2e/api/git-pack-gate.api.spec.ts` — **only** if the spike proves a hermetic path exists.
- Modify: `docs/superpowers/specs/2026-09-12-workflow-e2e-gap-closure-design.md` (record the verdict)

**Interfaces:**
- Consumes: nothing.
- Produces: a written verdict per gap and, if feasible, two specs.

**The question:** can branch creation/protection and a blocking PUSH gate be exercised hermetically under CI — H2, no OpenSandbox, no external GitHub, no LLM key?

**Known blockers to start from:** `e2e/sdd-workflow.spec.ts:69` already notes branch creation is inert under CI, and `e2e/git-pack-governance.spec.ts:21-47` inspects tool and pack metadata without ever blocking a run.

- [ ] **Step 1: Answer the question in writing**

For each of the two gaps, determine and record: what actually gates the behaviour, what external dependency it needs, and whether a local/hermetic substitute exists (for example a bare repo created on disk as a `file://` remote). Cite `file:line` for every mechanism. Write the findings to `docs/reviews/2026-09-12-branch-and-pack-gate-spike.md` following the evidence discipline rule from Task 3.

- [ ] **Step 2a: If a hermetic path exists — write the specs**

Write the two specs so they fail first, then pass. If the gate can only be reached with a real external remote, they must `test.skip` with the reason in the skip message rather than being weakened into assertions that do not exercise the gate.

- [ ] **Step 2b: If no hermetic path exists — record local-only**

Do not write specs that pretend to cover the gate. Record the verdict, the blocker, and the local-only run instructions in the spike document and in the spec.

- [ ] **Step 3: Update the spec document**

In `docs/superpowers/specs/2026-09-12-workflow-e2e-gap-closure-design.md`, replace the Group B table's speculative wording with the spike's verdict for gaps 5 and 6.

- [ ] **Step 4: Commit**

```bash
git add docs/reviews/2026-09-12-branch-and-pack-gate-spike.md \
        docs/superpowers/specs/2026-09-12-workflow-e2e-gap-closure-design.md
# add the two specs too, if they were written
git commit -m "docs(e2e): record the branch and pack gate feasibility verdict"
```

---

## Final verification

- [ ] Run the frontend unit suite and build: `cd agent-control-tower/act-dashboard && pnpm test && pnpm build`
- [ ] Run the full Playwright suite: `cd agent-control-tower/act-dashboard && npx playwright test`
- [ ] Confirm the new specs ran and passed, and that no previously passing spec regressed. A run where a new spec is silently skipped is not a pass — read the summary counts.
- [ ] Confirm the PR body states, for each of gaps 1, 2, 3, 4 and 7, whether the spec failed on first run, and records the D3 verdict plus the gap 2 and gap 4 product findings.
- [ ] Confirm no claim in the PR exceeds what a recorded command produced.

## Self-review notes

- **Spec coverage:** gap 1 to Task 5, gap 2 to Task 6, gap 3 to Task 7, gap 4 to Task 8, gap 5 and 6 to Task 10, gap 7 to Task 9, D4 to Task 1, D5 to Task 2, D6 to Task 3. D1 was completed before this plan was written (three untracked reports deleted). The spec's acceptance criteria map onto the Final verification list.
- **Deviations from the spec, flagged deliberately:** gap 7 is narrowed to creation and run, with the approval half moved to Task 7, because the Crew catalog cannot deterministically produce a task-capable agent. The spec's `REJECTED` enum name is corrected to `DENIED` in Task 4.
- **Type consistency:** `apiCall`, `seedAdkAgent`, `seedKanbanItem`, `transitionKanban`, `pollUntil` and `uniqueName` are the exact exports of `e2e/fixtures.ts`. Playwright API specs live under `e2e/api/` (matched by the `api` project); UI specs live directly under `e2e/` (matched by the `chromium` project, which ignores `e2e/api/`).

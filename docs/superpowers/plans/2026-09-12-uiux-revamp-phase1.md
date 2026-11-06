# UIUX Revamp Phase 1 — Subtraction and Honesty Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the dead frontend surface, retire the `/approvals` route, and make every status signal in the dashboard tell the truth — with no new UI concepts.

**Architecture:** Purely subtractive plus defect repair. Six zero-reference modules and one dead route are deleted; the E2E specs that still navigate to the dead route are retargeted so nothing dangles; and the components that assert false system state (health badges, agent counts, version labels, timestamps, key warnings, missing confirmations) are corrected against the data sources that already exist.

**Tech Stack:** React 19 + Vite + TypeScript (frontend, `agent-control-tower/act-dashboard`), Vitest for unit tests, Playwright for E2E, Spring Boot 3.3 + Java 21 (backend, `agent-control-tower`).

**Spec:** `docs/superpowers/specs/2026-09-12-uiux-ia-revamp-design.md` (Phase 1, Section 10).

**Scope guardrails:**
- Deleting the Knowledge fake panels, the inert Configure editors, the `Ops` and `Chat` pages, and the per-second clock belongs to Phase 2/3 — do not do them here.
- Do not add a WebSocket disconnect indicator here (it is a new UI element; Phase 2/3).
- Frontend commands run from `agent-control-tower/act-dashboard`. Backend commands run from `agent-control-tower`. Always `cd` explicitly in each shell invocation.
- On this machine Maven is not on PATH; use `/c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn`. Anywhere `mvn` appears below, substitute that path if `mvn` is not found.

---

## File Structure

**Deleted (Tasks 1-2):**
- `src/pages/AgentsPage.tsx` — 413-line duplicate of `CrewPage`
- `src/components/AgentToolPanel.tsx` — zero imports; calls a non-existent endpoint
- `src/components/EvidenceDrawer.tsx` — zero imports
- `src/hooks/useNotificationPrefs.ts` + `src/hooks/__tests__/useNotificationPrefs.test.ts`
- `src/api/harness.ts` — zero callers
- `src/api/dod.ts` — only consumer is the deleted `EvidenceDrawer`
- `src/pages/ApprovalsPage.tsx` + `src/pages/__tests__/ApprovalsPage.test.tsx`
- `src/pages/AgentsPage.tsx`'s stale `test.skip` in `e2e/template-api.spec.ts`

**Modified:**
- `src/App.tsx` — drop the `/approvals` import and route
- `e2e/approvals-decision-flow.spec.ts`, `e2e/journey-agent-run-report.spec.ts`, `e2e/git-pack-governance.spec.ts`, `e2e/harness-governance.spec.ts`, `e2e/agent-dev-workflow-governance.spec.ts`, `e2e/sdd-workflow.spec.ts` — retarget away from `/approvals`
- `act-dashboard-api/.../controller/DashboardController.java`, `.../dto/DashboardSummary.java` — report healthy and degraded agent counts separately
- `src/types/index.ts` — `DashboardSummary`, `KnowledgeItem.currentVersion`
- `src/components/TopBar.tsx` — honest health badge, pinned clock format
- `src/components/ExecutiveSummary.tsx` — honest active-agents detail
- `src/pages/KnowledgePage.tsx` — version label render, promote confirmation
- `src/pages/RunsPage.tsx` — fragment key
- `src/utils/formatTime.ts` — add `formatClock`
- `src/components/Toast.tsx` — click-through on non-aria toasts
- `src/components/KanbanBoard.tsx`, `src/pages/CrewPage.tsx`, `src/components/TaskDrawer.tsx` — confirmation/error handling

---

## Dependency and Parallelism Map

Execution is **subagent-driven with maximum safe parallelism**. The rule that makes parallelism safe here: **exactly one writer per file per wave.** Waves run concurrently; within a wave every file appears in at most one task.

### Wave 0 — freeze the shared contracts (coordinator only, no dispatch)

Nothing is dispatched until these are fixed, because more than one wave-1 task depends on them:

- `DashboardSummary` is the record `(activeAgents, healthyAgents, degradedAgents, runningRuns, pendingApprovals, totalTokensBurned)`, with `activeAgents == healthyAgents` (healthy only). Task 4 owns it.
- `formatClock(date: Date, withSeconds = false): string` lives in `src/utils/formatTime.ts`. Task 8 owns it.
- `formatVersion(version: string | number | null | undefined): string` is exported from `src/pages/KnowledgePage.tsx`. Task 6 owns it.
- Confirmation dialogs use the accessible names `Confirm` (confirm) and `Cancel` (dismiss), in all four Task 10 surfaces.
- The E2E Review-surface selector is `.col-k[data-col="REVIEW"]`; the ask badge is `.pill.warn`; the decision zone title is `.decision-zone .dz-title`.
- **Commit rule:** subagents must NOT run `git commit` or `git add`. Each agent reports its diff; the coordinator commits serially after review. This removes all git-index contention and gives review a single choke point.

### Wave table (authoritative for execution order)

| Wave | Concurrent tasks | Files each owns | Depends on | Verification after the wave |
|---|---|---|---|---|
| 0 | — (coordinator) | none | — | contracts above recorded in this file |
| 1 | **Task 1** | 6 deleted modules + `e2e/template-api.spec.ts` | Wave 0 | `pnpm build && pnpm test` |
| 1 | **Task 2** | `ApprovalsPage.tsx`(+test), `App.tsx` | Wave 0 | `pnpm build && pnpm test` |
| 1 | **Task 4** | `DashboardController.java`, `DashboardSummary.java`, `DashboardSummaryIntegrationTest.java`, `src/types/index.ts` (`DashboardSummary` only) | Wave 0 | `mvn verify -pl act-app -Dit.test=DashboardSummaryIntegrationTest` |
| 1 | **Task 7** | `RunsPage.tsx`, `RunsPage.live.test.tsx` | Wave 0 | `npx vitest run …/RunsPage.live.test.tsx` |
| 1 | **Task 9** | `Toast.tsx`, `Toast.test.tsx` | Wave 0 | `npx vitest run …/Toast.test.tsx` |
| 2 | **Task 3** | the 6 `e2e/*.spec.ts` files | Task 2 (the route must be gone) | the 6-spec Playwright run |
| 2 | **Task 5** | `TopBar.tsx`(+new test), `ExecutiveSummary.tsx`(+test) | Task 4 (reads the new summary fields) | `npx vitest run …/TopBar.test.tsx …/ExecutiveSummary.test.tsx` |
| 2 | **Task 6** | `KnowledgePage.tsx`, `src/types/index.ts` (`KnowledgeItem`), `KnowledgePage.test.tsx` | Task 4 (serialized on `types/index.ts`) | `npx vitest run …/KnowledgePage.test.tsx && pnpm build` |
| 3 | **Task 10** | `KanbanBoard.tsx`(+test), `CrewPage.tsx`(+test), `KnowledgePage.tsx`(+test), `TaskDrawer.tsx`(+test) | Task 6 (serialized on `KnowledgePage.tsx`) | the four-spec Vitest run, then `pnpm test && pnpm build` |
| 4 | **Task 8** | `formatTime.ts`(+test) and 8 components — `TopBar`, `AgentDrawer`, `MorningBriefing`, `ReviewWorkspace`, `TaskDrawer`, `KnowledgePage`, `OpsPage`, `ReportsPage` | Tasks 5, 6, 10 (serialized on `TopBar`, `KnowledgePage`, `TaskDrawer`) | the timestamp grep + `pnpm build && pnpm test` |

Serialization points, stated explicitly: `src/types/index.ts` is written by Task 4 then Task 6 (never concurrently). `KnowledgePage.tsx` is written by Task 6, then Task 10, then Task 8. `TaskDrawer.tsx` and `TopBar.tsx` are each written by an earlier task and then by Task 8.

### Where to use more agents than the wave table shows

- **Test levels in parallel.** For a task whose verification spans layers, dispatch one agent per layer once the contract is frozen. Task 4 is the small example: its Java integration test and its frontend `DashboardSummary` type change touch disjoint trees and can be authored concurrently, then verified together. Apply this aggressively in Phase 3/4, where a single feature needs unit + integration + MCP-tool + UI-E2E coverage at once.
- **E2E by entry point.** Independent E2E surfaces run as separate agents. In later phases, UI E2E and MCP E2E are separate agents because they exercise different entry points and share no files.
- **Reader/verifier pairs.** Never let the writer verify its own wave alone — see the protocol below.

### Cross-check protocol (anti-drift, anti-hallucination)

After each wave:

1. **Diff review.** The coordinator reads each agent's actual diff (`git diff -- <owned paths>`) and checks it against the spec section named in the task. An agent summarising its work is not evidence; the diff is.
2. **Ownership audit.** Confirm no agent touched a file outside its assigned list. If an agent needed an out-of-scope change, it must report that instead of making it; the coordinator folds it into the right wave.
3. **Independent verification agent.** Dispatch a fresh verifier agent per wave whose only job is to re-run the wave's verification commands and grep the acceptance criteria, reporting pass/fail with raw command output. It must not be the agent that wrote the code.
4. **Acceptance-criteria sweep.** Map the wave's tasks to the spec's Section 11 acceptance criteria and confirm each claimed criterion is actually exercised by a test that would fail if the behaviour broke.
5. **Serial commit.** Only then does the coordinator commit that wave, using the commit message given in each task.

If a review finds spec drift or an invented API/selector, fix it in the same wave before committing — do not carry it forward.

---

## Task 1: Delete the six dead modules and the stale skipped spec

**Files:**
- Delete: `src/pages/AgentsPage.tsx`
- Delete: `src/components/AgentToolPanel.tsx`
- Delete: `src/components/EvidenceDrawer.tsx`
- Delete: `src/hooks/useNotificationPrefs.ts`
- Delete: `src/hooks/__tests__/useNotificationPrefs.test.ts`
- Delete: `src/api/harness.ts`
- Delete: `src/api/dod.ts`
- Modify: `e2e/template-api.spec.ts` (remove the `test.skip` block that navigates to `/agents`)

- [ ] **Step 1: Confirm each target is still unreferenced**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && for id in AgentsPage AgentToolPanel EvidenceDrawer useNotificationPrefs "api/harness" "api/dod"; do echo "--- $id"; grep -rn "$id" src e2e --include=*.ts --include=*.tsx | grep -v "src/pages/AgentsPage.tsx\|src/components/AgentToolPanel.tsx\|src/components/EvidenceDrawer.tsx\|src/hooks/useNotificationPrefs.ts\|src/hooks/__tests__/useNotificationPrefs.test.ts\|src/api/harness.ts\|src/api/dod.ts"; done
```
Expected: the only output is `e2e/template-api.spec.ts:146` (the skipped test) and `src/components/EvidenceDrawer.tsx` importing `../api/dod`. Anything else means stop and re-check.

- [ ] **Step 2: Delete the modules**

```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && git rm src/pages/AgentsPage.tsx src/components/AgentToolPanel.tsx src/components/EvidenceDrawer.tsx src/hooks/useNotificationPrefs.ts src/hooks/__tests__/useNotificationPrefs.test.ts src/api/harness.ts src/api/dod.ts
```

- [ ] **Step 3: Remove the stale skipped test**

In `e2e/template-api.spec.ts`, delete the whole skipped block and its stale comment. It begins with the comment line `// STALE: the standalone /agents route was removed in the UI redesign (agents now` and ends at that test's closing `});`. The block starts with:

```ts
  // ── Test 4 ───────────────────────────────────────────────────────
  // STALE: the standalone /agents route was removed in the UI redesign (agents now
  // live on /crew). Rewrite against the current UI in the Phase E E2E overhaul.
  test.skip('AgentsPage template dropdown populates from API', async ({ page }) => {
```

Delete from the `// ── Test 4 ──` comment through the matching `});`. Also delete the now-empty `// ── Test 4 ──` separator so no dangling banner comment remains.

- [ ] **Step 4: Verify the build and unit tests**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && pnpm build && pnpm test
```
Expected: `tsc -b` reports no errors (it would fail on any surviving import of a deleted module), and Vitest reports no failing suites.

- [ ] **Step 5: Commit**

```bash
cd D:/project/aria-conductor && git add -A agent-control-tower/act-dashboard/src agent-control-tower/act-dashboard/e2e/template-api.spec.ts && git commit -m "chore(dashboard): delete six zero-reference modules and a stale skipped spec"
```

---

## Task 2: Retire the `/approvals` route and page

**Files:**
- Delete: `src/pages/ApprovalsPage.tsx`
- Delete: `src/pages/__tests__/ApprovalsPage.test.tsx`
- Modify: `src/App.tsx` (remove import at line 13 and route at line 43)

- [ ] **Step 1: Confirm no product code links to the route**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && grep -rn "'/approvals'\|\"/approvals\"\|to=\"/approvals\"" src e2e --include=*.ts --include=*.tsx
```
Expected: hits only in `src/App.tsx`, `src/pages/ApprovalsPage.tsx`, `src/pages/__tests__/ApprovalsPage.test.tsx`, and the E2E specs handled in Task 3. `src/utils/notificationRoutes.ts` must map `approval.requested` to `'/'`, not `/approvals`.

- [ ] **Step 2: Delete the page and its test**

```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && git rm src/pages/ApprovalsPage.tsx src/pages/__tests__/ApprovalsPage.test.tsx
```

- [ ] **Step 3: Remove the import and the route**

In `src/App.tsx` delete line 13:
```tsx
import { ApprovalsPage } from './pages/ApprovalsPage';
```
and delete the route line:
```tsx
              <Route path="/approvals" element={<ApprovalsPage />} />
```
The `</Route>` / `</Routes>` structure must remain intact; the `/runs` route becomes the last child.

- [ ] **Step 4: Verify**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && pnpm build && pnpm test
```
Expected: build clean, unit tests pass. `src/App.tsx` has no reference to `ApprovalsPage`.

- [ ] **Step 5: Commit**

```bash
cd D:/project/aria-conductor && git add -A agent-control-tower/act-dashboard/src && git commit -m "refactor(dashboard): retire the dead /approvals route and page"
```

---

## Task 3: Retarget the E2E specs that navigate to `/approvals`

With the route gone, `page.goto('/approvals')` matches no route and renders nothing, so every spec that navigates there must be retargeted. The Review surface is the Kanban board's `REVIEW` column on `/`.

Reference selectors (from `e2e/kanban-hitl.spec.ts`): columns are `.col-k[data-col="REVIEW"]`; a Review card is `[data-col="REVIEW"] [data-card="<id>"]`; the ask badge is `.pill.warn`; the decision zone title is `.decision-zone .dz-title`.

**Files:**
- Modify: `e2e/approvals-decision-flow.spec.ts`
- Modify: `e2e/journey-agent-run-report.spec.ts`
- Modify: `e2e/git-pack-governance.spec.ts`
- Modify: `e2e/harness-governance.spec.ts`
- Modify: `e2e/agent-dev-workflow-governance.spec.ts`
- Modify: `e2e/sdd-workflow.spec.ts`

- [ ] **Step 1: Delete the redirect test**

In `e2e/approvals-decision-flow.spec.ts`, delete the whole first test:
```ts
  test('/approvals redirects to the overview board', async ({ page }) => {
    await page.goto('/approvals');
    // Client-side <Navigate to="/" replace /> lands stale deep links on the
    // overview, next to the Review column.
    await expect(page).toHaveURL(/\/$/);
    await page.waitForLoadState('networkidle');
    // The landing surface is the governed board, not a retired approvals page.
    await expect(page.locator('#panel-exec')).toBeVisible();
    await expect(page.locator('h2').filter({ hasText: 'Kanban Board' }).first()).toBeVisible();
    // The retired queue tabs never render anywhere on the redirected page.
    await expect(page.locator('.tab-btn')).toHaveCount(0);
  });
```
Keep the two remaining tests unchanged. Update the file's header comment: replace the `ADAPTATION NOTE` text mentioning the redirect with a statement that the page and route are deleted and the Review column is the surface.

- [ ] **Step 2: Retarget `journey-agent-run-report.spec.ts`**

Replace:
```ts
  await page.goto('/approvals');
  await expect(page).toHaveURL(/\/$/);
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible({ timeout: 15_000 });
```
with:
```ts
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible({ timeout: 15_000 });
```
Also update the test's leading comment so it no longer says the page "redirects".

- [ ] **Step 3: Retarget the three governance specs**

In `e2e/git-pack-governance.spec.ts` replace:
```ts
    // 3. Navigate to Approvals page
    await page.goto(`${BASE_URL}/approvals`);
    await expect(page.locator('h1, h2, [data-testid="approvals-title"]').first()).toBeVisible();
```
with:
```ts
    // 3. The approvals surface is the kanban Review column on the overview.
    await page.goto(`${BASE_URL}/`);
    await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible();
```

In `e2e/harness-governance.spec.ts` replace:
```ts
    await page.goto(`${BASE_URL}/approvals`);
    await expect(page.locator('h1, h2').first()).toBeVisible();
```
with:
```ts
    await page.goto(`${BASE_URL}/`);
    await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible();
```

In `e2e/agent-dev-workflow-governance.spec.ts` replace:
```ts
    await page.goto(`${BASE_URL}/approvals`);
    await expect(page.locator('h1, h2').first()).toBeVisible();
```
with:
```ts
    await page.goto(`${BASE_URL}/`);
    await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible();
```

These keep each spec's original API assertions unchanged; only the stale page navigation is redirected to the real surface.

- [ ] **Step 4: Retarget `sdd-workflow.spec.ts` step 3**

First locate where the spec-review markdown actually renders now:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && grep -rn "spec-review-markdown" src
```

Then replace:
```ts
  // 3. Approvals page renders the card without crashing (null toolCallId) and shows markdown.
  await page.goto('/approvals');
  await expect(page.getByText('SPEC_REVIEW')).toBeVisible();
  await expect(page.locator('.spec-review-markdown')).toBeVisible();
```
with an assertion against the Review surface on `/`. If the grep shows `spec-review-markdown` is still emitted by a live component, assert it on `/`; if the grep shows the class survives only in `src/styles/index.css` with no producer, assert the ask is reachable instead:
```ts
  // 3. The Review surface renders the ask without crashing (null toolCallId).
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible({ timeout: 15_000 });
```
Do not leave a class assertion for a class nothing emits — that was the original defect.

- [ ] **Step 5: Run the retargeted specs**

Run (the stack must be up; see Task 3 Step 5a):
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && CI=true PLAYWRIGHT_BROWSER_CHANNEL=chromium npx playwright test approvals-decision-flow.spec.ts journey-agent-run-report.spec.ts git-pack-governance.spec.ts harness-governance.spec.ts agent-dev-workflow-governance.spec.ts sdd-workflow.spec.ts --workers=1 --reporter=list
```
Expected: no test navigates to `/approvals`; `sdd-workflow` remains skipped when no ADK runtime is present (its `test.skip` guard is unchanged); all others pass.

- [ ] **Step 5a: Bring the stack up if it is not already running**

```bash
cd D:/project/aria-conductor/agent-control-tower && /c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn -q install -DskipTests && /c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn spring-boot:run -pl act-app -Dspring-boot.run.profiles=h2
```
and in a second shell:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && pnpm dev
```
Wait for `http://localhost:8080/api/v1/agents` and `http://localhost:5173` to answer.

- [ ] **Step 6: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard/e2e && git commit -m "test(e2e): retarget specs off the deleted /approvals route onto the Review column"
```

---

## Task 4: Report healthy and degraded agents separately

Today `activeAgents` counts `HEALTHY + DEGRADED` and the UI renders it as "N Agents Online" next to "Healthy & responsive", so a DEGRADED agent is reported as healthy. Split the count.

**Files:**
- Modify: `act-dashboard-api/src/main/java/io/aria/conductor/dashboard/controller/DashboardController.java:39-58`
- Modify: `act-dashboard-api/src/main/java/io/aria/conductor/dashboard/dto/DashboardSummary.java`
- Modify: `src/types/index.ts` (`DashboardSummary`)
- Modify: `src/components/TopBar.tsx:68-72`
- Modify: `src/components/ExecutiveSummary.tsx:161-165`
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/DashboardSummaryIntegrationTest.java` (create)

- [ ] **Step 1: Find every consumer of the field before changing it**

Run:
```bash
cd D:/project/aria-conductor && grep -rn "activeAgents\|DashboardSummary(" --include=*.java --include=*.ts --include=*.tsx agent-control-tower packages 2>/dev/null | grep -v "/target/\|node_modules"
```
Expected: the controller, the DTO record, `src/types/index.ts`, `TopBar.tsx`, `ExecutiveSummary.tsx`, and any Java test. Note each hit; every Java constructor call must be updated in Step 3.

- [ ] **Step 2: Write the failing integration test**

Create `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/DashboardSummaryIntegrationTest.java`:

```java
package io.aria.conductor.app;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardSummaryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Test
    void degradedAgentsAreNotCountedAsHealthy() throws Exception {
        agentRepository.save(Agent.builder()
                .name("summary-healthy-" + java.util.UUID.randomUUID())
                .role("test")
                .healthStatus(HealthStatus.HEALTHY)
                .build());
        agentRepository.save(Agent.builder()
                .name("summary-degraded-" + java.util.UUID.randomUUID())
                .role("test")
                .healthStatus(HealthStatus.DEGRADED)
                .build());

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degradedAgents").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.healthyAgents").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower && /c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn verify -pl act-app -Dit.test=DashboardSummaryIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false
```
Expected: FAIL — `No value at JSON path "$.degradedAgents"`. Note: this project runs `*IntegrationTest` through Failsafe, so `mvn verify` with `-Dit.test=` is required (`mvn test` with `-Dtest=` silently runs zero tests).

- [ ] **Step 4: Split the counts in the DTO**

Replace the body of `DashboardSummary.java`'s record declaration so it reads:

```java
public record DashboardSummary(
        long activeAgents,
        long healthyAgents,
        long degradedAgents,
        long runningRuns,
        long pendingApprovals,
        long totalTokensBurned) {
}
```
`activeAgents` is kept for backward compatibility and now means healthy agents only; `healthyAgents` and `degradedAgents` are the explicit pair.

- [ ] **Step 5: Split the counts in the controller**

In `DashboardController.getSummary`, replace the `activeAgents` computation with:

```java
        long healthyAgents = agentRepository.countByHealthStatus(HealthStatus.HEALTHY);
        long degradedAgents = agentRepository.countByHealthStatus(HealthStatus.DEGRADED);
```

and replace the returned instance with:

```java
        return new DashboardSummary(healthyAgents, healthyAgents, degradedAgents,
                runningRuns, pendingApprovals, totalTokensBurned);
```

- [ ] **Step 6: Update the frontend type**

In `src/types/index.ts`, change the `DashboardSummary` interface to:

```ts
export interface DashboardSummary {
  activeAgents: number;
  healthyAgents: number;
  degradedAgents: number;
  runningRuns: number;
  pendingApprovals: number;
  totalTokensBurned: number;
}
```

- [ ] **Step 7: Run the test again**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower && /c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn verify -pl act-app -Dit.test=DashboardSummaryIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard-api agent-control-tower/act-app/src/test agent-control-tower/act-dashboard/src/types/index.ts && git commit -m "fix(dashboard): count healthy and degraded agents separately in the summary"
```

---

## Task 5: Make the TopBar and Executive Summary health signals truthful

`isHealthy = activeAgents > 0` (`TopBar.tsx:72`) prints "System Healthy" whenever any agent exists, while both providers can be UNHEALTHY. Derive the badge from real provider health, and stop claiming "Healthy & responsive" for a roster that contains a DEGRADED agent.

**Files:**
- Modify: `src/components/TopBar.tsx`
- Modify: `src/components/ExecutiveSummary.tsx:161-165`
- Test: `src/components/__tests__/TopBar.test.tsx` (create)
- Test: `src/components/__tests__/ExecutiveSummary.test.tsx` (extend)

- [ ] **Step 1: Write the failing TopBar test**

Create `src/components/__tests__/TopBar.test.tsx`:

```tsx
import { describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TopBar } from '../TopBar';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

vi.mock('../../api/dashboard', () => ({
  getSummary: vi.fn().mockResolvedValue({
    activeAgents: 3, healthyAgents: 3, degradedAgents: 1,
    runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0,
  }),
}));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn().mockResolvedValue([{ id: 'opencode', isDefault: true }]),
  getAdkProviderHealth: vi.fn().mockResolvedValue({ providerId: 'opencode', healthy: false }),
}));

function renderTopBar() {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TopBar />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TopBar health signals', () => {
  it('does not claim System Healthy when every provider is unhealthy', async () => {
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Providers Unavailable'));
    expect(document.body.textContent).not.toContain('System Healthy');
  });

  it('does not claim the agent roster is Online', async () => {
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Agents'));
    expect(document.body.textContent).not.toContain('Agents Online');
  });
});
```
Assert on `document.body.textContent` rather than `findByText`: the badge renders `{activeAgents}` and the noun as separate text nodes around an icon `<span>`, so a string matcher would not resolve a single element.

- [ ] **Step 2: Run it and confirm it fails**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/TopBar.test.tsx
```
Expected: FAIL — the rendered badge reads `System Healthy` and `3 Agents Online`.

- [ ] **Step 3: Derive the badge from provider health**

In `src/components/TopBar.tsx`, change the existing react-query import from `import { useQuery } from '@tanstack/react-query';` to:

```tsx
import { useQueries, useQuery } from '@tanstack/react-query';
```

and add the ADK import (do NOT import `formatClock` here — Task 8 owns the clock format and will delete the local `formatClock` function):

```tsx
import { getAdkProviderHealth, listAdkProviders } from '../api/adk';
```

Add the provider-health queries inside the component, after the existing `getSummary` query:
```tsx
  const { data: providers } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
    refetchInterval: 15_000,
  });

  const healthResults = useQueries({
    queries: (providers ?? []).map((p) => ({
      queryKey: ['adk-provider-health', p.id],
      queryFn: () => getAdkProviderHealth(p.id),
      refetchInterval: 15_000,
    })),
  });
  const healthyProviders = healthResults.filter((r) => r.data?.healthy).length;
  const providersKnown = providers !== undefined && providers.length > 0;
  const isHealthy = providersKnown && healthyProviders > 0;
```

Replace the two badges so they assert only what is known:
```tsx
        <span className="badge governed">
          <span className="dot" />
          {activeAgents} {activeAgents === 1 ? 'Agent' : 'Agents'}
        </span>
        <span className={`badge ${isHealthy ? 'live' : 'afterhours'}`}>
          <span className="dot" />
          {isHealthy ? `${healthyProviders} of ${providers?.length ?? 0} Providers Healthy` : 'Providers Unavailable'}
        </span>
```

- [ ] **Step 4: Stop the unverified Executive Summary detail**

In `src/components/ExecutiveSummary.tsx`, replace:
```tsx
        <StatCell
          label="Active Agents"
          value={summary?.activeAgents ?? '—'}
          detail="Healthy & responsive"
        />
```
with:
```tsx
        <StatCell
          label="Active Agents"
          value={summary?.activeAgents ?? '—'}
          detail={degraded > 0 ? `Healthy · ${degraded} degraded` : 'All healthy'}
          variant={degraded > 0 ? 'amber' : undefined}
        />
```
and add, next to the other derived values above the `return`:
```tsx
  const degraded = summary?.degradedAgents ?? 0;
```

- [ ] **Step 5: Run the tests**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/TopBar.test.tsx src/components/__tests__/ExecutiveSummary.test.tsx
```
Expected: PASS. If `ExecutiveSummary.test.tsx` fails because its mock summary lacks the new fields, add `healthyAgents` and `degradedAgents` to that mock rather than loosening the assertion.

- [ ] **Step 6: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard/src/components && git commit -m "fix(dashboard): derive health badges from provider health and degraded count"
```

---

## Task 6: Fix the knowledge version label

`KnowledgePage.tsx` renders `v{it.currentVersion}`. The backend returns `null` for 61 of 62 items (rendering a bare `v`) and the literal string `"v1.0.0"` for the workflow item (rendering `vv1.0.0`). The type also lies: it declares `number`.

**Files:**
- Modify: `src/types/index.ts:277`
- Modify: `src/pages/KnowledgePage.tsx` (add a helper; fix two call sites)
- Test: `src/pages/__tests__/KnowledgePage.test.tsx` (extend)

- [ ] **Step 1: Write the failing test**

Add to `src/pages/__tests__/KnowledgePage.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest';
import { formatVersion } from '../KnowledgePage';

describe('formatVersion', () => {
  it('returns an em dash when the backend sends null', () => {
    expect(formatVersion(null)).toBe('—');
  });
  it('does not double the v prefix when the backend value already has one', () => {
    expect(formatVersion('v1.0.0')).toBe('v1.0.0');
  });
  it('adds the v prefix to a bare number', () => {
    expect(formatVersion(3)).toBe('v3');
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/pages/__tests__/KnowledgePage.test.tsx
```
Expected: FAIL — `formatVersion is not a function`.

- [ ] **Step 3: Add the helper and fix both call sites**

In `src/pages/KnowledgePage.tsx`, export the helper near the other module-level helpers:

```tsx
export function formatVersion(version: string | number | null | undefined): string {
  if (version === null || version === undefined || version === '') return '—';
  const text = String(version).trim();
  return text.startsWith('v') ? text : `v${text}`;
}
```

Replace line 222 (`v{it.currentVersion} · {owner ? ...`) with:
```tsx
                        {formatVersion(it.currentVersion)} · {owner ? owner.name : 'Shared'} · {formatDate(it.createdAt)}
```

Replace line 250 (`v{selected.currentVersion} · ...`) with:
```tsx
                    {formatVersion(selected.currentVersion)} · {ownerOf(selected, agents)?.name ?? 'Shared'} · approved{' '}
```

- [ ] **Step 4: Correct the type**

In `src/types/index.ts`, change `currentVersion: number;` to:
```ts
  currentVersion: string | number | null;
```

- [ ] **Step 5: Run the tests and build**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/pages/__tests__/KnowledgePage.test.tsx && pnpm build
```
Expected: PASS, and `tsc -b` accepts the widened type at both call sites.

- [ ] **Step 6: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard/src/pages/KnowledgePage.tsx agent-control-tower/act-dashboard/src/types/index.ts agent-control-tower/act-dashboard/src/pages/__tests__/KnowledgePage.test.tsx && git commit -m "fix(dashboard): render knowledge version labels without a bare or doubled v"
```

---

## Task 7: Fix the keyless fragment on the Runs table

`RunsPage.tsx:306-339` maps to a fragment `<>…</>` that carries no `key` while its children do, so React logs a unique-key warning as soon as any run row exists.

**Files:**
- Modify: `src/pages/RunsPage.tsx:306-339`
- Test: `src/pages/__tests__/RunsPage.live.test.tsx` (extend)

- [ ] **Step 1: Learn the existing scaffolding in this test file**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && grep -n "render\|listRuns\|mockResolved\|describe(\|it(" src/pages/__tests__/RunsPage.live.test.tsx | head -40
```
Expected: the file already renders `RunsPage` with a stubbed runs API. Reuse that helper and its fixture shape; do not add new mocks.

- [ ] **Step 2: Write the failing test**

Add to `src/pages/__tests__/RunsPage.live.test.tsx`, replacing `renderRunsPageWithTwoRuns()` with this file's own render helper invoked so the runs API returns **two** runs:

```tsx
  it('renders multiple run rows without a React key warning', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    renderRunsPageWithTwoRuns();
    const keyWarnings = errorSpy.mock.calls.filter((call) =>
      String(call[0]).includes('unique "key" prop'),
    );
    expect(keyWarnings).toHaveLength(0);
    errorSpy.mockRestore();
  });
```

- [ ] **Step 3: Run it and confirm it fails**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/pages/__tests__/RunsPage.live.test.tsx
```
Expected: FAIL — `keyWarnings` has length 1 once two rows render.

- [ ] **Step 4: Put the key on the fragment**

In `src/pages/RunsPage.tsx`, change:
```tsx
              {filteredRuns.map((run) => (
                <>
                  <tr key={run.id} className={expandedRun === run.id ? 'row-expanded' : ''}>
```
to:
```tsx
              {filteredRuns.map((run) => (
                <Fragment key={run.id}>
                  <tr className={expandedRun === run.id ? 'row-expanded' : ''}>
```
and its closing:
```tsx
                </>
              ))}
```
to:
```tsx
                </Fragment>
              ))}
```
Leave the inner detail row's `key={`${run.id}-detail`}` as it is, and add `Fragment` to the file's existing `react` import (for example `import { Fragment, useMemo, useState } from 'react';` — match whatever that import currently lists rather than adding a second `react` import).

- [ ] **Step 5: Run the test**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/pages/__tests__/RunsPage.live.test.tsx
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard/src/pages/RunsPage.tsx agent-control-tower/act-dashboard/src/pages/__tests__/RunsPage.live.test.tsx && git commit -m "fix(dashboard): key the Runs table fragments to silence the React key warning"
```

---

## Task 8: Route all timestamps through one pinned formatter

`src/utils/formatTime.ts` already provides the single-source-of-truth `formatTimestamp` (locale-independent `HH:mm` / `YYYY-MM-DD HH:mm`), but eleven date-bearing call sites bypass it with `toLocaleString()` / `toLocaleTimeString([])`, which follow the OS locale and leak strings such as `周六 11:34` into an English UI.

**Files:**
- Modify: `src/utils/formatTime.ts` (add `formatClock`)
- Modify: `src/components/TopBar.tsx`, `src/components/AgentDrawer.tsx`, `src/components/MorningBriefing.tsx`, `src/components/ReviewWorkspace.tsx`, `src/components/TaskDrawer.tsx`, `src/pages/KnowledgePage.tsx`, `src/pages/OpsPage.tsx`, `src/pages/ReportsPage.tsx`
- Modify: `src/pages/ScheduledJobsPage.tsx` (added after verification — two date sites here were missed by the original list and are required for acceptance criterion 10)
- Test: `src/utils/__tests__/formatTime.test.ts` (extend)

Do NOT touch `toLocaleString()` calls on **numbers** (`ExecutiveSummary.tsx:44`, `ReviewWorkspace.tsx:219`, `TaskDrawer.tsx:351`, `RunsPage.tsx:211`, `ReportsPage.tsx:323`) — those are thousands separators, not dates.

- [ ] **Step 1: Add the failing test for `formatClock`**

Add to `src/utils/__tests__/formatTime.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { formatClock } from '../formatTime';

describe('formatClock', () => {
  it('formats to zero-padded 24h minutes by default', () => {
    expect(formatClock(new Date(2026, 8, 12, 9, 5))).toBe('09:05');
  });
  it('includes zero-padded seconds when asked', () => {
    expect(formatClock(new Date(2026, 8, 12, 9, 5, 7), true)).toBe('09:05:07');
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/utils/__tests__/formatTime.test.ts
```
Expected: FAIL — `formatClock is not a function`.

- [ ] **Step 3: Implement `formatClock`**

Append to `src/utils/formatTime.ts`:

```ts
/**
 * Time-of-day only, pinned to 24h regardless of OS locale. `withSeconds` is for
 * live clocks; prefer formatTimestamp for anything that may be on another day.
 */
export function formatClock(date: Date, withSeconds = false): string {
  const hh = pad(date.getHours());
  const mm = pad(date.getMinutes());
  return withSeconds ? `${hh}:${mm}:${pad(date.getSeconds())}` : `${hh}:${mm}`;
}
```

- [ ] **Step 4: Replace the date-bearing call sites**

Apply exactly these replacements. For each file, add the `formatTime` import if it is not already present (`import { formatClock, formatTimestamp } from '../utils/formatTime';`, adjusting the relative depth for `src/pages/`).

- `src/components/TopBar.tsx:8` — replace the whole `formatClock` function body (`date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })`) with `return formatClock(date, true);` and delete the now-shadowed local function name by removing the local `formatClock` declaration entirely, letting the imported one be used at the call site. (The local function and the import cannot share a name — delete the local.)
- `src/components/AgentDrawer.tsx:54` — replace `return dt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });` with `return formatClock(dt, true);`
- `src/components/MorningBriefing.tsx:16` — replace the `new Date().toLocaleString([], { … })` expression with `formatTimestamp(new Date().toISOString())`
- `src/components/ReviewWorkspace.tsx:198` — replace `new Date(item.updatedAt).toLocaleString()` with `formatTimestamp(item.updatedAt)`
- `src/components/ReviewWorkspace.tsx:223` — replace `new Date(linkedRun.completedAt).toLocaleString()` with `formatTimestamp(linkedRun.completedAt)`
- `src/components/TaskDrawer.tsx:270` — replace `new Date(item.updatedAt).toLocaleString()` with `formatTimestamp(item.updatedAt)`
- `src/components/TaskDrawer.tsx:355` — replace `new Date(linkedRun.completedAt).toLocaleString()` with `formatTimestamp(linkedRun.completedAt)`
- `src/pages/KnowledgePage.tsx:144` — replace the `new Date(iso).toLocaleString(undefined, { … })` expression with `formatTimestamp(iso)`
- `src/pages/OpsPage.tsx:48` — replace `d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })` with `formatClock(d)`
- `src/pages/OpsPage.tsx:352` — replace `new Date(a.requestedAt).toLocaleString()` with `formatTimestamp(a.requestedAt)`
- `src/pages/OpsPage.tsx:506` — replace `new Date(r.createdAt).toLocaleString()` with `formatTimestamp(r.createdAt)`
- `src/pages/ReportsPage.tsx:38` — replace the `new Date(iso).toLocaleString(undefined, { … })` expression with `formatTimestamp(iso)`

Where a removed multi-line expression leaves an unused local variable or an unused helper, delete the helper too.

- [ ] **Step 5: Verify no date call site remains, and that nothing broke**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && grep -rn "toLocaleString\|toLocaleDateString\|toLocaleTimeString" src --include=*.tsx --include=*.ts | grep -v "__tests__"
```
Expected: only the **number** sites remain (`ExecutiveSummary.tsx`, `ReviewWorkspace.tsx:219`, `TaskDrawer.tsx:351`, `RunsPage.tsx`, `ReportsPage.tsx`). Then run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/utils/__tests__/formatTime.test.ts && pnpm build
```
Expected: PASS and a clean build.

- [ ] **Step 6: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard/src && git commit -m "fix(dashboard): pin every timestamp to the locale-independent formatter"
```

---

## Task 9: Give non-aria toasts a click-through

`Toast.tsx:449` appends a toast with no `action`, so an `approval.requested` or `run.completed` toast cannot be clicked through, while the `aria.notification` branch (line 433) has a "View" action.

**Files:**
- Modify: `src/components/Toast.tsx`
- Test: `src/components/__tests__/Toast.test.tsx` (extend)

- [ ] **Step 1: Learn this test file's event-push scaffolding**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && grep -n "useWebSocketContext\|render(\|lastMessage\|describe(\|it(" src/components/__tests__/Toast.test.tsx | head -30
```
Expected: the file already renders `Toast` against a mocked WebSocket context and pushes events through it. Note the render helper and the way it delivers a `WsEvent`.

- [ ] **Step 2: Write the failing test**

Add to `src/components/__tests__/Toast.test.tsx`, substituting `renderToast()` and `pushWsEvent(...)` with the helper names the grep revealed:

```tsx
it('offers a View action on an approval.requested toast', async () => {
  renderToast();
  pushWsEvent({ type: 'approval.requested', payload: { runId: 'r1' } });
  expect(await screen.findByRole('button', { name: 'View' })).toBeTruthy();
});
```

- [ ] **Step 3: Run it and confirm it fails**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/Toast.test.tsx
```
Expected: FAIL — no `View` button for the non-aria toast.

- [ ] **Step 4: Add the action**

In `src/components/Toast.tsx`, replace the `else` branch:
```tsx
      } else {
        // Human-readable label only — never expose the raw event type.
        const message = toastMessage(eventToUse.type, eventToUse.payload);
        setToasts((prev) => [...prev.slice(-4), { id, message, type: eventToUse.type }]);
      }
```
with:
```tsx
      } else {
        // Human-readable label only — never expose the raw event type.
        const message = toastMessage(eventToUse.type, eventToUse.payload);
        const route = routeForNotificationType(eventToUse.type);
        setToasts((prev) => [
          ...prev.slice(-4),
          {
            id,
            message,
            type: eventToUse.type,
            action: route ? { label: 'View', onClick: () => navigate(route) } : undefined,
          },
        ]);
      }
```
`routeForNotificationType` and `navigate` are already in scope in this component.

- [ ] **Step 5: Run the test**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/Toast.test.tsx
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard/src/components/Toast.tsx agent-control-tower/act-dashboard/src/components/__tests__/Toast.test.tsx && git commit -m "fix(dashboard): let approval toasts navigate to the review surface"
```

---

## Task 10: One confirmation policy for destructive actions

Four destructive actions fire immediately with no confirmation, while sibling actions in the same app confirm. Apply a single policy: confirm before the action, and surface failures.

**Files:**
- Create: `src/components/ConfirmDialog.tsx` (+ `src/components/__tests__/ConfirmDialog.test.tsx`)
- Modify: `src/components/KanbanBoard.tsx:1037-1050` (cancel task) + its test
- Modify: `src/pages/CrewPage.tsx:1074-1076` (retire selected) + its test
- Modify: `src/pages/KnowledgePage.tsx:1101-1119` (promote) + its test
- Modify: `src/components/TaskDrawer.tsx:447-453` (reject) + its test

**REVISED APPROACH (Wave 3, after cross-check).** The original instruction was "reuse each file's own existing modal markup". Three parallel implementers followed it and produced **three different** confirmation implementations — `CrewPage` used `.mini-scrim`/`.mini-dialog` with `role="dialog"` + `inert`; `KanbanBoard` used `.modal-overlay`/`.modal-dialog`/`.modal-actions` with no ARIA role; `KnowledgePage` used `.modal-overlay`/`.modal-dialog` with `role="dialog"` + `aria-modal` + `aria-labelledby`; and `TaskDrawer` has no dialog markup at all (only a `.task-drawer-backdrop` scrim), so its implementer correctly stopped rather than inventing a fourth. That is not "one confirmation policy", and it would repeat the six-competing-toast-implementations problem the audit already flagged.

Therefore Task 10 now ships **one shared component** used by all four surfaces:

```tsx
interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmLabel?: string;   // default 'Confirm'
  cancelLabel?: string;    // default 'Cancel'
  danger?: boolean;        // styles the confirm control
  onConfirm: () => void;
  onCancel: () => void;
}
```

**Not in scope, deliberately:** the REVIEW card's "Quick deny" control (`KanbanBoard.tsx`) still fires immediately and must stay that way. Spec decision D4 makes quick-tier asks (`TOOL_CALL` and short `QUESTION`) deliberately frictionless — one click is the intended UX. This "one confirmation policy" governs irreversible *operations* (cancelling a running task, retiring agents, promoting knowledge, rejecting a task), not approval *decisions*, which have their own tier model. Do not gate Quick deny.

A `busy` prop was specified in an earlier revision and implemented, but the canonical behaviour closes the dialog immediately on confirm, so no call site can ever render an in-flight state. It was removed rather than left as dead interface surface.

`ConfirmDialog` must: render nothing when `open` is false; use the repo's existing `.modal-overlay` + `.modal-dialog` + `.modal-actions` markup (the majority convention, already used by KanbanBoard and KnowledgePage); set `role="dialog"`, `aria-modal="true"` and `aria-labelledby` pointing at its title; dismiss on backdrop click and on `Escape`; autofocus the confirm control (matching `ReviewWorkspace`'s autoFocus precedent); and never contain a `window.confirm`. Confirm/cancel accessible names come from `confirmLabel`/`cancelLabel`, defaulting to exactly `Confirm` and `Cancel`.

Because the four call sites must stay consistent, implement the component and all four retrofits **in one agent**, not four in parallel — parallelising this specific task works against its goal. The existing three implementations from the parallel attempt are in the working tree and must be replaced, not kept.

The pattern the four call sites must all follow: the destructive control only sets pending state; the mutation fires from `onConfirm`; `onCancel` and backdrop/Escape clear the pending state without mutating; the pending state is cleared on completion (success or failure). Surface failures through each page's existing error channel — do not add a second toast implementation.

- [ ] **Step 1: Learn the four test files' scaffolding and mutation names**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && for f in src/components/__tests__/KanbanBoard.test.tsx src/pages/__tests__/CrewPage.test.tsx src/pages/__tests__/KnowledgePage.test.tsx src/components/__tests__/TaskDrawer.test.tsx; do echo "=== $f"; grep -n "render\|userEvent\|vi.mock\|mutate\|retireSelected\|handleReject\|updateKnowledge" "$f" | head -15; done
```
Expected: each file already renders its component with a stubbed API. Note the render helper name and the mutation spy for each.

The control that must become gated, and the mutation it must not reach before confirmation:

| File | Control (accessible name) | Mutation |
|---|---|---|
| `KanbanBoard.test.tsx` | `Cancel task` | `transitionMutation.mutate` |
| `CrewPage.test.tsx` | `Retire selected…` | `retireSelected` |
| `KnowledgePage.test.tsx` | `⤴ Promote` | `updateKnowledge` |
| `TaskDrawer.test.tsx` | `Reject` | `handleReject` |

The confirmation modal introduced in Step 3 uses a confirm button labelled `Confirm` and a dismiss button labelled `Cancel` in every case.

- [ ] **Step 2: Write the four failing tests**

Add one test per file, using that file's own render helper and mutation spy. The shape is identical; substitute the control's accessible name, the mutation spy, and the render helper:

```tsx
it('does not fire the destructive action until the operator confirms', async () => {
  renderWithOneTarget();                                   // the file's own helper
  const user = userEvent.setup();

  await user.click(screen.getByRole('button', { name: 'Cancel task' }));
  expect(transitionMutate).not.toHaveBeenCalled();          // no action before confirming

  await user.click(screen.getByRole('button', { name: 'Confirm' }));
  expect(transitionMutate).toHaveBeenCalledTimes(1);        // action fires only on confirm
});
```

For `KanbanBoard.test.tsx` use `{ name: 'Cancel task' }` and the transition spy; for `CrewPage.test.tsx` use `{ name: /Retire selected/ }` and the retire spy; for `KnowledgePage.test.tsx` use `{ name: /Promote/ }` and the `updateKnowledge` spy; for `TaskDrawer.test.tsx` use `{ name: 'Reject' }` and the `handleReject` spy.

- [ ] **Step 3: Run them and confirm they fail**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx src/pages/__tests__/CrewPage.test.tsx src/pages/__tests__/KnowledgePage.test.tsx src/components/__tests__/TaskDrawer.test.tsx
```
Expected: FAIL — each mutation is invoked on the first click, so `not.toHaveBeenCalled()` fails.

- [ ] **Step 4: Gate each action behind the existing modal pattern**

Use the modal markup already in these files (the `KnowledgePage` review dialog and the `CrewPage` add-agent dialog with `role="dialog"` + `aria-labelledby`) rather than `window.confirm`.

- `KanbanBoard.tsx`: replace the inline `onClick` that calls `transitionMutation.mutate({ id: item.id, status: 'CANCELLED' })` with an `onClick` that sets a `confirmCancelId` state; render the confirmation modal when `confirmCancelId` is set; call `transitionMutation.mutate({ id: confirmCancelId, status: 'CANCELLED' })` only from the modal's `Confirm` handler. Clear `confirmCancelId` in the mutation's `onSuccess` and in the `Cancel` handler.
- `CrewPage.tsx`: change the `Retire selected…` button's `onClick={retireSelected}` to set a `confirmingRetire` boolean; render the confirmation modal when true; call `retireSelected` from the `Confirm` handler and clear the flag in both handlers.
- `KnowledgePage.tsx`: change the Promote button's `onClick` to set a `confirmingPromote` boolean; the `Confirm` handler performs the existing call and keeps `queryClient.invalidateQueries({ queryKey: ['knowledge'] })`, and adds error surfacing because the current call has none:
  ```tsx
  updateKnowledge(selected.id, { status: 'PROMOTED' })
    .then(() => queryClient.invalidateQueries({ queryKey: ['knowledge'] }))
    .catch(() => setToast({ kind: 'error', msg: 'Promote failed. Please retry.' }));
  ```
  (Reuse whatever toast state this page already declares; do not add a second toast implementation.)
- `TaskDrawer.tsx`: change the `Reject` button's `onClick={handleReject}` to set a `confirmingReject` boolean; call `handleReject` from the `Confirm` handler.

- [ ] **Step 5: Run the tests**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx src/pages/__tests__/CrewPage.test.tsx src/pages/__tests__/KnowledgePage.test.tsx src/components/__tests__/TaskDrawer.test.tsx
```
Expected: PASS.

- [ ] **Step 6: Run the full frontend suite to catch regressions**

Run:
```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test && pnpm build
```
Expected: all suites pass; build clean. Existing tests that click these controls directly now stop at the modal — update them to also click `Confirm` rather than removing the assertion.

- [ ] **Step 7: Commit**

```bash
cd D:/project/aria-conductor && git add agent-control-tower/act-dashboard/src && git commit -m "fix(dashboard): confirm destructive actions before firing them"
```

---

## Final verification

- [ ] **Frontend**

```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test && pnpm build
```

- [ ] **Backend**

```bash
cd D:/project/aria-conductor/agent-control-tower && /c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn verify -pl act-app
```

- [ ] **A dead reference cannot survive**

```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && grep -rn "ApprovalsPage\|AgentsPage\|AgentToolPanel\|EvidenceDrawer\|useNotificationPrefs" src e2e --include=*.ts --include=*.tsx
```
Expected: no output.

```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && grep -rn "'/approvals'" e2e src --include=*.ts --include=*.tsx
```
Expected: no `page.goto` to `/approvals` and no route; API-path strings such as `/api/v1/approvals` and `/approvals?kanbanItemId=` are expected and fine.

- [ ] **E2E smoke against a running stack**

```bash
cd D:/project/aria-conductor/agent-control-tower/act-dashboard && CI=true PLAYWRIGHT_BROWSER_CHANNEL=chromium npx playwright test kanban-hitl.spec.ts overview-dashboard.spec.ts approvals-decision-flow.spec.ts ops-monitoring.spec.ts --workers=1 --reporter=list
```

---

## Deferred to later phases (do not implement here)

- Removing the Knowledge `Per-Agent Spaces` / `Promotion Path` / `Access Control` panels and the two inert Configure editors (Phase 2, spec Section 8.3).
- Removing the `Ops` and `Chat` pages, the three-layer rail, and the `Agent Backends` rename (Phase 2, spec Section 4).
- The operator-first signal zones, decision tiers, and adaptive all-clear layout (Phase 3, spec Section 5-6).
- Removing the per-second clock and the non-interactive KPI tiles — Phase 3 owns the Overview top bar rewrite; Task 5 here only pins the clock's format.
- Strengthening the three formerly vacuous E2E specs into real Review-surface assertions (Phase 3, spec Section 9.2) — Task 3 only stops them dangling.
- A WebSocket disconnect indicator (needs a new UI element).

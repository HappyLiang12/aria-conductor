# Kanban HITL Revision 10 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement spec revision 10 — auto `REVIEW_REQUEST` ask when a run completes into Review, approval-panel visibility on any column (badge + decision zone wherever asks exist), a short approval view on ask-less Review cards, all-card Waiting-on-you aggregation, and in-place expand (ReviewWorkspace rendered inside the Overview layout with CSS transitions, replacing the fixed overlay).

**Architecture:** One new backend listener (`KanbanReviewAskCreator`) reacts to `KanbanItemTransitionedEvent` (IN_PROGRESS→REVIEW) and idempotently creates the ask. Frontend: badge/zone gating switches from "REVIEW only" to "has pending asks"; a shared `DecisionPanel`/`ShortApprovalView` pair replaces the drawer's local render function; `DrawerContext.reviewExpanded:boolean` becomes `reviewTargetId:string|null`; OverviewPage renders `ReviewWorkspace` in its main column while a target is set. No new endpoints; no migration.

**Tech Stack:** Java 21 / Spring Boot 3.3 (act-execution), React 19 + TanStack Query, Vitest + RTL, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-08-kanban-hitl-redesign-design.md` §10 (authoritative for this plan).

**Branch:** `feat/kanban-hitl-redesign` (checked out at `C:/Users/User/.qoder/worktree/aria-conductor/GGp7xL`); commits land on it and push to PR #79.

**Verified facts you must know:**

- `KanbanItemTransitionedEvent(source, itemId, fromStatus, toStatus)` — act-common/event; broadcast as WS `kanban.transitioned`.
- `Approval` (act-common): `runId` is NOT NULL — an ask cannot be created without a real run UUID. `AskType {APPROVAL, QUESTION, REVIEW_REQUEST}`; `ApprovalStatus {PENDING, APPROVED, DENIED, EXPIRED}`.
- `ApprovalRepository` (act-execution): `findByStatusAndKanbanItemId(status, itemId)`, `countPendingByKanbanItemIds(ids)`, `denyPendingByKanbanItemId(itemId, reason, now)`, `markStaleByKanbanItemId(itemId, now)` — the @Modifying ones require `@Transactional` callers.
- `Run` (act-common) fields: `id, agentId, status, promptSeed, conversationId, maxIterations, totalTokensUsed, iterationCount, errorMessage, finalOutput, createdAt, updatedAt, completedAt` — Lombok `@Data/@Builder`.
- `KanbanReviewCardListener` + its test (`KanbanReviewCardListenerTest`, pure Mockito) in `act-execution/.../kanban/` — mirror their style for the new listener test.
- Frontend: `KanbanBoard.tsx` renders the badge at approximately line 311 gated on `item.status === 'REVIEW' && !!item.pendingAskCount`; `ExecutiveSummary.tsx:80-101` aggregates `reviewCards` (REVIEW only); `TaskDrawer.tsx` asks query `enabled: open && Boolean(itemId) && item?.status === 'REVIEW'` (~line 116), decision zone gate `item.status === 'REVIEW' && pendingAsks.length > 0` (~line 371), local `renderDecisionZone()` (~line 550 area), `renderReviewFullpage`-equivalent JSX block at the end (~line 523+, class `.review-fullpage`), Escape effect (~lines 282-290), leave-REVIEW auto-exit effect (~292-298). `OverviewPage.tsx` already renders `expanded-strip` when `state.taskDrawer.open && state.reviewExpanded`. `DrawerContext.tsx` has `reviewExpanded` boolean + `openReviewMode()/closeReviewMode()`.
- `transitionKanbanItem(id, {status, feedback?})` (api/kanban) hits the orchestrator; `listAsksByKanbanItem`, `answerAsk`, `approveApproval`, `rejectApproval` (api/approvals). Ask routing contract: QUESTION → `answerAsk`; APPROVAL/REVIEW_REQUEST → `approveApproval`/`rejectApproval`.
- Environment: maven at `/c/Users/User/tools/apache-maven-3.9.6/bin` (prepend PATH); run maven from `agent-control-tower/`; NEVER `mvn clean`; filtered surefire runs need `-Djacoco.line.minimum=0 -Djacoco.branch.minimum=0`; frontend from `agent-control-tower/act-dashboard` (pnpm). Bash cwd resets between calls. Commit messages: ASCII only (em-dashes break the bash hook).

---

### Task 1: Backend — auto REVIEW_REQUEST ask on IN_PROGRESS→REVIEW

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanReviewAskCreator.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanReviewAskCreatorTest.java`

- [ ] **Step 1: Write the failing test** (pure Mockito, mirror `KanbanReviewCardListenerTest`):

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KanbanReviewAskCreatorTest {

    private ApprovalRepository approvalRepository;
    private KanbanRepository kanbanRepository;
    private RunRepository runRepository;
    private KanbanReviewAskCreator creator;

    private KanbanItem card;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        kanbanRepository = mock(KanbanRepository.class);
        runRepository = mock(RunRepository.class);
        creator = new KanbanReviewAskCreator(approvalRepository, kanbanRepository, runRepository);

        card = KanbanItem.builder().id("c1").title("add CSV export")
                .status(KanbanStatus.REVIEW).priority(KanbanPriority.MEDIUM)
                .assignee("dev-agent").linkedRunId(runId.toString()).build();
        when(kanbanRepository.findById("c1")).thenReturn(Optional.of(card));
    }

    private KanbanItemTransitionedEvent event(String from, String to) {
        return new KanbanItemTransitionedEvent(this, "c1", from, to);
    }

    @Test
    void createsReviewRequestAskOnRunCompletion() {
        Run run = Run.builder().id(runId).status(RunStatus.COMPLETED)
                .promptSeed("Kanban task: add CSV export").totalTokensUsed(1234)
                .iterationCount(3).completedAt(java.time.Instant.now()).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "c1"))
                .thenReturn(List.of());

        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));

        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        Approval ask = captor.getValue();
        assertThat(ask.getRunId()).isEqualTo(runId);
        assertThat(ask.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(ask.getAskType()).isEqualTo(Approval.AskType.REVIEW_REQUEST);
        assertThat(ask.getKanbanItemId()).isEqualTo("c1");
        assertThat(ask.getContent()).contains("COMPLETED").contains("add CSV export");
        assertThat(ask.getContextMd()).contains("dev-agent").contains("add CSV export");
    }

    @Test
    void skipsNonReviewTransitions() {
        creator.onKanbanItemTransitioned(event("TODO", "IN_PROGRESS"));
        creator.onKanbanItemTransitioned(event("REVIEW", "DONE"));
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void skipsWhenPendingAskAlreadyExists() {
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "c1"))
                .thenReturn(List.of(Approval.builder().runId(runId).status(ApprovalStatus.PENDING).build()));
        when(runRepository.findById(runId)).thenReturn(Optional.of(
                Run.builder().id(runId).status(RunStatus.COMPLETED).build()));

        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void skipsWhenNoLinkedRun() {
        card.setLinkedRunId(null);
        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void skipsWhenCardMissing() {
        when(kanbanRepository.findById("c1")).thenReturn(Optional.empty());
        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));
        verify(approvalRepository, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd C:/Users/User/.qoder/worktree/aria-conductor/GGp7xL/agent-control-tower && mvn test -pl act-execution -Djacoco.line.minimum=0 -Djacoco.branch.minimum=0 -Dtest=KanbanReviewAskCreatorTest`
Expected: COMPILE ERROR — `KanbanReviewAskCreator` does not exist.

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec 10.2: every run-completed card entering REVIEW carries a REVIEW_REQUEST
 * ask so the Review column always surfaces a structured decision surface.
 * Idempotent: skipped when a PENDING ask already exists on the card.
 */
@Slf4j
@Component
public class KanbanReviewAskCreator {

    private final ApprovalRepository approvalRepository;
    private final KanbanRepository kanbanRepository;
    private final RunRepository runRepository;

    public KanbanReviewAskCreator(ApprovalRepository approvalRepository,
                                  KanbanRepository kanbanRepository,
                                  RunRepository runRepository) {
        this.approvalRepository = approvalRepository;
        this.kanbanRepository = kanbanRepository;
        this.runRepository = runRepository;
    }

    @EventListener
    @Transactional
    public void onKanbanItemTransitioned(KanbanItemTransitionedEvent event) {
        if (!"IN_PROGRESS".equals(event.getFromStatus()) || !"REVIEW".equals(event.getToStatus())) {
            return;
        }
        try {
            kanbanRepository.findById(event.getItemId()).ifPresent(item -> {
                if (!approvalRepository.findByStatusAndKanbanItemId(
                        ApprovalStatus.PENDING, item.getId()).isEmpty()) {
                    return;
                }
                if (item.getLinkedRunId() == null || item.getLinkedRunId().isBlank()) {
                    return; // Approval.runId is NOT NULL — nothing to attach to
                }
                UUID runId = UUID.fromString(item.getLinkedRunId());
                Run run = runRepository.findById(runId).orElse(null);

                String status = run != null && run.getStatus() != null ? run.getStatus().name() : "COMPLETED";
                String content = "Run " + runId.toString().substring(0, 8) + " completed (" + status
                        + ") — awaiting your review: " + item.getTitle();
                StringBuilder ctx = new StringBuilder()
                        .append("**Task:** ").append(item.getTitle()).append('\n')
                        .append("**Agent:** ").append(item.getAssignee() != null ? item.getAssignee() : "n/a").append('\n')
                        .append("**Run:** ").append(runId).append('\n');
                if (run != null) {
                    ctx.append("**Completed:** ").append(run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now()).append('\n')
                       .append("**Iterations:** ").append(run.getIterationCount())
                       .append(" | **Tokens:** ").append(run.getTotalTokensUsed()).append('\n');
                    if (item.getDescription() != null && !item.getDescription().isBlank()) {
                        ctx.append('\n').append(shorten(item.getDescription(), 1500));
                    } else if (run.getPromptSeed() != null) {
                        ctx.append('\n').append(shorten(run.getPromptSeed(), 1500));
                    }
                }

                approvalRepository.save(Approval.builder()
                        .runId(runId)
                        .status(ApprovalStatus.PENDING)
                        .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                        .askType(Approval.AskType.REVIEW_REQUEST)
                        .kanbanItemId(item.getId())
                        .content(content)
                        .contextMd(ctx.toString())
                        .build());
                log.info("Auto-created REVIEW_REQUEST ask for card {} (run {})", item.getId(), runId);
            });
        } catch (Exception e) {
            log.warn("Auto REVIEW_REQUEST ask failed for {}: {}", event.getItemId(), e.getMessage());
        }
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass** (same command as Step 2) — PASS. Then run the full module lane: `mvn test -pl act-execution -Djacoco.line.minimum=0 -Djacoco.branch.minimum=0` — PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanReviewAskCreator.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanReviewAskCreatorTest.java
git commit -m "feat(kanban): auto REVIEW_REQUEST ask when a run completes into Review"
```

---

### Task 2: Frontend — badge + decision zone follow asks on ANY column

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx` (badge gate ~line 311)
- Modify: `agent-control-tower/act-dashboard/src/components/TaskDrawer.tsx` (asks query `enabled` ~line 116; zone gate ~line 371)
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/KanbanBoard.test.tsx`, `agent-control-tower/act-dashboard/src/components/__tests__/TaskDrawer.test.tsx`

- [ ] **Step 1: Failing tests.** In `KanbanBoard.test.tsx` add:

```tsx
  it('shows the asks badge on an IN_PROGRESS card with pending asks (mid-run gate)', () => {
    renderKanban([baseItem({ id: 'k-ip', status: 'IN_PROGRESS', pendingAskCount: 1 })]);
    expect(screen.getByText('1 asks')).toBeInTheDocument();
  });
```

In `TaskDrawer.test.tsx` add (mirror the existing decision-zone test's mocks — `mockedGetKanbanItem`/`mockedListAsksByKanbanItem`; read the file's helper names first and reuse them exactly):

```tsx
  it('shows the decision zone on an IN_PROGRESS card when it has pending asks', async () => {
    mockedGetKanbanItem.mockResolvedValue({ ...baseItem(), status: 'IN_PROGRESS', pendingAskCount: 1 });
    mockedListAsksByKanbanItem.mockResolvedValue([
      { id: 'a9', askType: 'APPROVAL', content: 'gate approval mid-run', status: 'PENDING' },
    ]);
    renderDrawer();
    expect(await screen.findByText(/NEEDS YOUR DECISION/)).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run to verify FAIL** — `npx vitest run src/components/__tests__/KanbanBoard.test.tsx src/components/__tests__/TaskDrawer.test.tsx`

- [ ] **Step 3: Implement.**

`KanbanBoard.tsx` — change the badge condition from:

```tsx
{item.status === 'REVIEW' && !!item.pendingAskCount && (
```

to:

```tsx
{!!item.pendingAskCount && (
```

`TaskDrawer.tsx` — change the asks query `enabled` from:

```tsx
    enabled: open && Boolean(itemId) && item?.status === 'REVIEW',
```

to:

```tsx
    enabled: open && Boolean(itemId),
```

and change the zone gate (~line 371) from:

```tsx
{item.status === 'REVIEW' && pendingAsks.length > 0 && renderDecisionZone()}
```

to:

```tsx
{pendingAsks.length > 0 && renderDecisionZone()}
```

Leave the fullpage gate (`open && state.reviewExpanded && item?.status === 'REVIEW'`) unchanged — expansion stays a Review-only affordance (spec 10.3).

- [ ] **Step 4: Run to verify PASS** (both files), then the full suite: `npx vitest run`.

- [ ] **Step 5: Commit** — `feat(dashboard): ask badges and decision zones follow asks on any column`

---

### Task 3: Frontend — short approval view on ask-less Review cards + all-card Waiting-on-you

**Files:**
- Create: `agent-control-tower/act-dashboard/src/components/ReviewPanels.tsx` (exports `DecisionPanel` and `ShortApprovalView`)
- Modify: `agent-control-tower/act-dashboard/src/components/TaskDrawer.tsx` (replace `renderDecisionZone()` body with `<DecisionPanel>`; add `<ShortApprovalView>` for REVIEW + no asks)
- Modify: `agent-control-tower/act-dashboard/src/components/ExecutiveSummary.tsx:80-101` (aggregate all cards)
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/ReviewPanels.test.tsx` (new), `ExecutiveSummary.test.tsx` (extend)

- [ ] **Step 1: Write the failing tests** (`ReviewPanels.test.tsx`; mock `../api/kanban` and `../api/approvals` following the conventions in `KanbanBoard.test.tsx`):

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DecisionPanel, ShortApprovalView } from '../ReviewPanels';
import type { Approval, KanbanItem } from '../../types';

vi.mock('../../api/kanban', () => ({
  transitionKanbanItem: vi.fn(),
}));
vi.mock('../../api/approvals', () => ({
  answerAsk: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));

import { transitionKanbanItem } from '../../api/kanban';
import { approveApproval, rejectApproval, answerAsk } from '../../api/approvals';

const mockedTransition = vi.mocked(transitionKanbanItem);
const mockedApprove = vi.mocked(approveApproval);
const mockedReject = vi.mocked(rejectApproval);
const mockedAnswer = vi.mocked(answerAsk);

const item = { id: 'k-1', title: 'add CSV export', status: 'REVIEW', assignee: 'dev-agent', linkedRunId: 'run-abc' } as KanbanItem;

beforeEach(() => vi.clearAllMocks());

describe('DecisionPanel', () => {
  it('renders each pending ask and routes gate asks to /decide endpoints', async () => {
    mockedApprove.mockResolvedValue({} as Approval);
    render(<DecisionPanel item={item} pendingAsks={[
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' } as Approval,
      { id: 'a2', askType: 'QUESTION', content: 'BOM?', status: 'PENDING' } as Approval,
    ]} />);
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0]);
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledWith('a1', undefined));
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[1]);
    await waitFor(() => expect(mockedAnswer).toHaveBeenCalledWith('a2', expect.objectContaining({ approved: true })));
    expect(mockedReject).not.toHaveBeenCalled();
  });
});

describe('ShortApprovalView', () => {
  it('shows run summary and quick actions for ask-less Review cards', () => {
    render(<ShortApprovalView item={item} />);
    expect(screen.getByText(/Run completed/i)).toBeInTheDocument();
    expect(screen.getByText(/dev-agent/)).toBeInTheDocument();
  });

  it('Approve transitions the card to DONE', async () => {
    mockedTransition.mockResolvedValue(item);
    render(<ShortApprovalView item={item} />);
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(mockedTransition).toHaveBeenCalledWith('k-1', { status: 'DONE' }));
  });

  it('Request changes sends feedback and transitions to TODO', async () => {
    mockedTransition.mockResolvedValue({ ...item, status: 'TODO' });
    render(<ShortApprovalView item={item} />);
    await userEvent.type(screen.getByLabelText('Request-changes feedback'), 'use streaming');
    await userEvent.click(screen.getByRole('button', { name: /request changes/i }));
    await waitFor(() => expect(mockedTransition).toHaveBeenCalledWith('k-1', { status: 'TODO', feedback: 'use streaming' }));
  });

  it('Deny transitions the card to CANCELLED', async () => {
    mockedTransition.mockResolvedValue({ ...item, status: 'CANCELLED' });
    render(<ShortApprovalView item={item} />);
    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));
    await waitFor(() => expect(mockedTransition).toHaveBeenCalledWith('k-1', { status: 'CANCELLED' }));
  });
});
```

In `ExecutiveSummary.test.tsx` change the aggregation expectation: asks on a TODO card now count:

```tsx
    mockedListKanbanItems.mockResolvedValue([
      { id: 'r1', status: 'REVIEW', pendingAskCount: 2 } as any,
      { id: 'r2', status: 'TODO', pendingAskCount: 3 } as any, // mid-run gate ask
    ]);
    // existing assertions: value 2 -> change to 5; click opens r1 (first card WITH asks)
```

(read the existing test and update value/click assertions to `5` and keep `r1`.)

- [ ] **Step 2: Run to verify FAIL.**

- [ ] **Step 3: Implement `ReviewPanels.tsx`:**

```tsx
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { transitionKanbanItem } from '../api/kanban';
import { answerAsk, approveApproval, rejectApproval } from '../api/approvals';
import type { Approval, KanbanItem, KanbanStatus } from '../types';

interface PanelProps {
  item: KanbanItem;
  pendingAsks: Approval[];
}

/** Ask decision surface - used by the collapsed drawer and the ReviewWorkspace rail. */
export function DecisionPanel({ item, pendingAsks }: PanelProps) {
  const queryClient = useQueryClient();
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const resolveAsk = useMutation({
    mutationFn: ({ ask, approved, answer }: { ask: Approval; approved: boolean; answer?: string }) =>
      ask.askType === 'QUESTION'
        ? answerAsk(ask.id, { approved, answer })
        : approved
          ? approveApproval(ask.id, answer)
          : rejectApproval(ask.id, answer),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
    },
  });

  return (
    <div className="decision-zone">
      <div className="dz-title">⚑ NEEDS YOUR DECISION · {pendingAsks.length} asks</div>
      {pendingAsks.map((ask) => (
        <div key={ask.id} className="ask-card">
          <div className="ask-q">
            {ask.askType === 'QUESTION' ? 'Question' : ask.askType === 'REVIEW_REQUEST' ? 'Review' : 'Approval'}
            : {ask.content?.slice(0, 160)}
          </div>
          {ask.contextMd && <div className="ask-ctx">{ask.contextMd}</div>}
          <textarea
            className="dod-textarea"
            rows={2}
            aria-label={`Answer for ask ${ask.id}`}
            placeholder="Answer / feedback (optional)"
            value={answers[ask.id] ?? ''}
            onChange={(e) => setAnswers((prev) => ({ ...prev, [ask.id]: e.target.value }))}
          />
          <div className="ask-actions">
            <button className="btn primary" onClick={() => resolveAsk.mutate({ ask, approved: true, answer: answers[ask.id] || undefined })}>Approve</button>
            <button className="btn" onClick={() => resolveAsk.mutate({ ask, approved: false, answer: answers[ask.id] || undefined })}>Deny</button>
          </div>
        </div>
      ))}
      <button className="btn primary" onClick={() => pendingAsks.forEach((a) => resolveAsk.mutate({ ask: a, approved: true, answer: answers[a.id] || undefined }))}>
        ✓ Approve all
      </button>
    </div>
  );
}

/** Quick decision surface for ask-less Review cards (spec 10.1). */
export function ShortApprovalView({ item }: { item: KanbanItem }) {
  const queryClient = useQueryClient();
  const [feedback, setFeedback] = useState('');
  const [error, setError] = useState<string | null>(null);
  const move = useMutation({
    mutationFn: ({ status, feedback: fb }: { status: KanbanStatus; feedback?: string }) =>
      transitionKanbanItem(item.id, { status, feedback: fb }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['kanban-items'] }),
    onError: () => setError('Action rejected — the card is unchanged.'),
  });

  return (
    <div className="decision-zone short-view">
      <div className="dz-title">Run completed — quick decision</div>
      <div className="ask-ctx">
        agent {item.assignee ?? 'n/a'} · run {item.linkedRunId ? item.linkedRunId.slice(0, 8) : '—'}
      </div>
      <textarea
        className="dod-textarea"
        rows={2}
        aria-label="Request-changes feedback"
        placeholder="What should change? (sent back to the agent)"
        value={feedback}
        onChange={(e) => setFeedback(e.target.value)}
      />
      {error && <div className="kanban-form-error">{error}</div>}
      <div className="ask-actions">
        <button className="btn primary" onClick={() => move.mutate({ status: 'DONE' })}>Approve</button>
        <button className="btn" onClick={() => { move.mutate({ status: 'TODO', feedback: feedback.trim() || undefined }); setFeedback(''); }}>Request changes</button>
        <button className="btn danger" onClick={() => move.mutate({ status: 'CANCELLED' })}>Deny</button>
      </div>
    </div>
  );
}
```

`TaskDrawer.tsx`:
- import `{ DecisionPanel, ShortApprovalView } from './ReviewPanels';`
- replace `{pendingAsks.length > 0 && renderDecisionZone()}` with:

```tsx
{pendingAsks.length > 0 && <DecisionPanel item={item} pendingAsks={pendingAsks} />}
{item.status === 'REVIEW' && pendingAsks.length === 0 && <ShortApprovalView item={item} />}
```

- delete the local `renderDecisionZone()` function and the now-unused local `answers` state if it was only used there (check: the fullpage JSX also references it — that JSX is deleted in Task 4, so coordinate: if Task 3 lands first, keep `renderDecisionZone` delegating: `const renderDecisionZone = () => <DecisionPanel item={item!} pendingAsks={pendingAsks} />;` and delete it in Task 4).

`ExecutiveSummary.tsx:80-82` — replace:

```tsx
  const reviewCards = (kanbanItems ?? []).filter((i) => i.status === 'REVIEW');
  const waitingAsks = reviewCards.reduce((sum, i) => sum + (i.pendingAskCount ?? 0), 0);
  const firstReviewCard: KanbanItem | undefined = reviewCards[0];
```

with:

```tsx
  const cardsWithAsks = (kanbanItems ?? []).filter((i) => (i.pendingAskCount ?? 0) > 0);
  const waitingAsks = cardsWithAsks.reduce((sum, i) => sum + (i.pendingAskCount ?? 0), 0);
  const firstReviewCard: KanbanItem | undefined = cardsWithAsks[0];
```

(`firstReviewCard` now = first card WITH asks, per spec 10.1; detail line: change `'Review cards need a decision'` to `'Cards need a decision'`.)

- [ ] **Step 4: Run to verify PASS** — the three files + full `npx vitest run`.

- [ ] **Step 5: Commit** — `feat(dashboard): short approval view and all-card waiting aggregate`

---

### Task 4: Frontend — in-place expand (ReviewWorkspace replaces the overlay)

**Files:**
- Create: `agent-control-tower/act-dashboard/src/components/ReviewWorkspace.tsx`
- Modify: `agent-control-tower/act-dashboard/src/components/DrawerContext.tsx` (`reviewExpanded:boolean` → `reviewTargetId:string|null`)
- Modify: `agent-control-tower/act-dashboard/src/components/TaskDrawer.tsx` (delete the fullpage JSX + Escape effect + leave-REVIEW effect; Expand → `openReviewMode(item.id)`; remove prev/next from drawer header)
- Modify: `agent-control-tower/act-dashboard/src/pages/OverviewPage.tsx` (render `<ReviewWorkspace>` in the main column when a target is set; wire prev/next + collapse via context)
- Modify: `agent-control-tower/act-dashboard/src/styles/index.css` (remove `.review-fullpage` rules; add transition rules)
- Test: update `DrawerContext.test.tsx`, `TaskDrawer.test.tsx`, `OverviewPage.test.tsx`; new `ReviewWorkspace.test.tsx`

- [ ] **Step 1: DrawerContext** — replace the boolean with:

```tsx
export interface DrawerState {
  taskDrawer: DrawerSlot;
  agentDrawer: AgentDrawerSlot;
  reviewTargetId: string | null;
}
```

initial `reviewTargetId: null`; context value:

```tsx
  openReviewMode: (itemId: string) => void;  // sets reviewTargetId AND collapses the drawer
  closeReviewMode: () => void;               // clears reviewTargetId AND reopens the drawer on that card
```

implementations:

```tsx
  const openReviewMode = useCallback((itemId: string) => {
    setState((prev) => ({
      taskDrawer: { open: false, itemId: prev.taskDrawer.itemId },
      agentDrawer: prev.agentDrawer,
      reviewTargetId: itemId,
    }));
  }, []);

  const closeReviewMode = useCallback(() => {
    setState((prev) => ({
      taskDrawer: { open: true, itemId: prev.reviewTargetId ?? prev.taskDrawer.itemId },
      agentDrawer: prev.agentDrawer,
      reviewTargetId: null,
    }));
  }, []);
```

`closeTaskDrawer` clears `reviewTargetId` (set to null). Update the `useMemo` value and the interface. Escape-guard selector `.review-fullpage` can stay (harmless) or be removed.

- [ ] **Step 2: Failing tests** (rewrite the affected ones; keep the routing-contract tests from Task 3's DecisionPanel — they moved to `ReviewPanels.test.tsx`):
  - `DrawerContext.test.tsx`: `openReviewMode('k1')` → `state.reviewTargetId==='k1' && state.taskDrawer.open===false`; `closeReviewMode()` → `reviewTargetId===null && taskDrawer.open===true && taskDrawer.itemId==='k1'`.
  - `ReviewWorkspace.test.tsx` (new; mock api modules; wrap in DrawerProvider or assert via callbacks as convenient):

```tsx
  it('renders markdown pane and decision rail with asks', async () => { ... });      // .review-workspace + .spec-review-markdown + ask content
  it('shows ShortApprovalView when the card has no asks', async () => { ... });      // /Run completed/i
  it('Collapse returns to the drawer on the same card', async () => { ... });        // click Collapse -> reviewTargetId null + taskDrawer open with itemId (assert via context state or window event)
  it('auto-exits when the card leaves REVIEW', async () => { ... });                 // refetch returns status DONE -> workspace unmounts
  it('prev/next in the header navigates sibling review cards', async () => { ... }); // seed ['kanban-items'] cache with two REVIEW cards
```

  - `OverviewPage.test.tsx`: when `state.reviewTargetId` set (pre-seed via provider state or openReviewMode in the test), the main column contains `.review-workspace`, the strip (`.expanded-strip`) is present, and `KanbanBoard`/`ExecutiveSummary` are NOT rendered; layout style is single-column.
  - `TaskDrawer.test.tsx`: Expand click → drawer closes (`state.taskDrawer.open === false` via context) and `reviewTargetId` set; the old `.review-fullpage` tests are DELETED (feature moved).

- [ ] **Step 3: Run to verify FAIL.**

- [ ] **Step 4: Implement.**

`ReviewWorkspace.tsx` (new; ~170 lines):

```tsx
import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getKanbanItem } from '../api/kanban';
import { listAsksByKanbanItem } from '../api/approvals';
import { MarkdownViewer } from './MarkdownViewer';
import { DecisionPanel, ShortApprovalView } from './ReviewPanels';
import { useDrawerContext } from './DrawerContext';
import type { Approval, KanbanItem } from '../types';

/** Spec 10.3: the expanded story card, rendered in-flow inside the Overview layout. */
export function ReviewWorkspace({ itemId }: { itemId: string }) {
  const { state, openTaskDrawer, closeReviewMode } = useDrawerContext();
  const queryClient = useQueryClient();

  const itemQuery = useQuery({ queryKey: ['kanban', 'item', itemId], queryFn: () => getKanbanItem(itemId), retry: false });
  const item = itemQuery.data;
  const asksQuery = useQuery({
    queryKey: ['kanban', 'asks', itemId],
    queryFn: () => listAsksByKanbanItem(itemId),
    enabled: Boolean(itemId),
  });
  const pendingAsks = (asksQuery.data ?? []).filter((a) => a.status === 'PENDING');

  // Auto-exit when the card leaves REVIEW (e.g. short-view Approve transitions to DONE).
  useEffect(() => {
    if (item && item.status !== 'REVIEW') closeReviewMode();
  }, [item, closeReviewMode]);

  const siblings = useMemo(
    () => ((queryClient.getQueryData<KanbanItem[]>(['kanban-items']) ?? []) as KanbanItem[])
      .filter((i) => i.status === 'REVIEW'),
    [queryClient, itemQuery.data],
  );
  const idx = siblings.findIndex((i) => i.id === itemId);
  const prev = idx > 0 ? siblings[idx - 1] : undefined;
  const next = idx >= 0 && idx < siblings.length - 1 ? siblings[idx + 1] : undefined;

  if (!item) {
    return <div className="review-workspace"><div className="loading-spinner" style={{ padding: 20 }}><div className="spinner" /></div></div>;
  }

  return (
    <section className="panel review-workspace" data-testid="review-workspace">
      <div className="rw-head">
        <div>
          <div className="id">TASK · {item.id.slice(0, 8).toUpperCase()}</div>
          <h3>{item.title}</h3>
        </div>
        <div className="rw-nav">
          <button className="btn" disabled={!prev} onClick={() => prev && openTaskDrawer(prev.id) /* replaced below */}>← prev</button>
          <button className="btn" disabled={!next} onClick={() => next && openTaskDrawer(next.id)}>next →</button>
          <button className="btn" onClick={closeReviewMode}>⤡ Collapse</button>
        </div>
      </div>
      <div className="rw-body">
        <div className="rf-spec">
          <MarkdownViewer content={pendingAsks[0]?.content ?? item.description ?? ''} />
        </div>
        <div className="rf-decisions">
          {pendingAsks.length > 0
            ? <DecisionPanel item={item} pendingAsks={pendingAsks} />
            : <ShortApprovalView item={item} />}
        </div>
      </div>
    </section>
  );
}
```

IMPORTANT — prev/next navigation semantics: sibling switch must keep the WORKSPACE open on the sibling (not reopen the drawer). Implement via a DrawerContext addition: `openReviewMode(sibling.id)` also works (it sets reviewTargetId + collapses the already-collapsed drawer — harmless). Use `openReviewMode(prev.id)` / `openReviewMode(next.id)` for the two buttons (adjust the JSX above accordingly — the `openTaskDrawer` calls in the snippet are placeholders to be replaced with `openReviewMode`).

`OverviewPage.tsx` — replace the expanded logic:

```tsx
import ReviewWorkspace from '../components/ReviewWorkspace';

export default function OverviewPage() {
  const { state } = useDrawerContext();
  const reviewId = state.reviewTargetId;
  return (
    <div className="view-zone" data-view="overview">
      <div className="layout"
           style={{ gridTemplateColumns: reviewId ? 'minmax(0, 1fr)' : 'minmax(0, 1fr) minmax(320px, 380px)' }}>
        <div className="col">
          {reviewId ? (
            <ReviewWorkspace itemId={reviewId} />
          ) : (
            <>
              <ExecutiveSummary />
              <KanbanBoard />
              <MorningBriefing />
            </>
          )}
        </div>
        {!reviewId && (
          <div className="col">
            <AgentTeam />
            <ReviewQueue />
            <ActivityTimeline />
          </div>
        )}
      </div>
      {reviewId && (
        <div className="layout expanded-strip">
          <AgentTeam />
          <ReviewQueue />
          <ActivityTimeline />
          <MorningBriefing />
        </div>
      )}
    </div>
  );
}
```

`TaskDrawer.tsx`:
- Expand button onClick → `openReviewMode(item.id)` (single call; context collapses the drawer).
- DELETE: the fullpage JSX block (`review-fullpage`), the Escape effect for the fullpage, the leave-REVIEW auto-exit effect (moved into ReviewWorkspace), prev/next buttons in the drawer header, and `renderDecisionZone` if still present (replaced by `<DecisionPanel>` in Task 3; drawer also gains `<ShortApprovalView>` from Task 3).

`index.css`:
- DELETE the `.review-fullpage` rules (dark + light override) and its z-index comment.
- ADD:

```css
.layout { transition: grid-template-columns 280ms ease; }
.review-workspace { animation: reviewIn 240ms ease; display: flex; flex-direction: column; gap: 12px; }
@keyframes reviewIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
.review-workspace .rw-head { display: flex; align-items: center; gap: 12px; }
.review-workspace .rw-nav { margin-left: auto; display: flex; gap: 8px; }
.review-workspace .rw-body { display: flex; gap: 16px; min-height: 420px; }
.review-workspace .rf-spec { flex: 1.4; min-width: 0; overflow-y: auto; background: rgba(255,255,255,0.02); border-radius: 8px; padding: 16px; max-height: 60vh; }
.review-workspace .rf-decisions { flex: 1; min-width: 320px; overflow-y: auto; max-height: 60vh; }
.expanded-strip { animation: reviewIn 240ms ease; }
```

(Reuse the existing `.expanded-strip` height rule if present; keep widgets scrollable.)

- [ ] **Step 5: Run to verify PASS** — `npx vitest run` (full), `npx tsc -b --noEmit`, `pnpm build`.

- [ ] **Step 6: LIVE VISUAL VERIFICATION (mandatory per the drag lesson).** The dev server on :5173 serves this branch. In a real browser: create/seed a Review card without asks → click it → drawer shows the short approval view → `⤢ Expand` → workspace appears IN the layout (side menu visible, widgets in the bottom strip, animated), Collapse → drawer reopens. Take screenshots of collapsed + expanded states. If the native-drag or transition misbehaves, fix before continuing.

- [ ] **Step 7: Commit** — `feat(dashboard): in-place review workspace with layout transitions`

---

### Task 5: Verification sweep + push

- [ ] **Step 1:** Backend lanes (from `agent-control-tower/`):

```bash
mvn test -pl act-execution,act-aria -Djacoco.line.minimum=0 -Djacoco.branch.minimum=0
mvn verify -pl act-app -DskipTests -Dit.test=KanbanPickupIntegrationTest -Djacoco.line.minimum=0 -Djacoco.branch.minimum=0
```

- [ ] **Step 2:** Frontend + MCP:

```bash
cd agent-control-tower/act-dashboard && npx vitest run && npx tsc -b --noEmit && pnpm build
cd ../../packages/mcp-server && npx vitest run
```

- [ ] **Step 3:** E2E (stack must be running):

```bash
cd agent-control-tower/act-dashboard && npx playwright test e2e/kanban-hitl.spec.ts e2e/kanban-board.spec.ts --project=chromium
```

- [ ] **Step 4:** Live scenario: dispatch a real run to an opencode hitl agent via the UI, confirm the mid-run ask badge now appears on the In Progress card (G2 fix), approve via the decision zone, confirm the card completes and (if it lands REVIEW ask-less) the short view shows.

- [ ] **Step 5:** Commit anything pending, `git push` (PR #79 updates automatically).

---

## Self-Review

- Spec §10.1 → Tasks 2-3 (badge/zone any column; short view; all-card aggregate). §10.2 → Task 1. §10.3 → Task 4. §10.4 → tests embedded in Tasks 1-4. Covered.
- Placeholders: the ReviewWorkspace snippet marks the prev/next `openTaskDrawer` calls as to-be-replaced with `openReviewMode` — stated explicitly inline, not left open.
- Type consistency: `reviewTargetId` used identically in DrawerContext/OverviewPage/TaskDrawer; `DecisionPanel {item, pendingAsks}` and `ShortApprovalView {item}` match usage in drawer + workspace; ask-routing contract unchanged (QUESTION → answerAsk; gate asks → approve/reject).

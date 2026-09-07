# UI Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every finding from the UI audit (5 BUG / 1 STUB / 9 UX-GAP / 15 code findings) — progress-event persistence with replay, WS subscription model, Approvals rework (stuck chains + history detail + markdown), quick-wins batch, and the audit-incident knowledge-state revert.

**Architecture:** Backend persists `RunProgressEvent` to a new `run_progress_events` table (Flyway V51) at the broadcast point and exposes `GET /api/v1/runs/{id}/progress?afterSeq=`; the frontend `useWebSocket` hook switches from single-`lastMessage` state to a callback-subscription model (no dropped frames); AgentDrawer fetches the backlog on open and appends live frames; ApprovalsPage gains a stuck-chains panel, expandable history detail, Deny confirm, and the full markdown renderer with the `.spec-review-markdown` class.

**Tech Stack:** Java 21 / Spring Boot 3.4.5 (Flyway V51, JPA, existing WS/STOMP), React 19 + TanStack Query + vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-06-ui-audit-fixes-design.md` (commit `73a9288` on branch `fix/ui-audit-findings`).

**Working conventions:** branch `fix/ui-audit-findings` (already contains the spec commit); all plan commands run from repo root unless noted; maven `export PATH="/c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin:$PATH"`; the local backend on :8080 holds the H2 file lock — stop it (`netstat -ano | grep :8080` → PID → `taskkill //F //PID <pid>`) before any `act-app`/full-suite test run, restart after if needed. Strict TDD on every task. Pre-commit guardrail must pass (never bypass). Never stage the 6 modified `e2e/screenshots/timeout-cancel/*.png` or untracked scratch files.

**Task 0 — DONE:** branch `fix/ui-audit-findings` created from origin/main; spec committed as `73a9288`.

---

### Task 1: Backend — Flyway V51 + `RunProgressEventEntity` + repository

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V51__run_progress_events.sql`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/RunProgressEventEntity.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/repository/RunProgressEventRepository.java`
- Create: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/repository/RunProgressEventRepositoryTest.java`

- [ ] **Step 1: Write the migration** — `V51__run_progress_events.sql`:

```sql
-- Persist run progress fragments so the AgentDrawer Live Activity Stream can
-- replay history (previously broadcast-only per the S8 "transient" decision —
-- reversed by the 2026-09-06 UI audit).
CREATE TABLE run_progress_events (
    id         UUID         NOT NULL PRIMARY KEY,
    run_id     UUID         NOT NULL,
    agent_id   UUID,
    iteration  INT          NOT NULL,
    kind       VARCHAR(32)  NOT NULL,
    seq        BIGINT       NOT NULL,
    content    TEXT,
    tool_name  VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_progress_run FOREIGN KEY (run_id) REFERENCES runs (id)
);
CREATE INDEX idx_progress_run_seq ON run_progress_events (run_id, seq);
CREATE INDEX idx_progress_created ON run_progress_events (created_at);
```

If `runs (id)` has a different column type in the migrations (verify: `grep -n "CREATE TABLE runs" agent-control-tower/act-app/src/main/resources/db/migration/*.sql`), match the FK column type exactly; if the FK is risky for row volume, drop the FK constraint line and keep the plain index (document in the commit body which you chose).

- [ ] **Step 2: Write the failing repository test** — `RunProgressEventRepositoryTest.java` (follow the existing act-common repository test pattern — `grep -rln "@DataJpaTest" agent-control-tower/act-common/src/test` for the convention; if none exists, use the act-knowledge H2 slice pattern with `@JUnitConfig`-style base from act-test-support):

```java
package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RunProgressEventEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RunProgressEventRepositoryTest {

    @Autowired
    RunProgressEventRepository repository;

    private RunProgressEventEntity entry(UUID runId, long seq, String kind) {
        return RunProgressEventEntity.builder()
                .id(UUID.randomUUID()).runId(runId).agentId(UUID.randomUUID())
                .iteration(1).kind(kind).seq(seq).content("fragment " + seq)
                .toolName(null).createdAt(Instant.now())
                .build();
    }

    @Test
    void findByRunIdAndSeqAfterOrderBySeqAsc_returnsOrderedBacklog() {
        UUID runId = UUID.randomUUID();
        repository.saveAll(List.of(entry(runId, 3, "THINKING"), entry(runId, 1, "THINKING"), entry(runId, 2, "TOOL_CALL")));

        List<RunProgressEventEntity> all = repository.findByRunIdOrderBySeqAsc(runId);
        List<RunProgressEventEntity> after = repository.findByRunIdAndSeqAfterOrderBySeqAsc(runId, 1L);

        assertThat(all).extracting(RunProgressEventEntity::getSeq).containsExactly(1L, 2L, 3L);
        assertThat(after).extracting(RunProgressEventEntity::getSeq).containsExactly(2L, 3L);
    }

    @Test
    void deleteByCreatedAtBefore_removesOnlyOldRows() {
        UUID runId = UUID.randomUUID();
        RunProgressEventEntity old = entry(runId, 1, "THINKING");
        old.setCreatedAt(Instant.now().minusSeconds(86400 * 30));
        RunProgressEventEntity fresh = entry(runId, 2, "THINKING");
        repository.saveAll(List.of(old, fresh));

        int removed = repository.deleteByCreatedAtBefore(Instant.now().minusSeconds(86400 * 8));

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findAll()).extracting(RunProgressEventEntity::getId).containsOnly(fresh.getId());
    }
}
```

- [ ] **Step 3: Run — expect compile failure** (classes missing)

Run: `cd agent-control-tower && mvn -q test -pl act-common -Dtest=RunProgressEventRepositoryTest`
Expected: compilation ERROR (`cannot find symbol: class RunProgressEventEntity`).

- [ ] **Step 4: Implement** — entity (act-common model style, mirror `Agent.java`):

```java
package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted run progress fragment (thinking/tool/iteration stream) — replaces the
 * S8 "transient, never persisted" contract (reversed by the 2026-09-06 UI audit:
 * the Live Activity Stream needs history replay).
 */
@Entity
@Table(name = "run_progress_events", indexes = {
        @Index(name = "idx_progress_run_seq", columnList = "run_id, seq"),
        @Index(name = "idx_progress_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunProgressEventEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID runId;

    @Column(columnDefinition = "UUID")
    private UUID agentId;

    @Column(nullable = false)
    private int iteration;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false)
    private long seq;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 255)
    private String toolName;

    @Column(nullable = false)
    private Instant createdAt;
}
```

Repository:

```java
package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RunProgressEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RunProgressEventRepository extends JpaRepository<RunProgressEventEntity, UUID> {

    List<RunProgressEventEntity> findByRunIdOrderBySeqAsc(UUID runId);

    List<RunProgressEventEntity> findByRunIdAndSeqAfterOrderBySeqAsc(UUID runId, Long afterSeq);

    @Modifying
    @Query("DELETE FROM RunProgressEventEntity p WHERE p.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
```

Check `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/repository/` for the existing repository registration pattern (e.g., a shared `@EnableJpaRepositories` scope — if repositories are listed explicitly anywhere, add this one).

- [ ] **Step 5: Run — expect PASS** (2 tests)

Run: `cd agent-control-tower && mvn -q test -pl act-common -Dtest=RunProgressEventRepositoryTest`

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V51__run_progress_events.sql agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/RunProgressEventEntity.java agent-control-tower/act-common/src/main/java/io/aria/conductor/common/repository/RunProgressEventRepository.java agent-control-tower/act-common/src/test/java/io/aria/conductor/common/repository/RunProgressEventRepositoryTest.java
git commit -m "feat(observability): persist run progress events (V51) with replay + retention queries"
```

---

### Task 2: Backend — persist progress in `EventBroadcastListener` (broadcast-first preserved)

**Files:**
- Modify: `agent-control-tower/act-dashboard-api/src/main/java/io/aria/conductor/dashboard/listener/EventBroadcastListener.java` (constructor + `onRunProgress` at lines 156-173)
- Modify: `agent-control-tower/act-dashboard-api/src/test/java/io/aria/conductor/dashboard/listener/EventBroadcastListenerTest.java`

Note: the `RunProgressEvent` javadoc in `act-common/src/main/java/io/aria/conductor/common/event/RunProgressEvent.java` says "Transient by design — NEVER persisted". Update that javadoc (lines 8-12) to: "Streamed progress fragment published by the OpenCode progress pump (and the langchain SSE forwarding fallback). Persisted to run_progress_events and broadcast over WS." — the S8 contract reversal is part of this change.

- [ ] **Step 1: Extend the existing listener test** (RED). In `EventBroadcastListenerTest`, add (matching its existing mock style — it mocks `SimpMessagingTemplate`; add `@Mock RunProgressEventRepository progressRepository` and pass it into the constructor):

```java
    @Test
    void onRunProgress_persistsFragment_thenBroadcasts() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        RunProgressEvent event = new RunProgressEvent(this, runId, agentId, 2,
                RunProgressEvent.Kind.THINKING, "thinking fragment", null, 7);

        listener.onRunProgress(event);

        ArgumentCaptor<RunProgressEventEntity> captor = ArgumentCaptor.forClass(RunProgressEventEntity.class);
        verify(progressRepository).save(captor.capture());
        RunProgressEventEntity saved = captor.getValue();
        assertThat(saved.getRunId()).isEqualTo(runId);
        assertThat(saved.getSeq()).isEqualTo(7L);
        assertThat(saved.getKind()).isEqualTo("THINKING");
        assertThat(saved.getContent()).isEqualTo("thinking fragment");
        verify(messagingTemplate).convertAndSend(eq("/topic/events"), any(WsBroadcastEvent.class));
    }

    @Test
    void onRunProgress_persistFailure_doesNotBreakBroadcast() {
        UUID runId = UUID.randomUUID();
        RunProgressEvent event = new RunProgressEvent(this, runId, UUID.randomUUID(), 1,
                RunProgressEvent.Kind.THINKING, "frag", null, 3);
        when(progressRepository.save(any())).thenThrow(new RuntimeException("db down"));

        listener.onRunProgress(event);

        verify(messagingTemplate).convertAndSend(eq("/topic/events"), any(WsBroadcastEvent.class));
    }
```

- [ ] **Step 2: Run — expect compile failure** (repository param missing in constructor).

Run: `cd agent-control-tower && mvn -q test -pl act-dashboard-api -Dtest=EventBroadcastListenerTest`

- [ ] **Step 3: Implement** — in `EventBroadcastListener`:
  - imports: `io.aria.conductor.common.model.RunProgressEventEntity`, `io.aria.conductor.common.repository.RunProgressEventRepository`, `lombok.extern.slf4j.Slf4j` (add `@Slf4j` on the class; replace the `System.err.println` in `broadcast()` with `log.warn` while touching the file).
  - constructor gains `RunProgressEventRepository progressRepository` (field + param).
  - `onRunProgress` becomes persist-then-broadcast:

```java
    @EventListener
    public void onRunProgress(RunProgressEvent event) {
        // Persist first (history replay), broadcast best-effort (live view).
        try {
            String content = event.getContent();
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            progressRepository.save(RunProgressEventEntity.builder()
                    .id(UUID.randomUUID())
                    .runId(event.getRunId())
                    .agentId(event.getAgentId())
                    .iteration(event.getIteration())
                    .kind(event.getKind().name())
                    .seq(event.getSeq())
                    .content(content)
                    .toolName(event.getToolName())
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist run progress (run={}): {}", event.getRunId(), e.getMessage());
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", event.getRunId().toString());
        payload.put("agentId", event.getAgentId().toString());
        payload.put("iteration", event.getIteration());
        payload.put("kind", event.getKind().name());
        payload.put("seq", event.getSeq());
        if (event.getToolName() != null) {
            payload.put("toolName", event.getToolName());
        }
        String content = event.getContent();
        if (content != null && content.length() > 500) {
            content = content.substring(0, 500) + "...";
        }
        payload.put("content", content);
        broadcast("run.progress", payload);
    }
```

  - Update the `RunProgressEvent` javadoc per the note above.

- [ ] **Step 4: Run — expect PASS** (all listener tests incl. the 2 new; act-dashboard-api module green)

Run: `cd agent-control-tower && mvn -q test -pl act-dashboard-api`

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard-api agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/RunProgressEvent.java
git commit -m "feat(observability): persist run.progress fragments at broadcast point (history replay source)"
```

---

### Task 3: Backend — replay endpoint `GET /api/v1/runs/{id}/progress`

**Files:**
- Create: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/RunProgressEventDto.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/controller/RunController.java` (add endpoint; find exact file via `grep -rn "RequestMapping(\"/api/v1/runs\")" agent-control-tower/act-agent`)
- Create: `agent-control-tower/act-agent/src/test/java/io/aria/conductor/agent/controller/RunProgressControllerTest.java`

- [ ] **Step 1: DTO**:

```java
package io.aria.conductor.agent.dto;

import java.time.Instant;
import java.util.UUID;

public record RunProgressEventDto(
        UUID id,
        UUID runId,
        UUID agentId,
        int iteration,
        String kind,
        long seq,
        String content,
        String toolName,
        Instant createdAt) {
}
```

- [ ] **Step 2: Failing controller test** (follow the existing RunController test pattern — `ls agent-control-tower/act-agent/src/test/java/io/aria/conductor/agent/controller/` and mirror its standalone/context style; standalone MockMvc shown):

```java
package io.aria.conductor.agent.controller;

import io.aria.conductor.common.model.RunProgressEventEntity;
import io.aria.conductor.common.repository.RunProgressEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RunController.class)
class RunProgressControllerTest {

    @Autowired MockMvc mvc;
    @MockBean RunProgressEventRepository progressRepository;
    // NOTE: RunController's other dependencies must be @MockBean'd too — mirror
    // whatever the existing RunController test mocks (copy its @MockBean list).

    @BeforeEach
    void stub() {
        UUID runId = UUID.randomUUID();
        when(progressRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(runId)
                        .agentId(UUID.randomUUID()).iteration(1).kind("THINKING")
                        .seq(1L).content("thinking").toolName(null)
                        .createdAt(Instant.parse("2026-09-06T00:00:00Z")).build()));
    }

    @Test
    void progressEndpoint_returnsAscendingList() throws Exception {
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("THINKING"))
                .andExpect(jsonPath("$[0].seq").value(1));
    }

    @Test
    void progressEndpoint_afterSeqFilters() throws Exception {
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress").param("afterSeq", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
}
```

(Move `RUN_ID` to a field used by `stub()`; wire the stubbing to the same constant. If the repo uses `@MockBean` deprecated warnings under Boot 3.4, use `org.springframework.test.context.bean.override.mockito.MockitoBean` as in Task 11 of the MCP plan.)

- [ ] **Step 3: Run — expect compile failure** (`/progress` mapping missing → 404 assertion failure).

Run: `cd agent-control-tower && mvn -q verify -pl act-agent -Dit.test=RunProgressControllerTest 2>&1 | tail -5` (if the test is named `*Test` it runs in surefire: `mvn -q test -pl act-agent -Dtest=RunProgressControllerTest`)

- [ ] **Step 4: Implement** — in `RunController` (constructor gains `RunProgressEventRepository progressRepository` — update its test constructor call sites):

```java
    @GetMapping("/{id}/progress")
    public ResponseEntity<List<RunProgressEventDto>> getRunProgress(
            @PathVariable UUID id,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq) {
        List<RunProgressEventDto> events = progressRepository
                .findByRunIdAndSeqAfterOrderBySeqAsc(id, afterSeq)
                .stream()
                .map(p -> new RunProgressEventDto(p.getId(), p.getRunId(), p.getAgentId(),
                        p.getIteration(), p.getKind(), p.getSeq(), p.getContent(),
                        p.getToolName(), p.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(events);
    }
```

- [ ] **Step 5: Run — expect PASS** (act-agent module green).

Run: `cd agent-control-tower && mvn -q test -pl act-agent`

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-agent
git commit -m "feat(observability): replay endpoint GET /runs/{id}/progress (afterSeq incremental)"
```

---

### Task 4: Backend — retention via scheduled task

**Files:**
- Create: `agent-control-tower/act-dashboard-api/src/main/java/io/aria/conductor/dashboard/listener/RunProgressRetentionTask.java`
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml` (add `aria.progress.retention-days: 7` under the existing `aria:` block)

Spec deviation (documented): retention is a standalone `@Scheduled` task in act-dashboard-api instead of a HousekeepingService integration — avoids coupling to housekeeping internals; the housekeeping report line is deferred to Phase 3.

- [ ] **Step 1: Failing test** (`agent-control-tower/act-dashboard-api/src/test/java/io/aria/conductor/dashboard/listener/RunProgressRetentionTaskTest.java`):

```java
package io.aria.conductor.dashboard.listener;

import io.aria.conductor.common.repository.RunProgressEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class RunProgressRetentionTaskTest {

    @Test
    void cleanup_deletesOlderThanRetentionDays() {
        RunProgressEventRepository repo = org.mockito.Mockito.mock(RunProgressEventRepository.class);
        RunProgressRetentionTask task = new RunProgressRetentionTask(repo, 7);

        task.cleanup();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByCreatedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(Instant.now().minusSeconds(86400 * 6));
    }
}
```

- [ ] **Step 2: Run — expect compile failure.** Run: `cd agent-control-tower && mvn -q test -pl act-dashboard-api -Dtest=RunProgressRetentionTaskTest`

- [ ] **Step 3: Implement**:

```java
package io.aria.conductor.dashboard.listener;

import io.aria.conductor.common.repository.RunProgressEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Retention for run_progress_events (spec Section 2): deletes fragments older
 * than aria.progress.retention-days (default 7). Runs daily at 03:30 server time.
 */
@Slf4j
@Component
public class RunProgressRetentionTask {

    private final RunProgressEventRepository progressRepository;
    private final int retentionDays;

    public RunProgressRetentionTask(RunProgressEventRepository progressRepository,
                                    @Value("${aria.progress.retention-days:7}") int retentionDays) {
        this.progressRepository = progressRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${aria.progress.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        int removed = progressRepository.deleteByCreatedAtBefore(Instant.now().minusSeconds(86400L * retentionDays));
        if (removed > 0) {
            log.info("Run progress retention: removed {} fragments older than {} days", removed, retentionDays);
        }
    }
}
```

NOTE: `@Scheduled` requires `@EnableScheduling` — verify it is already enabled (`grep -rn "EnableScheduling" agent-control-tower/act-app agent-control-tower/act-aria agent-control-tower/act-dashboard-api`); if absent, add `@EnableScheduling` to `McpServerConfig`-style app config in act-app (`ActApplication` or a config class) and report it.

- [ ] **Step 4: Run — expect PASS** + module green. Run: `cd agent-control-tower && mvn -q test -pl act-dashboard-api`

- [ ] **Step 5: application.yml** — under the existing `aria:` block add:

```yaml
  progress:
    retention-days: ${ARIA_PROGRESS_RETENTION_DAYS:7}
    cleanup-cron: ${ARIA_PROGRESS_CLEANUP_CRON:0 30 3 * * *}
```

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard-api agent-control-tower/act-app/src/main/resources/application.yml
git commit -m "feat(observability): daily retention task for run progress fragments (7-day default)"
```

---

### Task 5: Frontend — `useWebSocket` subscription model

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/hooks/useWebSocket.ts` (full rewrite of the return surface; STOMP plumbing unchanged)
- Modify: `agent-control-tower/act-dashboard/src/components/Layout.tsx` (context type + provider value)
- Modify consumers (minimal for THIS task — only fix the ones whose behavior changes): `src/components/Toast.tsx`, `src/components/AgentDrawer.tsx` (full migration happens in Task 6 for the drawer — here only keep it compiling: the drawer keeps `lastMessage` which REMAINS available), plus verify `NotificationBell.tsx`, `ActivityTimeline.tsx`, `HousekeepingPanel.tsx`, `KanbanBoard.tsx`, `MorningBriefing.tsx` still compile (they keep `lastMessage` — backward compatible).
- Test: `agent-control-tower/act-dashboard/src/hooks/__tests__/useWebSocket.test.tsx` (or the repo's hook-test location — `grep -rln "useWebSocket" agent-control-tower/act-dashboard/src --include=*.test.*` first; if a hook test exists, extend it)

Design (YAGNI-scoped per spec Section 3): the hook GAINS `subscribe(handler)` while KEEPING `lastMessage` — burst-critical consumers (AgentDrawer, Toast) migrate to `subscribe` in Tasks 6/8; low-frequency consumers stay on `lastMessage` (no drops observed for them).

- [ ] **Step 1: Failing test** — create/extend the hook test:

```tsx
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../useWebSocket';

// Minimal WebSocket stub: capture the instance so tests can push STOMP frames.
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  sent: string[] = [];
  constructor(url: string) { FakeWebSocket.instances.push(this); }
  send(data: string) { this.sent.push(data); }
  close() {}
}
// @ts-expect-error test stub
global.WebSocket = FakeWebSocket as unknown as typeof WebSocket;

function stompMessage(body: unknown): { data: string } {
  const headers = '';
  const frame = `MESSAGE\n${headers}\n${JSON.stringify(body)}\0`;
  return { data: frame };
}

describe('useWebSocket subscription model', () => {
  beforeEach(() => { FakeWebSocket.instances = []; });

  it('delivers every message to ALL handlers (no lastMessage overwrite drops)', async () => {
    const seen: string[][] = [];
    const { result } = renderHook(() => useWebSocket('ws://test/ws/events'));
    // three handlers — under lastMessage-only fanout, bursts drop events
    for (let i = 0; i < 3; i++) {
      result.current.subscribe((e) => seen[i]?.push(e.type));
      seen.push([]);
    }
    act(() => {
      const ws = FakeWebSocket.instances[0];
      ws.onopen?.();
      ws.onmessage?.(stompMessage({ type: 'run.progress', data: { seq: 1 } }));
      ws.onmessage?.(stompMessage({ type: 'run.progress', data: { seq: 2 } }));
    });
    expect(seen.map((s) => s.length)).toEqual([2, 2, 2]);
  });

  it('subscribe returns an unsubscribe function', async () => {
    const events: string[] = [];
    const { result } = renderHook(() => useWebSocket('ws://test/ws/events'));
    const sub = result.current.subscribe((e) => events.push(e.type));
    act(() => {
      const ws = FakeWebSocket.instances[0];
      ws.onopen?.();
      ws.onmessage?.(stompMessage({ type: 'run.progress', data: {} }));
    });
    sub.unsubscribe();
    act(() => {
      FakeWebSocket.instances[0].onmessage?.(stompMessage({ type: 'run.completed', data: {} }));
    });
    expect(events).toEqual(['run.progress']);
  });
});
```

- [ ] **Step 2: Run — expect FAIL** (`subscribe is not a function`).

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/hooks/__tests__/useWebSocket.test.tsx`

- [ ] **Step 3: Implement** — in `useWebSocket.ts`:

```ts
export interface WsSubscription {
  unsubscribe: () => void;
}

export interface UseWebSocketReturn {
  lastMessage: WsEvent | null; // kept for low-frequency consumers
  subscribe: (handler: (e: WsEvent) => void) => WsSubscription;
  isConnected: boolean;
  send: (data: string) => void;
}
```

Inside the hook add:

```ts
  const handlersRef = useRef(new Set<(e: WsEvent) => void>());

  const subscribe = useCallback((handler: (e: WsEvent) => void): WsSubscription => {
    handlersRef.current.add(handler);
    return { unsubscribe: () => handlersRef.current.delete(handler) };
  }, []);
```

and in the MESSAGE branch, AFTER `setLastMessage(parsed)` add:

```ts
            handlersRef.current.forEach((h) => {
              try { h(parsed); } catch { console.warn('[WS] handler failed'); }
            });
```

Return `{ lastMessage, subscribe, isConnected, send }`. In `Layout.tsx`, widen the context type with `subscribe` and pass the hook's value through unchanged (find the context definition: `grep -n "createContext" src/components/Layout.tsx`).

- [ ] **Step 4: Run — expect PASS** + whole frontend unit lane: `npx vitest run` (216+ tests; Toast/others still on lastMessage keep passing).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/hooks agent-control-tower/act-dashboard/src/components/Layout.tsx agent-control-tower/act-dashboard/src/hooks/__tests__
git commit -m "fix(dashboard): WS subscription model — every handler receives every frame (no burst drops)"
```

---

### Task 6: Frontend — AgentDrawer rebuild (backlog + live, stubs removed)

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/api/runs.ts` (add `getRunProgress`)
- Modify: `agent-control-tower/act-dashboard/src/components/AgentDrawer.tsx` (lines ~100-145 seed effect → backlog fetch; ~124-145 fold → subscribe; ~240-245 token bars; ~428-436 pump line; ~610 token hint)
- Modify: `agent-control-tower/act-dashboard/src/components/__tests__/AgentDrawer.test.tsx`
- Modify: `agent-control-tower/act-dashboard/src/api/runs.ts` test if a runs api test exists

- [ ] **Step 1: API function** — in `src/api/runs.ts` add:

```ts
export interface RunProgressEntry {
  id: string;
  runId: string;
  agentId: string;
  iteration: number;
  kind: string;
  seq: number;
  content: string;
  toolName: string | null;
  createdAt: string;
}

export async function getRunProgress(runId: string, afterSeq = 0): Promise<RunProgressEntry[]> {
  const data = await apiGet<{ runId: string }[]>(`/runs/${runId}/progress?afterSeq=${afterSeq}`);
  return data as unknown as RunProgressEntry[];
}
```

(Match the file's actual request helper name — read `src/api/runs.ts` first; if it uses a shared `apiGet`/`http` helper, use that; if raw axios with a typed wrapper, mirror it.)

- [ ] **Step 2: Failing tests (RED)** — extend `AgentDrawer.test.tsx` following its existing mock pattern (it already exists with mocked api modules — read its mocks first):
  - `drawer_showsBacklogHistory_onOpen`: mock `getRunProgress` → two entries (THINKING seq 1, TOOL_CALL seq 2 with toolName 'shell_exec') → assert the stream renders both lines (thinking + tool tags) and does NOT render the fake `Connected to` seed line.
  - `drawer_liveFrames_append_afterBacklog`: after backlog render, push a WS frame seq 3 → third line appears; duplicate seq 3 frame → NOT duplicated.
  - `drawer_failedRun_isNotActiveRun`: run list with one FAILED run only → "Active Run" section absent (no inflated elapsed).
  - `drawer_noPumpStubLine`: the text `GET /session/:id/message` does not appear anywhere.
  - `drawer_tokenBarsRemoved`: the hardcoded `500_000` cap UI (aria-label or text `Tokens`) absent; iteration count still visible.

- [ ] **Step 3: Run — expect RED** (seed line still present, no backlog fetch).

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/AgentDrawer.test.tsx`

- [ ] **Step 4: Implement** in `AgentDrawer.tsx`:
  1. Replace the seed effect (lines ~107-120): remove the fake seed; add a backlog effect keyed on `[open, agentId, activeRun?.id]`:

```ts
  useEffect(() => {
    if (!open || !agent) {
      setStream([]);
      return;
    }
    seenSeqs.current.clear();
    const runId = activeRun?.id;
    if (!runId) {
      setStream([]);
      return;
    }
    let cancelled = false;
    getRunProgress(runId)
      .then((backlog) => {
        if (cancelled) return;
        setStream(backlog.map((p) => progressToEntry(p)));
        backlog.forEach((p) => markSeq(runKeyOf(runId), p.seq));
      })
      .catch(() => log.warn('[drawer] progress backlog fetch failed'));
    return () => { cancelled = true; };
  }, [open, agent?.id, activeRun?.id]);
```

  2. Extract the mapping used by the live fold into a helper so backlog and live produce identical entries:

```ts
  function progressToEntry(p: { kind: string; content: string; toolName?: string | null; createdAt?: string; seq?: number }): StreamEntry {
    return {
      id: `p-${p.seq ?? Math.random()}`,
      ts: fmtTime(p.createdAt ? new Date(p.createdAt) : new Date()),
      tag: p.kind === 'TOOL_CALL' || p.kind === 'TOOL_RESULT' ? 'tool' : 'think',
      msg: p.toolName ? `[${p.toolName}] ${p.content}` : p.content,
    };
  }
```

  (Mirror the live fold's exact `StreamEntry` shape — read the live fold code at lines ~145-171 and reuse its fields; adapt this helper if the live path uses extra fields.)
  3. Migrate the WS fold (lines ~124-145) from `lastMessage` to `subscribe` (Task 5's model): `useEffect(() => { if (!open || !agentId) return; return subscribe((event) => { /* existing matchAgent + fold logic, unchanged */ }); }, [subscribe, open, agentId, agent?.id, activeRun?.id])` — keep the seq-dedupe and `matchAgent` logic verbatim inside the handler. Remove the `agent.heartbeat` condition.
  4. `activeRun`: `const activeRun = agentRuns.find((r) => r.status === 'RUNNING' || r.status === 'INITIALIZING' || r.status === 'PAUSED');` (drop `?? agentRuns[0]`).
  5. Remove the pump status line (lines ~428-436) — replace with a plain `<div className="section-h">Live Activity Stream</div>` header (keep the collapse toggle + window badge that follow).
  6. Remove the hardcoded token bars (lines ~240-245: `tokenCap`/`tokenPct`/`ctxUsed`/`ctxPct` and their JSX) — keep the iteration count display.
  7. Remove the `~{order.length / 4 | 0} tok` hint (line ~610) — keep the textarea + Send.
  8. Migrate the component off `lastMessage`: `const { subscribe, isConnected } = useWebSocketContext();` — delete `lastMessage` from its destructure.

- [ ] **Step 5: Run — expect GREEN** (all drawer tests + no regressions): `npx vitest run src/components/__tests__/AgentDrawer.test.tsx` then `npx vitest run`

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src
git commit -m "fix(dashboard): Live Activity Stream — backlog replay + no-drop live frames; remove demo stubs"
```

---

### Task 7: Frontend — ApprovalsPage rework

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/pages/ApprovalsPage.tsx`
- Modify: `agent-control-tower/act-dashboard/src/components/MarkdownViewer.tsx` (rendering upgrade)
- Modify: `agent-control-tower/act-dashboard/src/styles/` (add `.spec-review-markdown`)
- Modify: `agent-control-tower/act-dashboard/src/components/__tests__/MarkdownViewer.test.tsx`
- Create/extend: ApprovalsPage tests (`ls src/components/__tests__/ | grep -i approval` first — if none, create `src/pages/__tests__/ApprovalsPage.test.tsx` following TemplatesPanel.test.tsx patterns with mocked api modules)

- [ ] **Step 1: RED tests**:
  - `deny_opensConfirmDialog` — clicking Deny opens a confirm dialog (mirroring Approve's), Cancel keeps PENDING.
  - `historyRow_expandsMarkdown` — a resolved SPEC_REVIEW row expanded renders `MarkdownViewer` content (use `approval.content` from the mocked list).
  - `stuckChain_shown_whenGateExpired` — mocked workflows list contains one WAITING_APPROVAL chain whose runIds match NO pending approval → renders the stuck-chain panel with a Resubmit button; clicking it calls the resubmit API (mock `resubmitApproval` — add to `src/api/workflows.ts` if absent: `export const resubmitApproval = (id: string) => http.post(`/workflows/${id}/resubmit-approval`)` — verify the api file's helper naming first).
  - `markdown_rendersTable_links_code` (in MarkdownViewer.test.tsx): a table, a link, and a fenced-code block produce `<table>`, `<a>`, `<code>` elements respectively (RED against the regex renderer).
- [ ] **Step 2: Run — expect the 4 to FAIL.** Run: `cd agent-control-tower/act-dashboard && npx vitest run src/pages/__tests__/ApprovalsPage.test.tsx src/components/__tests__/MarkdownViewer.test.tsx`
- [ ] **Step 3: Implement**:
  1. **MarkdownViewer upgrade**: replace the regex renderer with a dependency-free block parser that handles (in order): fenced code blocks (```…```), tables (`| a | b |` rows with `---` separator), headings (#..######), unordered + ordered lists, bold/italic/inline-code/link inline spans. Keep it dependency-free (no react-markdown — avoids a new dep and bundle growth; the existing regex renderer is the fallback base to extend). Preserve any HTML-doc handling if MarkdownViewer has it (check first).
  2. Wrap output in `<div className="spec-review-markdown">` INSIDE MarkdownViewer's root (so every consumer gets styling + the E2E class).
  3. Add `.spec-review-markdown` styles to the design-system stylesheet (`grep -rn "spec-review" src/styles/` to find the file; add table/link/code/pre rules matching the app's CSS variables).
  4. **History detail**: convert History rows to expandable rows — clicking a row toggles a detail row rendering `<MarkdownViewer content={a.content ?? ''} />` for SPEC_REVIEW type (arguments block for TOOL_CALL).
  5. **Deny confirm**: add `confirmDeny` state mirroring `confirmApprove`; Deny opens the dialog; the dialog's confirm button calls `handleDeny`.
  6. **Stuck chains panel**: above the tabs —
```tsx
  const { data: workflows } = useQuery({ queryKey: ['workflows'], queryFn: listWorkflows });
  const pendingApprovalRunIds = new Set(pending.map((a) => a.runId));
  const stuckChains = (workflows ?? []).filter(
    (w) => w.status === 'WAITING_APPROVAL' &&
      !(w.steps ?? []).some((s) => s.runId && pendingApprovalRunIds.has(s.runId))
  );
```
     Render when `stuckChains.length > 0`: warning panel listing each chain (name + short id) with a `Resubmit approval` button → `resubmitApproval(w.id)` mutation → invalidate `['workflows']` + `['approvals']` (import `listWorkflows` from `../api/workflows`; verify `WorkflowResponse.steps` carries `runId` — it does, per `WorkflowResponse.StepInfo`).
  7. **Knowledge review confirm** (`KnowledgePage.tsx` ~line 202/214): wrap single + batch review mutations behind a `confirmReview` state + dialog mirroring the Approve dialog.
- [ ] **Step 4: Run — expect GREEN** for the 4 + no regressions: `npx vitest run`
- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src
git commit -m "fix(dashboard): approvals stuck-chain panel, history detail, markdown upgrade, review confirms"
```

---

### Task 8: Frontend — quick wins batch

**Files (each: modify or delete):**
- `src/components/Toast.tsx` (View button)
- Create: `src/utils/notificationRoutes.ts` (extracted route map)
- Modify: `src/components/NotificationBell.tsx` (use the extracted util)
- `src/pages/OpsPage.tsx:178-180` (remove Escalate mock button + handler)
- `src/pages/ScheduledJobsPage.tsx:90-92,151` (alert → toast; reuse the existing `setToast` if the page has one — grep; else add local toast state mirroring Toast usage)
- `src/pages/RunsPage.tsx` (agent names: add `useQuery(['agents'], listAgents)` + map `agentId → name` for display; keep sorting/filtering on agentId)
- `src/pages/ReportsPage.tsx` (Copy Link: ensure `setCopyToast('Link copied')` on success AND `setCopyToast('Copy failed')` in the clipboard catch — read `handleCopyLink` and fix the silent path)
- `src/pages/ChatPage.tsx` (inject duplicate POST: add `injectingRef = useRef(false)` guard around the inject call; reset in finally)
- `src/pages/WorkflowsPage.tsx` (covered in Task 7's runs-query? No — the stale step status fix lives HERE: add the runs-derived display status exactly as specified below)
- DELETE: `src/pages/DashboardPage.tsx`, `src/pages/DoDPage.tsx`, `src/pages/KanbanPage.tsx`, `src/pages/ToolManager.tsx` + their test files (`grep -rln "DashboardPage\|DoDPage\|KanbanPage\|ToolManager" src/ --include=*.test.*`) + any imports
- `src/components/SlashCommandMenu.tsx:15` (empty state)

- [ ] **Step 1: RED tests** (add to the existing component test files; new file `src/pages/__tests__/QuickWins.test.tsx` for the small ones):
  - Toast: `aria.notification View navigates` — render Toast with the WS context stubbed (mock `useWebSocketContext` per the existing test approach), fire an `aria.notification` event with `resourceType: 'report.generated'`, click View → `window.location`/router push to `/reports` (assert via a MemoryRouter + location display, mirroring AriaPanel.test.tsx's router test setup).
  - SlashCommandMenu: `showsEmptyState_whenNoSkills` — items empty → renders "No skills yet — author one in Knowledge" instead of null.
  - WorkflowsPage: `stepShowsRunStatus_whenRunTerminal` — run list contains a COMPLETED run whose id matches a step showing RUNNING → step renders COMPLETED (mock listRuns + workflows).
  - RunsPage: `showsAgentName_forDeletedAgent` — agent map resolves a known id; unknown id falls back to short UUID.
- [ ] **Step 2: Run — expect RED.** Run: `cd agent-control-tower/act-dashboard && npx vitest run src/pages/__tests__/QuickWins.test.tsx src/components/__tests__/Toast.test.tsx 2>&1 | tail -10` (adapt paths to where the tests live)
- [ ] **Step 3: Implement**:
  1. **notificationRoutes util** (`src/utils/notificationRoutes.ts`):
```ts
export function routeForNotificationResource(resourceType?: string): string | null {
  if (!resourceType) return null;
  if (resourceType === 'run.completed' || resourceType === 'run.failed') return '/runs';
  if (resourceType === 'approval.requested') return '/approvals';
  if (resourceType === 'knowledge.submitted') return '/knowledge';
  if (resourceType === 'report.generated') return '/reports';
  return null;
}
```
     NotificationBell's `getNavRoute` delegates to it (delete its inline copy). Toast's aria.notification View: `onClick: () => { const route = routeForNotificationResource(eventToUse.payload?.resourceType as string); if (route) navigate(route); }` — Toast needs `useNavigate` (wrap Toast usage — it renders inside Router context via Layout, so `useNavigate` works; import from react-router-dom).
  2. **OpsPage**: delete the `onEscalate` handler and the Escalate button JSX (~178-180).
  3. **ScheduledJobsPage**: replace the four `alert(...)` with the page's toast mechanism — grep `setToast` in the page; if none, add `const [toast, setToast] = useState<string | null>(null)` + a fixed-position span mirroring TemplatesPanel's toast pattern; replace `alert(\`Operation failed: ${...}\`)` → `setToast(\`Operation failed: ${...}\`)`.
  4. **RunsPage**: `const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: listAgents });` + `const agentName = (id: string) => agents?.find((a) => a.id === id)?.name ?? id.slice(0, 8);` — replace the raw id render in the table (`grep -n "agentId" src/pages/RunsPage.tsx` for the exact cell).
  5. **ReportsPage Copy Link**: read `handleCopyLink` — ensure both success and catch paths set `setCopyToast('Link copied')` / `setCopyToast('Copy failed')` and add `setTimeout(() => setCopyToast(null), 2000)`.
  6. **ChatPage inject lock**: at the inject handler (~`injectRunMessage` call site), add `const injectingRef = useRef(false);` — `if (injectingRef.current) return; injectingRef.current = true; try { ...await inject... } finally { injectingRef.current = false; }`.
  7. **WorkflowsPage stale step status**: add `useQuery({ queryKey: ['runs'], queryFn: listRuns })` (import exists? check `../api/runs`) + build `const runStatusById = new Map(runs.map(r => [r.id, r.status]))`; derive per-step display: `const displayStatus = step.status === 'RUNNING' && step.runId && TERMINAL.has(runStatusById.get(step.runId) ?? '') ? runStatusById.get(step.runId)! : step.status;` where `TERMINAL = new Set(['COMPLETED','FAILED','CANCELLED','ABORTED'])` — pass `displayStatus` into `stepStatusColor`/`stepStatusIcon`/label (lines 36-47 area).
  8. **Dead pages**: `git rm src/pages/DashboardPage.tsx src/pages/DoDPage.tsx src/pages/KanbanPage.tsx src/pages/ToolManager.tsx` + their test files; run `npx vitest run` and fix any import breakage (the grep earlier showed they are imported ONLY by their own tests).
  9. **SlashCommandMenu** (line 15): replace `if (items.length === 0) return null;` with an empty-state render:
```tsx
  if (items.length === 0) {
    return (
      <div className="slash-menu slash-menu-empty">
        No skills yet — author one in Knowledge (type=SKILL), then it appears here.
      </div>
    );
  }
```
     (+ one CSS rule for `.slash-menu-empty` mirroring the menu's existing styles).
- [ ] **Step 4: Run — expect GREEN**: `npx vitest run` + `npx tsc --noEmit -p .` + `pnpm build` (all three).
- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src
git commit -m "fix(dashboard): quick wins — toast nav, remove mocks/stubs, honest labels, dead page removal"
```

---

### Task 9: Incident revert — restore the 2 real knowledge items to PENDING (operational)

**Files:** none (DB operation). Runbook:

- [ ] **Step 1: Stop the backend** (H2 file lock): `netstat -ano | grep :8080` → PID → `taskkill //F //PID <pid>`.
- [ ] **Step 2: Backup the DB file**: `cp agent-control-tower/act-app/data/act_db.mv.db agent-control-tower/act-app/data/act_db.mv.db.bak-$(date +%s)`.
- [ ] **Step 3: H2 Shell** — locate the H2 jar (`ls ~/.m2/repository/com/h2database/h2/`), then:

```bash
java -cp ~/.m2/repository/com/h2database/h2/<version>/h2-<version>.jar org.h2.tools.Shell -url "jdbc:h2:file:./agent-control-tower/act-app/data/act_db;MODE=MySQL;AUTO_SERVER=TRUE" -user sa -password "" -sql "SELECT id, name, status FROM knowledge_items WHERE id IN ('804e7379-0000-0000-0000-000000000000','825af0dd-0000-0000-0000-000000000000')"
```

(Fill the REAL full ids — the audit report records them as short ids `804e7379`/`825af0dd`; `SELECT id, name, status FROM knowledge_items WHERE name LIKE '%f6f64965%' OR name LIKE '%wf-development-workflow-instance%'` to find them first. Also inspect the version table: `SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE '%VERSION%'` to get the exact version-table/column names, then check the rows' status columns.)

- [ ] **Step 4: Revert SQL** (adjust table/column names to what Step 3 reveals):

```sql
UPDATE knowledge_items SET status = 'PENDING' WHERE id = '<spec-804e7379-id>';
UPDATE knowledge_items SET status = 'PENDING' WHERE id = '<template-825af0dd-id>';
-- version rows: restore the version status the review flow would have left PENDING in
UPDATE <version_table> SET status = 'PENDING' WHERE knowledge_item_id = '<item-id>';
```

(If the review flow also moved a version row to APPROVED/REJECTED, restore that row to its pre-review status — inspect `KNOWLEDGE_VERSION`-named table columns first. If a review DECIDED timestamp column exists, null it.)

- [ ] **Step 5: Restart the backend** (`bash scripts/start-backend.sh --profile=h2 --skip-build` with the standard env, background) and verify in the UI/API: `curl -s "http://localhost:8080/api/v1/knowledge?status=PENDING" | grep -c "804e7379\|825af0dd"` → 2.

- [ ] **Step 6: No commit** (DB operation) — record the outcome in the PR description when pushed.

---

### Task 10: E2E additions + full verification + push

**Files:**
- Modify: `agent-control-tower/act-dashboard/e2e/api/` (add `mcp-approvals-history.api.spec.ts` — optional, only if the CI-time budget allows; the MCP spec is skipped in CI because aria.mcp is disabled in the E2E stack... VERIFY: the E2E stack runs h2 profile where aria.mcp.enabled defaults TRUE — the mcp spec connected live in CI (shard 4 skip was the GATE_TIMEOUT escape). So a history-detail E2E assertion CAN run in CI. Add to the existing mcp spec: after `decide_approval`, `list_approvals` (no status) → find the decided approval → its content is retrievable and the MCP replay endpoint returns the history via `get_workflow` — keep the addition minimal: assert the decided approval appears in History with its content. If time-boxed, defer to the browser spot-check.)

- [ ] **Step 1: Full Java gate**: `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2` — expect BUILD SUCCESS (~1900 tests).
- [ ] **Step 2: Frontend gates**: `cd agent-control-tower/act-dashboard && pnpm test && npx tsc --noEmit -p . && pnpm build` — all green.
- [ ] **Step 3: Browser spot re-audit** (live stack, requires backend up + frontend `pnpm dev`): re-test the five Critical/High entries — (1) Approvals stuck-chain panel visible for an expired chain, (2) Resubmit click → new PENDING window, (3) drawer shows backlog history for a completed run, (4) burst frames all rendered (compare audit-log count vs stream lines on a live run), (5) History detail markdown opens. Record screenshots to `.ui-audit/shots2/`.
- [ ] **Step 4: Push + PR**: `git push -u origin fix/ui-audit-findings` then `gh pr create` (base main) with the audit reports as evidence and this checklist.
- [ ] **Step 5: Watch CI** (`gh pr checks <n> --repo HappyLiang12/aria-conductor` polling) — expect green.

---

## Self-Review (author-checked)

- **Spec coverage**: spec Section 2 → Tasks 1-4; Section 3 → Task 5 + Task 6; Section 4 → Task 7; Section 5 → Task 8; Section 6 → Task 9; Section 7 → tests inside Tasks 1-8 + Task 10; Section 8 → Task 0/10. No gaps.
- **Placeholders**: Task 3's test stubs note "mirror the existing RunController test mocks" — bounded by a concrete grep command; Task 6/8 modify-points carry verbatim current code from the audit reads. No TBDs.
- **Type consistency**: `RunProgressEventEntity`/`RunProgressEventDto` field names consistent across Tasks 1-3; `getRunProgress`/`RunProgressEntry` consistent between Tasks 5-6; `PROTECTED` naming not reused. `McpToolUtils`-style inconsistencies: none (different feature).

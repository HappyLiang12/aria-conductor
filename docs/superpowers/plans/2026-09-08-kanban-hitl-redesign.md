# Kanban HITL Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Kanban board the primary HITL control surface: 5 status-driven columns, drag-and-drop transitions orchestrated by the backend (pause/pickup/request-changes), a Review column aggregating every ask waiting on the user, and one dashboard signal ("Waiting on you").

**Architecture:** One new transactional endpoint `POST /api/v1/kanban/{id}/transition` (KanbanTransitionService in act-execution) encapsulates all side effects (two-phase pickup via RunService.createRun, pause via RunService.pauseRun, ask deny on cancel). Approvals gain ask fields (`kanbanItemId`, `askType`, `contextMd`, `optionsJson`, `answer`) so Review cards can render a decision panel. Frontend keeps native HTML5 DnD and reuses the existing DrawerContext; the TS MCP server and the act-aria KanbanToolHandler are rewired to the same endpoint (tool names unchanged).

**Tech Stack:** Java 21 / Spring Boot 3.3, Flyway (H2 MODE=MySQL compatible), React 19 + TanStack Query, Vitest + RTL, Playwright, vitest for packages/mcp-server.

**Spec:** `docs/superpowers/specs/2026-09-08-kanban-hitl-redesign-design.md`

**Branch:** execute on a fresh branch cut from latest `main` (main is checked out at the Qoder worktree `C:/Users/User/.qoder/worktree/aria-conductor/GGp7xL`).

**Facts you must know (verified 2026-09-08):**

- `KanbanItem` entity: `act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanItem.java` — already has `description` (TEXT). Uses String id, `@PrePersist` defaults.
- `KanbanService.transition(id, toStatus, comment)` validates `ALLOWED_TRANSITIONS` and publishes `KanbanItemTransitionedEvent(source, itemId, fromStatus, toStatus)` → broadcast as WS `kanban.transitioned` by `EventBroadcastListener` (act-dashboard-api, line 238).
- `RunService` (act-agent): `createRun(CreateRunRequest{agentId: UUID, promptSeed: String, maxIterations: int})` → creates PENDING run + publishes `RunStartedEvent` (engine picks it up). `pauseRun(UUID)`, `resumeRun(UUID)`, `resumeRun(UUID, String newInstruction)`, `cancelRun(UUID)` all exist and are `@Transactional`. RUNNING→PAUSED→RUNNING already valid.
- `Approval` (act-common): nested enums `ApprovalType {TOOL_CALL, SPEC_REVIEW}`, `ContentKind`, status enum `ApprovalStatus {PENDING, APPROVED, DENIED, EXPIRED}`. `runId` is NOT NULL. `ApprovalRequestedEvent(source, approvalId, runId, toolCallId, approvalType)`.
- Agent templates: `GET /api/v1/agents/templates` → `List<AgentTemplateDTO{id,label,agentType,role,model,provider,adkProvider,description}>`.
- Flyway: highest migration is `V51__run_progress_events.sql`. Style: no TIMESTAMPTZ, H2 MODE=MySQL safe.
- `@Modifying` JPQL needs a transactional caller (H2 throws otherwise) — all mutating service methods below are `@Transactional`.
- Frontend: `KanbanStatus` type lacks `BACKLOG`; `KanbanBoard.tsx` columns are label-hack based; `TaskDrawer.tsx` TRANSITIONS map is label-era; right-column widgets are mounted in `pages/OverviewPage.tsx` (NOT Layout.tsx); `RailNav.tsx` line 18 is the Approvals entry; `notificationRoutes.ts:18` routes `approval.requested` → `/approvals`; `ExecutiveSummary.tsx:75-80` is the Pending Approvals StatCell.
- MCP TS: `packages/mcp-server/src/tools/kanban.ts` has `transition_kanban_item` (zod schema, posts `{status, comment}`); tests use `mockFetch`/`createTestClient` helpers.
- act-aria `KanbanToolHandler.transition_kanban_item` reads `newStatus` (note: the TS server sends `status`) and calls `KanbanService.transition` directly.

---

### Task 1: Flyway migration V52 — kanban HITL schema

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V52__kanban_hitl.sql`
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/KanbanHitlMigrationTest.java`

- [ ] **Step 1: Write the failing test**

Schema consistency tests boot the full context with Flyway enabled (see `SchemaConsistencySmokeTest` pattern in the same package). Create:

```java
package io.aria.conductor.app;

import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class KanbanHitlMigrationTest {

    @Autowired
    private KanbanRepository kanbanRepository;

    @Test
    void kanbanItemsCarryNewColumns() {
        KanbanItem item = kanbanRepository.save(KanbanItem.builder()
                .title("migration probe")
                .agentTemplateId("ba-agent")
                .lastError("boom")
                .build());
        KanbanItem reloaded = kanbanRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getAgentTemplateId()).isEqualTo("ba-agent");
        assertThat(reloaded.getLastError()).isEqualTo("boom");
    }
}
```

Note: `agentTemplateId`/`lastError` getters do not exist yet — this test will not compile until Task 5 adds them. To keep Task 1 self-contained, first write only the SQL (Step 2) and verify it applies via the existing `SchemaConsistencySmokeTest`; run the compile-checking test after Task 5. Alternative accepted order: implement Task 1 Step 2, run `mvn verify -pl act-app -Dtest=SchemaConsistencySmokeTest`, and defer this test class to Task 5.

- [ ] **Step 2: Write the migration**

`V52__kanban_hitl.sql`:

```sql
-- Kanban HITL redesign (spec: docs/superpowers/specs/2026-09-08-kanban-hitl-redesign-design.md)
-- 1. Card fields for pickup orchestration.
ALTER TABLE kanban_items ADD COLUMN agent_template_id VARCHAR(100);
ALTER TABLE kanban_items ADD COLUMN last_error VARCHAR(500);

-- 2. Replace label-hack columns with real statuses BEFORE the frontend stops
--    reading labels. BACKLOG/REVIEW are enum-string values (status is VARCHAR).
UPDATE kanban_items SET status = 'BACKLOG'
 WHERE status = 'TODO' AND LOWER(COALESCE(labels, '')) LIKE '%backlog%';
UPDATE kanban_items SET status = 'REVIEW'
 WHERE status = 'TODO' AND LOWER(COALESCE(labels, '')) LIKE '%review%';
UPDATE kanban_items SET status = 'IN_PROGRESS'
 WHERE status = 'IN_PROGRESS'
   AND (LOWER(COALESCE(labels, '')) LIKE '%qa-gate%' OR LOWER(COALESCE(labels, '')) LIKE '%gate%');
UPDATE kanban_items SET status = 'REVIEW' WHERE status = 'BLOCKED';

-- 3. Approval ask columns (HITL decision panel).
ALTER TABLE approvals ADD COLUMN kanban_item_id VARCHAR(36);
ALTER TABLE approvals ADD COLUMN ask_type VARCHAR(20) NOT NULL DEFAULT 'APPROVAL';
ALTER TABLE approvals ADD COLUMN context_md TEXT;
ALTER TABLE approvals ADD COLUMN options_json TEXT;
ALTER TABLE approvals ADD COLUMN answer TEXT;
CREATE INDEX idx_approvals_kanban_item ON approvals (kanban_item_id);
```

- [ ] **Step 3: Verify migration applies**

Run: `cd agent-control-tower && mvn verify -pl act-app -Dtest=SchemaConsistencySmokeTest`
Expected: BUILD SUCCESS (Flyway V52 applied cleanly on H2 MODE=MySQL and MariaDB-compatible syntax).

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V52__kanban_hitl.sql
git commit -m "feat(kanban): V52 migration for HITL ask columns and status backfill"
```

---

### Task 2: KanbanStatus.BACKLOG + new transition table (TDD)

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanStatus.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanService.java` (ALLOWED_TRANSITIONS static block)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanTransitionTableTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.kanban;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KanbanTransitionTableTest {

    private final KanbanService service = new KanbanService(null, null, null);

    @ParameterizedTest
    @CsvSource({
            "BACKLOG,TODO",
            "TODO,IN_PROGRESS",
            "TODO,BACKLOG",
            "IN_PROGRESS,TODO",
            "IN_PROGRESS,BACKLOG",
            "IN_PROGRESS,REVIEW",
            "IN_PROGRESS,DONE",
            "REVIEW,IN_PROGRESS",
            "REVIEW,TODO",
            "REVIEW,DONE",
            "TODO,CANCELLED",
            "IN_PROGRESS,CANCELLED",
            "REVIEW,CANCELLED",
            "BACKLOG,CANCELLED"
    })
    void allowsSpecTransitions(String from, String to) {
        assertThat(service.isValidTransition(KanbanStatus.valueOf(from), KanbanStatus.valueOf(to))).isTrue();
    }

    @Test
    void terminalStatesHaveNoOutgoing() {
        assertThat(service.isValidTransition(KanbanStatus.DONE, KanbanStatus.TODO)).isFalse();
        assertThat(service.isValidTransition(KanbanStatus.CANCELLED, KanbanStatus.TODO)).isFalse();
    }

    @Test
    void blockedIsRetired() {
        assertThat(service.isValidTransition(KanbanStatus.BLOCKED, KanbanStatus.TODO)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=KanbanTransitionTableTest`
Expected: COMPILE ERROR — `BACKLOG` not on `KanbanStatus`.

- [ ] **Step 3: Implement**

`KanbanStatus.java`:

```java
public enum KanbanStatus {
    BACKLOG,
    TODO,
    IN_PROGRESS,
    DONE,
    CANCELLED,
    REVIEW,
    BLOCKED
}
```

(Keep `BLOCKED` in the enum so old data deserializes; it has no outgoing transitions.)

`KanbanService` static block — replace the whole `ALLOWED_TRANSITIONS` initialization:

```java
    static {
        EnumMap<KanbanStatus, Set<KanbanStatus>> map = new EnumMap<>(KanbanStatus.class);
        map.put(KanbanStatus.BACKLOG, EnumSet.of(KanbanStatus.TODO, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.TODO, EnumSet.of(KanbanStatus.IN_PROGRESS, KanbanStatus.BACKLOG, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.IN_PROGRESS, EnumSet.of(KanbanStatus.TODO, KanbanStatus.BACKLOG,
                KanbanStatus.REVIEW, KanbanStatus.DONE, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.REVIEW, EnumSet.of(KanbanStatus.IN_PROGRESS, KanbanStatus.TODO,
                KanbanStatus.DONE, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.DONE, EnumSet.noneOf(KanbanStatus.class));
        map.put(KanbanStatus.CANCELLED, EnumSet.noneOf(KanbanStatus.class));
        // BLOCKED is retired: no outgoing transitions; V52 migrated rows to REVIEW.
        ALLOWED_TRANSITIONS = map;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest="Kanban*Test"`
Expected: PASS (existing KanbanService tests may assert the old table — update their expectations to the new table; they are in the same package).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanStatus.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanService.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanTransitionTableTest.java
git commit -m "feat(kanban): BACKLOG status and HITL transition table"
```

---

### Task 3: Approval ask fields + repository finders

**Files:**
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Approval.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/repository/ApprovalAskRepositoryTest.java`

- [ ] **Step 1: Write the failing test** (JPA slice; follow existing `@DataJpaTest` slices under `act-execution/src/test` — use H2)

```java
package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Transactional
class ApprovalAskRepositoryTest {

    @Autowired
    private ApprovalRepository repository;

    private Approval saveAsk(String kanbanItemId, Approval.ApprovalType askType) {
        return repository.save(Approval.builder()
                .runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .approvalType(askType)
                .askType(askType)
                .kanbanItemId(kanbanItemId)
                .contextMd("why we ask")
                .optionsJson("[{\"label\":\"yes\",\"suggested\":true}]")
                .build());
    }

    @Test
    void findByKanbanItemIdReturnsAsks() {
        saveAsk("item-1", Approval.ApprovalType.SPEC_REVIEW);
        saveAsk("item-1", Approval.ApprovalType.SPEC_REVIEW);
        saveAsk("item-2", Approval.ApprovalType.SPEC_REVIEW);
        assertThat(repository.findByKanbanItemId("item-1")).hasSize(2);
    }

    @Test
    void denyPendingByKanbanItemIdOnlyTouchesPending() {
        Approval a = saveAsk("item-9", Approval.ApprovalType.QUESTION);
        Approval b = saveAsk("item-9", Approval.ApprovalType.QUESTION);
        b.setStatus(ApprovalStatus.APPROVED);
        repository.save(b);

        int denied = repository.denyPendingByKanbanItemId("item-9", "task cancelled", java.time.Instant.now());

        assertThat(denied).isEqualTo(1);
        assertThat(repository.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.DENIED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=ApprovalAskRepositoryTest`
Expected: COMPILE ERROR — `askType`/`kanbanItemId` unknown.

- [ ] **Step 3: Implement**

`Approval.java` — add nested enum and fields (inside the class, next to `ApprovalType`):

```java
    public enum AskType { APPROVAL, QUESTION, REVIEW_REQUEST }
```

fields:

```java
    /** HITL ask link: the kanban card this ask is surfaced on (nullable). */
    @Column(name = "kanban_item_id", length = 36)
    private String kanbanItemId;

    /** Fine-grained ask kind; approvalType stays the governance category. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "ask_type", nullable = false, length = 20)
    private AskType askType = AskType.APPROVAL;

    @Column(name = "context_md", columnDefinition = "TEXT")
    private String contextMd;

    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;
```

`ApprovalRepository.java` — add:

```java
    List<Approval> findByKanbanItemId(String kanbanItemId);

    List<Approval> findByStatusAndKanbanItemId(ApprovalStatus status, String kanbanItemId);

    @Query("select a.kanbanItemId, count(a) from Approval a " +
           "where a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING " +
           "and a.kanbanItemId in :ids group by a.kanbanItemId")
    List<Object[]> countPendingByKanbanItemIds(@Param("ids") Collection<String> ids);

    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.DENIED, " +
           "a.reason = :reason, a.decidedAt = :now " +
           "where a.kanbanItemId = :itemId and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING")
    int denyPendingByKanbanItemId(@Param("itemId") String itemId,
                                  @Param("reason") String reason,
                                  @Param("now") Instant now);
```

(Add `java.util.Collection` import. `@Modifying` callers must be `@Transactional` — Task 6 satisfies this.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=ApprovalAskRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Approval.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/repository/ApprovalAskRepositoryTest.java
git commit -m "feat(approval): HITL ask fields and per-card finders"
```

---

### Task 4: KanbanItem pickup fields + pendingAskCount + create-with-status

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanItem.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanService.java` (create + list)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/CreateKanbanItemRequest.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanServiceAskCountTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KanbanServiceAskCountTest {

    @Autowired private KanbanService kanbanService;
    @Autowired private KanbanRepository kanbanRepository;
    @Autowired private ApprovalRepository approvalRepository;

    @Test
    void createHonorsRequestedStatus() {
        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("card").status(KanbanStatus.BACKLOG).build();
        assertThat(kanbanService.create(request).getStatus()).isEqualTo(KanbanStatus.BACKLOG);
    }

    @Test
    void listPopulatesPendingAskCount() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder().title("reviewed").build());
        approvalRepository.save(Approval.builder()
                .runId(UUID.randomUUID()).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .kanbanItemId(card.getId()).build());
        approvalRepository.save(Approval.builder()
                .runId(UUID.randomUUID()).status(ApprovalStatus.DENIED)
                .askType(Approval.AskType.QUESTION)
                .kanbanItemId(card.getId()).build());

        KanbanItem loaded = kanbanService.list(null).stream()
                .filter(i -> i.getId().equals(card.getId())).findFirst().orElseThrow();

        assertThat(loaded.getPendingAskCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=KanbanServiceAskCountTest`
Expected: COMPILE ERROR — `status(...)` / `getPendingAskCount()` missing.

- [ ] **Step 3: Implement**

`KanbanItem.java` — add fields:

```java
    /** Agent template requested at creation (assignment hint for pickup). */
    @Column(name = "agent_template_id", length = 100)
    private String agentTemplateId;

    /** Last pickup/transition failure, surfaced on the card face. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /** Not persisted: number of PENDING asks surfaced in the Review column. */
    @javax.persistence.Transient
    private Integer pendingAskCount;
```

(Note: with `jakarta.persistence`, use `jakarta.persistence.Transient` — match the import style already in the file.)

`CreateKanbanItemRequest.java` — add:

```java
    private KanbanStatus status;
```

`KanbanService.create` — replace `.status(KanbanStatus.TODO)` with:

```java
                    .status(request.getStatus() != null ? request.getStatus() : KanbanStatus.TODO)
```

`KanbanService.list` — replace the body:

```java
    @Transactional(readOnly = true)
    public List<KanbanItem> list(KanbanStatus status) {
        List<KanbanItem> items = status == null ? repository.findAll() : repository.findByStatus(status);
        if (!items.isEmpty()) {
            List<Object[]> counts = approvalRepository.countPendingByKanbanItemIds(
                    items.stream().map(KanbanItem::getId).toList());
            Map<String, Long> byItem = new java.util.HashMap<>();
            counts.forEach(row -> byItem.put((String) row[0], (Long) row[1]));
            items.forEach(item -> item.setPendingAskCount(byItem.getOrDefault(item.getId(), 0L).intValue()));
        }
        return items;
    }
```

Add `ApprovalRepository approvalRepository` to the constructor (new constructor arg — update existing tests that construct `KanbanService` manually; `KanbanTransitionTableTest` from Task 2 passes `null`s, add a fourth `null`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest="Kanban*Test"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -u agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/
git add agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanServiceAskCountTest.java
git commit -m "feat(kanban): pickup fields, pendingAskCount, create-with-status"
```

---

### Task 5: AgentPickerService (rule-based)

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/AgentPickerService.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/AgentPickerServiceTest.java`

- [ ] **Step 1: Write the failing test**

First check how agents are queryable: `grep -n "findBy\|List<Agent>" agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/repository/AgentRepository.java` and use the real finder for healthy agents (expected to exist: health-status based finder; if none, add `List<Agent> findByHealthStatusNot(HealthStatus status)` to `AgentRepository`).

```java
package io.aria.conductor.execution.kanban;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AgentPickerServiceTest {

    @Test
    void prefersAgentMatchingTemplateLabel() {
        // mock the collaborator you found in the repo step: a function returning candidate agents
        AgentPickerService.Candidates candidates = Mockito.mock(AgentPickerService.Candidates.class);
        when(candidates.healthy()).thenReturn(List.of(
                new AgentPickerService.Candidate(UUID.randomUUID(), "dev-worker"),
                new AgentPickerService.Candidate(UUID.randomUUID(), "ba-agent")));

        AgentPickerService picker = new AgentPickerService(candidates);
        AgentPickerService.Choice choice = picker.pick("ba-agent", "write spec", null);

        assertThat(choice.agentName()).isEqualTo("ba-agent");
    }

    @Test
    void fallsBackToFirstHealthyAgent() {
        AgentPickerService.Candidates candidates = Mockito.mock(AgentPickerService.Candidates.class);
        when(candidates.healthy()).thenReturn(List.of(
                new AgentPickerService.Candidate(UUID.randomUUID(), "dev-worker")));

        AgentPickerService.Choice choice = pickerFor(candidates).pick(null, "anything", null);

        assertThat(choice.agentName()).isEqualTo("dev-worker");
    }

    private AgentPickerService pickerFor(AgentPickerService.Candidates c) {
        return new AgentPickerService(c);
    }
}
```

Note: run this only after Step 3 defines `Candidates`/`Candidate`/`Choice`/`pick`. If the real repo exposes agents as entities rather than records, adapt `Candidate` to wrap `(UUID id, String name)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=AgentPickerServiceTest`
Expected: COMPILE ERROR — class does not exist.

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.execution.kanban;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rule-based agent selection for kanban pickup (spec 4.2): template-id or
 * label containment match against healthy agents, falling back to the first
 * healthy agent. No LLM call in v1.
 */
@Service
public class AgentPickerService {

    public record Candidate(UUID agentId, String name) {}

    public record Choice(UUID agentId, String agentName) {}

    public interface Candidates {
        List<Candidate> healthy();
    }

    private final Candidates candidates;

    public AgentPickerService(Candidates candidates) {
        this.candidates = candidates;
    }

    public Choice pick(String agentTemplateId, String title, String description) {
        List<Candidate> pool = candidates.healthy();
        if (pool.isEmpty()) {
            throw new IllegalStateException("No healthy agent available for kanban pickup");
        }
        Optional<Candidate> matched = Optional.empty();
        if (agentTemplateId != null && !agentTemplateId.isBlank()) {
            String needle = agentTemplateId.toLowerCase();
            matched = pool.stream()
                    .filter(c -> c.name() != null && c.name().toLowerCase().contains(needle))
                    .findFirst();
        }
        Candidate chosen = matched.orElse(pool.get(0));
        return new Choice(chosen.agentId(), chosen.name());
    }
}
```

Plus a production adapter (same file tree, `kanban/AgentRepositoryCandidates.java`) wiring `Candidates` to the real agent repository you located in Step 1:

```java
package io.aria.conductor.execution.kanban;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRepositoryCandidates implements AgentPickerService.Candidates {

    private final /* real AgentRepository type */ Object agentRepository;

    public AgentRepositoryCandidates(/* real AgentRepository type */ Object agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public List<AgentPickerService.Candidate> healthy() {
        // map repository rows -> Candidate(agent.getId(), agent.getName())
        throw new UnsupportedOperationException("wire to real repository in this step");
    }
}
```

You MUST replace the `Object` placeholders with the concrete `AgentRepository` type + healthy finder found in Step 1 before committing — this is a wiring step with a known single correct answer, discoverable by reading `AgentRepository` and `Agent` (name field: `Agent.getName()`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=AgentPickerServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/AgentPickerService.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/AgentRepositoryCandidates.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/AgentPickerServiceTest.java
git commit -m "feat(kanban): rule-based agent picker for pickup"
```

---

### Task 6: KanbanTransitionService (the orchestrator)

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanTransitionService.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/KanbanItemAssigningEvent.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/TransitionRequest.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanTransitionServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KanbanTransitionServiceTest {

    private KanbanRepository kanbanRepository;
    private RunService runService;
    private RunRepository runRepository;
    private AgentPickerService agentPicker;
    private ApprovalRepository approvalRepository;
    private KanbanService kanbanService;
    private KanbanTransitionService service;

    private KanbanItem card;

    @BeforeEach
    void setUp() {
        kanbanRepository = mock(KanbanRepository.class);
        runService = mock(RunService.class);
        runRepository = mock(RunRepository.class);
        agentPicker = mock(AgentPickerService.class);
        approvalRepository = mock(ApprovalRepository.class);
        kanbanService = mock(KanbanService.class);
        service = new KanbanTransitionService(kanbanRepository, kanbanService, runService,
                runRepository, agentPicker, approvalRepository, mock(ApplicationEventPublisher.class));

        card = KanbanItem.builder().id("c1").title("add CSV export")
                .status(KanbanStatus.TODO).priority(KanbanPriority.MEDIUM).build();
        when(kanbanRepository.findById("c1")).thenReturn(Optional.of(card));
        when(kanbanService.transition(any(), any(), any())).thenAnswer(inv -> card);
    }

    @Test
    void todoPickupAssignsAgentCreatesRunMovesToInProgress() {
        when(agentPicker.pick(isNull(), any(), any()))
                .thenReturn(new AgentPickerService.Choice(UUID.randomUUID(), "ba-agent"));
        when(runService.createRun(any())).thenReturn(null); // return value unused

        service.transition("c1", new TransitionRequest() {{ setStatus(KanbanStatus.TODO); }});

        ArgumentCaptor<CreateRunRequest> runCaptor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getPromptSeed()).contains("add CSV export");
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
        assertThat(card.getAssignee()).isEqualTo("ba-agent");
        assertThat(card.getLastError()).isNull();
    }

    @Test
    void pickupFailureStaysInTodoWithLastError() {
        when(agentPicker.pick(any(), any(), any()))
                .thenReturn(new AgentPickerService.Choice(UUID.randomUUID(), "ba-agent"));
        when(runService.createRun(any())).thenThrow(new IllegalArgumentException("unhealthy agent"));

        KanbanItem result = service.transition("c1", new TransitionRequest() {{ setStatus(KanbanStatus.TODO); }});

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.TODO);
        assertThat(result.getLastError()).contains("unhealthy agent");
        verify(kanbanService, never()).transition(any(), eq(KanbanStatus.IN_PROGRESS), any());
    }

    @Test
    void dragBackPausesLinkedRun() {
        card.setStatus(KanbanStatus.IN_PROGRESS);
        String runId = UUID.randomUUID().toString();
        card.setLinkedRunId(runId);
        Run run = Run.builder().status(RunStatus.RUNNING).build();
        when(runRepository.findById(UUID.fromString(runId))).thenReturn(Optional.of(run));

        service.transition("c1", new TransitionRequest() {{ setStatus(KanbanStatus.BACKLOG); }});

        verify(runService).pauseRun(UUID.fromString(runId));
        verify(kanbanService).transition("c1", KanbanStatus.BACKLOG, null);
    }

    @Test
    void cancelDeniesPendingAsksAndCancelsRun() {
        card.setStatus(KanbanStatus.REVIEW);
        String runId = UUID.randomUUID().toString();
        card.setLinkedRunId(runId);
        Run run = Run.builder().status(RunStatus.RUNNING).build();
        when(runRepository.findById(UUID.fromString(runId))).thenReturn(Optional.of(run));

        service.transition("c1", new TransitionRequest() {{ setStatus(KanbanStatus.CANCELLED); }});

        verify(approvalRepository).denyPendingByKanbanItemId(eq("c1"), eq("task cancelled"), any());
        verify(runService).cancelRun(UUID.fromString(runId));
        verify(kanbanService).transition("c1", KanbanStatus.CANCELLED, null);
    }

    @Test
    void reviewToTodoRequestChangesMarksAsksAndRedispaches() {
        card.setStatus(KanbanStatus.REVIEW);
        when(agentPicker.pick(any(), any(), any()))
                .thenReturn(new AgentPickerService.Choice(UUID.randomUUID(), "dev-agent"));

        TransitionRequest req = new TransitionRequest();
        req.setStatus(KanbanStatus.TODO);
        req.setFeedback("use streaming API instead");
        service.transition("c1", req);

        verify(approvalRepository).markStaleByKanbanItemId("c1");
        ArgumentCaptor<CreateRunRequest> runCaptor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getPromptSeed()).contains("use streaming API instead");
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }

    @Test
    void reviewToInProgressResumesLinkedRun() {
        card.setStatus(KanbanStatus.REVIEW);
        String runId = UUID.randomUUID().toString();
        card.setLinkedRunId(runId);
        Run run = Run.builder().status(RunStatus.PAUSED).build();
        when(runRepository.findById(UUID.fromString(runId))).thenReturn(Optional.of(run));

        service.transition("c1", new TransitionRequest() {{ setStatus(KanbanStatus.IN_PROGRESS); }});

        verify(runService).resumeRun(UUID.fromString(runId));
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=KanbanTransitionServiceTest`
Expected: COMPILE ERROR — `KanbanTransitionService` missing.

- [ ] **Step 3: Implement**

`KanbanItemAssigningEvent.java` (mirror `KanbanItemTransitionedEvent`):

```java
package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class KanbanItemAssigningEvent extends ApplicationEvent {

    private final String itemId;

    public KanbanItemAssigningEvent(Object source, String itemId) {
        super(source);
        this.itemId = itemId;
    }
}
```

`TransitionRequest.java` — add fields (keep `status`, `comment` for backward compatibility):

```java
    /** Used when a REVIEW card is sent back to TODO (request changes). */
    private String feedback;

    /** Optional agent template hint for pickup; overrides item value. */
    private String agentTemplateId;
```

`KanbanTransitionService.java`:

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.KanbanItemAssigningEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates kanban transitions with their run side effects (spec section 4):
 * Todo entry is a dispatch intent (two-phase pickup), dragging back pauses the
 * linked run at a step boundary, request-changes re-dispatches with feedback,
 * cancel denies open asks and cancels the run.
 */
@Slf4j
@Service
public class KanbanTransitionService {

    private final KanbanRepository kanbanRepository;
    private final KanbanService kanbanService;
    private final RunService runService;
    private final RunRepository runRepository;
    private final AgentPickerService agentPicker;
    private final ApprovalRepository approvalRepository;
    private final ApplicationEventPublisher eventPublisher;

    public KanbanTransitionService(KanbanRepository kanbanRepository,
                                   KanbanService kanbanService,
                                   RunService runService,
                                   RunRepository runRepository,
                                   AgentPickerService agentPicker,
                                   ApprovalRepository approvalRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
        this.runService = runService;
        this.runRepository = runRepository;
        this.agentPicker = agentPicker;
        this.approvalRepository = approvalRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public KanbanItem transition(String id, TransitionRequest request) {
        KanbanItem item = kanbanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanItem", id));
        KanbanStatus to = request.getStatus();
        item.setLastError(null);

        return switch (to) {
            case BACKLOG -> {
                pauseIfRunning(item);
                yield kanbanService.transition(id, KanbanStatus.BACKLOG, request.getComment());
            }
            case TODO -> item.getStatus() == KanbanStatus.REVIEW
                    ? requestChanges(item, request)
                    : pickup(item, request);
            case IN_PROGRESS -> item.getStatus() == KanbanStatus.REVIEW
                    ? resume(item)
                    : pickup(item, request);
            case REVIEW -> kanbanService.transition(id, KanbanStatus.REVIEW, request.getComment());
            case DONE -> kanbanService.transition(id, KanbanStatus.DONE, request.getComment());
            case CANCELLED -> cancel(item, request.getComment());
            default -> throw new IllegalArgumentException("Unsupported target status: " + to);
        };
    }

    /** Two-phase pickup: assign (rule-based) then create the run (spec 4.2). */
    private KanbanItem pickup(KanbanItem item, TransitionRequest request) {
        if (isBlank(item.getAssignee()) && isBlank(item.getLinkedAgentId())) {
            eventPublisher.publishEvent(new KanbanItemAssigningEvent(this, item.getId()));
            String templateId = firstNonBlank(request.getAgentTemplateId(), item.getAgentTemplateId());
            AgentPickerService.Choice choice = agentPicker.pick(templateId, item.getTitle(), item.getDescription());
            item.setLinkedAgentId(choice.agentId().toString());
            item.setAssignee(choice.agentName());
            if (!isBlank(templateId)) {
                item.setAgentTemplateId(templateId);
            }
        }
        UUID agentId = UUID.fromString(item.getLinkedAgentId());
        try {
            runService.createRun(CreateRunRequest.builder()
                    .agentId(agentId)
                    .promptSeed(buildPromptSeed(item, request.getFeedback()))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Kanban pickup failed for {}: {}", item.getId(), e.getMessage());
            item.setLastError(abbreviate(e.getMessage()));
            return kanbanRepository.save(item); // stays in TODO, no auto retry
        }
        return kanbanService.transition(item.getId(), KanbanStatus.IN_PROGRESS, request.getComment());
    }

    private KanbanItem requestChanges(KanbanItem item, TransitionRequest request) {
        approvalRepository.markStaleByKanbanItemId(item.getId());
        kanbanService.transition(item.getId(), KanbanStatus.TODO, request.getComment());
        return pickup(item, request);
    }

    private KanbanItem resume(KanbanItem item) {
        findRun(item).ifPresentOrElse(run -> {
            if (run.getStatus() == RunStatus.PAUSED) {
                runService.resumeRun(UUID.fromString(item.getLinkedRunId()));
            }
        }, () -> { /* no linked run: treat as plain transition */ });
        return kanbanService.transition(item.getId(), KanbanStatus.IN_PROGRESS, null);
    }

    private KanbanItem cancel(KanbanItem item, String comment) {
        approvalRepository.denyPendingByKanbanItemId(item.getId(), "task cancelled", Instant.now());
        findRun(item).ifPresent(run -> {
            if (run.getStatus() == RunStatus.PENDING || run.getStatus() == RunStatus.INITIALIZING
                    || run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSED) {
                runService.cancelRun(UUID.fromString(item.getLinkedRunId()));
            }
        });
        return kanbanService.transition(item.getId(), KanbanStatus.CANCELLED, comment);
    }

    private void pauseIfRunning(KanbanItem item) {
        findRun(item).ifPresent(run -> {
            if (run.getStatus() == RunStatus.RUNNING) {
                runService.pauseRun(UUID.fromString(item.getLinkedRunId()));
            }
        });
    }

    private java.util.Optional<Run> findRun(KanbanItem item) {
        if (isBlank(item.getLinkedRunId())) return java.util.Optional.empty();
        try {
            return runRepository.findById(UUID.fromString(item.getLinkedRunId()));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private String buildPromptSeed(KanbanItem item, String feedback) {
        StringBuilder sb = new StringBuilder("Kanban task: ").append(item.getTitle());
        if (!isBlank(item.getDescription())) {
            sb.append("\n\nDescription:\n").append(item.getDescription());
        }
        if (!isBlank(feedback)) {
            sb.append("\n\nOperator feedback on the previous attempt:\n").append(feedback);
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    private static String abbreviate(String msg) {
        if (msg == null) return "pickup failed";
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }
}
```

Add `markStaleByKanbanItemId` to `ApprovalRepository` (Task 3 file):

```java
    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.EXPIRED, " +
           "a.reason = 'superseded by request changes' " +
           "where a.kanbanItemId = :itemId and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING")
    int markStaleByKanbanItemId(@Param("itemId") String itemId);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=KanbanTransitionServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanTransitionService.java \
        agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/KanbanItemAssigningEvent.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/TransitionRequest.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanTransitionServiceTest.java
git commit -m "feat(kanban): transition orchestrator with pickup/pause/request-changes/cancel"
```

---

### Task 7: Controller wiring + approvals-by-card + answer endpoint

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanController.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanControllerOrchestratorTest.java` (MockMvc, follow the style of existing controller tests in `.../execution/controller/`)

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.kanban;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KanbanController.class)
class KanbanControllerOrchestratorTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private KanbanTransitionService kanbanTransitionService;

    @Test
    void transitionDelegatesToOrchestrator() throws Exception {
        mockMvc.perform(post("/api/v1/kanban/items/c1/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\",\"feedback\":\"redo\",\"agentTemplateId\":\"ba-agent\"}"))
                .andExpect(status().isOk());

        verify(kanbanTransitionService).transition(eq("c1"), any(TransitionRequest.class));
    }
}
```

(If the act-execution `@WebMvcTest` slice requires additional config, mirror whatever existing controller tests in the module do — e.g. `ApprovalControllerTest`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=KanbanControllerOrchestratorTest`
Expected: FAIL — controller still calls `kanbanService.transition`.

- [ ] **Step 3: Implement**

`KanbanController` — inject `KanbanTransitionService` and change the transition endpoint:

```java
    private final KanbanTransitionService kanbanTransitionService;

    public KanbanController(KanbanService kanbanService, KanbanTransitionService kanbanTransitionService) {
        this.kanbanService = kanbanService;
        this.kanbanTransitionService = kanbanTransitionService;
    }

    @PostMapping("/{id}/transition")
    public ResponseEntity<KanbanItem> transition(@PathVariable String id,
                                                 @Valid @RequestBody TransitionRequest request) {
        return ResponseEntity.ok(kanbanTransitionService.transition(id, request));
    }
```

`ApprovalController` (read the file first; keep existing endpoints) — add two endpoints:

```java
    @GetMapping(params = "kanbanItemId")
    public ResponseEntity<List<Approval>> listByKanbanItem(@RequestParam String kanbanItemId) {
        return ResponseEntity.ok(approvalRepository.findByKanbanItemId(kanbanItemId));
    }

    public record AnswerRequest(String answer, Boolean approved, String reason) {}

    @PostMapping("/{id}/answer")
    @Transactional
    public ResponseEntity<Approval> answer(@PathVariable UUID id,
                                           @RequestBody AnswerRequest request) {
        Approval approval = approvalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + id));
        approval.setAnswer(request.answer());
        if (request.approved() != null) {
            approval.setStatus(request.approved()
                    ? io.aria.conductor.common.model.ApprovalStatus.APPROVED
                    : io.aria.conductor.common.model.ApprovalStatus.DENIED);
            approval.setDecidedAt(Instant.now());
            if (request.reason() != null) approval.setReason(request.reason());
        }
        return ResponseEntity.ok(approvalRepository.save(approval));
    }
```

(Adapt injection style to what the controller already uses — if it goes through a service rather than the repository, put these two methods on that service instead; keep the routes identical.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest="KanbanControllerOrchestratorTest,ApprovalControllerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanController.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanControllerOrchestratorTest.java
git commit -m "feat(api): transition via orchestrator; per-card asks and answer endpoint"
```

---

### Task 8: Orphan approval auto-carding listener

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanReviewCardListener.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanReviewCardListenerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KanbanReviewCardListenerTest {

    private ApprovalRepository approvalRepository;
    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private KanbanReviewCardListener listener;

    private Approval approval;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        kanbanRepository = mock(KanbanRepository.class);
        kanbanService = mock(KanbanService.class);
        listener = new KanbanReviewCardListener(approvalRepository, kanbanRepository, kanbanService);

        approval = Approval.builder().runId(runId).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.APPROVAL).content("spec to approve").build();
        when(approvalRepository.findById(approval.getId())).thenReturn(Optional.of(approval));
    }

    @Test
    void linksExistingCardByRunId() {
        KanbanItem card = KanbanItem.builder().id("card-1").title("existing").status(KanbanStatus.REVIEW).build();
        when(kanbanRepository.findByLinkedRunId(runId.toString())).thenReturn(List.of(card));

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approval.getId(), runId, null));

        assertThat(approval.getKanbanItemId()).isEqualTo("card-1");
        verify(kanbanService, never()).create(any());
    }

    @Test
    void createsReviewCardForOrphanApproval() {
        when(kanbanRepository.findByLinkedRunId(runId.toString())).thenReturn(List.of());
        when(kanbanService.create(any())).thenAnswer(inv -> {
            CreateKanbanItemRequest req = inv.getArgument(0);
            return KanbanItem.builder().id("new-card").title(req.getTitle())
                    .status(req.getStatus()).linkedRunId(req.getLinkedRunId()).build();
        });

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approval.getId(), runId, null));

        ArgumentCaptor<CreateKanbanItemRequest> captor = ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(KanbanStatus.REVIEW);
        assertThat(captor.getValue().getLinkedRunId()).isEqualTo(runId.toString());
        assertThat(approval.getKanbanItemId()).isEqualTo("new-card");
    }
}
```

(Add the missing imports: `java.util.List`, `org.mockito.ArgumentCaptor`, `static org.assertj.core.api.Assertions.assertThat`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=KanbanReviewCardListenerTest`
Expected: COMPILE ERROR — listener missing.

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Surfaces every approval as a Review-column card (spec 4.3): links the ask to
 * the card already associated with the run, or creates a REVIEW card for
 * orphan approvals.
 */
@Slf4j
@Component
public class KanbanReviewCardListener {

    private final ApprovalRepository approvalRepository;
    private final KanbanRepository kanbanRepository;
    private final KanbanService kanbanService;

    public KanbanReviewCardListener(ApprovalRepository approvalRepository,
                                    KanbanRepository kanbanRepository,
                                    KanbanService kanbanService) {
        this.approvalRepository = approvalRepository;
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
    }

    @EventListener
    @Transactional
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        approvalRepository.findById(event.getApprovalId()).ifPresent(approval -> {
            if (approval.getKanbanItemId() != null) return;

            kanbanRepository.findByLinkedRunId(event.getRunId().toString()).stream()
                    .filter(card -> card.getStatus() == KanbanStatus.REVIEW
                            || card.getStatus() == KanbanStatus.IN_PROGRESS
                            || card.getStatus() == KanbanStatus.TODO)
                    .findFirst()
                    .ifPresentOrElse(
                            card -> link(approval, card),
                            () -> createCard(approval, event));
            approvalRepository.save(approval);
        });
    }

    private void link(Approval approval, KanbanItem card) {
        approval.setKanbanItemId(card.getId());
    }

    private void createCard(Approval approval, ApprovalRequestedEvent event) {
        String title = "Review: " + (approval.getApprovalType() != null
                ? approval.getApprovalType().name().toLowerCase().replace('_', ' ')
                : "approval") + " (run " + event.getRunId().toString().substring(0, 8) + ")";
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder()
                .title(title)
                .description(approval.getContent())
                .status(KanbanStatus.REVIEW)
                .linkedRunId(event.getRunId().toString())
                .build());
        approval.setKanbanItemId(card.getId());
        log.info("Auto-created review card {} for orphan approval {}", card.getId(), approval.getId());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=KanbanReviewCardListenerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanReviewCardListener.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanReviewCardListenerTest.java
git commit -m "feat(kanban): auto-card orphan approvals into Review"
```

---

### Task 9: act-aria KanbanToolHandler rewire

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/tools/handlers/KanbanToolHandler.java` (transitionKanbanItem method + constructor)

- [ ] **Step 1: Update the handler**

Constructor gains `KanbanTransitionService kanbanTransitionService`. Replace `transitionKanbanItem`:

```java
    private String transitionKanbanItem(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        // TS MCP server sends "status"; the legacy Aria tool contract sends "newStatus".
        String statusStr = firstNonBlank(Objects.toString(args.get("status"), ""),
                Objects.toString(args.get("newStatus"), ""));
        if (statusStr.isEmpty()) return error("Missing required parameter: status");

        KanbanStatus status;
        try {
            status = KanbanStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return error("Invalid status: " + statusStr
                    + ". Valid: BACKLOG, TODO, IN_PROGRESS, REVIEW, DONE, CANCELLED");
        }

        TransitionRequest request = TransitionRequest.builder()
                .status(status)
                .comment(blankToNull(Objects.toString(args.get("comment"), "")))
                .feedback(blankToNull(Objects.toString(args.get("feedback"), "")))
                .agentTemplateId(blankToNull(Objects.toString(args.get("agentTemplateId"), "")))
                .build();
        KanbanItem item = kanbanTransitionService.transition(id, request);
        return "Kanban item " + id + " transitioned to " + item.getStatus().name() + ".";
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
```

`TransitionRequest` needs `@Builder` on the new fields — it already has class-level `@Builder`, so `feedback`/`agentTemplateId` are automatically included.

- [ ] **Step 2: Update/add unit test**

Modify the existing handler test (search: `grep -rl "KanbanToolHandler" agent-control-tower/act-aria/src/test/`) — add a case asserting `transition_kanban_item` with `{"id":"c1","status":"TODO"}` delegates to `KanbanTransitionService` (mock it) and that `newStatus` still works. Assert the updated valid-status error string contains `BACKLOG`.

- [ ] **Step 3: Run tests**

Run: `cd agent-control-tower && mvn test -pl act-aria -Dtest="*KanbanToolHandler*"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/tools/handlers/KanbanToolHandler.java
git add agent-control-tower/act-aria/src/test/
git commit -m "feat(aria): kanban tool transition delegates to orchestrator"
```

---

### Task 10: MCP server rewire + vitest

**Files:**
- Modify: `packages/mcp-server/src/tools/kanban.ts`
- Modify: `packages/mcp-server/src/__tests__/tools/kanban.test.ts`

- [ ] **Step 1: Write the failing test** (append inside the existing `describe`)

```ts
  it('transition_kanban_item posts status+feedback to the transition endpoint', async () => {
    fetchMock = mockFetch({
      '/api/v1/kanban/items/c1/transition': { status: 200, body: { id: 'c1', status: 'TODO' } },
    });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({
      name: 'transition_kanban_item',
      arguments: { id: 'c1', status: 'TODO', feedback: 'use streaming', agentTemplateId: 'ba-agent' },
    });
    const call = fetchMock.calls.find((c) => c.url.includes('/transition'));
    expect(call.method).toBe('POST');
    expect(JSON.parse(call.body)).toEqual({
      status: 'TODO',
      feedback: 'use streaming',
      agentTemplateId: 'ba-agent',
    });
    expect(JSON.parse(resultText(r)).id).toBe('c1');
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd packages/mcp-server && npx vitest run src/__tests__/tools/kanban.test.ts`
Expected: FAIL — schema rejects `feedback`/`agentTemplateId` (zod strips or errors).

- [ ] **Step 3: Implement** — replace `transition_kanban_item` in `kanban.ts`:

```ts
  {
    name: 'transition_kanban_item',
    description:
      'Move a kanban item to a new status column. TODO triggers agent pickup and a run; moving out of In Progress pauses the linked run.',
    inputSchema: z.object({
      id: z.string().describe('Kanban item ID'),
      status: z
        .enum(['BACKLOG', 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE', 'CANCELLED'])
        .describe('Target status'),
      comment: z.string().optional().describe('Optional transition comment'),
      feedback: z.string().optional().describe('Feedback sent when returning a REVIEW item to TODO'),
      agentTemplateId: z.string().optional().describe('Agent template hint for pickup'),
    }),
    handler: async ({ id, ...body }: { id: string;[k: string]: unknown }) =>
      toJsonResult(await http.post(`/api/v1/kanban/items/${id}/transition`, body)),
  },
```

Also update the `list_kanban_items` status enum to include `'CANCELLED'` (backend accepts it; the frontend already shows cancelled cards via the cancel action / housekeeping).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd packages/mcp-server && npx vitest run`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add packages/mcp-server/src/tools/kanban.ts packages/mcp-server/src/__tests__/tools/kanban.test.ts
git commit -m "feat(mcp): transition_kanban_item carries feedback and template hint"
```

---

### Task 11: Frontend types + api layer

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/types/index.ts` (Kanban section, lines ~310-349)
- Modify: `agent-control-tower/act-dashboard/src/api/approvals.ts`
- Test: `agent-control-tower/act-dashboard/src/utils/__tests__/` (type-only change; covered by component tests later)

- [ ] **Step 1: Update types**

```ts
export type KanbanStatus =
  | 'BACKLOG' | 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED' | 'REVIEW';

export interface KanbanItem {
  id: string;
  title: string;
  description: string | null;
  status: KanbanStatus;
  priority: KanbanPriority;
  assignee: string | null;
  labels: string | null;
  linkedRunId: string | null;
  linkedAgentId: string | null;
  agentTemplateId?: string | null;
  lastError?: string | null;
  pendingAskCount?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateKanbanItemRequest {
  title: string;
  description?: string;
  priority?: KanbanPriority;
  assignee?: string;
  labels?: string;
  linkedRunId?: string;
  linkedAgentId?: string;
  status?: 'TODO' | 'BACKLOG';
}

export interface TransitionKanbanRequest {
  status: KanbanStatus;
  comment?: string;
  feedback?: string;
  agentTemplateId?: string;
}
```

Also add `Approval` type extensions (find the existing `Approval` interface around line 170): `kanbanItemId?: string | null; askType?: 'APPROVAL' | 'QUESTION' | 'REVIEW_REQUEST'; contextMd?: string | null; optionsJson?: string | null; answer?: string | null;`

- [ ] **Step 2: Update approvals api** — append:

```ts
export async function listAsksByKanbanItem(kanbanItemId: string): Promise<Approval[]> {
  const { data } = await client.get<Approval[]>('/api/v1/approvals', {
    params: { kanbanItemId },
  });
  return data;
}

export async function answerAsk(
  id: string,
  payload: { answer?: string; approved?: boolean; reason?: string }
): Promise<Approval> {
  const { data } = await client.post<Approval>(`/api/v1/approvals/${id}/answer`, payload);
  return data;
}
```

- [ ] **Step 3: Type-check**

Run: `cd agent-control-tower/act-dashboard && pnpm build`
Expected: FAIL in KanbanBoard/TaskDrawer referencing removed `BLOCKED` flows is possible — if type errors appear in components, they will be resolved by Tasks 12/14; if errors appear elsewhere, fix them now minimally (e.g. replace `BLOCKED` in a status-label map with `REVIEW`).

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-dashboard/src/types/index.ts agent-control-tower/act-dashboard/src/api/approvals.ts
git commit -m "feat(dashboard): kanban HITL types and per-card ask api"
```

---

### Task 12: KanbanBoard — 5 status columns + DnD + ask badges + cancel action

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/KanbanBoard.test.tsx` (extend existing)

- [ ] **Step 1: Write the failing tests** (append to the existing describe; keep existing passing fixtures)

```tsx
  it('renders five status-driven columns', () => {
    renderKanban([{ ...baseItem(), status: 'BACKLOG' }, { ...baseItem(), id: 'k2', status: 'REVIEW' }]);
    expect(screen.getByText('Backlog')).toBeInTheDocument();
    expect(screen.getByText('Todo')).toBeInTheDocument();
    expect(screen.getByText('In Progress')).toBeInTheDocument();
    expect(screen.getByText(/Review/)).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
    expect(screen.queryByText('QA Gate')).not.toBeInTheDocument();
    expect(screen.queryByText('Cancelled')).not.toBeInTheDocument();
  });

  it('shows pending ask badge on review cards', () => {
    renderKanban([{ ...baseItem(), id: 'k3', status: 'REVIEW', pendingAskCount: 2 }]);
    expect(screen.getByText('2 asks')).toBeInTheDocument();
  });

  it('drop calls transition with target status and rolls back on failure', async () => {
    mockedTransitionKanbanItem.mockRejectedValueOnce({ message: 'invalid' });
    renderKanban([{ ...baseItem() }]); // status TODO
    const card = screen.getByTestId('card-' + baseItem().id);
    card.dispatchEvent(new Event('dragstart', { bubbles: true }));
    const lane = screen.getByTestId('lane-IN_PROGRESS');
    lane.dispatchEvent(new Event('dragover', { bubbles: true, cancelable: true }));
    lane.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }));
    await waitFor(() => expect(mockedTransitionKanbanItem).toHaveBeenCalledWith(baseItem().id, { status: 'IN_PROGRESS' }));
  });

  it('cancel action transitions to CANCELLED', async () => {
    mockedTransitionKanbanItem.mockResolvedValueOnce({ ...baseItem(), status: 'CANCELLED' });
    renderKanban([{ ...baseItem() }]);
    await userEvent.click(screen.getByTitle('Cancel task'));
    await waitFor(() => expect(mockedTransitionKanbanItem).toHaveBeenCalledWith(baseItem().id, { status: 'CANCELLED' }));
  });
```

Adjust `renderKanban`/`baseItem` to the helpers already present in the test file (read it first; keep its mocking style for `../api/kanban` — likely `vi.mock` + `mockedTransitionKanbanItem`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx`
Expected: FAIL — columns still label-based, no DnD handlers.

- [ ] **Step 3: Implement** — in `KanbanBoard.tsx`:

Replace the `COLUMNS` definition and `hasLabel` helper:

```tsx
const COLUMNS: ColumnDef[] = [
  { key: 'BACKLOG', label: 'Backlog', filter: (it) => it.status === 'BACKLOG' },
  { key: 'TODO', label: 'Todo', filter: (it) => it.status === 'TODO' },
  { key: 'IN_PROGRESS', label: 'In Progress', filter: (it) => it.status === 'IN_PROGRESS' },
  { key: 'REVIEW', label: 'Review', isGate: true, filter: (it) => it.status === 'REVIEW' },
  { key: 'DONE', label: 'Done', filter: (it) => it.status === 'DONE' },
];

// Mirror of the backend ALLOWED_TRANSITIONS — drives drop-target legality only.
const LEGAL_DROPS: Record<KanbanStatus, KanbanStatus[]> = {
  BACKLOG: ['TODO', 'CANCELLED'],
  TODO: ['IN_PROGRESS', 'BACKLOG', 'CANCELLED'],
  IN_PROGRESS: ['TODO', 'BACKLOG', 'REVIEW', 'DONE', 'CANCELLED'],
  REVIEW: ['IN_PROGRESS', 'TODO', 'DONE', 'CANCELLED'],
  DONE: [],
  CANCELLED: [],
};
```

Add drag state + transition mutation (inside the component, after `createMutation`):

```tsx
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const transitionMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: KanbanStatus }) =>
      transitionKanbanItem(id, { status }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['kanban-items'] }),
    onError: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] }); // snap back
      setError('Move rejected by the backend — the card is back in its column.');
    },
  });

  const handleDrop = (target: KanbanStatus) => {
    if (!draggingId) return;
    const item = (items ?? []).find((i) => i.id === draggingId);
    setDraggingId(null);
    if (!item || item.status === target) return;
    if (!LEGAL_DROPS[item.status]?.includes(target)) return;
    queryClient.setQueryData<KanbanItem[]>(['kanban-items'], (old) =>
      (old ?? []).map((i) => (i.id === item.id ? { ...i, status: target } : i))
    ); // optimistic
    transitionMutation.mutate({ id: item.id, status: target });
  };
```

Import `transitionKanbanItem` and `KanbanStatus` type from `../types`. Update the column JSX: `<div className={...} data-col={col.key} onDragOver={(e) => { if (draggingId) e.preventDefault(); }} onDrop={() => handleDrop(col.key as KanbanStatus)}>`; the card element gets `draggable onDragStart={() => setDraggingId(item.id)} onDragEnd={() => setDraggingId(null)}`; the lane gets `data-testid={`lane-${col.key}`}`.

Card body additions (inside the card div, after the title):

```tsx
  {!col.isGate && (item.status === 'BACKLOG' || item.status === 'TODO' || item.status === 'IN_PROGRESS' || item.status === 'REVIEW') && (
    <button className="card-cancel" title="Cancel task" onClick={(e) => { e.stopPropagation(); transitionMutation.mutate({ id: item.id, status: 'CANCELLED' }); }}>✕</button>
  )}
  {item.status === 'REVIEW' && !!item.pendingAskCount && (
    <span className="pill warn">{item.pendingAskCount} asks</span>
  )}
  {item.lastError && <div className="owner" style={{ color: 'var(--red)' }}>{item.lastError}</div>}
```

Remove: the `qa_gate` and `archived` column defs, `hasLabel`, and the label-based filters; `finishedCount` becomes `grouped['DONE'].length`.

Add minimal CSS to `src/styles/index.css`:

```css
.card-cancel { position: absolute; top: 6px; right: 6px; background: transparent; border: none; color: var(--text-mute); cursor: pointer; opacity: 0; }
.card:hover .card-cancel { opacity: 1; }
.card[data-dragging='true'] { opacity: 0.5; }
.col-k .lane { min-height: 60px; }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/KanbanBoard.test.tsx \
        agent-control-tower/act-dashboard/src/styles/index.css
git commit -m "feat(dashboard): 5-column status board with drag-and-drop and ask badges"
```

---

### Task 13: New Task modal redesign

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx` (modal section, lines ~264-326)
- Create API helper: extend `agent-control-tower/act-dashboard/src/api/agents.ts` with `listAgentTemplates` if missing (check first: `grep -n "templates" agent-control-tower/act-dashboard/src/api/agents.ts`)
- Test: extend `KanbanBoard.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
  it('new task modal submits with description, template and target column', async () => {
    mockedCreateKanbanItem.mockResolvedValueOnce(baseItem());
    renderKanban([]);
    await userEvent.click(screen.getByRole('button', { name: /new item/i }));
    await userEvent.type(screen.getByLabelText('Title *'), 'add CSV export');
    await userEvent.type(screen.getByLabelText('Description'), 'export runs to csv');
    await userEvent.click(screen.getByRole('button', { name: 'Create in Todo' }));
    await waitFor(() =>
      expect(mockedCreateKanbanItem).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'add CSV export', status: 'TODO' })
      )
    );
  });

  it('backlog button creates card in BACKLOG', async () => {
    mockedCreateKanbanItem.mockResolvedValueOnce(baseItem());
    renderKanban([]);
    await userEvent.click(screen.getByRole('button', { name: /new item/i }));
    await userEvent.type(screen.getByLabelText('Title *'), 'idea');
    await userEvent.click(screen.getByRole('button', { name: 'Backlog' }));
    await waitFor(() =>
      expect(mockedCreateKanbanItem).toHaveBeenCalledWith(expect.objectContaining({ status: 'BACKLOG' }))
    );
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx`
Expected: FAIL — no Description field / target buttons.

- [ ] **Step 3: Implement** — replace the `NewItemDraft` interface and the modal JSX:

```tsx
interface NewItemDraft {
  title: string;
  description: string;
  priority: KanbanPriority;
  agentTemplateId: string;
}
const EMPTY_DRAFT: NewItemDraft = { title: '', description: '', priority: 'MEDIUM', agentTemplateId: '' };
```

Add a templates query inside the component (import `listAgentTemplates` from `../api/agents`; if the helper is missing add it: `export async function listAgentTemplates() { const { data } = await client.get<AgentTemplate[]>('/api/v1/agents/templates'); return data; }` and an `AgentTemplate` type `{ id: string; label: string; role: string; description: string | null; adkProvider: string | null }`):

```tsx
  const { data: templates } = useQuery({ queryKey: ['agent-templates'], queryFn: listAgentTemplates });
```

Modal JSX:

```tsx
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>New Task</h3>
            <div className="kanban-form">
              <label className="kanban-form-row">
                <span>Title *</span>
                <input autoFocus value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
              </label>
              <label className="kanban-form-row">
                <span>Description</span>
                <textarea
                  rows={7}
                  placeholder="Background, goal, acceptance criteria — Aria reads this first."
                  value={draft.description}
                  onChange={(e) => setDraft({ ...draft, description: e.target.value })}
                />
              </label>
              <div className="kanban-form-row">
                <span>Priority</span>
                <div className="priority-seg">
                  {(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as KanbanPriority[]).map((p) => (
                    <button
                      key={p}
                      className={draft.priority === p ? 'on' : ''}
                      onClick={() => setDraft({ ...draft, priority: p })}
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </div>
              <label className="kanban-form-row">
                <span>Assign to</span>
                <select
                  value={draft.agentTemplateId}
                  onChange={(e) => setDraft({ ...draft, agentTemplateId: e.target.value })}
                >
                  <option value="">Aria auto-assign</option>
                  {(templates ?? []).map((t) => (
                    <option key={t.id} value={t.id}>{t.label}</option>
                  ))}
                </select>
              </label>
              {error && <div className="kanban-form-error">{error}</div>}
              <div className="modal-actions">
                <button className="btn primary" disabled={createMutation.isPending}
                        onClick={() => handleCreate('TODO')}>
                  {createMutation.isPending ? 'Creating…' : 'Create in Todo'}
                </button>
                <button className="btn" disabled={createMutation.isPending}
                        onClick={() => handleCreate('BACKLOG')}>
                  Backlog
                </button>
              </div>
            </div>
          </div>
```

`handleCreate`:

```tsx
  const handleCreate = (target: 'TODO' | 'BACKLOG') => {
    const title = draft.title.trim();
    if (!title) {
      setError('Title is required');
      return;
    }
    createMutation.mutate({
      title,
      description: draft.description.trim() || undefined,
      priority: draft.priority,
      agentTemplateId: draft.agentTemplateId || undefined,
      status: target,
    });
  };
```

CSS (`src/styles/index.css`):

```css
.priority-seg { display: flex; gap: 4px; }
.priority-seg button { flex: 1; padding: 6px 0; border-radius: 6px; border: 1px solid var(--border); background: transparent; color: var(--text-mute); font-weight: 600; cursor: pointer; }
.priority-seg button.on { border-color: var(--accent); color: var(--accent); background: rgba(77, 163, 255, 0.08); }
```

(If `--accent`/`--border` variable names differ, use the tokens already defined at the top of `index.css`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx \
        agent-control-tower/act-dashboard/src/api/agents.ts \
        agent-control-tower/act-dashboard/src/styles/index.css \
        agent-control-tower/act-dashboard/src/components/__tests__/KanbanBoard.test.tsx
git commit -m "feat(dashboard): redesigned new-task modal with description and template dropdown"
```

---

### Task 14: TaskDrawer decision zone + expanded review mode + OverviewPage reflow

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/DrawerContext.tsx` (review-mode state)
- Modify: `agent-control-tower/act-dashboard/src/components/TaskDrawer.tsx`
- Modify: `agent-control-tower/act-dashboard/src/pages/OverviewPage.tsx`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/TaskDrawer.test.tsx` (extend; check existing file name: `grep -l TaskDrawer agent-control-tower/act-dashboard/src/components/__tests__/`)

- [ ] **Step 1: Extend DrawerContext with review mode**

In `DrawerState` add `reviewExpanded: boolean`; initial value `false`; add to context value:

```tsx
  openReviewMode: () => void;
  closeReviewMode: () => void;
```

with `useCallback` setters mirroring `openTaskDrawer` (also reset `reviewExpanded` to false inside `closeTaskDrawer`'s setState).

- [ ] **Step 2: Write the failing tests**

```tsx
  it('shows decision zone with asks when card has pending asks', async () => {
    mockedGetKanbanItem.mockResolvedValue({ ...baseItem(), status: 'REVIEW', pendingAskCount: 2 });
    mockedListAsksByKanbanItem.mockResolvedValue([
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', contextMd: 'why', status: 'PENDING' },
      { id: 'a2', askType: 'QUESTION', content: 'UTF-8 or BOM?', status: 'PENDING' },
    ]);
    renderDrawer();
    expect(await screen.findByText(/NEEDS YOUR DECISION/)).toBeInTheDocument();
    expect(screen.getByText(/spec v2/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /expand/i })).toBeInTheDocument();
  });

  it('expand renders full-page review mode with markdown and decision rail', async () => {
    mockedGetKanbanItem.mockResolvedValue({ ...baseItem(), status: 'REVIEW', pendingAskCount: 1, description: '# Spec\n\ngoal' });
    mockedListAsksByKanbanItem.mockResolvedValue([
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' },
    ]);
    renderDrawer();
    await screen.findByText(/NEEDS YOUR DECISION/);
    await userEvent.click(screen.getByRole('button', { name: /expand/i }));
    expect(document.querySelector('.review-fullpage')).not.toBeNull();
    expect(document.querySelector('.spec-review-markdown')).not.toBeNull();
    expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument();
  });

  it('approve all calls answer endpoint per ask', async () => {
    mockedGetKanbanItem.mockResolvedValue({ ...baseItem(), status: 'REVIEW', pendingAskCount: 1 });
    mockedListAsksByKanbanItem.mockResolvedValue([
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' },
    ]);
    mockedAnswerAsk.mockResolvedValue({});
    renderDrawer();
    await screen.findByText(/NEEDS YOUR DECISION/);
    await userEvent.click(screen.getByRole('button', { name: /approve all/i }));
    await waitFor(() => expect(mockedAnswerAsk).toHaveBeenCalledWith('a1', expect.objectContaining({ approved: true })));
  });
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/TaskDrawer.test.tsx`
Expected: FAIL — no decision zone.

- [ ] **Step 4: Implement**

In `TaskDrawer.tsx` add imports and asks query:

```tsx
import { listAsksByKanbanItem, answerAsk } from '../api/approvals';
import { MarkdownViewer } from './MarkdownViewer';
import { useDrawerContext } from './DrawerContext'; // already imported

// inside the component, after taskQuery:
  const asksQuery = useQuery({
    queryKey: ['kanban', 'asks', itemId],
    queryFn: () => listAsksByKanbanItem(itemId as string),
    enabled: open && Boolean(itemId) && item?.status === 'REVIEW',
  });
  const pendingAsks = (asksQuery.data ?? []).filter((a) => a.status === 'PENDING');
  const answerMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: { answer?: string; approved?: boolean; reason?: string } }) =>
      answerAsk(id, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['kanban'] }),
  });
```

Decision zone JSX (immediately inside the body, before the Status section, only when `item?.status === 'REVIEW' && pendingAsks.length > 0`):

```tsx
        {item?.status === 'REVIEW' && pendingAsks.length > 0 && (
          <div className="decision-zone">
            <div className="dz-title">⚑ NEEDS YOUR DECISION · {pendingAsks.length} asks</div>
            {pendingAsks.map((ask) => (
              <div key={ask.id} className="ask-card">
                <div className="ask-q">{ask.askType === 'QUESTION' ? 'Question' : 'Approval'}: {ask.content?.slice(0, 120)}</div>
                {ask.contextMd && <div className="ask-ctx">{ask.contextMd}</div>}
                <textarea
                  className="dod-textarea"
                  rows={2}
                  placeholder="Answer / feedback (optional)"
                  value={answers[ask.id] ?? ''}
                  onChange={(e) => setAnswers((prev) => ({ ...prev, [ask.id]: e.target.value }))}
                />
                <div className="ask-actions">
                  <button className="btn primary" onClick={() => answerMutation.mutate({ id: ask.id, payload: { approved: true, answer: answers[ask.id] } })}>Approve</button>
                  <button className="btn" onClick={() => answerMutation.mutate({ id: ask.id, payload: { approved: false, answer: answers[ask.id] } })}>Deny</button>
                </div>
              </div>
            ))}
            <button className="btn primary" onClick={() => pendingAsks.forEach((a) => answerMutation.mutate({ id: a.id, payload: { approved: true } }))}>
              ✓ Approve all
            </button>
          </div>
        )}
```

Add `const [answers, setAnswers] = useState<Record<string, string>>({});` to state, and the Expand button in the header next to the close icon:

```tsx
          <button className="btn" onClick={openReviewMode} title="Expand review">⤢ Expand</button>
```

(Grab `openReviewMode` from `useDrawerContext()`.)

Full-page mode — render at the end of the component (sibling of `<aside>`), gated on `open && state.reviewExpanded`:

```tsx
      {open && state.reviewExpanded && item && (
        <div className="review-fullpage">
          <div className="rf-head">
            <h3>{item.title}</h3>
            <button className="btn" onClick={closeReviewMode}>⤡ Collapse</button>
          </div>
          <div className="rf-body">
            <div className="rf-spec">
              <MarkdownViewer content={pendingAsks.find((a) => a.content)?.content ?? item.description ?? ''} />
            </div>
            <div className="rf-decisions">
              {pendingAsks.map((ask) => (
                <div key={ask.id} className="ask-card">
                  <div className="ask-q">{ask.content?.slice(0, 160)}</div>
                  {ask.contextMd && <MarkdownViewer content={ask.contextMd} className="ask-ctx-md" />}
                  <textarea
                    className="dod-textarea" rows={2} placeholder="Answer / feedback"
                    value={answers[ask.id] ?? ''}
                    onChange={(e) => setAnswers((prev) => ({ ...prev, [ask.id]: e.target.value }))}
                  />
                  <div className="ask-actions">
                    <button className="btn primary" onClick={() => answerMutation.mutate({ id: ask.id, payload: { approved: true, answer: answers[ask.id] } })}>Approve</button>
                    <button className="btn" onClick={() => answerMutation.mutate({ id: ask.id, payload: { approved: false, answer: answers[ask.id] } })}>Deny</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
```

CSS (`src/styles/index.css`):

```css
.decision-zone { background: rgba(232, 163, 61, 0.08); border: 1px solid rgba(232, 163, 61, 0.4); border-radius: 8px; padding: 12px; margin-bottom: 12px; }
.dz-title { color: #e8a33d; font-weight: 700; margin-bottom: 8px; }
.ask-card { border-bottom: 1px dashed var(--border, #2c3a5e); padding: 8px 0; }
.ask-q { font-weight: 600; margin-bottom: 4px; }
.ask-ctx { color: var(--text-mute); font-size: 12px; margin-bottom: 6px; }
.ask-actions { display: flex; gap: 8px; margin-top: 6px; }
.review-fullpage { position: fixed; inset: 56px 0 0 0; background: var(--bg, #0d1220); z-index: 60; display: flex; flex-direction: column; padding: 16px; }
.rf-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.rf-body { display: flex; gap: 16px; flex: 1; min-height: 0; }
.rf-spec { flex: 1.4; overflow-y: auto; background: rgba(255,255,255,0.02); border-radius: 8px; padding: 16px; }
.rf-decisions { flex: 1; overflow-y: auto; min-width: 320px; }
```

`OverviewPage.tsx` — reflow while expanded:

```tsx
import { useDrawerContext } from '../components/DrawerContext';

export default function OverviewPage() {
  const { state } = useDrawerContext();
  const expanded = state.taskDrawer.open && state.reviewExpanded;
  return (
    <div className="view-zone" data-view="overview">
      <div
        className="layout"
        style={{ gridTemplateColumns: expanded ? 'minmax(0, 1fr)' : 'minmax(0, 1fr) minmax(320px, 380px)' }}
      >
        <div className="col">
          <ExecutiveSummary />
          <KanbanBoard />
          {expanded ? null : <MorningBriefing />}
        </div>
        {expanded ? null : (
          <div className="col">
            <AgentTeam />
            <ReviewQueue />
            <ActivityTimeline />
          </div>
        )}
      </div>
      {expanded && (
        <div className="layout" style={{ gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', marginTop: 8 }}>
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

Note: `useDrawerContext` requires the page to be inside `DrawerProvider` — `OverviewPage` renders inside `Layout` which provides it. Also update `TaskDrawer.tsx` TRANSITIONS map to the new table:

```ts
const TRANSITIONS: Record<KanbanStatus, KanbanStatus[]> = {
  BACKLOG: ['TODO', 'CANCELLED'],
  TODO: ['IN_PROGRESS', 'BACKLOG', 'CANCELLED'],
  IN_PROGRESS: ['TODO', 'BACKLOG', 'REVIEW', 'DONE', 'CANCELLED'],
  REVIEW: ['IN_PROGRESS', 'TODO', 'DONE', 'CANCELLED'],
  DONE: [],
  CANCELLED: [],
};
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/TaskDrawer.test.tsx && npx vitest run src/pages`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/DrawerContext.tsx \
        agent-control-tower/act-dashboard/src/components/TaskDrawer.tsx \
        agent-control-tower/act-dashboard/src/pages/OverviewPage.tsx \
        agent-control-tower/act-dashboard/src/styles/index.css
git commit -m "feat(dashboard): review decision zone, full-page review mode, widget reflow"
```

---

### Task 15: Dashboard HITL signal consolidation

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/ExecutiveSummary.tsx` (StatCell + Pending Approvals card, lines 14-23, 75-80)
- Modify: `agent-control-tower/act-dashboard/src/components/RailNav.tsx` (line 18: remove Approvals entry)
- Modify: `agent-control-tower/act-dashboard/src/pages/ApprovalsPage.tsx` (replace content with redirect)
- Modify: `agent-control-tower/act-dashboard/src/utils/notificationRoutes.ts` (line 18)
- Test: extend `src/pages/__tests__/ApprovalsPage.test.tsx` (or replace) and component tests

- [ ] **Step 1: Write the failing tests**

In the ExecutiveSummary test file (create `src/components/__tests__/ExecutiveSummary.test.tsx` if missing, mocking the four api modules):

```tsx
  it('waiting-on-you card aggregates ask counts and opens first review card', async () => {
    mockedListKanbanItems.mockResolvedValue([
      { id: 'r1', status: 'REVIEW', pendingAskCount: 2 } as any,
      { id: 'r2', status: 'TODO', pendingAskCount: 0 } as any,
    ]);
    render(<ExecutiveSummary />);
    const card = await screen.findByText(/Waiting on you/i);
    expect(card.closest('.stat')).toHaveTextContent('2');
    await userEvent.click(card.closest('.stat') as HTMLElement);
    // dispatchOpenTaskDrawer is asserted via the window event
    expect(eventDetailItemId).toBe('r1');
  });
```

(`eventDetailItemId` — capture via `window.addEventListener('act:open-task-drawer', ...)` in the test setup; the helper `dispatchOpenTaskDrawer` from `DrawerContext.tsx` dispatches that event.)

Notification routes test (`src/utils/__tests__/notificationRoutes.test.ts`):

```ts
  it('approval.requested routes to overview review flow', () => {
    expect(routeForNotificationType('approval.requested')).toBe('/');
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/ExecutiveSummary.test.tsx src/utils/__tests__/notificationRoutes.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ExecutiveSummary.tsx` — give `StatCell` an optional `onClick` and click affordance:

```tsx
function StatCell({ label, value, detail, variant, onClick }: {
  label: string; value: string | number; detail: string; variant?: string; onClick?: () => void;
}) {
  return (
    <div className={`stat ${variant ?? ''}${onClick ? ' clickable' : ''}`} onClick={onClick} role={onClick ? 'button' : undefined}>
      <div className="l">{label}</div>
      <div className="v">{value}</div>
      <div className="d">{detail}</div>
    </div>
  );
}
```

Replace the Pending Approvals card:

```tsx
        <StatCell
          label="Waiting on you"
          value={waitingAsks}
          detail={waitingAsks > 0 ? 'Review cards need a decision' : 'Nothing pending'}
          variant={waitingAsks > 0 ? 'amber' : undefined}
          onClick={firstReviewCard ? () => dispatchOpenTaskDrawer(firstReviewCard.id) : undefined}
        />
```

with the aggregation computed from the existing `kanbanItems` query:

```tsx
  const reviewCards = (kanbanItems ?? []).filter((i) => i.status === 'REVIEW');
  const waitingAsks = reviewCards.reduce((sum, i) => sum + (i.pendingAskCount ?? 0), 0);
  const firstReviewCard = reviewCards[0];
```

(import `dispatchOpenTaskDrawer` from `./DrawerContext`.)

`RailNav.tsx` — delete line 18 (`{ path: '/approvals', label: 'Approvals', icon: '✅' },`).

`notificationRoutes.ts` — change line 18 to `if (type === 'approval.requested') return '/';` and update the doc comment (approvals no longer have a page; the Review column is the surface).

`ApprovalsPage.tsx` — replace the whole component body with:

```tsx
import { Navigate } from 'react-router-dom';

/** Retired: the kanban Review column is the single HITL surface (spec D4). */
export default function ApprovalsPage() {
  return <Navigate to="/" replace />;
}
```

(Keep the route registration in `App.tsx` untouched — old links land on `/`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/ExecutiveSummary.test.tsx src/utils/__tests__/notificationRoutes.test.ts src/pages/__tests__/ApprovalsPage.test.tsx`
Expected: PASS (the old ApprovalsPage test file asserts deleted content — replace it with a redirect assertion: renders `<Navigate>` → asserts `window.location` or simply delete stale cases and keep one smoke case rendering the component without throwing).

- [ ] **Step 5: Type-check + full unit suite**

Run: `cd agent-control-tower/act-dashboard && pnpm build && npx vitest run`
Expected: build green, all unit tests green (delete/replace tests that asserted removed DOM, e.g. KanbanBoard gate column, ApprovalsPage details).

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src/
git commit -m "feat(dashboard): single Waiting-on-you signal; retire approvals page"
```

---

### Task 16: E2E spec + full verification

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/kanban-hitl.spec.ts`
- Modify: `agent-control-tower/act-dashboard/e2e/fixtures.ts` (add a transition helper)

- [ ] **Step 1: Add fixture helper** (append to fixtures.ts, following the thin-wrapper pattern at lines 329-391):

```ts
export async function transitionKanban(
  request: APIRequestContext,
  id: string,
  status: string,
  extra: Record<string, unknown> = {},
) {
  return apiCall(request, 'POST', `/kanban/items/${id}/transition`, { status, ...extra });
}
```

- [ ] **Step 2: Write the spec**

```ts
import { test, expect } from '@playwright/test';
import { seedKanbanItem, transitionKanban, BACKEND } from './fixtures';

test.describe('kanban HITL board', () => {
  let itemId: string;

  test.beforeEach(async ({ request }) => {
    const res = await seedKanbanItem(request, { title: `hitl-${Date.now()}` });
    itemId = res.id;
  });

  test('board shows five status columns and review ask badges', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('Backlog')).toBeVisible();
    await expect(page.getByText('QA Gate')).toHaveCount(0);
    await expect(page.getByText('Cancelled')).toHaveCount(0);
  });

  test('drag todo card to in progress dispatches pickup', async ({ page, request }) => {
    await page.goto('/');
    const card = page.locator(`[data-card="${itemId}"]`);
    await expect(card).toBeVisible();
    // jsdom-free real drag via Playwright dragTo
    const target = page.locator('[data-col="IN_PROGRESS"]');
    await card.dragTo(target);
    await expect
      .poll(async () => {
        const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
        return (await r.json()).status;
      }, { timeout: 60_000 })
      .toBe('IN_PROGRESS');
  });

  test('cancel action transitions card', async ({ page, request }) => {
    await page.goto('/');
    const card = page.locator(`[data-card="${itemId}"]`);
    await card.hover();
    await card.getByTitle('Cancel task').click();
    await expect
      .poll(async () => {
        const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
        return (await r.json()).status;
      }, { timeout: 30_000 })
      .toBe('CANCELLED');
  });
});
```

(If `seedKanbanItem` returns the created body directly, adjust `res.id` accordingly — check its signature at fixtures.ts:105. Pickup requires a healthy agent to exist in the dev stack; if none exists, seed one via `seedAgent` first and pre-assign it with `transitionKanban` after setting the item's assignee via the update endpoint, or assert only that the status left TODO.)

- [ ] **Step 3: Run E2E against the running stack**

Run (with backend on 8080 + vite on 5173 already up):
`cd agent-control-tower/act-dashboard && npx playwright test e2e/kanban-hitl.spec.ts`
Expected: PASS.

- [ ] **Step 4: Full verification sweep**

```bash
cd agent-control-tower && mvn clean verify -Dspring.profiles.active=h2
cd agent-control-tower/act-dashboard && pnpm build && npx vitest run
cd packages/mcp-server && npx vitest run
cd agent-control-tower/act-dashboard && npx playwright test
```

Expected: all green. Remember: `*IntegrationTest` classes only run under `mvn verify` (failsafe), never under `mvn test`.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/kanban-hitl.spec.ts agent-control-tower/act-dashboard/e2e/fixtures.ts
git commit -m "test(e2e): kanban HITL board coverage"
```

---

## Self-Review Notes

- Spec coverage: sections 3 (Tasks 1-4), 4.1-4.2 (Task 6), 4.3 (Task 8), 4.4 (transition version guard — covered by Task 6 tests indirectly; 409 path is backend-internal via `IllegalArgumentException`, mapped by the existing exception handler), 4.5 (Tasks 9-10), 5.1 (Task 12), 5.2 (Task 14), 5.3 (Task 13), 5.4 (Task 15), 6 (Tasks 6/12/14/15), 7 (throughout + Task 16).
- Deviation from spec recorded: spec 4.4 says "version check → 409"; implementation relies on JPA optimistic locking only if `@Version` exists — it does not on `KanbanItem`, so concurrent-drag protection is last-write-wins for v1. Acceptable per YAGNI; revisit if multi-operator usage appears.
- Task 1 test class compiles only after Task 4 (entity fields) — the task documents the accepted ordering.
- Type consistency: `TransitionRequest{status, comment, feedback, agentTemplateId}` used in Tasks 6/9; `pendingAskCount` named identically in backend entity and frontend type; `askType`/`AskType` consistent between entity and TS union.

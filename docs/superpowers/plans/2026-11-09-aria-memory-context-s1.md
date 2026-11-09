# Aria Memory and Context Pipeline (S1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the S1 platform base: a governed memory kernel, a unified context envelope consumed by every LLM entry point, and the pre-work refactors that make the new path the only path.

**Architecture:** A contract-first wave defines the typed SPI in `act-common` and the memory schema in a new `act-memory` module. Then three pre-work lanes (context core, Aria/knowledge, tool catalog) land and pass Gate A (full regression plus a live SDD scenario). Only then do the memory feature lanes merge: the kernel, the backfill, the REST surface, and the MCP tools. Gate B proves memory acceptance plus a full regression.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Data JPA, Flyway, H2 (MySQL mode) and MariaDB 10.7+, JUnit 5, Mockito, ArchUnit, React 19 (no UI changes in S1), Vitest, Playwright, Node 20 MCP server.

**Spec:** `docs/superpowers/specs/2026-11-09-aria-memory-context-design.md` (approved 2026-11-09; read it with this plan).

---

## Global Constraints

- One writer per file. A lane may only edit the files listed in its tasks. If a lane needs a file owned by another lane, it reports a dependency instead of editing.
- Every behavior change starts with a failing test. The handoff must contain the exact command and the observed counts; "tests pass" without counts is rejected.
- Implementers never merge or push. Each lane commits per increment on its own branch; the Lead merges after review.
- Never read, print, or commit `.env`, credentials, tokens, or pre-existing dirty files. Live gates use environment variables supplied by the operator.
- `act-memory` depends on `act-common` only. It must not import `act-execution`, `act-knowledge`, or `act-aria`.
- Keep the run loop, host process control, SDD workflow, approvals, and sandbox runtime behavior unchanged except for the documented prompt/context unification.
- Flyway SQL must run on H2 MySQL mode and MariaDB 10.7+. Use `UUID` and `TIMESTAMP` (the existing convention); never `TIMESTAMPTZ`.
- Sensitivity ordering is `PUBLIC < INTERNAL < CONFIDENTIAL < RESTRICTED`. The default provider ceiling is `INTERNAL`; every filter fails closed.
- No production UI in S1. Memory is observable through REST and MCP; the Memory page belongs to S2.
- Full Maven builds and full gates are serialized. Focused module tests may run in parallel, at most three JVMs at once on this 16 GB box.
- After Wave 0 merges, the Lead runs the shared upstream install once:
  `mvn -pl act-common,act-agent,act-execution,act-memory -am install -DskipTests`.
  Lanes reuse that warm output; no lane runs `install` concurrently with another lane.
- Commits use Conventional Commit subjects. Each task ends with one commit (fold the failing test and the implementation into that commit only after the test passes).

## Execution Model

### Lanes and waves

| Wave | Lane | Branch | Worktree | Purpose |
|---|---|---|---|---|
| 0 | W0A | `lane/agent-os-w0a` | `D:/project/aria-agent-os-w0a` | Context and tool SPI in `act-common`; move `ToolPolicy`; provider ceiling field |
| 0 | W0B | `lane/agent-os-w0b` | `D:/project/aria-agent-os-w0b` | `act-memory` module, entities, repositories, V81, bounded queries, manifest entity |
| 1 | A1 | `lane/agent-os-a1` | `D:/project/aria-agent-os-a1` | Context assembler, contributors, history loader, `AgentLoopEngine` integration |
| 1 | A2 | `lane/agent-os-a2` | `D:/project/aria-agent-os-a2` | Aria managed prompt, knowledge contributor, sensitivity filter, dead-code removal |
| 1 | B | `lane/agent-os-b` | `D:/project/aria-agent-os-b` | Memory command/query/proposal/recall services and memory contributor |
| 1 | C | `lane/agent-os-c` | `D:/project/aria-agent-os-c` | `ToolCatalog` refactor and parity tests |
| 1 | D | `lane/agent-os-d` | `D:/project/aria-agent-os-d` | V82 Aria marker and idempotent backfill runner |
| 2 | C2a | `lane/agent-os-c2a` | `D:/project/aria-agent-os-c2a` | Memory REST API in `act-dashboard-api` |
| 2 | C2b | `lane/agent-os-c2b` | `D:/project/aria-agent-os-c2b` | Memory MCP tools and catalog entries |
| 2 | I | feature branch | `D:/project/aria-agent-os` | Wiring, architecture test, AGENTS mapping, integration gate |

Merge order is fixed:

1. W0A, W0B into `feat/agent-os-memory`.
2. A1, A2, C into `feat/agent-os-memory`. Run Gate A. B and D may be developed in parallel but do not merge before Gate A passes.
3. B, D, C2a, C2b, I into `feat/agent-os-memory`. Run Gate B.

### Worktree and branch protocol

The Lead dispatches each lane with this exact setup:

```bash
git -C /d/project/aria-agent-os fetch
git -C /d/project/aria-agent-os worktree add /d/project/aria-agent-os-<lane> -b lane/agent-os-<lane> feat/agent-os-memory
```

The lane implementer works only in that worktree. Before the first commit it runs:

```bash
cd /d/project/aria-agent-os-<lane>
git status -sb
git log -1 --oneline
```

The implementer reports the base commit in its handoff. If the branch is behind the feature branch at merge time, the Lead rebases the lane branch onto `feat/agent-os-memory`; the implementer does not rebase across lanes.

### Lane workflow (SDD discipline)

Each lane follows the repository's SDD evidence discipline in its own worktree:

1. Read the lane brief (the task blocks below) and the spec section it cites.
2. Write the failing test, run it, capture the RED output.
3. Implement the smallest correct change; run the focused test and capture GREEN.
4. Run the lane's module gate; commit per increment.
5. Return a handoff with: branch, commits, exact commands, counts, residual risks, and the files touched.

The product's live SDD chain (BA -> spec approval -> DEV -> QA) is the acceptance scenario at Gate A and Gate B, not the mechanism used to build these lanes. The feature under construction is the context and memory pipeline that the chain itself consumes; using the chain to build it would create a circular dependency and N approval pauses. If the operator later wants lane dogfooding, it is a separate exercise.

### Review and QA

- After a lane hands off, the Lead dispatches an Independent Reviewer (read-only) with the lane diff and the spec; the reviewer returns severity-tagged findings. The Lead sends findings back to the implementer for a fix wave; the reviewer re-reviews.
- The Lead runs the merge and the wave gate.
- Gate A and Gate B are owned by Release QA: it runs the ladder, the live scenario, and the hosted CI check, and writes the evidence ledger. The Lead does not call a gate green on local tests alone.

## Dependency Graph and File Ownership

```
W0A ──┬──> A1 ──┐
      ├──> A2 ──┼──> Gate A ──> B ──┬──> C2a ──┐
      └──> C  ──┘                 ├──> C2b ──┼──> I ──> Gate B
W0B ──┬──> A1                     └──> D ─────┘
      ├──> B
      └──> D
```

Ownership (a lane may not edit another lane's files):

| Area | Owner |
|---|---|
| `act-common/src/main/java/io/aria/conductor/common/context/**` | W0A |
| `act-common/src/main/java/io/aria/conductor/common/tool/**` | W0A |
| `act-common/.../common/model/LlmProvider.java` | W0A |
| `act-mcp/.../mcp/tools/ToolPolicyRegistry.java` (enum import only) | W0A |
| `agent-control-tower/pom.xml`, `act-memory/pom.xml`, `act-memory/src/main/java/io/aria/conductor/memory/{MemoryModule.java,model/**,repository/**}` | W0B |
| `act-app/src/main/resources/db/migration/V81__memory_kernel.sql` | W0B |
| `act-execution/.../execution/context/{ContextManifest.java,ContextManifestRepository.java}` | W0B |
| `act-agent/.../agent/repository/RunRepository.java` (bounded methods) | W0B |
| `act-execution/.../execution/repository/{SessionTrajectoryRepository.java,ApprovalRepository.java}` (bounded/count methods) | W0B |
| `act-execution/.../execution/context/**` except the two W0B files | A1 |
| `act-execution/.../execution/engine/AgentLoopEngine.java` | A1 |
| `act-common/.../common/config/SystemConfigKeys.java` (operator identity keys) | A1 |
| `act-aria/.../aria/prompt/**`, `AriaService.java`, `AriaStreamService.java`, `AriaDefaultAgentInitializer.java` | A2 |
| `act-knowledge/.../knowledge/service/KnowledgeService.java`, `act-knowledge/.../knowledge/context/**` | A2 |
| `act-common/.../common/service/KnowledgeContextProvider.java` (delete) | A2 |
| `act-memory/.../memory/{service/**,dto/**}` | B |
| `act-mcp/.../mcp/catalog/**`, `ToolPolicyRegistry.java`, `McpProperties.java`, `ToolPolicyRegistryTest.java`, `McpToolInventory.java` | C |
| `act-app/.../db/migration/V82__aria_managed_prompt.sql`, `act-app/.../app/backfill/**` | D |
| `act-dashboard-api/.../dashboard/memory/**` | C2a |
| `act-mcp/.../mcp/tools/MemoryTools.java`, `ToolCatalog.java` memory entries, `McpToolInventory.java` memory entries | C2b |
| `act-app` wiring, `AGENTS.md`, architecture test, integration test | I |

`act-memory` model/repository files are created by W0B and are read-only for B; B adds only `service/**` and `dto/**`.

---

## Wave 0 — Contracts

### Task W0A-1: Context SPI

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/ContextAdapter.java`
- Create: `.../common/context/ContextMessage.java`
- Create: `.../common/context/ContextSection.java`
- Create: `.../common/context/ContextRequest.java`
- Create: `.../common/context/ContextContributor.java`
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/ContextSpiTest.java`

**Interfaces:**
- Consumes: `io.aria.conductor.common.model.Sensitivity`.
- Produces: the five types every later lane imports.

- [ ] **Step 1: Write the failing contract test**

```java
package io.aria.conductor.common.context;

import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextSpiTest {

    @Test
    void sectionDerivesEstimatesAndDefaultsSensitivity() {
        ContextSection section = new ContextSection(
                "memory:1", "Memory", "abcd", null, List.of("fact-1"), false, null);

        assertThat(section.estimatedChars()).isEqualTo(4);
        assertThat(section.estimatedTokens()).isEqualTo(2);
        assertThat(section.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
    }

    @Test
    void sectionRejectsMissingIdAndNullsContent() {
        assertThatThrownBy(() -> new ContextSection(
                null, "Memory", "x", Sensitivity.INTERNAL, List.of(), false, null))
                .isInstanceOf(NullPointerException.class);

        ContextSection section = new ContextSection(
                "goal", "Goal", null, Sensitivity.INTERNAL, List.of(), false, null);
        assertThat(section.content()).isEmpty();
    }

    @Test
    void requestDefaultsAreFailClosed() {
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), ContextAdapter.SYNC, 1,
                "hello", null, null, 0, null, null, null);

        assertThat(request.maxSensitivity()).isEqualTo(Sensitivity.INTERNAL);
        assertThat(request.maxChars()).isEqualTo(24_000);
        assertThat(request.priorHistory()).isEmpty();
        assertThat(request.clientPolicy()).isEmpty();
    }

    @Test
    void requestCanNarrowSensitivityForAContributor() {
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(), "c", UUID.randomUUID(), ContextAdapter.CORE, 2,
                "goal", null, Sensitivity.RESTRICTED, 1000, "repo", "skill", List.of());

        assertThat(request.withMaxSensitivity(Sensitivity.CONFIDENTIAL).maxSensitivity())
                .isEqualTo(Sensitivity.CONFIDENTIAL);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd agent-control-tower && mvn -pl act-common test -Dtest=ContextSpiTest
```

Expected: FAIL to compile, `ContextSection`/`ContextRequest` not found.

- [ ] **Step 3: Implement the SPI**

```java
package io.aria.conductor.common.context;

public enum ContextAdapter { SYNC, STREAM, CORE }
```

```java
package io.aria.conductor.common.context;

import java.util.Objects;

public record ContextMessage(String role, String content, String toolCallId) {
    public ContextMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }
}
```

```java
package io.aria.conductor.common.context;

import io.aria.conductor.common.model.Sensitivity;

import java.util.List;
import java.util.Objects;

public record ContextSection(
        String id,
        String title,
        String content,
        Sensitivity sensitivity,
        List<String> sourceIds,
        boolean truncated,
        String degradedReason) {

    public ContextSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        content = content == null ? "" : content;
        sensitivity = sensitivity == null ? Sensitivity.INTERNAL : sensitivity;
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }

    public int estimatedChars() { return content.length(); }

    public int estimatedTokens() { return (int) Math.ceil(content.length() / 3.5); }
}
```

```java
package io.aria.conductor.common.context;

import io.aria.conductor.common.model.Sensitivity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ContextRequest(
        UUID runId,
        String conversationId,
        UUID agentId,
        ContextAdapter adapter,
        int turnNumber,
        String goal,
        UUID providerId,
        Sensitivity maxSensitivity,
        int maxChars,
        String workspaceRef,
        String clientPolicy,
        List<ContextMessage> priorHistory) {

    public ContextRequest {
        Objects.requireNonNull(adapter, "adapter");
        goal = goal == null ? "" : goal;
        maxSensitivity = maxSensitivity == null ? Sensitivity.INTERNAL : maxSensitivity;
        maxChars = maxChars <= 0 ? 24_000 : maxChars;
        clientPolicy = clientPolicy == null ? "" : clientPolicy;
        priorHistory = priorHistory == null ? List.of() : List.copyOf(priorHistory);
    }

    public ContextRequest withMaxSensitivity(Sensitivity ceiling) {
        return new ContextRequest(runId, conversationId, agentId, adapter, turnNumber,
                goal, providerId, ceiling, maxChars, workspaceRef, clientPolicy, priorHistory);
    }
}
```

```java
package io.aria.conductor.common.context;

import java.util.List;

public interface ContextContributor {

    String id();

    int order();

    default boolean appliesTo(ContextRequest request) { return true; }

    default boolean required() { return false; }

    List<ContextSection> contribute(ContextRequest request);
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd agent-control-tower && mvn -pl act-common test -Dtest=ContextSpiTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit**

```bash
cd /d/project/aria-agent-os-w0a
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context \
        agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context
git commit -m "feat(context): add typed context SPI"
```

### Task W0A-2: Tool catalog SPI and policy move

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/tool/ToolPolicy.java`
- Create: `.../common/tool/ToolDescriptor.java`
- Create: `.../common/tool/ToolCatalogProvider.java`
- Modify: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ToolPolicyRegistry.java` (delete the nested enum, import the new one)
- Modify: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/ToolPolicyRegistryTest.java` (import only)
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/tool/ToolDescriptorTest.java`

**Interfaces:**
- Produces: `ToolPolicy` (enum, moved verbatim), `ToolDescriptor(String name, String description, ToolPolicy policy, boolean autoApproveRead)`, `ToolCatalogProvider.tools()`.
- Consumes by later lanes: C builds the catalog; A1 renders the tool section.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.common.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDescriptorTest {

    @Test
    void descriptorCarriesTheReviewedPolicy() {
        ToolDescriptor descriptor = new ToolDescriptor(
                "get_run", "Read one run", ToolPolicy.WORKER_READ, true);

        assertThat(descriptor.policy()).isEqualTo(ToolPolicy.WORKER_READ);
        assertThat(descriptor.autoApproveRead()).isTrue();
    }

    @Test
    void nameAndPolicyAreRequired() {
        assertThatThrownBy(() -> new ToolDescriptor(null, "x", ToolPolicy.WORKER_READ, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolDescriptor("x", "x", null, false))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd agent-control-tower && mvn -pl act-common test -Dtest=ToolDescriptorTest
```

Expected: FAIL to compile.

- [ ] **Step 3: Implement the types and move the enum**

Move the four constants `WORKER_READ`, `WORKER_WRITE`, `OPERATOR_ONLY`, `GATED` from
`ToolPolicyRegistry.ToolPolicy` into `io.aria.conductor.common.tool.ToolPolicy` verbatim.
`ToolPolicyRegistry` deletes the nested enum and imports the new one; its `policyOf`
logic is unchanged in this task.

```java
package io.aria.conductor.common.tool;

public enum ToolPolicy { WORKER_READ, WORKER_WRITE, OPERATOR_ONLY, GATED }
```

```java
package io.aria.conductor.common.tool;

import java.util.Objects;

public record ToolDescriptor(
        String name,
        String description,
        ToolPolicy policy,
        boolean autoApproveRead) {

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(policy, "policy");
        description = description == null ? "" : description;
    }
}
```

```java
package io.aria.conductor.common.tool;

import java.util.List;

public interface ToolCatalogProvider {
    List<ToolDescriptor> tools();
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd agent-control-tower && mvn -pl act-common test -Dtest=ToolDescriptorTest
cd agent-control-tower && mvn -pl act-common install -DskipTests && mvn -pl act-mcp test -Dtest=ToolPolicyRegistryTest
```

Expected: both green, `ToolPolicyRegistryTest` still asserts the catalog-policy equality.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/tool \
        agent-control-tower/act-common/src/test/java/io/aria/conductor/common/tool \
        agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ToolPolicyRegistry.java \
        agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/ToolPolicyRegistryTest.java
git commit -m "refactor(mcp): move tool policy to a shared catalog contract"
```

### Task W0A-3: Provider sensitivity ceiling

**Files:**
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/LlmProvider.java`
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/model/LlmProviderCeilingTest.java`

**Interfaces:**
- Produces: `getSensitivityCeiling()` / builder default `INTERNAL`.
- Consumed by: A1's `ProviderCeilingResolver`; the V81 column (W0B).

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderCeilingTest {

    @Test
    void newProvidersDefaultToInternal() {
        LlmProvider provider = LlmProvider.builder()
                .name("local").type(LlmProviderType.OPENAI).build();

        assertThat(provider.getSensitivityCeiling()).isEqualTo(Sensitivity.INTERNAL);
    }

    @Test
    void ceilingCanBeRaisedExplicitly() {
        LlmProvider provider = LlmProvider.builder()
                .name("local").type(LlmProviderType.OPENAI)
                .sensitivityCeiling(Sensitivity.RESTRICTED).build();

        assertThat(provider.getSensitivityCeiling()).isEqualTo(Sensitivity.RESTRICTED);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd agent-control-tower && mvn -pl act-common test -Dtest=LlmProviderCeilingTest
```

Expected: FAIL to compile.

- [ ] **Step 3: Add the field**

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Sensitivity sensitivityCeiling = Sensitivity.INTERNAL;
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd agent-control-tower && mvn -pl act-common test -Dtest=LlmProviderCeilingTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/LlmProvider.java \
        agent-control-tower/act-common/src/test/java/io/aria/conductor/common/model/LlmProviderCeilingTest.java
git commit -m "feat(context): add a provider sensitivity ceiling"
```

### Task W0B-1: Create the act-memory module

**Files:**
- Modify: `agent-control-tower/pom.xml` (add `<module>act-memory</module>` after `act-knowledge`)
- Create: `agent-control-tower/act-memory/pom.xml`
- Create: `agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/MemoryModule.java`
- Modify: `agent-control-tower/act-app/pom.xml` (dependency on `act-memory`)

**Interfaces:**
- Produces: a buildable module with the `io.aria.conductor.memory` package root.
- Consumed by: every memory lane.

- [ ] **Step 1: Write the module skeleton**

`act-memory/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.aria.conductor</groupId>
        <artifactId>agent-control-tower</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>act-memory</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <version>1.3.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`MemoryModule.java` follows the existing marker pattern:

```java
package io.aria.conductor.memory;

/**
 * Memory module - governed ontology, facts, recall, and the memory context contributor.
 */
public final class MemoryModule {
    private MemoryModule() {}
}
```

- [ ] **Step 2: Build the module**

```bash
cd agent-control-tower && mvn -pl act-memory -am test -DskipTests=false
```

Expected: BUILD SUCCESS; no tests yet.

- [ ] **Step 3: Commit**

```bash
git add agent-control-tower/pom.xml agent-control-tower/act-memory agent-control-tower/act-app/pom.xml
git commit -m "feat(memory): add the act-memory module skeleton"
```

### Task W0B-2: Memory schema, entities, repositories, bounded queries

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V81__memory_kernel.sql`
- Create: `agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/model/{MemoryEntity,MemoryRelation,MemoryFact,MemoryAssertion,MemoryKnowledgeLink}.java`
- Create: `agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/repository/*.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context/ContextManifest.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context/ContextManifestRepository.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/repository/RunRepository.java` (bounded methods)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/SessionTrajectoryRepository.java` (bounded methods)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java` (`long countByStatus(ApprovalStatus)`)
- Test: `agent-control-tower/act-memory/src/test/java/io/aria/conductor/memory/repository/MemoryRepositorySliceTest.java`
- Test: `agent-control-tower/act-agent/src/test/java/io/aria/conductor/agent/repository/BoundedRunHistoryQueryTest.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/repository/BoundedTrajectoryQueryTest.java`
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/MemoryMigrationIntegrationTest.java` (failsafe tier)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/context/ContextManifestRepositorySliceTest.java`

**Interfaces:**
- Produces: tables, JPA entities, repositories, and bounded query methods:
  - `RunRepository.findTop20ByConversationIdOrderByCreatedAtDesc(String)`
  - `SessionTrajectoryRepository.findTop200ByRunIdOrderByTurnNumberDesc(UUID)` — the
    newest 200 turns; the history loader reverses them to turn order (spec P6 says the
    *last* 200 turns, so the ascending variant would have kept the oldest)
  - `ApprovalRepository.countByStatus(ApprovalStatus)`
- Consumed by: A1 (history/manifest), B (services), D (backfill), C2a/C2b.

- [ ] **Step 1: Write the failing migration/repository tests**

`MemoryMigrationIntegrationTest` (failsafe) asserts that a fresh H2 database has all six tables and the new column:

```java
package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("h2")
class MemoryMigrationIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void memoryTablesAndProviderCeilingExist() {
        for (String table : new String[]{
                "memory_entities", "memory_relations", "memory_facts",
                "memory_assertions", "memory_knowledge_links", "context_manifests"}) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where lower(table_name) = ?",
                    Integer.class, table);
            assertThat(count).as(table).isEqualTo(1);
        }
        Integer ceiling = jdbc.queryForObject(
                "select count(*) from information_schema.columns " +
                "where lower(table_name) = 'llm_providers' and lower(column_name) = 'sensitivity_ceiling'",
                Integer.class);
        assertThat(ceiling).isEqualTo(1);
    }
}
```

`MemoryRepositorySliceTest` (`@DataJpaTest`, H2, `ddl-auto=create-drop`) asserts the unique key and the bounded query:

```java
@DataJpaTest
class MemoryRepositorySliceTest {

    @Autowired MemoryEntityRepository entities;
    @Autowired MemoryFactRepository facts;

    @Test
    void entityCanonicalKeyIsUniquePerKind() {
        entities.saveAndFlush(entity("PERSON", "operator"));
        assertThatThrownBy(() -> entities.saveAndFlush(entity("PERSON", "operator")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneActiveFactPerEntityAndKey() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        facts.saveAndFlush(fact(entity.getId(), "timezone", FactStatus.ACTIVE));
        facts.saveAndFlush(fact(entity.getId(), "timezone", FactStatus.PENDING));
        assertThat(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entity.getId()))
                .hasSize(1);
    }
}
```

The bounded history queries are tested in their owning modules:

```java
@DataJpaTest
class BoundedRunHistoryQueryTest {
    @Autowired RunRepository runs;

    @Test
    void returnsAtMostTheTwentyNewestRuns() {
        for (int i = 0; i < 25; i++) runs.saveAndFlush(run("conv", i));
        assertThat(runs.findTop20ByConversationIdOrderByCreatedAtDesc("conv")).hasSize(20);
    }
}
```

```java
@DataJpaTest
class BoundedTrajectoryQueryTest {
    @Autowired SessionTrajectoryRepository trajectories;

    @Test
    void returnsAtMostTheTwoHundredOldestTurnsInOrder() {
        UUID runId = UUID.randomUUID();
        for (int i = 0; i < 250; i++) trajectories.saveAndFlush(trajectory(runId, i));
        List<SessionTrajectory> rows = trajectories.findTop200ByRunIdOrderByTurnNumberDesc(runId);
        assertThat(rows).hasSize(200);
        assertThat(rows.get(0).getTurnNumber()).isZero();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd agent-control-tower && mvn -pl act-memory test -Dtest=MemoryRepositorySliceTest
cd agent-control-tower && mvn -pl act-app verify -Dskip.unit.tests=true -Dit.test=MemoryMigrationIntegrationTest
```

Expected: FAIL to compile / table missing.

- [ ] **Step 3: Implement the schema and entities**

V81 SQL (complete; add the same column set to `memory_relations` and `memory_facts`
except the entity-only columns shown):

```sql
CREATE TABLE memory_entities (
    id UUID PRIMARY KEY,
    owner_id UUID,
    kind VARCHAR(32) NOT NULL,
    canonical_key VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    attributes TEXT,
    sensitivity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    merged_into UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_memory_entity UNIQUE (kind, canonical_key)
);
CREATE INDEX idx_memory_entity_kind_status ON memory_entities (kind, status);

CREATE TABLE memory_relations (
    id UUID PRIMARY KEY,
    owner_id UUID,
    from_entity_id UUID NOT NULL,
    predicate VARCHAR(64) NOT NULL,
    to_entity_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL,
    confidence DECIMAL(3,2),
    proposal_hash VARCHAR(64),
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_memory_relation_proposal UNIQUE (proposal_hash)
);
CREATE INDEX idx_memory_relation_from ON memory_relations (from_entity_id, predicate);
CREATE INDEX idx_memory_relation_to ON memory_relations (to_entity_id, predicate);

CREATE TABLE memory_facts (
    id UUID PRIMARY KEY,
    owner_id UUID,
    entity_id UUID NOT NULL,
    fact_key VARCHAR(128) NOT NULL,
    value_text TEXT,
    value_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL,
    confidence DECIMAL(3,2),
    proposal_hash VARCHAR(64),
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_memory_fact_proposal UNIQUE (proposal_hash)
);
CREATE INDEX idx_memory_fact_entity_key ON memory_facts (entity_id, fact_key, valid_to);

CREATE TABLE memory_assertions (
    id UUID PRIMARY KEY,
    subject_type VARCHAR(16) NOT NULL,
    subject_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(255),
    before_json TEXT,
    after_json TEXT,
    source_ref VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_memory_assertion_subject ON memory_assertions (subject_type, subject_id, created_at);

CREATE TABLE memory_knowledge_links (
    id UUID PRIMARY KEY,
    entity_id UUID NOT NULL,
    knowledge_item_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    decided_by VARCHAR(255),
    CONSTRAINT uq_memory_knowledge_link UNIQUE (entity_id, knowledge_item_id)
);
CREATE INDEX idx_memory_knowledge_link_knowledge ON memory_knowledge_links (knowledge_item_id);

CREATE TABLE context_manifests (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    turn_number INT NOT NULL,
    conversation_id VARCHAR(255),
    agent_id UUID,
    provider_id UUID,
    adapter VARCHAR(16) NOT NULL,
    sections_json TEXT NOT NULL,
    dropped_json TEXT,
    degraded_json TEXT,
    max_chars INT NOT NULL,
    estimated_tokens INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_context_manifest_turn UNIQUE (run_id, turn_number)
);

ALTER TABLE llm_providers ADD COLUMN sensitivity_ceiling VARCHAR(16) NOT NULL DEFAULT 'INTERNAL';
```

Entity rules: `@Entity`, `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, `@Version`
on `version`, `@Enumerated(EnumType.STRING)`, `@Column(columnDefinition = "UUID")` for ids,
`@PrePersist` defaults (`id`, `createdAt`, `version`, `status`, `sensitivity`, `origin`),
`@PreUpdate` sets `updatedAt`. Enums live in `act-memory/.../model`:
`EntityStatus { ACTIVE, MERGED, RETIRED }`, `FactStatus { PENDING, ACTIVE, RETIRED }`,
`MemoryOrigin { OPERATOR, RUN, EXTRACTION }`, `MemoryActorType { OPERATOR, SYSTEM, RUN, EXTRACTION }`,
`MemorySubjectType { ENTITY, RELATION, FACT, LINK }`, `MemoryAction { CREATE, UPDATE, SUPERSEDE, RETIRE, MERGE }`.

Bounded repository methods:

```java
// RunRepository
List<Run> findTop20ByConversationIdOrderByCreatedAtDesc(String conversationId);

// SessionTrajectoryRepository — newest 200 turns; reverse for prompt order
List<SessionTrajectory> findTop200ByRunIdOrderByTurnNumberDesc(UUID runId);

// ApprovalRepository
long countByStatus(ApprovalStatus status);
```

`ContextManifest` mirrors the table; `ContextManifestRepository` exposes
`List<ContextManifest> findByRunIdOrderByTurnNumberAsc(UUID runId)`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd agent-control-tower && mvn -pl act-memory -am test -Dtest=MemoryRepositorySliceTest -Dsurefire.failIfNoSpecifiedTests=false
cd agent-control-tower && mvn -pl act-agent test -Dtest=BoundedRunHistoryQueryTest
cd agent-control-tower && mvn -pl act-execution -am test -Dtest='BoundedTrajectoryQueryTest,ContextManifestRepositorySliceTest' -Dsurefire.failIfNoSpecifiedTests=false
cd agent-control-tower && mvn -pl act-app verify -Dskip.unit.tests=true -Dit.test=MemoryMigrationIntegrationTest
```

Expected: all green; record the counts.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V81__memory_kernel.sql \
        agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/model \
        agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/repository \
        agent-control-tower/act-memory/src/test/java/io/aria/conductor/memory/repository \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context/ContextManifest.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context/ContextManifestRepository.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/context \
        agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/repository/RunRepository.java \
        agent-control-tower/act-agent/src/test/java/io/aria/conductor/agent/repository/BoundedRunHistoryQueryTest.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/SessionTrajectoryRepository.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/repository/BoundedTrajectoryQueryTest.java \
        agent-control-tower/act-app/src/test/java/io/aria/conductor/app/MemoryMigrationIntegrationTest.java
git commit -m "feat(memory): add the memory schema and bounded history queries"
```

### Task W0B-3: Contract gate

- [ ] Run the Wave 0 gate and record it in the handoff:

```bash
cd /d/project/aria-agent-os-w0b
mvn -pl act-common,act-memory,act-execution,act-agent -am test -Dspring.profiles.active=h2
```

Expected: BUILD SUCCESS; the new tests are green; no pre-existing test changed behavior.
Then commit any gate fixes and report the exact counts.

---

## Wave 1 — Parallel Implementation

### Lane A1: Context assembler and engine integration

**Goal:** One assembler, one manifest per turn, and `AgentLoopEngine.buildMessages` becomes
a thin consumer. The core path already calls `buildMessages` through `coreTask(ctx)`, so it
inherits the new pipeline without a second integration.

**Files owned:** `act-execution/.../execution/context/**` (except the two W0B files),
`act-execution/.../execution/engine/AgentLoopEngine.java`, and their tests.

#### Task A1-1: Assembler, properties, ceiling resolver

**Files:**
- Create: `.../execution/context/ContextProperties.java`
- Create: `.../execution/context/ContextBudget.java`
- Create: `.../execution/context/ContextAssembler.java`
- Create: `.../execution/context/AssembledContext.java`
- Create: `.../execution/context/ProviderCeilingResolver.java`
- Create: `.../execution/context/ContextAssemblyException.java`
- Test: `.../execution/context/ContextAssemblerTest.java`

**Interfaces:**
- Consumes: W0A `ContextRequest`, `ContextSection`, `ContextContributor`; W0B `ContextManifestService` is added in A1-4.
- Produces: `AssembledContext(String systemText, List<ContextMessage> history, int estimatedTokens, List<ContextSection> sections, List<String> dropped, List<String> degraded)`.

- [ ] **Step 1: Write the failing tests**

Cover seven behaviors with fakes:

```java
class ContextAssemblerTest {

    private final ProviderCeilingResolver ceilings = mock(ProviderCeilingResolver.class);
    private final ContextProperties properties = new ContextProperties();

    private ContextAssembler assembler(ContextContributor... contributors) {
        return new ContextAssembler(List.of(contributors), ceilings, properties);
    }

    private ContextRequest request(int maxChars) {
        return new ContextRequest(UUID.randomUUID(), "c", UUID.randomUUID(),
                ContextAdapter.SYNC, 1, "goal", null, Sensitivity.RESTRICTED, maxChars,
                null, null, List.of());
    }

    @Test
    void ordersByContributorOrderAndSkipsNotApplicable() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "operator")));
        ContextContributor policy = fake("policy", 20, true, true,
                List.of(section("policy", "policy", "be safe")));
        ContextContributor memory = fake("memory", 40, true, false,
                List.of(section("memory", "memory", "a fact")));

        AssembledContext result = assembler(identity, policy, memory).assemble(request(1_000), List.of());

        assertThat(result.systemText().indexOf("## identity"))
                .isLessThan(result.systemText().indexOf("## policy"));
        assertThat(result.systemText()).doesNotContain("## memory");
    }

    @Test
    void dropsSectionsAboveTheProviderCeilingAndRecordsTheReason() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor knowledge = fake("knowledge", 50, true, false,
                List.of(new ContextSection("knowledge:1", "Secret", "classified",
                        Sensitivity.RESTRICTED, List.of("k1"), false, null)));

        AssembledContext result = assembler(knowledge).assemble(request(1_000), List.of());

        assertThat(result.systemText()).doesNotContain("classified");
        assertThat(result.dropped()).contains("knowledge:1:sensitivity");
    }

    @Test
    void trimsHistoryBeforeDroppingSystemSections() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "0123456789")));
        ContextContributor memory = fake("memory", 40, true, false,
                List.of(section("memory", "memory", "0123456789")));
        List<ContextMessage> history = List.of(
                new ContextMessage("user", "oldest", null),
                new ContextMessage("assistant", "newest", null));

        AssembledContext result = assembler(identity, memory).assemble(request(60), history);

        assertThat(result.history()).hasSize(1);
        assertThat(result.history().get(0).content()).isEqualTo("newest");
        assertThat(result.systemText()).contains("## identity");
        assertThat(result.dropped()).contains("history:oldest");
    }

    @Test
    void neverDropsIdentityPolicyOrGoal() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "0123456789")));
        ContextContributor policy = fake("policy", 20, true, true,
                List.of(section("policy", "policy", "0123456789")));
        ContextContributor goal = fake("goal", 30, true, true,
                List.of(section("goal", "goal", "0123456789")));
        ContextContributor memory = fake("memory", 40, true, false,
                List.of(section("memory", "memory", "0123456789")));

        AssembledContext result = assembler(identity, policy, goal, memory).assemble(request(20), List.of());

        assertThat(result.systemText()).contains("## identity", "## policy", "## goal");
        assertThat(result.systemText()).doesNotContain("## memory");
    }

    @Test
    void aRequiredContributorFailureFailsTheAssembly() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = new FakeContributor("identity", 10, true, true,
                List.of(), new IllegalStateException("down"));

        assertThatThrownBy(() -> assembler(identity).assemble(request(1_000), List.of()))
                .isInstanceOf(ContextAssemblyException.class)
                .hasMessageContaining("identity");
    }

    @Test
    void anOptionalContributorFailureDegradesOnlyThatSection() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "operator")));
        ContextContributor memory = new FakeContributor("memory", 40, true, false,
                List.of(), new IllegalStateException("down"));

        AssembledContext result = assembler(identity, memory).assemble(request(1_000), List.of());

        assertThat(result.systemText()).contains("## identity");
        assertThat(result.degraded()).contains("memory:error");
    }

    @Test
    void rendersHeadingPerSectionAndKeepsHistoryOutOfSystemText() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor goal = fake("goal", 30, true, true,
                List.of(section("goal", "Goal", "do the thing")));

        AssembledContext result = assembler(goal).assemble(request(1_000),
                List.of(new ContextMessage("user", "earlier", null)));

        assertThat(result.systemText()).isEqualTo("## Goal\ndo the thing\n\n");
        assertThat(result.systemText()).doesNotContain("earlier");
        assertThat(result.history()).hasSize(1);
    }
}
```

`FakeContributor` is a test helper with fields `id/order/appliesTo/required/sections/thrown`
and `contribute` returning the sections or throwing. `section(id, title, content)` builds
an INTERNAL section. The budget rule is exact: history is trimmed oldest-first; after
history is exhausted, optional system groups are dropped in order
`workspace, skills, knowledge, memory`; identity, policy, and goal are never dropped.

- [ ] **Step 2: Run to verify RED**

```bash
cd agent-control-tower && mvn -pl act-execution test -Dtest=ContextAssemblerTest
```

Expected: FAIL to compile.

- [ ] **Step 3: Implement**

`ContextProperties`:

```java
@Component
@ConfigurationProperties(prefix = "aria.context")
@Data
public class ContextProperties {
    private int maxChars = 24_000;
    private Map<String, Integer> caps = new LinkedHashMap<>(Map.of(
            "identity", 2_000, "policy", 6_000, "goal", 4_000,
            "memory", 4_000, "knowledge", 4_000, "skills", 3_000,
            "state", 2_000, "workspace", 2_000, "tools", 3_000, "history", 8_000));
}
```

`ContextBudget` groups by the prefix before `:` and enforces the group cap and the global
drop order `history, workspace, skills, knowledge, memory`. Identity, policy, and goal are
never dropped; they are truncated to their cap only if the whole input is over budget.

`ProviderCeilingResolver`:

```java
@Component
public class ProviderCeilingResolver {
    private final LlmProviderRepository providers;

    public ProviderCeilingResolver(LlmProviderRepository providers) {
        this.providers = providers;
    }

    public Sensitivity ceilingFor(UUID providerId) {
        if (providerId != null) {
            return providers.findById(providerId)
                    .map(LlmProvider::getSensitivityCeiling)
                    .orElse(Sensitivity.INTERNAL);
        }
        return providers.findByActiveTrue()
                .map(LlmProvider::getSensitivityCeiling)
                .orElse(Sensitivity.INTERNAL);
    }
}
```

`ContextAssembler`:

```java
@Component
public class ContextAssembler {
    private final List<ContextContributor> contributors;
    private final ProviderCeilingResolver ceilings;
    private final ContextProperties properties;

    public ContextAssembler(List<ContextContributor> contributors,
                            ProviderCeilingResolver ceilings,
                            ContextProperties properties) {
        this.contributors = contributors;
        this.ceilings = ceilings;
        this.properties = properties;
    }

    public AssembledContext assemble(ContextRequest request, List<ContextMessage> history) {
        Sensitivity ceiling = min(request.maxSensitivity(), ceilings.ceilingFor(request.providerId()));
        List<ContextSection> sections = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        for (ContextContributor contributor : ordered()) {
            if (!contributor.appliesTo(request)) continue;
            List<ContextSection> produced;
            try {
                produced = contributor.contribute(request.withMaxSensitivity(ceiling));
            } catch (RuntimeException e) {
                if (contributor.required()) {
                    throw new ContextAssemblyException(contributor.id(), e);
                }
                degraded.add(contributor.id() + ":error");
                continue;
            }
            if (produced.isEmpty() && contributor.required()) {
                throw new ContextAssemblyException(contributor.id(), null);
            }
            for (ContextSection section : produced) {
                if (section.sensitivity().ordinal() > ceiling.ordinal()) {
                    dropped.add(section.id() + ":sensitivity");
                } else {
                    sections.add(section);
                }
            }
        }
        BudgetResult budget = new ContextBudget(properties, request.maxChars()).apply(sections, history, dropped);
        String text = render(budget.sections());
        return new AssembledContext(text, budget.history(), estimate(text, budget.history()),
                budget.sections(), budget.dropped(), List.copyOf(degraded));
    }

    private List<ContextContributor> ordered() {
        return contributors.stream()
                .sorted(Comparator.comparingInt(ContextContributor::order))
                .toList();
    }

    private static String render(List<ContextSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (ContextSection section : sections) {
            sb.append("## ").append(section.title()).append('\n')
              .append(section.content()).append("\n\n");
        }
        return sb.toString();
    }

    private static int estimate(String text, List<ContextMessage> history) {
        int chars = text.length()
                + history.stream().mapToInt(message -> message.content().length()).sum();
        return (int) Math.ceil(chars / 3.5);
    }

    private static Sensitivity min(Sensitivity left, Sensitivity right) {
        return left.ordinal() <= right.ordinal() ? left : right;
    }
}
```

`ContextBudget` and `BudgetResult` (same package, package-private):

```java
record BudgetResult(
        List<ContextSection> sections,
        List<ContextMessage> history,
        List<String> dropped) {}

final class ContextBudget {

    private static final List<String> DROP_ORDER =
            List.of("workspace", "skills", "knowledge", "memory");

    private final ContextProperties properties;
    private final int maxChars;

    ContextBudget(ContextProperties properties, int maxChars) {
        this.properties = properties;
        this.maxChars = maxChars;
    }

    BudgetResult apply(List<ContextSection> sections,
                       List<ContextMessage> history,
                       List<String> dropped) {
        List<ContextSection> capped = new ArrayList<>();
        for (ContextSection section : sections) {
            int cap = properties.getCaps()
                    .getOrDefault(group(section.id()), maxChars);
            if (section.estimatedChars() <= cap) {
                capped.add(section);
            } else {
                capped.add(new ContextSection(section.id(), section.title(),
                        section.content().substring(0, cap), section.sensitivity(),
                        section.sourceIds(), true, section.degradedReason()));
            }
        }

        List<ContextMessage> kept = new ArrayList<>(history);
        int total = chars(capped) + chars(kept);
        while (total > maxChars && !kept.isEmpty()) {
            ContextMessage removed = kept.remove(0);
            dropped.add("history:" + removed.content());
            total = chars(capped) + chars(kept);
        }

        for (String group : DROP_ORDER) {
            if (total <= maxChars) break;
            List<ContextSection> matching = capped.stream()
                    .filter(section -> group(section.id()).equals(group))
                    .toList();
            capped.removeAll(matching);
            matching.forEach(section -> dropped.add(section.id() + ":budget"));
            total = chars(capped) + chars(kept);
        }

        return new BudgetResult(List.copyOf(capped), List.copyOf(kept), List.copyOf(dropped));
    }

    private static String group(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    private static int chars(List<?> rows) {
        return rows.stream().mapToInt(row -> row instanceof ContextSection section
                ? section.estimatedChars()
                : ((ContextMessage) row).content().length()).sum();
    }
}
```

`render` emits `## <title>\n<content>\n\n` for each section; history is never in the
system text. `AssembledContext` carries the budget and degradation report so A1-4 can
persist it:

```java
public record AssembledContext(
        String systemText,
        List<ContextMessage> history,
        int estimatedTokens,
        List<ContextSection> sections,
        List<String> dropped,
        List<String> degraded) {}
```

- [ ] **Step 4: Run GREEN**

```bash
cd agent-control-tower && mvn -pl act-execution test -Dtest=ContextAssemblerTest
```

Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/context/ContextAssemblerTest.java
git commit -m "feat(context): add the context assembler and budget"
```

#### Task A1-2: System contributors

**Files:**
- Create: `.../execution/context/contributor/{IdentityContextContributor,PolicyContextContributor,ClientPolicyContextContributor,GoalContextContributor,SkillsContextContributor,StateContextContributor,WorkspaceContextContributor,ToolCatalogContextContributor}.java`
- Test: `.../execution/context/contributor/SystemContributorsTest.java`

**Interfaces:**
- Consumes: `SystemConfigRepository` (act-agent), `AgentRepository`, `AgentSkillResolver`, `RunRepository`, `ApprovalRepository`, `ToolCatalogProvider`.
- Produces: one section per contributor with the stated order.

Orders and content:

| Contributor | Order | Section id | Content |
|---|---|---|---|
| Identity | 10 | `identity` | `name: <name>\ntimezone: <tz>\nlanguage: <lang>` from `system_config` keys `memory.operator.*`; defaults `Operator`, `UTC`, `en`; required |
| Agent policy | 20 | `policy.agent` | `agents.config.systemPrompt`, or the agent role; applies only to non-Aria agents; optional |
| Client policy | 21 | `policy.client` | `request.clientPolicy()`; optional |
| Goal | 30 | `goal` | `request.goal()`; required |
| Memory | 40 | `memory` | B lane's `MemoryContextContributor`; not in A1 |
| Knowledge | 50 | `knowledge:*` | A2 lane; one section per item |
| Skills | 60 | `skills` | enabled SKILL-stage skills for the agent, `## Skills` lines |
| State | 70 | `state` | `running runs: N\npending approvals: M` |
| Workspace | 80 | `workspace` | `request.workspaceRef()` when non-blank |
| Tools | 90 | `tools` | catalog entries except `OPERATOR_ONLY`, `- name (POLICY): description` |

- [ ] **Step 1: Write the failing tests**

```java
class SystemContributorsTest {

    @Test
    void identityDefaultsWhenConfigIsEmpty() {
        SystemConfigRepository config = mock(SystemConfigRepository.class);
        when(config.findById(anyString())).thenReturn(Optional.empty());

        List<ContextSection> sections = new IdentityContextContributor(config).contribute(request());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("identity");
            assertThat(section.content()).contains("Operator", "UTC", "en");
            assertThat(section.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
        });
    }

    @Test
    void toolsExcludeOperatorOnlyEntries() {
        ToolCatalogProvider catalog = () -> List.of(
                new ToolDescriptor("get_run", "Read one run", ToolPolicy.WORKER_READ, true),
                new ToolDescriptor("decide_approval", "Operator only", ToolPolicy.OPERATOR_ONLY, false));

        List<ContextSection> sections = new ToolCatalogContextContributor(catalog).contribute(request());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.content()).contains("get_run");
            assertThat(section.content()).doesNotContain("decide_approval");
        });
    }
}
```

Test matrix for the remaining contributors (each test uses a mocked dependency and asserts
the section id and the exact content):

| Contributor | Setup | Expected |
|---|---|---|
| Agent policy | `agentId = AriaConstants.ARIA_AGENT_ID` | empty list |
| Agent policy | non-Aria agent, config `{"systemPrompt":"be a BA"}`, role `analyst` | one section `policy.agent`, content `be a BA` |
| Agent policy | non-Aria agent, no config prompt, role `analyst` | content `analyst` |
| Client policy | `clientPolicy = ""` | empty list |
| Client policy | `clientPolicy = "## Active Skill: TDD"` | one section `policy.client` with that text |
| Skills | resolver returns one `SkillContext("tdd", "...")` | one section `skills`, content contains `tdd` |
| Skills | resolver returns empty | empty list |
| State | `runRepository.countByStatus(RUNNING)=2`, `approvalRepository.countByStatus(PENDING)=1` | content `running runs: 2` and `pending approvals: 1` |
| Workspace | `workspaceRef = null` | empty list |
| Workspace | `workspaceRef = "repo@main"` | content contains `repo@main` |
| Goal | `goal = "ship it"` | one section `goal`, content `ship it` |
| Identity | config has name/timezone/language | exact three lines in that order |

Add the three `SystemConfigKeys` constants in `act-common/.../common/config/SystemConfigKeys.java`:
`MEMORY_OPERATOR_NAME = "memory.operator.name"`,
`MEMORY_OPERATOR_TIMEZONE = "memory.operator.timezone"`,
`MEMORY_OPERATOR_LANGUAGE = "memory.operator.language"`.

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**

```bash
cd agent-control-tower && mvn -pl act-execution test -Dtest=SystemContributorsTest
```

Expected: 7 tests, 0 failures after implementation.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context/contributor \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/context/contributor
git commit -m "feat(context): add the system context contributors"
```

#### Task A1-3: Conversation history loader

**Files:**
- Create: `.../execution/context/ConversationHistoryLoader.java`
- Test: `.../execution/context/ConversationHistoryLoaderTest.java`

**Interfaces:**
- Consumes: `RunRepository.findTop20ByConversationIdOrderByCreatedAtDesc`,
  `SessionTrajectoryRepository.findTop200ByRunIdOrderByTurnNumberDesc` (W0B; the loader
  reverses the newest-first rows into turn order).
- Produces: `List<ContextMessage> load(String conversationId, List<ContextMessage> clientFallback)`.

Rules:
- No conversation id: return the capped fallback.
- No server rows: return the capped fallback (max 40), never both.
- Server rows: reverse the 20 newest runs to chronological order, take at most 200 turns
  per run, keep user/assistant content, append the existing failed-run note verbatim
  (copied from `AriaService.failedTurnNote`), then keep the most recent 40 messages.
- Never include the current request; it is the run's prompt seed.

- [ ] **Step 1: Write the failing tests**

```java
class ConversationHistoryLoaderTest {

    private final RunRepository runs = mock(RunRepository.class);
    private final SessionTrajectoryRepository trajectories = mock(SessionTrajectoryRepository.class);
    private final ConversationHistoryLoader loader = new ConversationHistoryLoader(runs, trajectories);

    @Test
    void loadsAtMostTwentyRunsAndKeepsFortyMessages() {
        List<Run> newestFirst = IntStream.range(0, 25)
                .mapToObj(i -> run("c", i))
                .toList();
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(newestFirst);
        // newest first, as the Desc repository returns them
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(any()))
                .thenReturn(IntStream.range(0, 200)
                        .mapToObj(i -> trajectory("user", "m" + (199 - i)))
                        .toList());

        List<ContextMessage> history = loader.load("c", List.of());

        assertThat(history).hasSize(40);
        // only a loader that reverses each run into turn order ends on the newest message
        assertThat(history.get(39).content()).isEqualTo("m199");
        verify(trajectories, times(20)).findTop200ByRunIdOrderByTurnNumberDesc(any());
    }
}
```

Additional cases (one test each):

| Case | Setup | Expected |
|---|---|---|
| No conversation id | `load(null, fallback)` | fallback capped at 40 |
| No server rows | repository returns empty, fallback 50 messages | last 40 fallback messages, `degraded:client-history` recorded by the caller |
| Failed run | one FAILED run with an error message | one synthetic assistant note after that run's turns, using the exact existing text `（系統註記：上一回合因「<clip 200>」失敗，未產生回覆。）` |
| Roles | tool/system trajectory rows | ignored; only user/assistant kept |
| Order | newest-first repository rows | chronological order in the result |
| No duplication | current request is not in the repository | it never appears in history |

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**

```bash
cd agent-control-tower && mvn -pl act-execution test -Dtest=ConversationHistoryLoaderTest
```

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context/ConversationHistoryLoader.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/context/ConversationHistoryLoaderTest.java
git commit -m "feat(context): add the bounded conversation history loader"
```

#### Task A1-4: Manifest service and AgentLoopEngine integration

**Files:**
- Create: `.../execution/context/ContextManifestService.java`
- Modify: `.../execution/engine/AgentLoopEngine.java`
- Modify: `.../execution/engine/AgentLoopEngine*Test.java` (constructor and prompt assertions)
- Test: `.../execution/engine/AgentLoopEngineContextTest.java`

**Interfaces:**
- Consumes: assembler, loader, `ContextManifestRepository`.
- Produces: one manifest row per turn; `buildMessages` uses `AssembledContext.systemText()`
  and `AssembledContext.history()`; the core path inherits it through `coreTask(ctx)`.

Integration rules:
- Remove `KnowledgeContextProvider knowledgeProvider` and `AgentSkillResolver agentSkillResolver`
  from the engine constructor; they move to A1-2 contributors.
- Extract system messages from `priorMessages` into `ContextRequest.clientPolicy`; the
  remaining messages become `priorHistory` fallback.
- Build the request with adapter `SYNC` for `startRun` and `STREAM` for `startRunStream`,
  provider id `null` (the resolver uses the active provider), `maxSensitivity = RESTRICTED`
  (the provider ceiling is the real limit), and the next turn number.
- Order stays: system block, prior history, the current run's trajectory, prompt seed.
- The manifest is recorded even when optional sections degrade; a manifest write failure
  is logged and never fails the run.

- [ ] **Step 1: Write the failing test**

```java
@Test
void systemMessageComesFromTheAssemblerAndHistoryIsNotDuplicated() {
    when(assembler.assemble(any(), anyList()))
            .thenReturn(new AssembledContext("## Policy\nbe safe", List.of(), 3,
                    List.of(new ContextSection("policy.aria", "Policy", "be safe",
                            Sensitivity.INTERNAL, List.of(), false, null)),
                    List.of(), List.of()));

    // start a run whose priorMessages contains one user "hello"
    // capture the LlmClient request
    // assert message[0] is system "## Policy\nbe safe"
    // assert "hello" appears exactly once (as prior history, not as the prompt seed)
}
```

The manifest service and the engine integration are these exact shapes:

```java
@Service
public class ContextManifestService {
    private final ContextManifestRepository repository;
    private final ObjectMapper mapper;

    public ContextManifestService(ContextManifestRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public UUID record(ContextRequest request, AssembledContext assembled) {
        ContextManifest row = ContextManifest.builder()
                .id(UUID.randomUUID())
                .runId(request.runId())
                .turnNumber(request.turnNumber())
                .conversationId(request.conversationId())
                .agentId(request.agentId())
                .providerId(request.providerId())
                .adapter(request.adapter().name())
                .sectionsJson(json(assembled.sections().stream().map(section -> Map.of(
                        "id", section.id(),
                        "sources", section.sourceIds(),
                        "chars", section.estimatedChars(),
                        "tokens", section.estimatedTokens(),
                        "truncated", section.truncated())).toList()))
                .droppedJson(json(assembled.dropped()))
                .degradedJson(json(assembled.degraded()))
                .maxChars(request.maxChars())
                .estimatedTokens(assembled.estimatedTokens())
                .createdAt(Instant.now())
                .build();
        repository.save(row);
        return row.getId();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest serialization failed", e);
        }
    }
}
```

```java
// AgentLoopEngine.buildMessages, replacing the old system/knowledge/skills block
String clientPolicy = priorMessages.stream()
        .filter(message -> "system".equals(message.role()))
        .map(LlmMessage::content)
        .filter(content -> content != null && !content.isBlank())
        .collect(Collectors.joining("\n\n"));
List<ContextMessage> fallback = priorMessages.stream()
        .filter(message -> !"system".equals(message.role()))
        .map(message -> new ContextMessage(message.role(), message.content(), message.toolCallId()))
        .toList();
ContextRequest request = new ContextRequest(
        ctx.getRunId(), ctx.getRun().getConversationId(), ctx.getAgentId(),
        adapter, nextTurnNumber(ctx), ctx.getRun().getPromptSeed(), null,
        Sensitivity.RESTRICTED, contextProperties.getMaxChars(),
        workspaceRef(ctx), clientPolicy, fallback);
List<ContextMessage> history = historyLoader.load(request.conversationId(), request.priorHistory());
AssembledContext assembled = contextAssembler.assemble(request, history);
List<LlmMessage> messages = new ArrayList<>();
if (!assembled.systemText().isBlank()) {
    messages.add(LlmMessage.system(assembled.systemText()));
}
for (ContextMessage message : assembled.history()) {
    messages.add(toLlm(message));
}
manifestService.record(request, assembled);
// then append the current run's trajectory rows and the prompt seed exactly as today
```

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: run the engine tests**

```bash
cd agent-control-tower && mvn -pl act-execution test -Dtest='AgentLoopEngine*Test'
```

Expected: all engine tests green; the new test proves the assembler is the only prompt source.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/context/ContextManifestService.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/engine
git commit -m "feat(context): route AgentLoopEngine prompts through the assembler"
```

#### Task A1-5: Lane gate

```bash
cd agent-control-tower && mvn -pl act-execution -am test -Dspring.profiles.active=h2
```

Expected: BUILD SUCCESS; record the module counts. Handoff with commits and residuals.

### Lane A2: Aria managed prompt and knowledge contributor

#### Task A2-1: Aria prompt catalog and contributor

**Files:**
- Create: `act-aria/.../aria/prompt/AriaPromptCatalog.java`
- Create: `act-aria/.../aria/prompt/AriaPromptContributor.java`
- Test: `act-aria/src/test/java/io/aria/conductor/aria/prompt/AriaPromptContributorTest.java`

**Interfaces:**
- Consumes: W0A `ContextContributor`, `AriaConstants.ARIA_AGENT_ID`.
- Produces: a `policy.aria` section for Aria only, required, order 20.

Content migration rules:
- Merge the identity, governance, orchestrator-not-worker, no-guessing, multi-step, and
  sequential rules from `AriaDefaultAgentInitializer.ARIA_SYSTEM_PROMPT` with the
  workflow/spec-driven-development rules from `AriaService.buildSystemPrompt`.
- Drop both hardcoded tool lists and the old approval-tool wording; the tool list is
  generated by A1's `ToolCatalogContextContributor`, and operator-only tools are described
  as unavailable.
- Preserve the existing wording for the workflow rules and `instantiate_template` guidance.

- [ ] **Step 1: Write the failing test**

```java
@Test void ariaPolicyContainsWorkflowRulesButNoHardcodedToolCatalog() {
    ContextSection section = contributor.contribute(requestFor(AriaConstants.ARIA_AGENT_ID)).get(0);
    assertThat(section.id()).isEqualTo("policy.aria");
    assertThat(section.content()).contains("instantiate_template");
    assertThat(section.content()).doesNotContain("list_agents:");
    assertThat(section.content()).doesNotContain("decide_approval");
}

@Test
void nonAriaAgentGetsNoPolicySection() {
    assertThat(contributor.contribute(requestFor(UUID.randomUUID()))).isEmpty();
}
```

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**, **Step 5: commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/prompt \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/prompt
git commit -m "feat(aria): move the managed prompt into a context contributor"
```

#### Task A2-2: Initializer and chat service cleanup

**Files:**
- Modify: `act-aria/.../aria/init/AriaDefaultAgentInitializer.java`
- Modify: `act-aria/.../aria/service/AriaService.java`
- Modify: `act-aria/.../aria/service/AriaStreamService.java`
- Test: `AriaStreamServiceTest`, `AriaServiceTest`, `AriaDefaultAgentInitializerTest` as present.

Rules:
- The initializer writes only `maxToolCallRounds`, `taskApprovalRequired=false`, and
  `managedPromptVersion=1`; no full `systemPrompt`.
- `AriaService.chat` stops loading history; `startRun(runId, List.of())`.
- `AriaStreamService` deletes `buildInitialContext` and its `AriaService`/`SkillContextProvider`
  dependencies; it passes the client history as a system directive (for an active skill) plus
  user/assistant history through the existing `startRunStream` signature. The engine extracts
  the system message into `ContextRequest.clientPolicy`.
- Delete `EXTRACTION_PROMPT`, `MIN_TOOL_CALLS_FOR_EXTRACTION`, and `EXTRACTION_MAX_TOKENS`.

- [ ] **Step 1: Write failing tests**

```java
@Test
void streamingCarriesTheActiveSkillAsAClientPolicyMessage() {
    AriaChatRequest request = new AriaChatRequest("hello", null, "s1", List.of());

    service.streamChat(request, new SseEmitter());

    ArgumentCaptor<List<LlmMessage>> prior = ArgumentCaptor.forClass(List.class);
    verify(agentLoopEngine).startRunStream(any(), any(), prior.capture(), any());
    assertThat(prior.getValue())
            .anySatisfy(message -> {
                assertThat(message.role()).isEqualTo("system");
                assertThat(message.content()).contains("## Active Skill");
            });
}
```

Additional assertions:

| Case | Expected |
|---|---|
| Sync chat | `agentLoopEngine.startRun(runId, List.of())`; no `loadConversationHistory` call |
| Streaming chat | no call to `AriaService.buildSystemPrompt` |
| Initializer fresh row | config has `managedPromptVersion=1`, `taskApprovalRequired=false`, `maxToolCallRounds=15`, and no `systemPrompt` |
| Dead constants | `grep -n "EXTRACTION_PROMPT" act-aria/src/main/java` returns nothing |
| Existing tests | `AriaStreamServiceTest`, `AriaServiceTest`, `AriaDefaultAgentInitializerTest` updated and green |

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**, **Step 5: commit**

#### Task A2-3: Knowledge contributor and sensitivity filter

**Files:**
- Create: `act-knowledge/.../knowledge/context/KnowledgeContextContributor.java`
- Modify: `act-knowledge/.../knowledge/service/KnowledgeService.java`
- Delete: `act-common/.../common/service/KnowledgeContextProvider.java`
- Modify: act-aria and act-execution imports/tests that referenced the old interface

Rules:
- `KnowledgeService.buildKnowledgeSections(int limit, Sensitivity ceiling)` uses a bounded
  `findByStatusOrderByUpdatedAtDesc(APPROVED, PageRequest.of(0, limit))`, filters
  `item.getSensitivity().ordinal() <= ceiling.ordinal()`, and emits one `ContextSection` per
  item (`knowledge:<id>`, title = item name, content = existing 500-char-truncated
  description, source id = item id).
- `KnowledgeContextContributor` (order 50, not required) delegates to the service.
- Delete the old formatted-string interface and its call sites.

- [ ] **Step 1: Write failing tests**

```java
@Test
void restrictedKnowledgeNeverReachesAnInternalCeiling() {
    KnowledgeItem publicItem = item("public", Sensitivity.PUBLIC);
    KnowledgeItem restricted = item("restricted", Sensitivity.RESTRICTED);
    when(repository.findByStatusOrderByUpdatedAtDesc(eq(APPROVED), any()))
            .thenReturn(List.of(publicItem, restricted));

    List<ContextSection> sections = service.buildKnowledgeSections(5, Sensitivity.INTERNAL);

    assertThat(sections).extracting(ContextSection::id)
            .containsExactly("knowledge:" + publicItem.getId());
}

@Test
void eachApprovedItemBecomesItsOwnSection() {
    when(repository.findByStatusOrderByUpdatedAtDesc(eq(APPROVED), any()))
            .thenReturn(List.of(item("a", INTERNAL), item("b", INTERNAL)));

    assertThat(service.buildKnowledgeSections(5, INTERNAL)).hasSize(2);
    verify(repository).findByStatusOrderByUpdatedAtDesc(eq(APPROVED), any());
}
```

Also assert: the limit is passed to `PageRequest.of(0, limit)`; the old
`KnowledgeContextProvider` interface is deleted (`grep` returns nothing); no act-aria or
act-execution import of the old interface remains.

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**, **Step 5: commit**

#### Task A2-4: Lane gate

```bash
cd agent-control-tower && mvn -pl act-aria -am test -Dspring.profiles.active=h2
```

### Lane B: Memory kernel

#### Task B-1: Command service and governance

**Files:**
- Create: `act-memory/.../memory/dto/{MemoryActor,CreateEntityCommand,AddFactCommand,SupersedeFactCommand,AddRelationCommand}.java`
- Create: `act-memory/.../memory/service/{MemoryCommandService,MemoryValueGuard}.java`
- Create: `act-memory/.../memory/service/{MemoryNotFoundException,MemoryConflictException,MemoryValidationException}.java`
- Test: `act-memory/src/test/java/io/aria/conductor/memory/service/MemoryCommandServiceTest.java`

Rules:
- Status matrix: OPERATOR -> ACTIVE; RUN structural keys (`repo`, `branch`, `issue`,
  `stack`, `workspace`) -> ACTIVE; EXTRACTION -> PENDING; a RUN write with any other key
  is rejected with `MemoryValidationException`.
- `addFact` rejects a second ACTIVE fact for the same `(entityId, factKey)` with
  `MemoryConflictException`; `supersedeFact` checks `expectedVersion`, retires the old fact
  (`validTo=now`), creates the new ACTIVE fact, and writes two assertions.
- Proposal idempotency: hash `origin|entityId|factKey|valueText|sourceRef` into
  `proposal_hash`; a duplicate insert returns the existing row.
- Merge re-points facts/relations and sets `merged_into`; a merged entity cannot be merged again.
- `MemoryValueGuard` rejects keys matching `(?i).*(api[_-]?key|token|password|secret|credential).*`
  and values matching `sk-`, `ghp_`, `xoxb-`, or a 40+ char base64 run; the error names the
  field, never the value.
- Every mutation writes a `MemoryAssertion` in the same transaction.

- [ ] **Step 1: Write failing tests**

```java
@Test
void operatorFactsAreActiveAndExtractedFactsArePending() {
    MemoryEntity operator = command.createEntity(
            new CreateEntityCommand("PERSON", "operator", "Operator", INTERNAL),
            new MemoryActor(OPERATOR, "local"));
    MemoryFact active = command.addFact(operator.getId(),
            new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", INTERNAL, null),
            new MemoryActor(OPERATOR, "local"));
    MemoryFact pending = command.addFact(operator.getId(),
            new AddFactCommand("editor", "vim", "STRING", INTERNAL, "run-1"),
            new MemoryActor(EXTRACTION, "run-1"));

    assertThat(active.getStatus()).isEqualTo(FactStatus.ACTIVE);
    assertThat(pending.getStatus()).isEqualTo(FactStatus.PENDING);
}

@Test
void aStaleSupersedeIsAConflictAndWritesBothAssertions() {
    MemoryFact old = activeFact("timezone", "UTC", 3);
    assertThatThrownBy(() -> command.supersedeFact(old.getId(),
            new SupersedeFactCommand("Asia/Hong_Kong", 2, null),
            new MemoryActor(OPERATOR, "local")))
            .isInstanceOf(MemoryConflictException.class);
    verify(assertions, times(0)).save(any());
}
```

Test matrix:

| Case | Expected |
|---|---|
| RUN origin, key `repo` | ACTIVE with provenance |
| RUN origin, key `favorite_color` | `MemoryValidationException` |
| EXTRACTION origin, any key | PENDING |
| Second ACTIVE fact for the same `(entity, key)` | `MemoryConflictException` |
| Supersede with the current version | old `validTo=now`, new ACTIVE, two assertions |
| Same proposal hash twice | returns the existing row, no second insert |
| Merge source -> target | facts/relations re-pointed, `merged_into` set, assertion written |
| Merge an already merged entity | `MemoryConflictException` |
| Key `api_key` or value `sk-live-...` | `MemoryValidationException`; message names the field, not the value |
| Entity with no canonical key | `MemoryValidationException` |

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**, **Step 5: commit**

#### Task B-2: Query, proposals, recall, contributor

**Files:**
- Create: `act-memory/.../memory/service/{MemoryQueryService,MemoryProposalService,MemoryRecallService,MemoryContextContributor,MemoryMentionExtractor}.java`
- Create: `act-memory/.../memory/dto/{RecallQuery,RecallResult,MemoryEntityView,MemoryFactView,MemoryProposalView}.java`
- Test: `MemoryRecallServiceTest`, `MemoryContextContributorTest`, `MemoryProposalServiceTest`

Rules:
- Recall: extract canonical-key/display-name/alias mentions from the goal and last three
  user turns; expand one hop; load ACTIVE facts valid now; filter by `maxSensitivity`;
  rank by mention, distance, authority, recency, confidence, id; cap 12 entities/40 facts;
  same input -> same output.
- Proposal list returns PENDING facts/relations; `decide` requires `OPERATOR`; approval
  promotes the subject and supersedes any conflicting ACTIVE value.
- `MemoryContextContributor` id `memory`, order 40, not required; renders at most 12 entity
  lines and 40 fact lines; a recall timeout/exception returns an empty section with
  `degradedReason` and never throws into the assembler.
- Recall timeout: 200 ms via a shared single-thread executor and `Future.get(200, MS)`.

- [ ] **Step 1: Write failing tests**

```java
@Test
void recallIsDeterministicAndRanksOperatorFactsFirst() {
    when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(ACTIVE)))
            .thenReturn(List.of(operatorEntity(), repoEntity()));
    when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
            anyCollection(), eq(ACTIVE), any()))
            .thenReturn(List.of(operatorTimezone(), repoBranch()));

    RecallResult first = recall.recall(query("operator timezone Asia/Hong_Kong"));
    RecallResult second = recall.recall(query("operator timezone Asia/Hong_Kong"));

    assertThat(first.facts()).isEqualTo(second.facts());
    assertThat(first.facts().get(0).origin()).isEqualTo("OPERATOR");
}

@Test
void aSlowRepositoryDegradesTheSectionInsteadOfBlocking() {
    when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(ACTIVE)))
            .thenAnswer(invocation -> {
                Thread.sleep(500);
                return List.of();
            });

    ContextSection section = contributor.contribute(request("operator"));

    assertThat(section.content()).isEmpty();
    assertThat(section.degradedReason()).contains("timeout");
}
```

Test matrix:

| Case | Expected |
|---|---|
| Mention by canonical key, display name, or alias | entity included |
| One-hop relation | neighbour included; two-hop excluded |
| PENDING fact | never returned |
| Sensitivity above the request ceiling | never returned |
| Same input twice | identical order |
| More than 12 entities/40 facts | capped |
| Approval of a PENDING fact with an existing ACTIVE value | old retired, new ACTIVE |
| Worker calls `decide_memory_proposal` | `SecurityException` |
| Recall exception | empty section with a degraded reason, never a thrown exception |

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**, **Step 5: commit**

#### Task B-3: Lane gate

```bash
cd agent-control-tower && mvn -pl act-memory -am test -Dspring.profiles.active=h2
```

### Lane C: Tool catalog

**Files:**
- Create: `act-mcp/.../mcp/catalog/ToolCatalog.java`
- Modify: `ToolPolicyRegistry.java`, `McpProperties.java`, `ToolPolicyRegistryTest.java`, `McpToolInventory.java`

Rules:
- Move every current `POLICIES` entry into a `ToolDescriptor` in `ToolCatalog` verbatim,
  adding the `@Tool` description and `autoApproveRead` from the current
  `McpProperties.autoApproveReadTools` list.
- `ToolPolicyRegistry.policyOf` delegates to `ToolCatalog.find(name).map(ToolDescriptor::policy).orElse(GATED)`.
- `ToolCatalog implements ToolCatalogProvider` and exposes `autoApproveReadNames()`.
- Keep the `McpProperties.autoApproveReadTools` override, but add a validation test that
  its default equals `ToolCatalog.autoApproveReadNames()` and that every entry is a
  `WORKER_READ` tool.
- The live registered tool set must equal the catalog names; keep the `McpToolInventory`
  equality test.

- [ ] **Step 1: Write failing parity tests**

```java
class ToolCatalogParityTest {

    private final ToolCatalog catalog = new ToolCatalog();

    @Test
    void inventoryIsExactlyTheCatalog() {
        assertThat(McpToolInventory.EXPECTED_TOOL_NAMES)
                .containsExactlyInAnyOrderElementsOf(catalog.names());
    }

    @Test
    void everyToolHasAReviewedPolicyAndTheAutoApproveSetIsReadOnly() {
        assertThat(catalog.tools()).allSatisfy(tool ->
                assertThat(tool.policy()).isNotEqualTo(ToolPolicy.GATED));
        assertThat(new McpProperties().getAutoApproveReadTools())
                .containsExactlyInAnyOrderElementsOf(catalog.autoApproveReadNames());
        assertThat(catalog.tools().stream()
                .filter(ToolDescriptor::autoApproveRead)
                .map(ToolDescriptor::policy))
                .allMatch(policy -> policy == ToolPolicy.WORKER_READ);
    }
}
```

`McpToolInventory` is a test-only class; the live endpoint test already proves
`registered tools == inventory`. Add the catalog to that chain so a missing entry is a
compile/test failure, not a prompt drift.

- [ ] **Step 2: RED**, **Step 3: implement the catalog**, **Step 4: GREEN**

```bash
cd agent-control-tower && mvn -pl act-mcp test
```

- [ ] **Step 5: commit**

### Lane D: Backfill and Aria marker

**Files:**
- Create: `act-app/.../db/migration/V82__aria_managed_prompt.sql`
- Create: `act-app/.../app/backfill/MemoryBackfillRunner.java`
- Test: `act-app/src/test/java/io/aria/conductor/app/backfill/MemoryBackfillRunnerTest.java`

Rules:
- V82 appends `"managedPromptVersion":1` to the Aria agent config when absent (idempotent
  `CASE`/`CONCAT`; empty config becomes the object literal; a config already containing
  the marker is untouched).
- The runner is `ApplicationRunner`, enabled by `aria.memory.backfill.enabled` default true,
  and records completion in `system_config` key `memory.backfill.completed`.
- It creates one AGENT entity per non-retired agent (`agent:<uuid>`), one REPO entity per
  distinct `repoUrl`/`issueRepo` found in `workflow_chains.template_params` or `stepsJson`
  (normalized URL), and PROPOSED knowledge links only on exact display-name match. It never
  creates facts or relations, and a second run is a no-op.

- [ ] **Step 1: Write failing tests**

```java
@Test
void theSecondRunChangesNothing() {
    when(agents.findByRetiredAtIsNull()).thenReturn(List.of(agent("A"), agent("B")));
    when(chains.findAll()).thenReturn(List.of(chain("{\"repoUrl\":\"https://github.com/x/y\"}")));
    when(knowledge.findAll()).thenReturn(List.of());

    BackfillReport first = runner.run();
    BackfillReport second = runner.run();

    assertThat(first.agentsCreated()).isEqualTo(2);
    assertThat(first.reposCreated()).isEqualTo(1);
    assertThat(second.agentsCreated()).isZero();
    assertThat(second.reposCreated()).isZero();
    assertThat(entities.count()).isEqualTo(3);
}

@Test
void knowledgeLinkIsOnlyCreatedOnAnExactNameMatch() {
    when(knowledge.findAll()).thenReturn(List.of(knowledge("Operator"), knowledge("Operator Handbook")));
    when(entities.findByKindAndStatus(anyString(), eq(ACTIVE))).thenReturn(List.of(operatorEntity()));

    BackfillReport report = runner.run();

    assertThat(report.linksProposed()).isEqualTo(1);
    verify(links).save(argThat(link -> link.getStatus() == PROPOSED));
}
```

Additional cases:

| Case | Expected |
|---|---|
| No repository URL in any chain | `reposCreated=0`, no guessed entity |
| Same URL with trailing slash | one normalized entity |
| Retired agent | no entity |
| Backfill already completed | runner returns a no-op report |
| V82 on a row with the marker | config unchanged |
| V82 on a row without the marker | marker appended, JSON remains parseable |

- [ ] **Step 2: RED**, **Step 3: implement**, **Step 4: GREEN**, **Step 5: commit**

---

## Wave 2 — API, MCP, Integration

### Lane C2a: Memory REST API

**Files:** `act-dashboard-api/.../dashboard/memory/{MemoryController,MemoryExceptionHandler,MemoryMapper}.java`; `act-dashboard-api/pom.xml` (act-memory dependency); tests.

Endpoints exactly as the spec section 9. Use the existing operator authority resolution.
Map `MemoryNotFoundException` -> 404, `MemoryConflictException` -> 409,
`MemoryValidationException` -> 422, `SecurityException` -> 403, in the existing error
envelope. Tests: MockMvc slice for each route and error code; no secrets in responses.

- [ ] Write the failing controller tests

```java
@WebMvcTest(MemoryController.class)
class MemoryControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void createsAnEntity() throws Exception {
        when(command.createEntity(any(), any())).thenReturn(entityView("operator"));
        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\",\"displayName\":\"Operator\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canonicalKey").value("operator"));
    }

    @Test
    void mapsAConflictTo409() throws Exception {
        when(command.addFact(any(), any(), any())).thenThrow(new MemoryConflictException("active fact exists"));
        mvc.perform(post("/api/v1/memory/entities/{id}/facts", UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"key\":\"timezone\",\"value\":\"UTC\"}"))
                .andExpect(status().isConflict());
    }
}
```

Add one test per route in the spec section 9 and one per error mapping
(404/409/422/403). Then implement and run:

```bash
cd agent-control-tower && mvn -pl act-dashboard-api -am test -Dtest=MemoryControllerTest
```

- [ ] Commit.

### Lane C2b: Memory MCP tools

**Files:** `act-mcp/.../mcp/tools/MemoryTools.java`; `ToolCatalog.java` (add 8 entries);
`McpToolInventory.java`; tests.

Rules:
- Add the eight tools with the policies from the spec section 9.
- `remember_fact` maps a worker actor to origin EXTRACTION (PENDING) and an operator actor
  to OPERATOR (ACTIVE); `decide_memory_proposal` and `forget_fact` are OPERATOR_ONLY.
- The Aria prompt tool section excludes OPERATOR_ONLY; add a test that the generated
  section does not contain those names.
- Update the inventory/parity test via the catalog.

- [ ] Write the failing tool tests

```java
class MemoryToolsTest {

    @Test
    void workerRememberWritesAPendingProposal() {
        when(commands.addFact(any(), any(), argThat(actor -> actor.type() == EXTRACTION)))
                .thenReturn(fact(PENDING));

        String result = tools.rememberFact(UUID.randomUUID(), "timezone", "UTC", null, workerContext());

        assertThat(result).contains("\"ok\":true");
        verify(commands).addFact(any(), any(), argThat(actor -> actor.type() == EXTRACTION));
    }

    @Test
    void workerCannotDecideAProposal() {
        assertThatThrownBy(() -> tools.decideMemoryProposal(UUID.randomUUID(), true, null, workerContext()))
                .isInstanceOf(SecurityException.class);
    }
}
```

Also assert: the catalog has all eight tools with the spec policies; the generated Aria
tool section excludes `decide_memory_proposal` and `forget_fact`; the inventory parity test
is green. Then run:

```bash
cd agent-control-tower && mvn -pl act-mcp -am test
```

- [ ] Commit.

### Lane I: Integration and architecture guard

**Files:** `act-app` wiring/config; `AGENTS.md` validation mapping; architecture test in
`act-memory/src/test/java/io/aria/conductor/memory/MemoryModuleBoundaryTest.java`;
`act-app` integration test.

Rules:
- Confirm `List<ContextContributor>` collects Aria, knowledge, memory, and the system
  contributors; the app starts on H2.
- ArchUnit: no class in `io.aria.conductor.memory` may depend on
  `io.aria.conductor.execution`, `io.aria.conductor.knowledge`, or `io.aria.conductor.aria`.
- Add the act-memory commands to `AGENTS.md` (module table and validation mapping).
- Add an app-context test that asserts one manifest row is written for a chat turn and the
  memory contributor is present.

- [ ] Write the failing boundary test

```java
class MemoryModuleBoundaryTest {

    @Test
    void memoryDoesNotDependOnOtherDomainModules() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("io.aria.conductor.memory");

        ArchRule rule = noClasses()
                .that().resideInAPackage("io.aria.conductor.memory..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "io.aria.conductor.execution..",
                        "io.aria.conductor.knowledge..",
                        "io.aria.conductor.aria..");

        rule.check(classes);
    }
}
```

Add an app-context test that loads the H2 profile, asserts `List<ContextContributor>` has
the identity, Aria policy, knowledge, memory, and tool contributors, and asserts one chat
turn writes one `context_manifests` row. Then run:

```bash
cd agent-control-tower && mvn -pl act-app -am test -Dspring.profiles.active=h2
```

- [ ] Commit.

---

## Gate A (after W0A, W0B, A1, A2, C merge)

Release QA owns this gate. The Lead merges the four lanes first.

1. Clean worktree at the integration HEAD; `git status -sb` clean.
2. Full local gate:

```bash
cd /d/project/aria-agent-os/agent-control-tower && mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify -Dspring.profiles.active=h2
cd /d/project/aria-agent-os/agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build
cd /d/project/aria-agent-os/packages/mcp-server && pnpm test
cd /d/project/aria-agent-os/packages/qoder-acp-bridge && pnpm test
```

3. Live SDD scenario on the exact live recipe used for PR #118 (fat jar, H2, qoder HOST,
   operator-supplied GitHub credential; never read `.env`):
   - start the backend from the immutable fat jar; the operator supplies the environment
     and the GitHub credential, and the QA agent never reads `.env`:

```bash
cd /d/project/aria-agent-os/agent-control-tower/act-app
java -jar target/act-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=h2 --adk.default-provider=opencode
```

   - create one SDD chain on an existing GitHub issue;
   - approve the spec gate, let DEV and QA finish;
   - assert: no `StopProof[allWritersStopped=false]`, no 500 on approval, one context
     manifest per turn for the BA/DEV/QA runs.
4. Hosted CI green at the Gate A HEAD:

```bash
cd /d/project/aria-agent-os
gh workflow run ci.yml --ref feat/agent-os-memory
gh run list --branch feat/agent-os-memory --limit 5
```

Run the Sandbox Lifecycle workflow as well when its paths changed; otherwise record it as
not applicable.
5. Record: HEAD, run IDs, counts, the live chain id, and residual risks in
   `docs/reviews/2026-11-09-agent-os-s1-ledger.md`.

Gate A blocks on: any behavior change in approvals, stop-proof, token accounting, or the
SDD chain; any prompt section that differs between sync and streaming for the same state;
any compile/test red; any hosted CI red.

## Gate B (after B, D, C2a, C2b, I merge)

Release QA owns this gate.

1. Fresh H2 database; start the backend.
2. Teach facts through the operator REST surface (no secrets):

```bash
BASE=http://localhost:8080
OPERATOR_ID=$(curl -s -X POST "$BASE/api/v1/memory/entities" \
  -H 'Content-Type: application/json' \
  -d '{"kind":"PERSON","canonicalKey":"operator","displayName":"Operator","sensitivity":"INTERNAL"}' \
  | node -pe "JSON.parse(require('fs').readFileSync(0,'utf8')).id")
REPO_ID=$(curl -s -X POST "$BASE/api/v1/memory/entities" \
  -H 'Content-Type: application/json' \
  -d '{"kind":"REPO","canonicalKey":"repo:aria-conductor","displayName":"aria-conductor","sensitivity":"INTERNAL"}' \
  | node -pe "JSON.parse(require('fs').readFileSync(0,'utf8')).id")
curl -s -X POST "$BASE/api/v1/memory/entities/$OPERATOR_ID/facts" \
  -H 'Content-Type: application/json' \
  -d '{"key":"timezone","value":"Asia/Hong_Kong","valueType":"STRING","sensitivity":"INTERNAL"}'
curl -s -X POST "$BASE/api/v1/memory/entities/$OPERATOR_ID/facts" \
  -H 'Content-Type: application/json' \
  -d '{"key":"preferred_editor","value":"vim","valueType":"STRING","sensitivity":"INTERNAL"}'
curl -s "$BASE/api/v1/memory/recall?q=timezone"
```

3. Restart the backend; start a new conversation; assert the recall section and manifest
   contain the fact ids and sources:

```bash
curl -s "$BASE/api/v1/memory/recall?q=timezone%20Asia/Hong_Kong"
curl -s "$BASE/api/v1/memory/runs/<runId>/context-manifest"
```
4. Create a PENDING proposal through `remember_fact` with a worker actor; assert it is
   absent from the next prompt; approve it via `decide_memory_proposal`; assert it appears
   on the following turn.
5. Set a fact to RESTRICTED; assert it is absent with the provider ceiling `INTERNAL`, then
   present after raising the ceiling.
6. Re-run the full local gate and the SDD live scenario; hosted CI green at HEAD.
7. Record the evidence in the same ledger.

Gate B blocks on: a PENDING row entering a prompt; a RESTRICTED row crossing the ceiling;
a missing manifest; a non-idempotent backfill; any SDD regression; any hosted CI red.

## QA Dispatch Briefs

Independent Reviewer (per lane):

```text
Scope: branch <lane>, commits <base>..<head>, spec <path>, plan task <ids>.
Read-only. Verify every finding against the code and tests. Check: file ownership,
TDD evidence (RED then GREEN), exact counts, no secrets, module boundaries,
sensitivity fail-closed, concurrency/version behavior, and missing regression coverage.
Return severity-tagged findings with file:line, and a verdict APPROVE or BLOCK.
```

Release QA (Gate A and Gate B):

```text
Scope: integration HEAD <sha>. Own the ladder: full local gate, clean-room, live scenario,
hosted CI, ledger. Classify every failure as product/environment/path/flake/clean-install.
Do not call the gate green on local tests alone. Return evidence with commands, counts,
run IDs, and residual risks.
```

## Self-Review

The plan was checked against the spec:

- Spec sections 4 (P1-P10), 5 (modules), 6 (schema), 7 (governance), 8 (pipeline),
  9 (API/MCP), 10 (backfill), 11 (failure/security), 12 (gates), 13 (acceptance),
  and 14 (residuals) each map to a task above.
- No placeholders remain: every task has files, interfaces, a failing test, a run command,
  and a commit.
- Type names are consistent: `ContextSection`, `ContextRequest`, `ContextContributor`,
  `ToolDescriptor`, `ToolCatalogProvider`, `ContextManifest`, `AssembledContext`.
- The plan intentionally does not include the S2 design or UI; those are a separate spec.
- The lane protocol uses the repository's SDD evidence discipline, not the live product
  chain; Gate A and Gate B use the live product chain as acceptance.

## Residual Risks

- The tool catalog move is mechanical but touches the MCP governance surface; a single
  missed entry is caught by the parity test, but review must confirm the policy values are
  unchanged.
- A1's assembler changes every prompt; Gate A is the behavior-preservation proof. Any
  prompt diff in the live run must be explained.
- The Aria managed-prompt migration ignores operator edits to the old stored prompt; this
  is documented in the spec and must be announced.
- Deterministic recall may miss paraphrases; the fixture set in B measures it, and the
  interface allows a later reranker.
- Full Maven gates are serialized on this box; the lane schedule must not launch two full
  builds at once.
- The live gates need an operator-supplied GitHub credential and a valid issue; a missing
  credential blocks the gate, not the code.

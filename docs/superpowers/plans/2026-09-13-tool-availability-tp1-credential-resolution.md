# Tool Availability TP1 — Credential Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the SDD git handoff resolve its GitHub credential from the tool-pack credential store, so a credential configured by an operator actually works, and fail fast with an actionable error when none is configured.

**Architecture:** `GitBranchConfig` currently reads `GH_TOKEN` straight from the process environment, bypassing the encrypted `PackCredentialService` that `GitPackHandler` already uses. This plan routes both through one resolution path, adds an `isAvailable()` probe so callers stop using exceptions for control flow, and adds a pre-flight gate in `WorkflowTemplateService` so a GitHub-dependent template is refused before a chain exists rather than failing mid-approval with HTTP 500.

**Tech Stack:** Java 21, Spring Boot 3.3, JUnit 5, Mockito, AssertJ, WireMock, Maven (multi-module), PowerShell startup scripts.

**Spec:** `docs/superpowers/specs/2026-09-13-tool-availability-review-gate-design.md` (section 7)

## Global Constraints

- Java 21, Spring Boot 3.3, Maven. Java package root is `io.aria.conductor`.
- Do not change the `POST /api/v1/approvals/{id}/decide` contract.
- The `GITHUB_TOKEN` environment fallback must keep working: CI and existing `.env` files depend on it. `PackCredentialService.resolve` already provides it, so do not re-implement it.
- Credential values must never appear in an API response or a log line.
- Module test commands follow `AGENTS.md`: `cd agent-control-tower && mvn test -pl <module>`.
- Only the files listed in each task may change. `WorkflowChain`, `ToolDefinition`, `ToolPack` and `PackCredential` need no schema change in TP1.

---

### Task 1: `GitBranchService.isAvailable()`

Adds the availability probe. Without it, callers can only discover "no credential" by catching an exception from the first operation.

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchService.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/git/GitBranchServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public boolean isAvailable()` on `GitBranchService` — returns `true` when a non-blank token was supplied. Used by Task 2's tests and Task 3's gate.

- [ ] **Step 1: Write the failing test**

Append to `GitBranchServiceTest`. The existing class already imports `org.assertj.core.api.Assertions.assertThat`.

```java
    @Test
    void isAvailable_falseWhenTokenIsBlank() {
        assertThat(new GitBranchService("").isAvailable()).isFalse();
        assertThat(new GitBranchService(null).isAvailable()).isFalse();
        assertThat(new GitBranchService("   ").isAvailable()).isFalse();
    }

    @Test
    void isAvailable_trueWhenTokenIsPresent() {
        assertThat(new GitBranchService("ghp_dummy_token").isAvailable()).isTrue();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=GitBranchServiceTest`

Expected: FAIL to compile with `cannot find symbol: method isAvailable()`.

- [ ] **Step 3: Write minimal implementation**

In `GitBranchService`, add the method immediately after the two constructors (after the `GitBranchService(String ghToken, String apiBaseUrl)` constructor, before `createBranch`):

```java
    /**
     * True when a non-blank token was supplied, i.e. branch operations can be
     * attempted. Callers use this to fail fast instead of catching
     * {@link GitBranchException} from the first operation.
     */
    public boolean isAvailable() {
        return ghToken != null && !ghToken.isBlank();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=GitBranchServiceTest`

Expected: PASS, all tests in the class green.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchService.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/git/GitBranchServiceTest.java
git commit -m "feat(git): expose GitBranchService.isAvailable() credential probe"
```

---

### Task 2: Resolve the token from the credential store

Makes an operator-configured credential authoritative. `PackCredentialService.resolve` already implements per-agent override → pack-global → `GITHUB_TOKEN` env fallback, so this task deletes the second, env-only path instead of writing a new one.

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchConfig.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchService.java` (remove dead `fromEnvironment`)
- Create: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/git/GitBranchConfigTest.java`
- Modify: `scripts/start-backend.ps1:84-86`
- Modify: `scripts/start-backend.sh:109-111`

**Interfaces:**
- Consumes: `GitBranchService.isAvailable()` from Task 1; `PackCredentialService.resolve(String packId, String agentId, String credKey)` (existing `@Service` in `io.aria.conductor.execution.credential`).
- Produces: `GitBranchConfig.GIT_PACK_ID` (`"pack-git-0001"`), `GitBranchConfig.GITHUB_TOKEN_KEY` (`"GITHUB_TOKEN"`) and `GitBranchConfig.DEPRECATED_GH_TOKEN_KEY` (`"GH_TOKEN"`) — package-private constants reused by Task 2's test. `gitBranchService(PackCredentialService)` becomes the bean signature.

- [ ] **Step 1: Write the failing test**

Create `GitBranchConfigTest.java`:

```java
package io.aria.conductor.execution.git;

import io.aria.conductor.execution.credential.PackCredentialService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitBranchConfigTest {

    private final GitBranchConfig config = new GitBranchConfig();

    @Test
    void resolvesTokenFromTheCredentialStore() {
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenReturn("ghp_from_store");

        GitBranchService service = config.gitBranchService(credentials);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void fallsBackToTheDeprecatedGhTokenAlias() {
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenReturn(null);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null,
                GitBranchConfig.DEPRECATED_GH_TOKEN_KEY))
                .thenReturn("ghp_legacy");

        GitBranchService service = config.gitBranchService(credentials);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void returnsDisabledStubWhenNothingResolves() {
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenReturn(null);

        GitBranchService service = config.gitBranchService(credentials);

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.createBranch("https://github.com/owner/repo.git", "b"))
                .isInstanceOf(GitBranchException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=GitBranchConfigTest`

Expected: FAIL to compile — `gitBranchService` takes a `String`, not a `PackCredentialService`, and `GIT_PACK_ID` does not exist.

- [ ] **Step 3: Rewrite `GitBranchConfig`**

Replace the whole file with:

```java
package io.aria.conductor.execution.git;

import io.aria.conductor.execution.credential.PackCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring wiring for {@link GitBranchService}. The token is resolved through
 * {@link PackCredentialService}, so a credential stored in the git tool pack by
 * an operator is authoritative; the service still falls back to the
 * {@code GITHUB_TOKEN} environment variable, which keeps CI and existing .env
 * files working. When nothing resolves, the bean is a no-op variant that logs a
 * warning and throws a clear {@link GitBranchException} on every operation, so
 * non-GitHub environments still boot. Callers check
 * {@link GitBranchService#isAvailable()} rather than catching that exception.
 */
@Configuration
@Slf4j
public class GitBranchConfig {

    static final String GIT_PACK_ID = "pack-git-0001";
    static final String GITHUB_TOKEN_KEY = "GITHUB_TOKEN";
    /**
     * Transitional alias. The startup scripts warned on {@code GH_TOKEN} and
     * {@code scripts/sdd-mcp-e2e.mjs} still gates on it, so environments in the wild
     * are as likely to export {@code GH_TOKEN} as {@code GITHUB_TOKEN}. Resolving the
     * alias through the same service keeps one resolution path while those setups
     * migrate. Remove once nothing exports the old name.
     */
    static final String DEPRECATED_GH_TOKEN_KEY = "GH_TOKEN";

    @Bean
    public GitBranchService gitBranchService(PackCredentialService credentialService) {
        String token = credentialService.resolve(GIT_PACK_ID, null, GITHUB_TOKEN_KEY);
        boolean fromAlias = false;
        if (token == null || token.isBlank()) {
            token = credentialService.resolve(GIT_PACK_ID, null, DEPRECATED_GH_TOKEN_KEY);
            fromAlias = token != null && !token.isBlank();
        }
        if (token == null || token.isBlank()) {
            log.warn("No GitHub credential resolved from the git tool pack ({}) or GITHUB_TOKEN: "
                    + "GitBranchService is disabled — Git branch operations will throw "
                    + "GitBranchException when invoked", GIT_PACK_ID);
            return new GitBranchService("") {
                private GitBranchException disabled() {
                    return new GitBranchException(0,
                            "No GitHub credential configured; Git branch operations are disabled. "
                                    + "Store a GITHUB_TOKEN in the git tool pack or set the "
                                    + "GITHUB_TOKEN environment variable.");
                }

                @Override
                public void createBranch(String repoUrl, String branchName) {
                    throw disabled();
                }

                @Override
                public void putFile(String repoUrl, String branchName, String path,
                                    String content, String commitMessage) {
                    throw disabled();
                }

                @Override
                public Optional<String> getFile(String repoUrl, String branchName, String path) {
                    throw disabled();
                }

                @Override
                public Optional<String> branchHeadSha(String repoUrl, String branchName) {
                    throw disabled();
                }
            };
        }
        if (fromAlias) {
            log.warn("GitHub credential resolved from the deprecated GH_TOKEN name; "
                    + "rename it to GITHUB_TOKEN");
        }
        return new GitBranchService(token);
    }
}
```

Then delete the now-dead `fromEnvironment()` from `GitBranchService` (it reads `GH_TOKEN`, has no callers — verified by grep — and would be a third credential path):

```java
    /** Read the token from {@code GH_TOKEN} (used by Spring wiring / tests). */
    public static GitBranchService fromEnvironment() {
        return new GitBranchService(System.getenv("GH_TOKEN"));
    }
```

Also update the `GitBranchService` class javadoc line that says the token is injected via constructor "never read from the environment inside the class" — keep that sentence, it stays true; just drop the stale `GH_TOKEN` naming in the first sentence so it reads `The GitHub token is injected through the constructor`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=GitBranchConfigTest,GitBranchServiceTest`

Expected: PASS for both classes.

- [ ] **Step 5: Align the startup scripts' credential key**

Both startup scripts warn on `GH_TOKEN`, which `.env.example` does not document. Replace `scripts/start-backend.ps1` lines 84-86 with:

```powershell
if (-not $env:GITHUB_TOKEN) {
    Write-Warning "GITHUB_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox. Store it in the git tool pack (Configure -> Skills & Tools) or set the GITHUB_TOKEN environment variable."
}
```

And `scripts/start-backend.sh` lines 109-111 with:

```bash
if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "WARN: GITHUB_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox. Store it in the git tool pack (Configure -> Skills & Tools) or set the GITHUB_TOKEN environment variable."
fi
```

Verify the two startup scripts no longer mention the old name:

Run: `grep -rn "GH_TOKEN" scripts/start-backend.ps1 scripts/start-backend.sh`

Expected: no output. `scripts/sdd-mcp-e2e.mjs` still gates on `GH_TOKEN` and is intentionally left alone — see the ledger note; it keeps working because `GitBranchConfig` resolves the alias.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchConfig.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchService.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/git/GitBranchConfigTest.java \
        scripts/start-backend.ps1 scripts/start-backend.sh
git commit -m "fix(git): resolve GitHub credential from the tool-pack credential store"
```

---

### Task 3: Fail fast when a template needs GitHub and cannot have it

Turns a mid-approval HTTP 500 into a pre-flight validation error. This is the temporary gate that TP4 later replaces with a review-gate decision.

**Files:**
- Modify: `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/WorkflowTemplateService.java`
- Modify: `agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceTest.java:55-70`
- Modify: `agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceSddTest.java:54-69`
- Test: `agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceSddTest.java`

**Interfaces:**
- Consumes: `GitBranchService.isAvailable()` from Task 1.
- Produces: `WorkflowTemplateService` constructor gains a 10th parameter, `GitBranchService gitBranchService`, appended last. Any later task constructing it manually must pass the extra argument.

- [ ] **Step 1: Update the two existing construction sites so the module still compiles**

In **both** `WorkflowTemplateServiceTest.java` and `WorkflowTemplateServiceSddTest.java`: add a mock field alongside the other `@Mock` fields,

```java
    @Mock GitBranchService gitBranchService;
```

add the import,

```java
import io.aria.conductor.execution.git.GitBranchService;
```

and pass it as the last constructor argument:

```java
        service = new WorkflowTemplateService(itemRepository, versionRepository,
                templateConverter, workflowService, chainRepository, knowledgeService,
                dodService, kanbanService, openCodeProperties, gitBranchService);
```

- [ ] **Step 2: Write the failing test**

Append both tests to `WorkflowTemplateServiceSddTest`. Every import they need is already present in that file (`assertThat`, `assertThatThrownBy`, `any`, `anyList`, `Map`, `Set`, `List`, `Optional`, `UUID`), and the `step(...)` and `approvedWorkflowTemplate(...)` helpers already exist — the code below uses them exactly as the neighbouring tests do. The only new import is `io.aria.conductor.execution.git.GitBranchService` from Step 1.

```java
    @Test
    void instantiateTemplate_gitDependentTemplateWithoutCredential_failsFast() {
        UUID templateId = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("git-flow", "GitHub handoff");
        item.setId(templateId);
        item.setCurrentVersion("v1");
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, "v1"))
                .thenReturn(Optional.of(KnowledgeVersion.builder()
                        .yamlContent("steps: [ba, dev]")
                        .build()));
        WorkflowStep dev = step(WorkflowStep.StepKind.DEV, "Clone {repoUrl} and implement");
        when(templateConverter.yamlToWorkflowSteps("steps: [ba, dev]"))
                .thenReturn(List.of(dev));
        when(templateConverter.extractParameterNames(anyList()))
                .thenReturn(Set.of("issueRef", "repoUrl"));
        when(gitBranchService.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.instantiateTemplate(
                templateId, Map.of("issueRef", "42", "repoUrl", "https://github.com/o/r.git")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GITHUB_TOKEN");
    }

    @Test
    void instantiateTemplate_gitDependentTemplateWithCredential_passesTheGate() {
        UUID templateId = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("git-flow", "GitHub handoff");
        item.setId(templateId);
        item.setCurrentVersion("v1");
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, "v1"))
                .thenReturn(Optional.of(KnowledgeVersion.builder()
                        .yamlContent("steps: [ba, dev]")
                        .build()));
        WorkflowStep dev = step(WorkflowStep.StepKind.DEV, "Clone {repoUrl} and implement");
        when(templateConverter.yamlToWorkflowSteps("steps: [ba, dev]"))
                .thenReturn(List.of(dev));
        when(templateConverter.extractParameterNames(anyList()))
                .thenReturn(Set.of("issueRef", "repoUrl"));
        when(gitBranchService.isAvailable()).thenReturn(true);

        UUID chainId = UUID.randomUUID();
        when(workflowService.createAndStart(any(CreateWorkflowRequest.class)))
                .thenReturn(WorkflowResponse.builder()
                        .id(chainId)
                        .name("git-flow-instance")
                        .build());
        WorkflowChain chain = aWorkflowChain().withId(chainId).build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(chainRepository.save(any(WorkflowChain.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowResponse response = service.instantiateTemplate(
                templateId, Map.of("issueRef", "42", "repoUrl", "https://github.com/o/r.git"));

        assertThat(response.getId()).isEqualTo(chainId);
    }
```

Note: `workflowService.deserializeSteps(...)` is deliberately not stubbed — Mockito returns an empty `List` for a `List`-returning method, which is what the existing first test in this file relies on too.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=WorkflowTemplateServiceSddTest`

Expected: `instantiateTemplate_gitDependentTemplateWithoutCredential_failsFast` FAILS — no exception is thrown, because the gate does not exist yet.

- [ ] **Step 4: Implement the gate**

In `WorkflowTemplateService`: add the field and constructor parameter,

```java
    private final GitBranchService gitBranchService;
```

```java
    public WorkflowTemplateService(KnowledgeItemRepository itemRepository,
                                   KnowledgeVersionRepository versionRepository,
                                   WorkflowTemplateConverter templateConverter,
                                   WorkflowService workflowService,
                                   WorkflowChainRepository chainRepository,
                                   KnowledgeService knowledgeService,
                                   DoDService dodService,
                                   KanbanService kanbanService,
                                   OpenCodeProperties openCodeProperties,
                                   GitBranchService gitBranchService) {
```

with the matching assignment `this.gitBranchService = gitBranchService;`, add the import

```java
import io.aria.conductor.execution.git.GitBranchService;
```

and insert this block immediately after the existing R8-F1 `repoUrl` resolution block (which ends at the closing brace of `if (declaredParams.contains(GitHandoffMetadata.KEY_REPO_URL)) { ... }`) and before the `// Substitute parameters` comment:

```java
        // TP1: a template that hands off to GitHub is unusable without a credential.
        // Refuse it here, before a chain exists, instead of letting the spec approval
        // fail mid-flight. Placed after the repoUrl check so the repoUrl error still
        // wins when both preconditions are unmet.
        if (declaredParams.contains(GitHandoffMetadata.KEY_REPO_URL)
                && !gitBranchService.isAvailable()) {
            throw new IllegalArgumentException(
                    "This template hands off to GitHub, but no GitHub credential is configured. "
                            + "Store a GITHUB_TOKEN in the git tool pack (Configure -> Skills & Tools), "
                            + "or set the GITHUB_TOKEN environment variable.");
        }
```

- [ ] **Step 5: Run the module tests to verify everything passes**

Run: `cd agent-control-tower && mvn test -pl act-knowledge`

Expected: PASS. Confirms the new gate works and that no existing instantiation test regressed — every pre-existing `extractParameterNames` stub returns a set without `repoUrl`, so the gate is skipped for them.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/WorkflowTemplateService.java \
        agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceSddTest.java \
        agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceTest.java
git commit -m "feat(workflow): refuse GitHub-dependent templates without a git credential"
```

---

### Task 4: Verify the split-brain is closed on the live stack

Proves the fix against the running application, not just mocks: a credential stored through the pack API must open the gate that was previously closed.

**Files:**
- No source changes. This task produces verification evidence only.

**Interfaces:**
- Consumes: everything above.
- Produces: recorded command output for the spec's verification section.

- [ ] **Step 1: Rebuild and restart the backend so it runs the new code**

Run:

```bash
pwsh -NoProfile -File scripts/stop.ps1
pwsh -NoProfile -File scripts/start.ps1 -NonInteractive
```

Expected: `Backend : http://localhost:8080 [OK]` in the mode summary. The backend must be rebuilt because `mvn spring-boot:run` in `start-backend.ps1` reuses installed artefacts.

- [ ] **Step 2: Confirm the gate is closed before any credential exists**

The route is `POST /api/v1/knowledge/{id}/instantiate-workflow` (`KnowledgeController:37`) with body
`{"parameters": {...}}` (`InstantiateWorkflowRequest`). The id
`d0000001-0000-0000-0000-000000000001` is the seeded `development-workflow` template from V40.

```bash
POST_BODY='{"parameters":{"issueRef":"42","issueRepo":"HappyLiang12/aria-conductor","repoUrl":"https://github.com/HappyLiang12/aria-conductor"}}'
curl -s -o /tmp/tp1-closed.json -w "HTTP %{http_code}\n" \
  -X POST http://localhost:8080/api/v1/knowledge/d0000001-0000-0000-0000-000000000001/instantiate-workflow \
  -H 'Content-Type: application/json' -d "$POST_BODY"
cat /tmp/tp1-closed.json
```

`repoUrl` must be passed explicitly: the pre-existing R8-F1 `opencode.repo-url` check fires first when it is absent, and that error is not the one under test.

Expected: HTTP 400 and a body naming `GITHUB_TOKEN`. That is the behaviour change — previously this request succeeded and the failure surfaced much later as an HTTP 500 during spec approval. No chain is created on this path, so the check is side-effect free.

- [ ] **Step 3: Store a credential through the tool-pack API**

`ToolPackController.storeCredential` takes `key`, `value` and an optional `agentId` (omitted = pack-global).

```bash
curl -s -X POST http://localhost:8080/api/v1/packs/pack-git-0001/credentials \
  -H 'Content-Type: application/json' \
  -d '{"key":"GITHUB_TOKEN","value":"ghp_verification_placeholder"}'
```

Expected: `{"status":"stored","key":"GITHUB_TOKEN"}`.

- [ ] **Step 4: Restart, then confirm the gate opens**

This step is also the proof of the `store over env` precedence that spec §7.5 requires: the credential is
in the store and in neither environment variable, so the gate can only open if `resolve` prefers the
store. Confirm the precondition first — if either name is exported, the proof is void:

```bash
grep -E "^(GITHUB_TOKEN|GH_TOKEN)=" .env || echo "PASS: neither name is in .env"
```

`GitBranchConfig.gitBranchService` is a singleton `@Bean`, so the token is resolved once at context
startup — a credential stored at runtime does not affect the already-constructed bean. Restart before
re-checking:

```bash
pwsh -NoProfile -File scripts/stop.ps1
pwsh -NoProfile -File scripts/start.ps1 -NonInteractive
```

Then re-run the Step 2 request.

Expected: the `GITHUB_TOKEN` error is gone. The request now proceeds, which means it creates a chain and starts the BA step — a real side effect with real token cost. Capture the chain id from the response and cancel it immediately:

```bash
curl -s -X POST http://localhost:8080/api/v1/workflows/<chainId>/cancel
```

If no cancel route exists, use the `cancelWorkflow` helper in `act-dashboard/e2e/fixtures.ts` as the reference for the correct path, and record which was used. The point of this step is only that the credential precondition no longer blocks — not that the chain runs to completion.

- [ ] **Step 5: Functionally clear the credential and confirm the gate closes again**

There is no credential-delete endpoint until TP2 (spec §8.1). Storing an empty value is the interim clear: `resolve` returns a blank string and `GitBranchConfig` treats it as absent.

```bash
curl -s -X POST http://localhost:8080/api/v1/packs/pack-git-0001/credentials \
  -H 'Content-Type: application/json' \
  -d '{"key":"GITHUB_TOKEN","value":""}'
```

Restart as in Step 4, re-run the Step 2 request, and expect the HTTP 400 naming `GITHUB_TOKEN` to return. Record that a credential row with an empty value remains in the database and that TP2's `DELETE /api/v1/packs/{id}/credentials/{key}` is what removes it properly.

- [ ] **Step 6: Record the results**

Create `docs/reviews/2026-09-13-tp1-credential-resolution-verification.md` containing the captured output of Steps 2, 3, 4 and 5, following the evidence discipline in `AGENTS.md`: every claim carries either a committed artifact path or a runnable command with its captured output. Report Step 4 as a partial verification and state explicitly that a full SDD run through to a real GitHub push remains NOT VERIFIED, because no valid GitHub token is available in this environment.

Two environment facts to note in that file:
- The backend holds an H2 file lock on `agent-control-tower/act-app/data/act_db.mv.db` while it runs, so `DevSqlControllerH2ProfilePresenceTest` errors in the unit lane (H2 SQL 90020) whenever the stack is up. This is why the verification below is done with the stack running and no Java test suite is run at the same time.
- `mvn test -pl act-app` does NOT run the `*IntegrationTest` classes — Surefire excludes them and Failsafe runs them. The correct command for that lane is `mvn verify -pl act-app -Dspring.profiles.active=h2`.

- [ ] **Step 7: Commit only the new evidence file**

```bash
git add docs/reviews/2026-09-13-tp1-credential-resolution-verification.md
git commit -m "docs: record TP1 credential-resolution verification"
```

Do NOT run `git add docs/` — the working tree holds unrelated uncommitted documentation (the E2E report and its screenshots, this plan, and the design spec) that is not yours to commit.

---

## Self-review notes

- **Spec coverage.** TP1's five items map to tasks as follows: resolve via the credential store → Task 2; `isAvailable()` → Task 1; the pre-flight gate in `WorkflowTemplateService` → Task 3; the `GH_TOKEN` → `GITHUB_TOKEN` normalisation → Task 2 Step 5; tests for resolution precedence and the actionable error → Task 2 Step 1 and Task 3 Step 2. Verification → Task 4.
- **Deliberately out of scope.** The review-gate decision mechanism, the tool configuration surface, requirement declarations and the `ToolExecutionResult` outcome kind all belong to TP2–TP4 and are not planned here.
- **Known gap, stated rather than hidden.** Task 4 cannot prove a complete SDD handoff to GitHub; it proves the credential precondition and the fail-fast gate. A real end-to-end push needs a valid token and is reported NOT VERIFIED.

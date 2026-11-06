# SDD Git Pipeline Artifact Flow & Verdict Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement spec `docs/superpowers/specs/2026-08-15-sdd-git-pipeline-artifact-flow-design.md` (D-A..D-E): Git branch handoff for SDD steps, spec traveling with the branch, VERDICT= marker fallback, V45 prompt corrections - with tests covering the ENTIRE workflow cycle (instantiate -> BA -> approve -> branch -> Dev -> fallback -> QA -> verdict routing), not just isolated units.

**Architecture:** Chain-scoped temporary branch `sdd/<chainId>` carries the spec (spec/spec.md) and Dev code. `GitBranchService` (pure GitHub REST API, GH_TOKEN) is the deterministic backend channel; agent git push/pull is the primary path with a backend-driven commit+push fallback. Verdict arrives via submit_dod_review OR the VERDICT= marker, both routed identically.

**Tech Stack:** Java 21 / Spring Boot 3.3, WireMock (existing test dep), Jackson, java.net.http, Flyway H2 migrations.

---

### Task 1: GitBranchService - GitHub REST API client

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchService.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/git/GitBranchServiceTest.java`

- [ ] **Step 1: Write the failing tests (WireMock)**

In `GitBranchServiceTest.java` (mirror OpenCodeHttpClientRetryTest WireMock setup):

```java
@ExtendWith(MockitoExtension.class)
class GitBranchServiceTest {
    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }
    @AfterEach
    void tearDown() { wireMock.stop(); }

    @Test
    void createBranch_createsRefFromDefaultBranch() { ... }
    @Test
    void putFile_base64EncodesContent() { ... }
    @Test
    void getFile_decodesBase64Content() { ... }
    @Test
    void branchHeadSha_returnsHeadSha() { ... }
    @Test
    void putFile_maps401toDomainException() { ... }
}
```

Cover the GitHub API shapes:
- `createBranch(repoUrl, branchName)`: GET `/repos/{owner}/{repo}` -> `default_branch`; GET `/repos/{owner}/{repo}/git/ref/heads/{default}` -> `object.sha`; POST `/repos/{owner}/{repo}/git/refs` body `{"ref": "refs/heads/sdd/<id>", "sha": "<base sha>"}`.
- `putFile(repoUrl, branchName, path, content, message)`: PUT `/repos/{owner}/{repo}/contents/{path}` body `{"message", "content" (base64), "branch"}`.
- `getFile(...)`: GET contents path with `?ref=branch` -> `content` base64 -> decode.
- `branchHeadSha(repoUrl, branchName)`: GET `/repos/{owner}/{repo}/git/ref/heads/{branch}` -> `object.sha`; 404 -> empty Optional.

Design notes: repoUrl parsing (https://github.com/owner/repo.git -> owner/repo); GH_TOKEN passed to the constructor (NOT read from env inside the class - injectable for tests); error mapping (401 -> GitBranchException with clear message).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl act-execution -Dtest=GitBranchServiceTest -DfailIfNoTests=false`
Expected: FAIL - class does not exist.

- [ ] **Step 3: Implement GitBranchService**

`io.aria.conductor.execution.git.GitBranchService`:
- Constructor: `GitBranchService(String ghToken)` + static factory `fromEnvironment()` reading `System.getenv("GH_TOKEN")` (or a Spring bean config reading `${GH_TOKEN:}`).
- `createBranch(String repoUrl, String branchName)` - throws GitBranchException on API error/401.
- `putFile(String repoUrl, String branchName, String path, String content, String commitMessage)`.
- `Optional<String> getFile(String repoUrl, String branchName, String path)`.
- `Optional<String> branchHeadSha(String repoUrl, String branchName)` - empty when branch missing.
- `GitBranchException extends RuntimeException` with status + message.
- Jackson ObjectMapper for JSON; java.net.http client with 30s timeout.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl act-execution -Dtest=GitBranchServiceTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/git/GitBranchService.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/git/GitBranchServiceTest.java
git commit -m "feat(sdd): GitBranchService - GitHub REST client for chain branch handoff (D-A)"
```

---

### Task 2: Verdict marker fallback in WorkflowAutoChainer

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/WorkflowAutoChainer.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/listener/WorkflowAutoChainerSddTest.java`

- [ ] **Step 1: Write the failing tests**

In `WorkflowAutoChainerSddTest.java` add (mock the QA-completed event with finalOutput containing the marker, NO tool verdict):

```java
@Test
void qaCompletion_verdictMarkerPass_routesToPassWithoutToolCall() { ... }
@Test
void qaCompletion_verdictMarkerDefect_reschedulesDev() { ... }
@Test
void qaCompletion_verdictMarkerSpecGap_reschedulesBa() { ... }
@Test
void qaCompletion_noMarkerNoToolCall_failsWithRetryHint() { ... } // existing assertion stays
```

Assertions: marker routes produce the SAME chain state transitions and DoD record updates as the tool path (compare with the existing tool-path tests: chain COMPLETED for PASS, Dev step re-scheduled for DEFECT, BA step re-scheduled for SPEC_GAP, DoD stage updated).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl act-execution -Dtest=WorkflowAutoChainerSddTest -DfailIfNoTests=false`
Expected: FAIL - marker ignored, chain fails.

- [ ] **Step 3: Implement**

In `WorkflowAutoChainer.java`:
1. Extract the existing verdict-routing logic from the tool-verdict branch into `private void applyVerdict(WorkflowChain chain, QaVerdict verdict, String reason)` (shared by tool path AND marker path - verify by diffing behavior, do not duplicate).
2. Add `private static Optional<QaVerdict> parseVerdictMarker(String output)` - regex `VERDICT\s*=\s*(PASS|DEFECT|SPEC_GAP)`, case-insensitive, first match wins.
3. In the no-verdict branch: try `parseVerdictMarker(finalOutput)`; when present -> `applyVerdict(chain, verdict, "verdict from output marker")`; when absent -> keep the existing failure message.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl act-execution -Dtest=WorkflowAutoChainerSddTest -DfailIfNoTests=false`
Expected: PASS. Then full module `mvn test -pl act-execution -am -q` green.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/WorkflowAutoChainer.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/listener/WorkflowAutoChainerSddTest.java
git commit -m "feat(sdd): VERDICT= marker fallback routed like tool verdict (D-B, R7-F2)"
```

---

### Task 3: V45 template prompts (DEV clone+push, QA clone+verdict marker)

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V45__sdd_pipeline_prompts.sql`
- Modify: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/V43SeedConfigTest.java`

- [ ] **Step 1: Read V44 to get exact stored strings**

Read `agent-control-tower/act-app/src/main/resources/db/migration/V44__sdd_prompt_issue_guidance.sql` and use its DEV/QA from-strings byte-for-byte in V45.

- [ ] **Step 2: Write the failing test assertions**

In `V43SeedConfigTest.java` add `v45_pipelinePrompts_presentInTemplateYaml`: load the template yaml_content and assert:
- DEV prompt contains `git clone --branch {branchName} {repoUrl}`, `spec/spec.md`, `git push origin {branchName}`, `Do NOT claim tests passed`
- QA prompt contains `git clone --branch {branchName} {repoUrl}`, `VERDICT=<PASS|DEFECT|SPEC_GAP>`

- [ ] **Step 3: Run to verify it fails**

Run: `mvn test -pl act-app -Dtest=V43SeedConfigTest -DfailIfNoTests=false`
Expected: FAIL - V45 texts absent.

- [ ] **Step 4: Write V45 migration**

`V45__sdd_pipeline_prompts.sql` (template item id `d0000001-0000-0000-0000-000000000001`; from-strings copied exactly from V44 output):

```sql
-- V45: SDD pipeline prompts - branch-scoped checkout + verdict marker.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    '<V44-dev-prompt-exact-text>',
    'Check out the project first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Read the spec at /workspace/repo/spec/spec.md and implement ONLY what it requires. Make code changes inside /workspace/repo. Run the real test commands and report their actual output. Do NOT claim tests passed unless you ran them and saw them pass. When done: git add -A && git commit -m ''sdd dev'' && git push origin {branchName}.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    '<V44-qa-prompt-exact-text>',
    'Check out the work first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Verify the code in /workspace/repo against the spec at /workspace/repo/spec/spec.md. Run the real tests and record their actual results. Write your findings to /workspace/qa_report.md. Submit your verdict with the submit_dod_review tool AND end your output with VERDICT=<PASS|DEFECT|SPEC_GAP> then REPORT_ID=<uuid>.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn test -pl act-app -Dtest=V43SeedConfigTest,MigrationIntegrationTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V45__sdd_pipeline_prompts.sql agent-control-tower/act-app/src/test/java/io/aria/conductor/app/V43SeedConfigTest.java
git commit -m "feat(sdd): V45 pipeline prompts - branch checkout, real tests, verdict marker (D-C)"
```

---

### Task 4: branchName system placeholder injection at instantiation

**Files:**
- Modify: `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/WorkflowTemplateService.java`
- Modify: `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/converter/WorkflowTemplateConverter.java` (SYSTEM_PLACEHOLDERS - add branchName)
- Test: `WorkflowTemplateServiceTest` / `SddWorkflowIntegrationTest`

- [ ] **Step 1: Write the failing tests**

In `SddWorkflowIntegrationTest` (or WorkflowTemplateServiceTest) add:
- `instantiateTemplate_injectsBranchNameSystemPlaceholder`: after instantiateTemplate, the chain's steps JSON contains `sdd/<chainId>` in the DEV/QA prompts.
- `instantiateTemplate_rejectsBranchNameInCallerParams`: caller passing branchName param is rejected (system placeholder protection, same as specRef).

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl act-app -Dtest=SddWorkflowIntegrationTest -DfailIfNoTests=false` (or the service test module command)
Expected: FAIL.

- [ ] **Step 3: Implement**

1. `WorkflowTemplateConverter.SYSTEM_PLACEHOLDERS`: add `branchName` (same treatment as specRef: excluded from extractParameterNames, substituted by the system).
2. `WorkflowTemplateService.instantiateTemplate`: after the chain id is generated, inject `branchName -> "sdd/" + chainId` into the parameter map for substitution (system-side, not from caller params; caller-supplied branchName stays rejected by the existing whitelist logic).

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl act-app -Dtest=SddWorkflowIntegrationTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/WorkflowTemplateService.java agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/converter/WorkflowTemplateConverter.java agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/SddWorkflowIntegrationTest.java
git commit -m "feat(sdd): inject branchName system placeholder at instantiation (D-D)"
```

---

### Task 5: Spec approval creates the branch and commits the spec

**Files:**
- Modify: `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java`
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml` (expose GH_TOKEN to backend - verify it is already readable via `${GH_TOKEN:}`; add a binding if needed)
- Test: `agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinatorTest.java`

- [ ] **Step 1: Write the failing tests**

In `SpecReviewCoordinatorTest.java` (mock GitBranchService):
- `onApproved_createsBranchAndCommitsSpec`: approval APPROVED -> GitBranchService.createBranch(repoUrl, "sdd/<chainId>") then putFile(repoUrl, branch, "spec/spec.md", specContent, ...) - assert the spec content passed is the CLEANED content (cleanSpecContent output).
- `onApproved_gitFailure_failsTransitionLoudly`: createBranch throws GitBranchException -> the approval handler propagates/fails the transition (assert exception or FAILED state per current handler style).
- Repo URL source: where does the coordinator get repoUrl? The chain's template parameters. Check how instantiateTemplate stores parameters (WorkflowChain entity / steps JSON) - if repoUrl is not persisted, persist it at instantiation (add to the chain's metadata or steps). If a storage gap exists, add the minimal field (e.g. chain name/metadata JSON) in this task.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn test -pl act-knowledge -Dtest=SpecReviewCoordinatorTest -DfailIfNoTests=false`
Expected: FAIL - GitBranchService not wired.

- [ ] **Step 3: Implement**

1. `SpecReviewCoordinator`: inject `GitBranchService` (constructor param - update Spring wiring; act-knowledge already depends on act-execution so the class is visible).
2. In the APPROVED branch (before `advanceWorkflow`): resolve repoUrl from the chain's stored parameters; `gitBranchService.createBranch(repoUrl, "sdd/" + chain.getId())`; `gitBranchService.putFile(repoUrl, branch, "spec/spec.md", cleanedSpec, "sdd: approve spec")`.
3. GitBranchService bean: create in act-execution config (`AdkProviderConfig` or a new `GitConfig`) reading `${GH_TOKEN:}`; skip bean creation (or use a no-op variant) when GH_TOKEN is blank so non-GitHub environments still boot - log a warning.
4. If repoUrl is not persisted at instantiation: add minimal persistence (see Step 1 note).

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl act-knowledge -Dtest=SpecReviewCoordinatorTest -DfailIfNoTests=false`
Expected: PASS. Full `mvn test -pl act-knowledge -am -q` green.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinatorTest.java
git commit -m "feat(sdd): spec approval creates sdd branch and commits spec.md (D-A)"
```

---

### Task 6: Dev-completion backend push fallback

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/WorkflowAutoChainer.java` (Dev-step completion handler)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java` (expose a run-command-in-sandbox method)
- Test: `WorkflowAutoChainerSddTest.java` + `OpenCodeAdkProviderTest.java`

- [ ] **Step 1: Write the failing tests**

In `WorkflowAutoChainerSddTest.java`:
- `devCompletion_noNewCommit_triggersBackendPush`: Dev step completed, GitBranchService.branchHeadSha returns the base sha (no Dev commit) -> the chainer invokes the sandbox git commit+push command (verify via mocked provider/manager).
- `devCompletion_branchHeadAdvanced_skipsFallback`: branchHeadSha differs from the spec-commit sha -> no backend push call.
In `OpenCodeAdkProviderTest.java`:
- `runCommandInSandbox_delegatesToManager`: provider exposes `runSandboxCommand(agentId, cmd)` -> sandboxManager.commands().run(cmd).

- [ ] **Step 2: Run to verify they fail**

Run: `mvn test -pl act-execution -Dtest=WorkflowAutoChainerSddTest,OpenCodeAdkProviderTest -DfailIfNoTests=false`
Expected: FAIL.

- [ ] **Step 3: Implement**

1. `OpenCodeSandboxManager`: add `public String runCommand(String sandboxId, String command)` delegating to `sandbox.commands().run(command)` and returning output (verify the Execution model accessor used in diagnose()).
2. `OpenCodeAdkProvider`: add `public String runSandboxCommand(UUID agentId, String command)` -> resolves the instance and delegates.
3. `WorkflowAutoChainer` Dev-completion handler: after the Dev step completes, `branchHeadSha` vs the sha recorded at spec approval (store the spec-commit sha - e.g. in the DoD record metadata or a chain field; simplest: capture the HEAD sha right after putFile in Task 5 and store it in the DoD record/chain). If unchanged -> `provider.runSandboxCommand(devAgentId, "cd /workspace/repo && git add -A && git commit -m 'sdd dev (backend fallback)' && git push origin " + branchName)` - log the outcome; failures are logged loudly but do not crash the chain (QA will see an empty branch and can verdict accordingly).

- [ ] **Step 4: Run to verify they pass**

Run: `mvn test -pl act-execution -Dtest=WorkflowAutoChainerSddTest,OpenCodeAdkProviderTest -DfailIfNoTests=false` then full `mvn test -pl act-execution -am -q`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/WorkflowAutoChainer.java agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManager.java
git commit -m "feat(sdd): backend push fallback when Dev does not push (D-A insurance path)"
```

---

### Task 7: FULL-CYCLE integration test - the whole workflow loop

**Files:**
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/GitPipelineIntegrationTest.java`

This task is the user's explicit requirement: tests covering the ENTIRE workflow cycle end-to-end.

- [ ] **Step 1: Write the failing test (one full cycle)**

`GitPipelineIntegrationTest extends BaseH2IntegrationTest` (mock ADK like SddWorkflowIntegrationTest; mock GitBranchService bean; WireMock not needed - mock at the service bean level):

```java
@Test
void fullCycle_ba_approve_devFallback_qaMarkerPass_completesChain() {
    // 1. instantiateTemplate with issueRef/issueRepo/repoUrl
    //    -> assert DEV/QA prompts contain sdd/<chainId> (branchName injected)
    // 2. BA step completes (mock ADK) -> SPEC_REVIEW approval PENDING
    // 3. approve via coordinator -> verify GitBranchService.createBranch + putFile("spec/spec.md", specBody)
    // 4. Dev step completes (mock ADK, no git push) -> branchHeadSha unchanged
    //    -> verify backend fallback runSandboxCommand("...git commit...git push...") invoked
    // 5. QA step completes with output containing VERDICT=PASS and NO tool verdict
    //    -> chain reaches COMPLETED, DoD stage reflects pass
}

@Test
void fullCycle_verdictDefect_reschedulesDev() { /* steps 1-4 reused, QA marker VERDICT=DEFECT -> Dev step re-scheduled */ }

@Test
void fullCycle_verdictSpecGap_reschedulesBa() { /* QA marker VERDICT=SPEC_GAP -> BA re-scheduled */ }

@Test
void fullCycle_noMarkerNoTool_failsChainWithHint() { /* QA output without marker/tool -> chain FAILED with submit_dod_review hint */ }
```

Reuse the mock-ADK and Awaitility patterns from SddWorkflowIntegrationTest (createTemplate, configureMockAdk, chain polling). Verify the current createSddChain helper supports repoUrl/branchName params (extend createTemplate to include the new placeholders).

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl act-app -Dtest=GitPipelineIntegrationTest -DfailIfNoTests=false`
Expected: FAIL (class missing or assertions failing until Tasks 1-6 land - note: run this AFTER Tasks 1-6 are committed).

- [ ] **Step 3: Iterate until green**

Fix any wiring gaps the full-cycle test exposes (e.g. missing bean config, repoUrl persistence, DoD metadata for the spec-commit sha).

Run: `mvn test -pl act-app -Dtest=GitPipelineIntegrationTest -DfailIfNoTests=false`
Expected: PASS - all 4 cycle variants green.

- [ ] **Step 4: Full regression**

Run: `mvn test -pl act-app -am -q` green; `mvn test -pl act-execution -am -q` green.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/GitPipelineIntegrationTest.java
git commit -m "test(sdd): full-cycle integration - instantiate to verdict routing, all 4 verdict paths"
```

---

### Task 8: MCP e2e driver extension (nightly)

**Files:**
- Modify: `scripts/sdd-mcp-e2e.mjs`

- [ ] **Step 1: Extend the driver steps**

After the existing step 7 (APPROVE), add:
8. Poll the Git branch state via `gh api repos/{owner}/{repo}/branches/sdd/<chainId>` (or the backend diagnosis endpoint) - assert `spec/spec.md` exists (spec traveled with the branch).
9. After Dev completes, assert the branch HEAD advanced (Dev pushed OR backend fallback committed).
10. After QA completes, assert the chain reaches a terminal state consistent with the verdict marker (PASS -> COMPLETED with a QA report; or DEFECT/SPEC_GAP -> rescheduled steps).

- [ ] **Step 2: Verify syntax**

Run: `node --check scripts/sdd-mcp-e2e.mjs` -> clean.

- [ ] **Step 3: Commit**

```bash
git add scripts/sdd-mcp-e2e.mjs
git commit -m "test(sdd): MCP e2e driver asserts branch artifact flow (nightly)"
```

---

## Dependencies

```
T1 (GitBranchService) -> T5 (coordinator wiring), T6 (fallback head check)
T2 (verdict marker)   independent
T3 (V45 prompts)      independent
T4 (branchName injection) independent (needed by T7 prompts assertions)
T5 (approval -> branch) depends on T1
T6 (backend push fallback) depends on T1 (+ T5's recorded sha)
T7 (full-cycle test) depends on T1-T6 - run after all land
T8 (MCP driver) depends on T1-T7
Merge order: T1 -> (T2/T3/T4 parallel) -> T5 -> T6 -> T7 -> T8
```

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| V45 from-strings mismatch with V44 stored text | Task 3 Step 1 reads V44 SQL first; MigrationIntegrationTest backstops |
| GH_TOKEN blank in non-GitHub environments | GitBranchService bean skipped with warning; sandbox path unaffected |
| repoUrl not persisted at instantiation | Task 5 Step 1 notes the check; add minimal metadata storage if missing |
| spec-commit sha storage for fallback comparison | Store HEAD sha in DoD record metadata right after putFile (Task 5); Task 6 compares against it |
| act-knowledge -> act-execution dependency direction | Verified existing: SpecReviewCoordinator already imports execution repositories |
| Sandbox commands().run blocking during fallback | It runs after the Dev step completes (no concurrent task); 30s timeout via SDK Execution model if needed |
| Full-cycle test flakiness (async chains) | Reuse SddWorkflowIntegrationTest Awaitility patterns; mock ADK instant responses |

## Rejected Alternatives

1. **Chain-shared sandbox**: rejected by user in favor of Git handoff (more realistic workflow, versioned artifacts).
2. **Spec inline in prompt**: rejected by user - token bloat; spec travels with the branch instead.
3. **File read-back fallback (SDK files().read)**: replaced by backend-driven `git commit+push` command inside the sandbox - simpler and produces a real commit.
4. **LLM-inferred verdict**: rejected by user - marker parsing is deterministic (same pattern as SPEC_ID/REPORT_ID).

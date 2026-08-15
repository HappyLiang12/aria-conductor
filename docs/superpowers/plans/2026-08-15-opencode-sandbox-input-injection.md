# OpenCode Sandbox Input Injection & Feedback Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement spec `docs/superpowers/specs/2026-08-14-opencode-sandbox-input-injection-design.md` (D1-D7): reusable agent image with gh, aria-conductor-owned GH_TOKEN injection, opencode.json with question=deny + DB-active-provider model propagation, gh-based issue fetching + git clone prompts, SPEC_REVIEW feedback loop, and config/diagnosis fixes.

**Architecture:** Reusable template image (opencode + git + gh, no credentials) instantiated per agent; credentials and config injected per-sandbox by aria-conductor (sandbox-env + workspace opencode.json). Feedback loop reuses the existing SPEC_REVIEW REJECT -> rescheduleStep chain (no opencode question tool - it has no answer API).

**Tech Stack:** Java 21 / Spring Boot 3.3, OpenSandbox SDK 1.0.18, opencode-ai@1.18.15, node:22-slim base image, H2/Flyway migrations, PowerShell scripts.

---

### Task 1: D1 - Install gh CLI in the sandbox image + bump tag to 1.1

**Files:**
- Modify: `agent-control-tower/opencode-sandbox/Dockerfile`
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml` (image tag)
- Modify: `agent-control-tower/opencode-sandbox/README.md` (build + tag notes)

- [ ] **Step 1: Update the Dockerfile**

In `agent-control-tower/opencode-sandbox/Dockerfile`, replace the curl-only apt install block (L9-10) with:

```dockerfile
# curl is required by the OpenSandbox server-side proxy path
# (GET /v1/sandboxes/{id}/proxy/{port} forwards via `docker exec ... curl`).
# gh CLI lets agents read issues and clone repos headlessly (GH_TOKEN injected
# per-sandbox by aria-conductor; the image itself embeds NO credentials).
RUN apt-get update && apt-get install -y --no-install-recommends curl gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
         -o /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
         > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y gh \
    && rm -rf /var/lib/apt/lists/*
```

- [ ] **Step 2: Bump the image tag**

In `agent-control-tower/act-app/src/main/resources/application.yml`, change:

```yaml
  image: aria-conductor/opencode-sandbox:1.0
```

to:

```yaml
  image: aria-conductor/opencode-sandbox:1.1
```

- [ ] **Step 3: Update README build instructions**

In `agent-control-tower/opencode-sandbox/README.md`, update the build command to `docker build -t aria-conductor/opencode-sandbox:1.1 .` and add one line: "The image is a reusable agent template (opencode + git + gh); credentials are injected per sandbox by aria-conductor, never baked in."

- [ ] **Step 4: Verify image build**

Run: `docker build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox/`
Expected: build succeeds; then `docker run --rm aria-conductor/opencode-sandbox:1.1 gh --version` prints a version (smoke test).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/opencode-sandbox/Dockerfile agent-control-tower/opencode-sandbox/README.md agent-control-tower/act-app/src/main/resources/application.yml
git commit -m "feat(sandbox): install gh CLI in reusable agent image, tag 1.1 (D1)"
```

---

### Task 2: D2 - GH_TOKEN injection + preflight warning

**Files:**
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml`
- Modify: `scripts/start-backend.ps1`
- Modify: `scripts/start-backend.sh` (parallel injection if present)

- [ ] **Step 1: Inject GH_TOKEN into sandbox env**

In `application.yml`, inside `opencode.sandbox-env:` (next to the existing DEEPSEEK_API_KEY line) add:

```yaml
  sandbox-env:
    DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY:}
    LLM_API_KEY: ${LLM_API_KEY:${DEEPSEEK_API_KEY:}}
    GH_TOKEN: ${GH_TOKEN:}
```

(LLM_API_KEY is added here because D3's opencode.json references `{env:LLM_API_KEY}` for OpenAI-compatible providers.)

- [ ] **Step 2: Preflight warning in start-backend.ps1**

In `scripts/start-backend.ps1`, after the DEEPSEEK_API_KEY preflight block (L47-51), add:

```powershell
if (-not $env:GH_TOKEN) {
    Write-Warning "GH_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox."
}
```

Also add the same warning in `scripts/start-backend.sh` next to its DEEPSEEK_API_KEY check, with bash syntax (`if [ -z "$GH_TOKEN" ]; then echo "WARN: ..."; fi`).

- [ ] **Step 3: Verify**

Run: PowerShell parse check `[System.Management.Automation.Language.Parser]::ParseFile('C:\...\scripts\start-backend.ps1', [ref]$null, [ref]$null)` -> no errors. `bash -n scripts/start-backend.sh` if bash is available.

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/application.yml scripts/start-backend.ps1 scripts/start-backend.sh
git commit -m "feat(sdd): inject GH_TOKEN/LLM_API_KEY into sandbox env + preflight warning (D2)"
```

---

### Task 3: D3 - opencode.json generation (question=deny + active provider model)

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProviderTest.java`

Design: `prepareInstance` writes an `opencode.json` into the agent workspace dir BEFORE `uploadWorkspace` runs. Content is generated from the ACTIVE DB LlmProvider (`LlmProviderRepository.findByActiveTrue()`, repository in `io.aria.conductor.agent.repository`, already a dependency of act-execution) with fallback to deepseek defaults.

- [ ] **Step 1: Write the failing tests**

In `OpenCodeAdkProviderTest.java`, add:

```java
@Test
void prepareInstance_writesOpenCodeJsonWithQuestionDeniedAndActiveProvider() throws Exception {
    LlmProvider active = LlmProvider.builder().name("deepseek").type(LlmProviderType.OPENAI_COMPATIBLE)
            .baseUrl("https://api.deepseek.com/v1").defaultModel("deepseek-v4-flash")
            .apiKey("k").active(true).build();
    when(providerRepository.findByActiveTrue()).thenReturn(Optional.of(active));
    UUID agentId = UUID.randomUUID();
    when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-1");
    when(sandboxManager.getSandboxUrl("sb-1", 4096)).thenReturn("http://127.0.0.1:4096");
    when(httpClient.isHealthy()).thenReturn(true);

    provider.prepareAgent(agentId, agent(agentId));

    String json = Files.readString(tempDir.resolve(agentId.toString()).resolve("opencode.json"));
    assertThat(json).contains("\"question\": \"deny\"");
    assertThat(json).contains("deepseek/deepseek-v4-flash");
    assertThat(json).contains("https://api.deepseek.com/v1");
}

@Test
void prepareInstance_usesDeepseekDefaultsWhenNoActiveProvider() throws Exception {
    when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
    UUID agentId = UUID.randomUUID();
    when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-1");
    when(sandboxManager.getSandboxUrl("sb-1", 4096)).thenReturn("http://127.0.0.1:4096");
    when(httpClient.isHealthy()).thenReturn(true);

    provider.prepareAgent(agentId, agent(agentId));

    String json = Files.readString(tempDir.resolve(agentId.toString()).resolve("opencode.json"));
    assertThat(json).contains("deepseek/deepseek-chat");
    assertThat(json).contains("\"question\": \"deny\"");
}
```

Also add the mock field: `@Mock LlmProviderRepository providerRepository;` and imports (`io.aria.conductor.agent.repository.LlmProviderRepository`, `io.aria.conductor.common.model.LlmProvider`, `io.aria.conductor.common.model.LlmProviderType`, `java.nio.file.Files`, `java.util.Optional`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl act-execution -Dtest=OpenCodeAdkProviderTest -DfailIfNoTests=false`
Expected: FAIL - compile error `cannot find symbol: providerRepository` (constructor does not take it yet).

- [ ] **Step 3: Implement opencode.json generation**

In `OpenCodeAdkProvider.java`:

1. Add field + constructor params:
```java
private final LlmProviderRepository providerRepository;

public OpenCodeAdkProvider(OpenCodeProperties properties, LlmProviderRepository providerRepository) {
    this(properties, new OpenCodeSandboxManager(
            properties.getSandboxServerUrl(), properties.getSandboxApiKey()), null, providerRepository);
}

OpenCodeAdkProvider(OpenCodeProperties properties,
                    OpenCodeSandboxManager sandboxManager,
                    OpenCodeHttpClient httpClient,
                    LlmProviderRepository providerRepository) {
    this.properties = properties;
    this.sandboxManager = sandboxManager;
    this.fixedHttpClient = httpClient;
    this.providerRepository = providerRepository;
    // ... existing body
}
```
Update ALL existing `new OpenCodeAdkProvider(...)` call sites: the test setup (~L67) passes `mock(LlmProviderRepository.class)` or the `@Mock providerRepository`; the Spring configuration that constructs the bean (grep `new OpenCodeAdkProvider(properties)` - likely in `ExecutionModule`/`AdkProviderConfig`) passes the repository bean. Spring will auto-inject `LlmProviderRepository` into the public constructor.

2. In `prepareInstance` (after `Files.createDirectories(workspace)` succeeds, BEFORE `uploadWorkspace`), add:
```java
writeOpenCodeConfig(workspace);
```
with:
```java
private void writeOpenCodeConfig(Path workspace) {
    try {
        LlmProvider active = providerRepository.findByActiveTrue().orElse(null);
        String providerId = active != null && active.getName() != null && !active.getName().isBlank()
                ? active.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-")
                : "deepseek";
        String model = active != null && active.getDefaultModel() != null && !active.getDefaultModel().isBlank()
                ? active.getDefaultModel()
                : "deepseek-chat";
        String baseUrl = active != null && active.getBaseUrl() != null && !active.getBaseUrl().isBlank()
                ? active.getBaseUrl()
                : "https://api.deepseek.com/v1";
        String json = """
                {
                  "$schema": "https://opencode.ai/config.json",
                  "permission": { "question": "deny" },
                  "model": "%s/%s",
                  "provider": {
                    "%s": {
                      "options": {
                        "apiKey": "{env:LLM_API_KEY}",
                        "baseURL": "%s"
                      }
                    }
                  }
                }
                """.formatted(providerId, model, providerId, baseUrl);
        Files.writeString(workspace.resolve("opencode.json"), json);
        log.info("Wrote opencode.json for workspace {} (provider={}, model={})", workspace, providerId, model);
    } catch (IOException e) {
        log.warn("Could not write opencode.json to {}: {}", workspace, e.getMessage());
    }
}
```
3. The test-only constructor (3-arg, used by `setWorkspaceBaseForTest` flow) must now accept the repository too - replace the 3-arg constructor with the 4-arg one everywhere. Verify all usages compile.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl act-execution -Dtest=OpenCodeAdkProviderTest -DfailIfNoTests=false`
Expected: PASS (all OpenCodeAdkProviderTest tests green).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProviderTest.java
git commit -m "feat(sdd): inject opencode.json with question=deny and active provider model (D3, R4-F6)"
```

---

### Task 4: D7a - max-task-minutes 30 -> 45

**Files:**
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml`

- [ ] **Step 1: Change the value**

In `application.yml`, change `max-task-minutes: 30` (under `opencode:`, L43) to `max-task-minutes: 45`.

- [ ] **Step 2: Verify binding**

Run: `mvn test -pl act-execution -Dtest=OpenCodePropertiesBindingTest -DfailIfNoTests=false`
Expected: PASS (binding test covers the property).

- [ ] **Step 3: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/application.yml
git commit -m "fix(sdd): opencode max-task-minutes 30 -> 45 (R4-F1)"
```

---

### Task 5: D7b - Diagnosis /proc fallback + metrics JSON

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManager.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManagerTest.java`

- [ ] **Step 1: Write the failing tests**

In `OpenCodeSandboxManagerTest.java`, add:

```java
@Test
void diagnose_processFallback_readsProcWhenPsMissing() {
    // mock: commands().run("ps aux ...") throws; the manager falls back to /proc scan
    // assert the output contains "== processes ==" and the fallback marker or /proc entries
}

@Test
void diagnose_metricsSection_isJson() {
    // mock metrics to a known object; assert the section contains valid JSON keys (cpu/memory)
}
```

Concrete assertion style (adapt to the mock Sandbox setup already used in this test class): after `diagnose(sandboxId)` returns, `assertThat(result).contains("== processes ==")` and either `contains("fallback")` when the ps command throws, and `assertThat(result).contains("\"cpu\"")` (or whatever field name the SandboxMetrics model exposes - verify via javap first: `javap -cp ~/.m2/repository/com/alibaba/opensandbox/sandbox/1.0.18/sandbox-1.0.18.jar com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxMetrics`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl act-execution -Dtest=OpenCodeSandboxManagerTest -DfailIfNoTests=false`
Expected: FAIL on the new assertions.

- [ ] **Step 3: Implement**

In `OpenCodeSandboxManager.diagnose`:
1. Process section: when `commands().run("ps aux ...")` throws, run a fallback command `ls /proc | grep -E '^[0-9]+$' | head -30` (or read `/proc` directly) and mark the section `== processes (proc fallback) ==`. When the primary ps command succeeds but returns empty output, also try the fallback.
2. Metrics section: serialize via a JSON writer available in act-execution (ObjectMapper - `com.fasterxml.jackson.databind.ObjectMapper` is already used across the module; call `objectMapper.writeValueAsString(metrics)` guarded by try/catch, fallback to `metrics.toString()`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl act-execution -Dtest=OpenCodeSandboxManagerTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManager.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManagerTest.java
git commit -m "fix(sdd): diagnosis proc fallback and metrics JSON (R4-F5)"
```

---

### Task 6: D4/D5/D6 - V44 template prompt guidance

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V44__sdd_prompt_issue_guidance.sql`
- Modify: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/V43SeedConfigTest.java` (extend assertions; do NOT rename the class)

- [ ] **Step 1: Write the failing test assertions**

In `V43SeedConfigTest.java`, add a test `v44_promptGuidance_presentInTemplateYaml` that loads the development-workflow template yaml_content (same lookup the existing tests use) and asserts:
- BA prompt contains `gh issue view` and `## Questions` and `Spec was rejected`
- DEV prompt contains `git clone {repoUrl}`

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl act-app -Dtest=V43SeedConfigTest -DfailIfNoTests=false`
Expected: FAIL - guidance strings absent.

- [ ] **Step 3: Write V44 migration**

`V44__sdd_prompt_issue_guidance.sql` (targeting template item id `d0000001-0000-0000-0000-000000000001` like V43):

```sql
-- V44: SDD template prompt guidance for sandbox-based agents.
--   BA: fetch the issue via gh, emit Questions section instead of interactive asks.
--   DEV: clone the project independently before implementing.

-- BA prompt: replace the V43-extended prompt with the gh + Questions version.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Analyze issue {issueRef} and write a spec with sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error Handling. End your output with SPEC_ID=<uuid> after approval.',
    'Analyze issue {issueRef} and write a spec with sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error Handling. If the issue body is not already in your prompt, fetch it first with: gh issue view {issueRef} -R {issueRepo} --json title,body,labels (GH_TOKEN is already configured). If anything is ambiguous and requires the user to decide, end the spec with a ## Questions section listing each question on its own line; omit the section when nothing is ambiguous. NEVER ask interactive questions - put everything into the spec. If the task message contains rejection feedback (Spec was rejected: ...), incorporate the reviewer answers into the revised spec and drop the Questions section for answered items. End your output with SPEC_ID=<uuid> after approval.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- DEV prompt: clone guidance.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing.',
    'Check out the project first: git clone {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
```

Note: if the V43 REPLACE text does not match exactly (verify against V43__fix_sdd_seed_configs.sql lines 40-44 - the exact stored string), adjust the from-string accordingly. The migration must be idempotent-safe (Flyway runs once).

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl act-app -Dtest=V43SeedConfigTest,MigrationIntegrationTest -DfailIfNoTests=false`
Expected: PASS (V44 applies cleanly; seed assertions green).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V44__sdd_prompt_issue_guidance.sql agent-control-tower/act-app/src/test/java/io/aria/conductor/app/V43SeedConfigTest.java
git commit -m "feat(sdd): V44 template prompts - gh issue fetch, Questions section, git clone (D4/D5/D6)"
```

---

### Task 7: D6 - Aria prompt guidance (light-touch)

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java` (`buildSystemPrompt` SDD section)
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/init/AriaDefaultAgentInitializer.java` (`ARIA_SYSTEM_PROMPT` SDD section)

- [ ] **Step 1: Write the failing tests**

In the existing Aria prompt tests (grep `AriaKnowledgeContextTest` / `AriaService` prompt tests in act-aria), add assertions that the SDD prompt section contains "pass issueRepo" and "answer trivial questions" guidance (or add a new small test `sddPrompt_containsIssueRepoAndFeedbackGuidance`).

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl act-aria -Dtest=<thePromptTest> -DfailIfNoTests=false`
Expected: FAIL.

- [ ] **Step 3: Implement**

Append to the SDD sections of BOTH prompts (next to the existing "NEVER use create_workflow for the BA->Dev->QA loop; always use instantiate_template." line):

"Always pass issueRepo (owner/repo) and repoUrl parameters when instantiating the development-workflow template. When a SPEC_REVIEW rejection contains user answers, carry them into the resubmission; answer trivial questions yourself from the issue body before escalating to the user."

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl act-aria -Dtest=<thePromptTest> -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/init/AriaDefaultAgentInitializer.java
git commit -m "feat(sdd): Aria prompt guidance for issueRepo/repoUrl and rejection feedback (D6)"
```

---

### Task 8: MCP-layer verification - full SDD loop driven through Aria MCP tools

Aria exposes its orchestration surface as MCP tools (`packages/mcp-server`):
`aria_chat`, `create_workflow`, `list_workflows`, `get_workflow`,
`list_pending_approvals`, `get_approval`, `decide_approval`, `submit_knowledge`.
This task proves the D1-D7 fixes through that public surface.

**Files:**
- Create: `packages/mcp-server/src/__tests__/sdd-fixes.test.ts`
- Create: `scripts/sdd-mcp-e2e.ps1` (real-stack manual/nightly driver)

- [ ] **Step 1: Write the failing vitest tests (automated layer)**

In `packages/mcp-server/src/__tests__/sdd-fixes.test.ts` (mirror the http mock
pattern of `http-client.test.ts`; the MCP tools call `http.post`/`http.get`
against the backend REST API, so mock at the http layer):

```ts
import { describe, expect, it, vi } from 'vitest';
import * as http from '../http-client';
import { registerWorkflowTools } from '../tools/workflows';

// R-F4 via MCP: create_workflow with SDD kinds must surface the backend rejection.
it('create_workflow rejects SDD kinds with the governance message', async () => {
  const post = vi.spyOn(http, 'post').mockRejectedValue(
    new Error('400: SDD workflow steps (BA/DEV/QA) must be created via instantiate_template to ensure the SPEC_REVIEW gate'));
  const tools = registerWorkflowTools();
  const create = tools.find(t => t.name === 'create_workflow')!;
  const result = await create.handler({ name: 'x', agentIds: ['<uuid1>'], steps: [{ kind: 'BA' }] });
  expect(result.isError).toBe(true);
  expect(result.content?.[0]?.text).toContain('instantiate_template');
});

// D6 feedback loop via MCP: pending SPEC_REVIEW -> decide REJECT with answers.
it('decide_approval reject flows answers back to the BA reschedule', async () => {
  const post = vi.spyOn(http, 'post').mockResolvedValue({ approvalId: 'a1', status: 'processed' } as never);
  const tools = registerApprovalTools(); // import from ../tools/approvals
  const decide = tools.find(t => t.name === 'decide_approval')!;
  const result = await decide.handler({ approvalId: 'a1', approved: false, reason: 'Use postgres instead' });
  expect(result.isError).toBe(false);
  // reason must round-trip to the backend so SpecReviewCoordinator reschedules BA with it
  expect(post).toHaveBeenCalledWith('/api/v1/approvals/a1/decide', expect.objectContaining({ reason: 'Use postgres instead' }));
});

// Workflow state observation via MCP (used to watch the BA run without waiting blind).
it('get_workflow exposes chain state', async () => {
  const get = vi.spyOn(http, 'get').mockResolvedValue({ id: 'c1', status: 'RUNNING', steps: [] } as never);
  const tools = registerWorkflowTools();
  const getWf = tools.find(t => t.name === 'get_workflow')!;
  const result = await getWf.handler({ id: 'c1' });
  expect(result.isError).toBe(false);
});
```

Adjust tool names/handler signatures to the actual exports in
`packages/mcp-server/src/tools/*.ts` (inspect `workflows.ts` and `approvals.ts`
first - the handler contract is `{ ...params } => { isError, content }`).

- [ ] **Step 2: Run to verify they fail**

Run: `cd packages/mcp-server && npx vitest run src/__tests__/sdd-fixes.test.ts`
Expected: FAIL (file/tool registrations not wired yet, or assertions failing on
the current handler behavior).

- [ ] **Step 3: Implement/adapt until green**

If the tools already behave correctly (the fixes live in the backend), the
tests should go green once written; if a handler swallows errors or drops
fields, fix the handler minimally (e.g. propagate the backend 400 body).

Run: `cd packages/mcp-server && npx vitest run`
Expected: PASS (full MCP suite green).

- [ ] **Step 4: Write the real-stack MCP E2E driver**

Create `scripts/sdd-mcp-e2e.ps1` that drives the MCP stdio protocol against a
running stack (backend 8080 + OpenSandbox 8090 + MCP server via
`npx tsx packages/mcp-server/src/server.ts stdio`):

```powershell
# Prerequisites: scripts/start-backend.ps1 -AdkProvider opencode already ran;
# GH_TOKEN exported; an active DB LlmProvider exists.
$steps = @(
  'initialize + tools/list: assert create_workflow/aria_chat/decide_approval present',
  'create_workflow with a BA-kind step: EXPECT error containing instantiate_template (R-F4)',
  'aria_chat: "instantiate development-workflow with issueRef=#38 issueRepo=owner/repo repoUrl=https://github.com/owner/repo.git" -> capture chainId',
  'poll get_workflow: BA step RUNNING; optionally hit GET /api/v1/adk/opencode/sandboxes/{agentId}/diagnosis to watch the sandbox live',
  'poll list_pending_approvals until SPEC_REVIEW appears -> get_approval: spec content clean (no <tool_call> chatter) and optionally contains ## Questions (D4/D6)',
  'decide_approval REJECT with reason=answer text -> poll get_workflow: BA rescheduled (D6 feedback loop)',
  'after next SPEC_REVIEW: decide_approval APPROVE -> poll get_workflow: chain advances to DEV (D5 clone prompt exercised on the real stack)'
)
```

Write the script as a documented manual/nightly harness (JSON-RPC over stdio,
newline-delimited frames, `tools/call` per step). If implementing a full MCP
client in PowerShell is too heavy, implement it in Node: `scripts/sdd-mcp-e2e.mjs`
using `@modelcontextprotocol/sdk` `Client` over `StdioClientTransport` (the SDK
is already a dependency of packages/mcp-server). Mark it nightly-optional
(DEEPSEEK_API_KEY + GH_TOKEN gated).

- [ ] **Step 5: Verify the driver against the real stack**

Run: `scripts/sdd-mcp-e2e.mjs` (or .ps1) with the stack up.
Expected: steps 1-4 pass; step 5 shows a clean spec (this is the R4-F2 proof:
BA read the issue via gh and produced spec content); steps 6-7 prove the
feedback loop and Dev advancement.

- [ ] **Step 6: Commit**

```bash
git add packages/mcp-server/src/__tests__/sdd-fixes.test.ts scripts/sdd-mcp-e2e.mjs
git commit -m "test(sdd): MCP-layer verification - governance, feedback loop, real-stack E2E driver (Task 8)"
```

---

## Test plan (extended verification matrix)

| Layer | Test | Proves |
|-------|------|--------|
| Image smoke | `docker run --rm aria-conductor/opencode-sandbox:1.1 gh --version` | D1 gh installed |
| Image smoke | `docker run --rm -e GH_TOKEN=$GH_TOKEN aria-conductor/opencode-sandbox:1.1 sh -c "gh issue view 1 -R octocat/hello-world --json title \| head -5"` | D2 headless gh works |
| Unit | OpenCodeAdkProviderTest: opencode.json contains question=deny + active provider model | D3 |
| Unit | OpenCodeAdkProviderTest: fallback deepseek defaults when no active provider | D3 |
| Unit | OpenCodeSandboxManagerTest: diagnose /proc fallback + metrics JSON | D7b |
| Integration | V43SeedConfigTest: V44 BA prompt contains gh issue view + ## Questions + Spec was rejected; DEV prompt contains git clone {repoUrl} | D4/D5/D6 |
| Integration | MigrationIntegrationTest: V44 applies cleanly on H2 | D4/D5/D6 |
| MCP automated | sdd-fixes.test.ts: create_workflow SDD rejection surfaces through MCP | R-F4 via MCP |
| MCP automated | sdd-fixes.test.ts: decide_approval reason round-trips to backend | D6 via MCP |
| MCP E2E (real stack, nightly) | sdd-mcp-e2e.mjs: aria_chat -> instantiate with issueRepo/repoUrl -> BA completes -> SPEC_REVIEW -> REJECT with answers -> BA reschedule -> APPROVE -> Dev advances | full D1-D6 loop |
| Sandbox live check | GET /api/v1/adk/opencode/sandboxes/{agentId}/diagnosis during the BA run: metrics show CPU work + processes non-empty | no zombie; task alive |
| Regression | full `mvn test`, `pnpm vitest run` (mcp-server), Playwright suites, Python pytest | no regressions |

## Dependencies

```
T1 (image)  independent
T2 (env)    independent
T3 (opencode.json)  independent (constructor change - touches AdkProviderConfig wiring)
T4 (yml 45) independent
T5 (diagnosis) independent
T6 (V44)    independent (depends on V43's stored prompt text - verify exact strings)
T7 (Aria prompts) independent
Merge order: any; recommended T1 -> T2 -> T3 -> T4 -> T5 -> T6 -> T7 -> T8 (T8 is the integration gate: it needs the stack + all fixes)
```

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| V44 REPLACE from-string mismatch with V43 stored text | Verify the exact stored string in V43 SQL before writing V44; MigrationIntegrationTest backstops |
| OpenCodeAdkProvider constructor change breaks Spring wiring | grep all `new OpenCodeAdkProvider` call sites (ExecutionModule/AdkProviderConfig/test setup) and update all |
| gh apt repo unavailable at image build time | Retry build; pin the keyring URL; if offline, fall back to downloading the gh release binary into /usr/local/bin |
| D3 uses active DB provider but sandbox runs headless | Fallback to deepseek defaults keeps first-run working without a DB provider |
| New template params {issueRepo}/{repoUrl} break instantiateTemplate whitelist | The whitelist rejects UNKNOWN keys - verify the template declares these params in YAML (add them in the same V44 REPLACE if the template has a parameters section; if the whitelist validates against placeholder names extracted from the YAML, the REPLACE introducing the placeholders also declares them) |
| Tests use the 3-arg constructor | Replace with 4-arg constructor + @Mock LlmProviderRepository everywhere |

## Rejected Alternatives

1. **opencode question-answer round trip** (respond to the question tool via messageID): closed upstream (issues #19702/#27644), speculative - rejected in favor of the SPEC_REVIEW feedback loop (D6).
2. **SSE stream monitoring** (GET /event): version-specific bugs (#26697/#27966) - deferred.
3. **Mounting the repo directly into the sandbox**: SDK text-upload limits (4MB/file, depth 3) make it impractical - rejected in favor of git clone (D5).
4. **Baking GH_TOKEN into the image**: credential leak risk - rejected; per-sandbox env injection (D2).

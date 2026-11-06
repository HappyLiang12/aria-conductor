# Plan Inputs — MCP-into-opencode (Phase 1 Boot 3.3→3.4 + Phase 2 backend-embedded MCP)

Research snapshot. All excerpts are **verbatim** from the working tree at branch `fix/e2e-db-isolation`; every excerpted file was verified identical to `origin/main` via `git diff origin/main --stat -- <files>` (empty diff = branch-agnostic). Line numbers refer to the current working tree.

---

## 1. Root pom + act-app pom

### 1a. `agent-control-tower/pom.xml` — parent declaration (lines 7-18)

```xml
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>io.aria.conductor</groupId>
    <artifactId>agent-control-tower</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Aria Conductor</name>
```

**Dependency-management approach:** it uses `spring-boot-starter-parent` (NOT a BOM import). Spring Boot version is set ONLY here (line 10, `<version>3.3.5</version>` inside `<parent>`) — there is no separate `<spring-boot.version>` property. Upgrading = change this one literal.

### 1b. `<modules>` list (lines 20-29)

```xml
    <modules>
        <module>act-common</module>
        <module>act-agent</module>
        <module>act-execution</module>
        <module>act-knowledge</module>
        <module>act-aria</module>
        <module>act-dashboard-api</module>
        <module>act-app</module>
        <module>act-test-support</module>
    </modules>
```

NOTE: `act-app` precedes `act-test-support` in the list; a new `act-mcp` module entry must be inserted before `act-app` (or anywhere before it, since act-app depends on the new module).

### 1c. `<properties>` block (lines 31-52, full)

```xml
    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <springdoc.version>2.6.0</springdoc.version>
        <caffeine.version>3.1.8</caffeine.version>
        <logstash-logback.version>7.4</logstash-logback.version>
        <archunit.version>1.3.0</archunit.version>
        <jqwik.version>1.9.1</jqwik.version>
        <pitest.version>1.16.1</pitest.version>
        <pitest-junit5.version>1.2.1</pitest-junit5.version>
        <!-- JaCoCo ratchet: per-module overrides raise these; never set above measured coverage -->
        <jacoco.line.minimum>0</jacoco.line.minimum>
        <jacoco.branch.minimum>0</jacoco.branch.minimum>
        <!-- CI lane switches: unit lane sets skip.integration.tests, integration lane sets skip.unit.tests.
             Both default to ${skipTests} so plain `mvn install -DskipTests` still skips everything
             (explicit <skipTests> in plugin config would otherwise override the CLI flag). -->
        <skipTests>false</skipTests>
        <skip.unit.tests>${skipTests}</skip.unit.tests>
        <skip.integration.tests>${skipTests}</skip.integration.tests>
    </properties>
```

NOTE for Phase 1: `--enable-preview` is set on the compiler and in surefire/failsafe argLines (lines 160-162, 198, 211). Boot 3.4 supports Java 21/23; `--enable-preview` on 21 stays valid but is unusual — flag it to the plan author. Also `springdoc.version` 2.6.0 targets Boot 3.3; Boot 3.4 needs springdoc 2.7.0+.

### 1d. `dependencyManagement` (lines 54-128) — structure summary

Contains ONLY: internal module BOM entries (`act-common`, `act-agent`, `act-execution`, `act-knowledge`, `act-aria`, `act-dashboard-api`, `act-test-support`, each `${project.version}`), springdoc `springdoc-openapi-starter-webmvc-ui` (`${springdoc.version}`), `caffeine` (`${caffeine.version}`), `logstash-logback-encoder` (`${logstash-logback.version}`), `archunit-junit5` (`${archunit.version}`), `jqwik` (`${jqwik.version}`). Everything else (Spring Boot starters, Jackson, etc.) is version-managed by `spring-boot-starter-parent`. A Spring AI BOM import would be ADDED here as a second `<dependency><scope>import</scope><type>pom</type></dependency>` entry. Verbatim first entry as a template:

```xml
    <dependencyManagement>
        <dependencies>
            <!-- Internal modules -->
            <dependency>
                <groupId>io.aria.conductor</groupId>
                <artifactId>act-common</artifactId>
                <version>${project.version}</version>
            </dependency>
```

### 1e. `agent-control-tower/act-app/pom.xml` — module dependencies (lines 23-47)

```xml
    <dependencies>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-common</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-agent</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-execution</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-knowledge</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-aria</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-dashboard-api</artifactId>
        </dependency>
```

(then Spring Boot starters `spring-boot-starter-web`, `spring-boot-starter-actuator`; H2 + MariaDB runtime; flyway-core + flyway-mysql; test: spring-boot-starter-test, archunit-junit5, `org.wiremock:wiremock-standalone:3.9.1`; springdoc; logstash-logback-encoder — lines 48-114.) act-app also declares `jacoco.line.minimum>0.80` / `jacoco.branch.minimum>0.60` ratchets (lines 17-21).

---

## 2. `agent-control-tower/act-knowledge/pom.xml` — FULL (67 lines) — template for act-mcp/pom.xml

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

    <artifactId>act-knowledge</artifactId>
    <name>ACT Knowledge</name>
    <description>Knowledge governance module</description>

    <properties>
        <!-- M1 coverage ratchet (measured 78.8% line / 61.9% branch post-Phase-B, see docs/testing-baseline.md) -->
        <jacoco.line.minimum>0.70</jacoco.line.minimum>
        <jacoco.branch.minimum>0.54</jacoco.branch.minimum>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-common</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-agent</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-execution</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
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
            <groupId>org.eclipse.jgit</groupId>
            <artifactId>org.eclipse.jgit</artifactId>
            <version>6.8.0.202311291450-r</version>
        </dependency>

        <!-- Test dependencies -->
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-test-support</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

---

## 3. OpenCodeAdkProvider + sandbox env + config generation

### 3a. `writeOpenCodeConfig()` — `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java` lines 471-525, VERBATIM

```java
    /**
     * Write the sandbox {@code opencode.json} into the agent workspace so the next
     * {@code uploadWorkspace} ships it into the sandbox. The config enforces a
     * headless-safe permission policy — no permission may resolve to {@code ask}
     * (which would block forever on human input in headless mode): the {@code *}
     * wildcard allows every non-interactive tool, while {@code question} and
     * {@code external_directory} are explicitly denied so tools touching paths
     * outside /workspace get a visible error the model can read and continue from
     * instead of hanging (R6-F1). It also points opencode at the ACTIVE DB
     * {@link LlmProvider} model, falling back to deepseek defaults when no
     * provider is active (first-run keeps working without DB setup).
     *
     * <p>Best-effort: a write failure is logged and the sandbox still comes up.
     */
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
                      "permission": {
                        "*": "allow",
                        "question": "deny",
                        "external_directory": "deny"
                      },
                      "model": "%s/%s",
                      "provider": {
                        "%s": {
                          "npm": "@ai-sdk/openai-compatible",
                          "options": {
                            "apiKey": "{env:LLM_API_KEY}",
                            "baseURL": "%s"
                          },
                          "models": {
                            "%s": {}
                          }
                        }
                      }
                    }
                    """.formatted(providerId, model, providerId, baseUrl, model);
            Files.writeString(workspace.resolve("opencode.json"), json);
            log.info("Wrote opencode.json for workspace {} (provider={}, model={})", workspace, providerId, model);
        } catch (IOException e) {
            log.warn("Could not write opencode.json to {}: {}", workspace, e.getMessage());
        }
    }
```

**How the JSON is produced:** raw Java text block + `String.formatted(...)` + `Files.writeString` — NO JSON library / object model. MCP wiring into opencode.json will extend this text block (or replace it).

**Call site** — `prepareInstance(UUID agentId, Agent agent)`, same file lines 435-469 (abbreviated to the load-bearing lines):

```java
    private OpenCodeInstance prepareInstance(UUID agentId, Agent agent) {
        Path workspace = workspaceBase.resolve(agentId.toString());
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Cannot create workspace dir " + workspace + ": " + e.getMessage(), e);
        }
        writeOpenCodeConfig(workspace);

        String sandboxId = sandboxManager.createSandbox(agentId, properties.getImage(), properties.getSandboxEnv());
        OpenCodeHttpClient client = null;
        try {
            sandboxManager.uploadWorkspace(agentId, workspace);
            sandboxManager.runServeCommand(sandboxId, properties.getPort());
            String serveUrl = sandboxManager.getSandboxUrl(sandboxId, properties.getPort());
            client = clientForUrl(serveUrl);
            waitForHealth(client, agentId);
            OpenCodeInstance instance = new OpenCodeInstance(sandboxId, true, Instant.now(), 0, client);
            instances.put(agentId, instance);
            log.info("OpenCode sandbox {} ready for agent {} at {}", sandboxId, agentId, serveUrl);
            return instance;
        } catch (TaskExecutionException e) {
            ...
        }
        ...
    }
```

Order is fixed: `writeOpenCodeConfig(workspace)` → `createSandbox(..., properties.getSandboxEnv())` → `uploadWorkspace(agentId, workspace)` → `runServeCommand` → `getSandboxUrl` → health-wait. `WORKSPACE_BASE = "act-app/data/workspaces"` (line 65) — the workspace dir is local, resolved per agentId, uploaded into the sandbox at `/workspace`.

### 3b. Sandbox env application — `OpenCodeSandboxManager.java`

`OpenCodeAdkProvider.java` line 100: `new OpenCodeSandboxManager(properties.getSandboxServerUrl(), properties.getSandboxApiKey())`.

`OpenCodeSandboxManager.createSandbox` (lines 88-107) + `buildSandbox` (lines 141-157) — the exact `Sandbox.Builder.env()` call site:

```java
    public String createSandbox(UUID agentId, String image, Map<String, String> env) {
        Sandbox sandbox = createSandboxWithRetry(agentId, image, env);
        String sandboxId = sandbox.getId();
        sandboxes.put(agentId, sandbox);
        log.info("OpenSandbox sandbox created for agent {}: {}", agentId, sandboxId);
        return sandboxId;
    }
```

```java
    private Sandbox buildSandbox(UUID agentId, String image, Map<String, String> env) {
        Sandbox.Builder builder = Sandbox.builder()
                .connectionConfig(connectionConfig)
                .image(image)
                .timeout(SANDBOX_TIMEOUT)
                // The server reports execd endpoints without a scheme and with the
                // configured host (bridge mode: 127.0.0.1:{mapped}/proxy/{port}, see
                // docker-compose `[docker] host_ip`). We skip the SDK's built-in health
                // check (it would probe the scheme-less endpoint and fail) and instead
                // verify readiness ourselves against the URL built by {@link #getSandboxUrl}.
                .skipHealthCheck(true);
        if (env != null && !env.isEmpty()) {
            builder.env(env);
            log.info("Injecting {} env var(s) into sandbox for agent {}", env.size(), agentId);
        }
        return builder.build();
    }
```

SDK is `com.alibaba.opensandbox:sandbox` (`Sandbox` from `com.alibaba.opensandbox.sandbox.Sandbox`); class javadoc (lines 39-41) notes: "Env vars (e.g. LLM model credentials) can be injected into every sandbox via {@link #createSandbox(UUID, String, java.util.Map)}; the SDK builder supports {@code Sandbox.Builder#env(Map)} (verified against OpenSandbox 1.0.18)."

`serve` launch (lines 218-229): `"opencode serve --hostname 0.0.0.0 --port " + port` on a virtual thread. `SANDBOX_TIMEOUT = Duration.ofMinutes(30)` (line 57).

### 3c. `OpenCodeProperties.java` (full class, 64 lines, abbreviated verbatim fields)

```java
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "opencode")
public class OpenCodeProperties {

    /** OpenSandbox server base URL (lifecycle server, not the sandbox-internal serve). */
    private String sandboxServerUrl = "http://localhost:8080";

    /** Optional API key for the OpenSandbox server (env: OPENSANDBOX_API_KEY). */
    private String sandboxApiKey = "";

    /** Template image with opencode pre-installed. */
    private String image = "aria-conductor/opencode-sandbox:1.0";

    /** Port the sandbox-internal opencode serve binds to. */
    private int port = 4096;

    /**
     * Env vars injected into every agent sandbox (e.g. LLM model credentials
     * such as DEEPSEEK_API_KEY consumed by opencode inside the sandbox).
     */
    private Map<String, String> sandboxEnv = new LinkedHashMap<>();

    /** Default task timeout in minutes (used when TaskContext.maxDuration is null). */
    private int maxTaskMinutes = 45;

    /** Interval between sandbox TTL renewals ... */
    private Duration sandboxRenewInterval = Duration.ofMinutes(5);

    /** Default target repository URL ... (env: {@code SDD_REPO_URL}). */
    private String repoUrl = "";

    /** S9/S10: progress pump poll interval in ms (opencode.progress-poll-ms). */
    private long progressPollMs = 2000;

    /** S9/S10: thinking coalesce window in ms (opencode.progress-coalesce-ms). */
    private long progressCoalesceMs = 400;
}
```

### 3d. `agent-control-tower/act-app/src/main/resources/application.yml` lines 38-50 — `opencode` block VERBATIM

```yaml
opencode:
  sandbox-server-url: http://localhost:8090
  sandbox-api-key: ${OPENSANDBOX_API_KEY:}
  image: aria-conductor/opencode-sandbox:1.1
  port: 4096
  max-task-minutes: 45
  # Default repo URL for SDD templates that declare {repoUrl} but the caller omits it (R8-F1).
  repo-url: ${SDD_REPO_URL:}
  # Env vars injected into every agent sandbox (e.g. LLM model credentials for opencode)
  sandbox-env:
    DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY:}
    LLM_API_KEY: ${LLM_API_KEY:${DEEPSEEK_API_KEY:}}
    GH_TOKEN: ${GH_TOKEN:}
```

**Profile overrides of `opencode.*`:**
- `application-h2.yml`: NO `opencode:` block (full file read — only `adk.default-provider: ${ADK_DEFAULT_PROVIDER:opencode}` at line 52; note default provider here IS opencode).
- `application-mariadb.yml`: NO `opencode:` block (grep confirmed; has `adk.default-provider: ${ADK_DEFAULT_PROVIDER:opencode}` line 42).
- `agent-control-tower/act-app/src/test/resources/application-test.yml` lines 31-37:

```yaml
opencode:
  sandbox-server-url: http://localhost:18080
  sandbox-api-key: test-opensandbox-key
  max-task-minutes: 30
  sandbox-env:
    DEEPSEEK_API_KEY: test-key
```

NOTE (from project memory): exporting `OPENCODE_SANDBOX_*` env leaks into `OpenCodePropertiesBindingTest` — 2 test cases fail; known non-production issue.

### 3e. Agent entity visible at the call site — `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Agent.java` (full field list, lines 9-67)

```java
@Entity
@Table(name = "agents", indexes = {
        @Index(name = "idx_agents_type", columnList = "agentType"),
        @Index(name = "idx_agents_health", columnList = "healthStatus")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentType agentType;

    @Column(columnDefinition = "TEXT")
    private String role;

    private String model;

    private String provider;

    private String adkProvider;

    @Column(columnDefinition = "TEXT")
    private String config;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HealthStatus healthStatus;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    private Instant retiredAt;
    ...
}
```

Getters available at the call site (Lombok `@Data`): `getId()` (UUID), `getName()`, `getDescription()`, `getAgentType()`, `getRole()`, `getModel()`, `getProvider()`, `getAdkProvider()`, `getConfig()`, `getHealthStatus()`.

---

## 4. Service signatures for tool wrapping

### 4a. WorkflowTemplateService — `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/WorkflowTemplateService.java`

**findMatchingTemplates** (lines 77-95, signature + javadoc verbatim):

```java
    /**
     * Find APPROVED workflow templates matching the given intent keywords.
     * If {@code userIntent} is {@code null} or blank, all APPROVED workflow templates are returned.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeItemResponse> findMatchingTemplates(String userIntent) {
```

Implementation: queries `itemRepository.findByTypeAndStatus(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED)`, filters by `item.getName()`/`item.getDescription()` containing the lowercase intent, maps via `knowledgeService::toResponseWithLatestVersion`.

**instantiateTemplate** (lines 108-109 signature; body lines 110-243 summarized with exact behaviors):

```java
    @Transactional
    public WorkflowResponse instantiateTemplate(UUID templateItemId, Map<String, String> parameters) {
```

Verbatim key facts from the body:
- Loads `KnowledgeItem` by id → `ResourceNotFoundException` if missing; throws `IllegalArgumentException` if `item.getType() != KnowledgeType.WORKFLOW` or `item.getStatus() != KnowledgeStatus.APPROVED`.
- YAML: `versionRepository.findByKnowledgeItemIdAndVersion(item.getId(), item.getCurrentVersion())`; `yamlContent = version.getYamlContent()`; legacy fallback to `version.getContent()`; throws `"Template has no YAML content"` if still blank.
- Steps: `templateConverter.yamlToWorkflowSteps(yamlContent)`; declared params via `templateConverter.extractParameterNames(steps)`; unknown param keys → `IllegalArgumentException`.
- repoUrl (R8-F1): if declared params contain `GitHandoffMetadata.KEY_REPO_URL` and caller omits it, falls back to `openCodeProperties.getRepoUrl()`, else throws `"Template requires repoUrl parameter; pass it or set opencode.repo-url"`.
- Substitution: `templateConverter.substituteParameters(step.getPromptTemplate(), resolvedParams)` per step.
- Chain creation (lines 182-198 verbatim):

```java
        List<CreateWorkflowRequest.StepDef> stepDefs = steps.stream()
                .map(s -> CreateWorkflowRequest.StepDef.builder()
                        .agentId(s.getAgentId())
                        .promptTemplate(s.getPromptTemplate())
                        .maxIterations(s.getMaxIterations())
                        .kind(s.getKind())
                        .build())
                .toList();

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name(item.getName() + "-instance")
                .description("Instantiated from template: " + item.getName())
                .steps(stepDefs)
                .allowSddSteps(true)
                .build();

        WorkflowResponse response = workflowService.createAndStart(request);
```

- SDD detection: any step kind `BA|DEV|QA` → `dodService.init(response.getId().toString(), "SDD", List.of("dev", "qa"))`.
- Post-create chain patch (lines 215-239): reloads `chainRepository.findById(response.getId())`, sets `sourceKnowledgeItemId = templateItemId`, persists `templateParams = GitHandoffMetadata.toJson(resolvedParams)`, injects `branchName = "sdd/" + newChain.getId()` into any `{branchName}` placeholder, saves.
- **How the created chain id is returned:** `return response;` — `WorkflowResponse.getId()` IS the chain id (`WorkflowService.createAndStart` saves the `WorkflowChain` and maps `toResponse(saved)`).

### 4b. DTOs

`agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/WorkflowResponse.java` (full):

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowResponse {

    private UUID id;
    private String name;
    private WorkflowChain.Status status;
    private int currentStepIndex;
    private int totalSteps;
    private List<StepInfo> steps;
    private Instant createdAt;
    private Instant completedAt;
    private boolean isTemplate;
    private UUID knowledgeItemId;
    private String description;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepInfo {
        private int index;
        private UUID agentId;
        private String promptTemplate;
        private WorkflowStep.Status status;
        private UUID runId;
        private String outputPreview;
    }
}
```

`agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/CreateWorkflowRequest.java` (full):

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateWorkflowRequest {

    @NotBlank(message = "Workflow name is required")
    private String name;

    private String description;

    @NotEmpty(message = "At least one step is required")
    @Valid
    private List<StepDef> steps;

    /**
     * Set by the governed {@code instantiate_template} path to permit BA/DEV/QA step kinds.
     * Direct REST/tool callers leave this {@code false}, so {@code createAndStart} rejects SDD
     * kinds and forces the SPEC_REVIEW gate.
     */
    private boolean allowSddSteps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepDef {
        private UUID agentId;
        @NotBlank(message = "Prompt template is required for each step")
        private String promptTemplate;
        @Builder.Default
        private int maxIterations = 3;
        private WorkflowStep.StepKind kind;
    }
}
```

NOTE: the request DTO does NOT carry `yamlContent` passthrough; the template's YAML comes from `KnowledgeVersion.yamlContent` (or legacy `content`) inside `instantiateTemplate`. `WorkflowTemplateService` never re-persists caller-supplied yamlContent — the caller-supplied `parameters` map is what flows through (persisted on the chain as `templateParams`).

### 4c. KnowledgeService — `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/KnowledgeService.java`

Signatures (line numbers verbatim):

```java
line 107:  @Transactional(readOnly = true)
line 108:  public String getYamlContent(UUID knowledgeItemId, String version)   // null-safe: returns null if version row missing; legacy fallback to version.getContent() for WORKFLOW
line 127:  @Transactional(readOnly = true)
line 128:  public List<KnowledgeItemResponse> listKnowledge(KnowledgeType type, KnowledgeStatus status)
line 142:  @Transactional(readOnly = true)
line 143:  public KnowledgeItemResponse getKnowledge(UUID id)
```

`listKnowledge` semantics: `type != null && status != null` → `findByTypeAndStatus`; `type` only → `findByType`; `status` only → `findByStatus`; else `findAll()`. All mapped via `this::toResponseWithLatestVersion`.
`getKnowledge` throws `ResourceNotFoundException("KnowledgeItem", id)` when absent.

Public method inventory (grep, line numbers): `submitKnowledge(CreateKnowledgeRequest)` L47; `submitKnowledge(CreateKnowledgeRequest, String yamlContent)` L55; `getYamlContent` L107; `listKnowledge` L127; `getKnowledge` L142; `updateKnowledge` L149; `reviewKnowledge(UUID, ReviewDecisionRequest)` L211; `retireKnowledge(UUID)` L263; `getVersions(UUID)` L292; `getVersionContent(UUID,String)` L303; `promoteKnowledgeItem` L326; `getApprovedKnowledgeContext(int)` L374; `buildKnowledgeContextPrompt(int)` L381; `getStats()` L399.

`agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/dto/KnowledgeItemResponse.java` (full):

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeItemResponse {

    private UUID id;
    private String name;
    private KnowledgeType type;
    private String description;
    private String currentVersion;
    private KnowledgeStatus status;
    private Sensitivity sensitivity;
    private String filePath;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant retiredAt;
    private KnowledgeVersionResponse latestVersion;
}
```

`KnowledgeVersionResponse` (full, same package):

```java
public class KnowledgeVersionResponse {
    private UUID id;
    private String version;
    private VersionStatus status;
    private String content;
    private String yamlContent;
    private Instant createdAt;
    private Instant approvedAt;
}
```

`KnowledgeController` (`.../knowledge/controller/KnowledgeController.java`): `@RequestMapping("/api/v1/knowledge")`; `GET ""` → `listKnowledge(@RequestParam(required=false) KnowledgeType type, @RequestParam(required=false) KnowledgeStatus status)` (lines 53-59); `GET /{id}` → `getKnowledge` (61-65); `POST /{id}/instantiate-workflow` (37-44):

```java
    /** Template instantiation parameters: {@code {parameters: {issueRef: "#1"}}}. */
    public record InstantiateWorkflowRequest(Map<String, String> parameters) {}

    @PostMapping("/{id}/instantiate-workflow")
    public ResponseEntity<WorkflowResponse> instantiateWorkflow(
            @PathVariable UUID id,
            @RequestBody(required = false) InstantiateWorkflowRequest request) {
        Map<String, String> params = request != null && request.parameters() != null
                ? request.parameters() : Map.of();
        return ResponseEntity.ok(workflowTemplateService.instantiateTemplate(id, params));
    }
```

`GET /{id}/yaml` exists (line 89) serving `getYamlContent`.

### 4d. ApprovalGate + ApprovalController

`agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalGate.java` — `decideApproval` (lines 240-278, VERBATIM):

```java
    /**
     * Process an approval decision — updates entity and unblocks the waiting virtual thread.
     */
    @Transactional
    public void decideApproval(UUID approvalId, boolean approved, String reason) {
        log.info("Processing approval decision: approvalId={}, approved={}", approvalId, approved);

        Approval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        if (approval.getStatus() != ApprovalStatus.PENDING) {
            log.warn("Approval {} is already in status {}, ignoring decision", approvalId, approval.getStatus());
            return;
        }

        // Update approval entity
        approval.setStatus(approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED);
        approval.setReason(reason);
        approval.setDecidedAt(Instant.now());
        approvalRepository.save(approval);

        // Update associated tool call
        if (approval.getToolCallId() != null) {
            toolCallRepository.findById(approval.getToolCallId()).ifPresent(tc -> {
                tc.setStatus(approved ? ToolCallStatus.EXECUTING : ToolCallStatus.DENIED);
                toolCallRepository.save(tc);
            });
        }

        // Unblock the waiting virtual thread
        CompletableFuture<ApprovalDecision> future = pendingApprovals.remove(approvalId);
        if (future != null && !future.isDone()) {
            ApprovalDecision decision = approved
                    ? ApprovalDecision.approve(reason)
                    : ApprovalDecision.deny(reason);
            future.complete(decision);
        }

        // Publish event
        eventPublisher.publishEvent(new io.aria.conductor.common.event.ApprovalDecidedEvent(
                this, approvalId, approval.getStatus()));
    }
```

Also: `requestApproval(Action, RunContext)` L167, `requestApproval(Action, RunContext, String escalationNote)` L176, `requestTurnApproval(RunContext, List<Action>)` L62, `cancelAllPendingForRun(UUID)` L330. Timeout from `${approvals.timeout-ms:1800000}` (constructor L45).

**How approvals are listed/read** — `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java`: `@RequestMapping("/api/v1/approvals")`; `GET ""` (lines 71-90) returns `List<ApprovalDetail>`; no status → `approvalRepository.findAll(PageRequest.of(0, 200, Sort.by("requestedAt").descending()))`; with status → `findByStatus(status)`. `ApprovalDetail` record (lines 49-64):

```java
    public record ApprovalDetail(
            UUID id,
            UUID runId,
            UUID toolCallId,
            ApprovalStatus status,
            String reason,
            Instant requestedAt,
            Instant decidedAt,
            Instant expiresAt,
            String approvalType,
            String content,
            String contentKind,
            UUID knowledgeItemId,
            String toolName,
            String arguments,
            String riskTier) {}
```

`POST /{id}/decide` (lines 104-122): body `record DecideApprovalRequest(boolean approved, String reason)`, delegates to `approvalGate.decideApproval(id, request.approved(), request.reason())`, returns `{approvalId, approved, status:"processed"}`; `IllegalArgumentException` → 400.

`ApprovalStatus` enum (`act-common/.../model/ApprovalStatus.java`): PENDING, APPROVED, DENIED, EXPIRED (values as used in ApprovalGate). `Approval.ApprovalType` has at least `TOOL_CALL` and `SPEC_REVIEW` (see `cancelAllPendingForRun` filter L334 and `toDetail` fallback `"TOOL_CALL"`).

### 4e. Chain / run status reads

`agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/WorkflowService.java`:

```java
line 53:  @Transactional
line 54:  public WorkflowResponse createAndStart(CreateWorkflowRequest request) {
line 97:  @Transactional
line 98:  public boolean advanceWorkflow(UUID chainId, int completedStepIndex, String finalOutput) {
line 154: @Transactional(readOnly = true)
line 155: public WorkflowResponse getWorkflow(UUID id) {
              // WorkflowChain chain = workflowChainRepository.findById(id)
              //         .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", id));
              // return toResponse(chain);
line 161: @Transactional(readOnly = true)
line 162: public List<WorkflowResponse> listWorkflows() {   // findAll() → toResponse
line 215: public WorkflowResponse cancelWorkflow(UUID chainId) {
```

`WorkflowController` `@RequestMapping("/api/v1/workflows")`: `GET ""` → `listWorkflows()`; `GET /{id}` → `getWorkflow(id)`; `POST /{id}/cancel`; `POST /{id}/retry`; `POST /templates/{id}/reuse`. Also `SddWorkflowController` in act-knowledge maps `@RequestMapping("/api/v1/workflows")` + `POST /{id}/resubmit-approval`.

`WorkflowChain.Status` — `act-common/.../model/WorkflowChain.java` line 23 VERBATIM:

```java
    public enum Status { PENDING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }
```

`WorkflowStep` enums (`act-common/.../model/WorkflowStep.java` lines 17, 20):

```java
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }
    public enum StepKind { GENERIC, BA, DEV, QA, CODE_REVIEW }
```

Runs: `RunService` (`act-agent/.../service/RunService.java`): `getRun(UUID id)` L112 → `RunResponse`; `listRuns()` L84; `listRunsByAgent(UUID)` L91; `listRunsByStatus(RunStatus)` L98; `listRunsByAgentAndStatus(UUID, RunStatus)` L105. `RunController` `@RequestMapping("/api/v1/runs")`: `GET ""` with optional `agentId`/`status` params; `GET /{id}` → `runService.getRun(id)`.

`RunResponse` (`act-agent/.../dto/RunResponse.java`, full field list):

```java
    private UUID id;
    private UUID agentId;
    private RunStatus status;
    private String promptSeed;
    private int maxIterations;
    private long totalTokensUsed;
    private int iterationCount;
    private String errorMessage;
    private String finalOutput;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
```

`RunStatus` — `act-common/.../model/RunStatus.java` VERBATIM:

```java
public enum RunStatus {
    PENDING, INITIALIZING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED,
    /** Task-level run aborted by the engine (timeout / budget / approval denial). */
    ABORTED
}
```

---

## 5. Aria agent identity

`agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/AriaConstants.java` — FULL:

```java
package io.aria.conductor.aria;

import java.util.UUID;

/**
 * Shared constants for the Aria platform assistant.
 */
public final class AriaConstants {

    /**
     * Stable synthetic agent id used for every Aria-originated prompt call.
     * Aria is a platform assistant rather than a user-managed agent.
     */
    public static final UUID ARIA_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private AriaConstants() {
        // utility class
    }
}
```

`AriaDefaultAgentInitializer` (act-aria, `@Component @Order(Ordered.HIGHEST_PRECEDENCE) implements ApplicationRunner`): creates agent with `id = AriaConstants.ARIA_AGENT_ID`, `name = "Aria"`, `agentType = AgentType.NATIVE`, `adkProvider = configured default (adk.default-provider, fallback "langchain")`, `config` JSON includes `{"maxToolCallRounds":15,"taskApprovalRequired":false,"systemPrompt":ARIA_SYSTEM_PROMPT}`. CREATE-only (operator edits preserved). Tool allowlist constant `ARIA_ORCHESTRATION_TOOLS` (lines 167-194) includes `"instantiate_template"`, `"create_workflow"`, `"get_workflow"`, `"list_workflows"`, `"cancel_workflow"`, `"list_pending_approvals"`, `"decide_approval"` etc.

Seeded SDD role agents — `agent-control-tower/act-app/src/main/resources/db/migration/V42__seed_sdd_role_agents.sql` (full INSERT):

```sql
INSERT INTO agents (id, name, role, agent_type, adk_provider, model, config, health_status, created_at, updated_at) VALUES
(CAST('ba000000-0000-0000-0000-000000000001' AS UUID), 'SDD BA Agent', 'ba', 'NATIVE', 'langchain', 'mock', '{}', 'HEALTHY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(CAST('de000000-0000-0000-0000-000000000002' AS UUID), 'SDD DEV Agent', 'dev', 'NATIVE', 'langchain', 'mock', '{}', 'HEALTHY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(CAST('aa000000-0000-0000-0000-000000000003' AS UUID), 'SDD QA Agent', 'qa', 'NATIVE', 'langchain', 'mock', '{}', 'HEALTHY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

(JAVA-side mirror: `AriaDefaultAgentInitializer.SEEDED_SDD_ROLE_AGENT_IDS` lines 208-211. NOTE: `qa` prefix is NOT hex — hence `aa`.) V50 = `V50__seed_slash_command_skills.sql` (slash-command skills seed, not agent ids).

---

## 6. Test conventions

### 6a. Representative knowledge-module test header — `agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceTest.java` lines 1-71, VERBATIM:

```java
package io.aria.conductor.knowledge.service;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.knowledge.converter.WorkflowTemplateConverter;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.aria.conductor.test.TestDataBuilder.aKnowledgeItem;
import static io.aria.conductor.test.TestDataBuilder.aWorkflowChain;
import static io.aria.conductor.test.TestDataBuilder.aWorkflowStep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateServiceTest {

    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock WorkflowTemplateConverter templateConverter;
    @Mock WorkflowService workflowService;
    @Mock WorkflowChainRepository chainRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock DoDService dodService;
    @Mock KanbanService kanbanService;
    @Mock OpenCodeProperties openCodeProperties;

    WorkflowTemplateService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTemplateService(itemRepository, versionRepository,
                templateConverter, workflowService, chainRepository, knowledgeService,
                dodService, kanbanService, openCodeProperties);
    }
```

Style: JUnit 5 + `MockitoExtension`, package-private class, AssertJ, `io.aria.conductor.test.TestDataBuilder` builders from act-test-support.

### 6b. act-execution test asserting opencode.json — `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProviderTest.java`

Header (lines 57-90 verbatim style excerpt):

```java
@ExtendWith(MockitoExtension.class)
class OpenCodeAdkProviderTest {

    private static final String IMAGE = "test-image";

    /**
     * Full permission key set documented for opencode (https://opencode.ai/docs/permissions/).
     * Used to verify the generated opencode.json never resolves any permission to "ask".
     */
    private static final List<String> DOCUMENTED_PERMISSION_KEYS = List.of(
            "read", "edit", "glob", "grep", "bash", "task", "skill", "lsp",
            "question", "webfetch", "websearch", "external_directory", "doom_loop");

    @Mock OpenCodeSandboxManager sandboxManager;
    @Mock OpenCodeHttpClient httpClient;
    @Mock LlmProviderRepository providerRepository;
    @Mock ApplicationEventPublisher publisher;

    @TempDir Path tempDir;

    OpenCodeProperties properties;
    OpenCodeAdkProvider provider;

    @BeforeEach
    void setUp() {
        properties = new OpenCodeProperties();
        properties.setSandboxServerUrl("http://localhost:8080");
        properties.setSandboxApiKey("test-key");
        properties.setImage(IMAGE);
        properties.setPort(4096);
        properties.setMaxTaskMinutes(30);
        provider = new OpenCodeAdkProvider(properties, sandboxManager, httpClient, providerRepository);
        provider.setWorkspaceBaseForTest(tempDir);
    }
```

One representative assertion block — test `prepareInstance_writesOpenCodeJsonWithQuestionDeniedAndActiveProvider` (lines 727-750, VERBATIM):

```java
    @Test
    void prepareInstance_writesOpenCodeJsonWithQuestionDeniedAndActiveProvider() throws Exception {
        LlmProvider active = LlmProvider.builder().name("deepseek-qa").type(LlmProviderType.OPENAI)
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
        assertThat(json).contains("deepseek-qa/deepseek-v4-flash");
        assertThat(json).contains("https://api.deepseek.com/v1");
        // R5-F1: opencode requires custom providers to declare the SDK adapter
        // (npm) and a non-empty models map — without them the provider resolves to
        // zero models and opencode fails with ProviderModelNotFoundError at runtime.
        assertThat(json).contains("\"npm\": \"@ai-sdk/openai-compatible\"");
        assertThat(json).contains("\"models\"");
        assertThat(json).contains("\"deepseek-v4-flash\": {}");
    }
```

Also `prepareInstance_openCodeJson_isParseableAndSchemaComplete` (L753) parses with Jackson and asserts `permission.path("external_directory") == "deny"` and no permission resolves to `"ask"`; `prepareInstance_usesDeepseekDefaultsWhenNoActiveProvider` (L786). There are 4 tests around opencode.json (grep hits at L740/765/795/815).

---

## 7. E2E twin scenario + fixtures + playwright config

### 7a. `agent-control-tower/act-dashboard/e2e/sdd-workflow.spec.ts` — FULL (129 lines), VERBATIM

```typescript
import { test, expect } from '@playwright/test';

/**
 * Phase 1 E2E contract anchor (RED) for the Spec-Driven Development workflow
 * (docs/superpowers/specs/2026-08-12-spec-driven-development-workflow-design.md).
 *
 * Drives the loop over the REST API and asserts the Approvals page renders the
 * SPEC_REVIEW card. Written FIRST — it must fail until the backend + frontend
 * wiring lands (later tasks):
 *   - POST /api/v1/knowledge/{id}/instantiate-workflow            (planned, Task 3)
 *   - GET /api/v1/approvals gains approvalType/content/knowledgeItemId (planned)
 *   - POST /api/v1/workflows/{id}/resubmit-approval               (planned)
 *   - Approvals page SPEC_REVIEW card with .spec-review-markdown   (planned, Task 10)
 *
 * Verified-real endpoints used unchanged: GET /api/v1/knowledge?type=WORKFLOW&status=APPROVED,
 * GET /api/v1/workflows, GET /api/v1/workflows/{id}, POST /api/v1/approvals/{id}/decide.
 * V40 seed provides the APPROVED 'development-workflow' template.
 *
 * Prerequisites: backend running (h2 profile, V40+), frontend dev server up.
 * API_URL/BASE_URL are parameterised like the other e2e specs (worktrees/CI).
 */

test.describe.configure({ mode: 'serial', timeout: 600_000 }); // 10 min — drives real BA→Dev→QA runs

const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';

/**
 * Polls {fn} until it returns a non-null value or the deadline elapses.
 * Mirrors the pollUntil pattern from e2e/fixtures.ts, local to this spec.
 */
async function pollUntil<T>(
  fn: () => Promise<T | null>,
  timeoutMs: number,
  intervalMs = 2_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | null = null;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value != null) return value;
    last = value;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`pollUntil timed out after ${timeoutMs}ms (last result: ${JSON.stringify(last)?.slice(0, 300)})`);
}

test('development-workflow: spec approval then PASS verdict completes the chain', async ({ page, request }) => {
  // 0. Ensure the BA/DEV/QA role agents exist (template resolves steps by agent_role).
  const existing = await (await request.get(`${API_URL}/api/v1/agents`)).json();
  const roles = new Set((existing ?? []).map((a: any) => a.role));
  for (const role of ['ba', 'dev', 'qa']) {
    if (!roles.has(role)) {
      const created = await request.post(`${API_URL}/api/v1/agents`, {
        data: { name: `sdd-${role}-${Date.now()}`, role, agentType: 'NATIVE' },
      });
      expect(created.ok(), `create ${role} agent`).toBeTruthy();
    }
  }

  // 1. Instantiate the seeded development-workflow template (V40 seed, APPROVED WORKFLOW item).
  const templates = await request.get(`${API_URL}/api/v1/knowledge?type=WORKFLOW&status=APPROVED`);
  expect(templates.ok()).toBeTruthy();
  const tpl = (await templates.json()).find((k: any) => k.name === 'development-workflow');
  expect(tpl).toBeTruthy();

  // R8-F1: the template declares {repoUrl} (V45 prompts) and instantiation fails fast
  // when neither the caller nor the system config (opencode.repo-url) provides it.
  // CI has no GH_TOKEN, so the branch-creation step is a no-op - the URL is inert here.
  const inst = await request.post(`${API_URL}/api/v1/knowledge/${tpl.id}/instantiate-workflow`, {
    data: { parameters: { issueRef: '#1-test', repoUrl: 'https://github.com/HappyLiang12/aria-conductor.git' } },
  });
  expect(inst.ok()).toBeTruthy();
  const chain = await inst.json();
  expect(chain.id).toBeTruthy();

  // 2. Poll until the chain enters WAITING_APPROVAL with a SPEC_REVIEW approval.
  //    Contract: SPEC_REVIEW approvals carry markdown content, the knowledge link,
  //    and a null toolCallId (no tool gate involved).
  let approval: any = null;
  try {
    approval = await pollUntil(async () => {
      const list = await (await request.get(`${API_URL}/api/v1/approvals`)).json();
      return list.find((a: any) => a.approvalType === 'SPEC_REVIEW' && a.status === 'PENDING');
    }, 30_000);
  } catch (e) {
    // The BA run needs a real ADK runtime (langchain subprocess / opencode sandbox).
    // Without one the chain fails before the spec gate - skip rather than flake.
    const wf = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
    test.skip(
      wf.status === 'FAILED' || wf.status === 'RUNNING',
      'BA run requires an ADK runtime (langchain/open-sandbox); skipping spec-gate assertions',
    );
    throw e;
  }
  expect(approval.content).toContain('#');
  expect(approval.knowledgeItemId).toBeTruthy();
  expect(approval.toolCallId).toBeNull();

  // 3. Approvals page renders the card without crashing (null toolCallId) and shows markdown.
  await page.goto('/approvals');
  await expect(page.getByText('SPEC_REVIEW')).toBeVisible();
  await expect(page.locator('.spec-review-markdown')).toBeVisible();

  // 4. Approve -> the coordinator writes back to knowledge and resumes the chain.
  const decide = await request.post(`${API_URL}/api/v1/approvals/${approval.id}/decide`, {
    data: { approved: true, reason: 'lgtm' },
  });
  expect(decide.ok()).toBeTruthy();

  // 5. The chain must leave WAITING_APPROVAL (resumed by the coordinator). The Dev/QA runs
  //    then depend on the LLM/ADK being available; without one the chain lands in FAILED -
  //    which still proves the approval gate -> coordinator -> resume contract. The full
  //    PASS/DEFECT/SPEC_GAP routing is covered deterministically by the Java integration
  //    tests (SddWorkflowIntegrationTest).
  await pollUntil(async () => {
    const wf = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
    return wf.status !== 'WAITING_APPROVAL' ? wf : null;
  }, 30_000);
  const resumed = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
  expect(['RUNNING', 'COMPLETED', 'FAILED']).toContain(resumed.status);
});

test('development-workflow: resubmit-approval recreates an EXPIRED approval', async ({ request }) => {
  const list = await request.get(`${API_URL}/api/v1/workflows`);
  const waiting = (await list.json()).find((w: any) => w.status === 'WAITING_APPROVAL');
  test.skip(!waiting, 'requires a WAITING_APPROVAL chain (fixture-dependent)');
  const res = await request.post(`${API_URL}/api/v1/workflows/${waiting.id}/resubmit-approval`);
  expect(res.ok()).toBeTruthy();
});
```

### 7b. `agent-control-tower/act-dashboard/e2e/fixtures.ts` — `apiCall` helper VERBATIM (lines 1-34; the rest of the file provides `uniqueName`, `seedAgent`, `seedWorkflow`, `seedKnowledgeItem`, `seedKanbanItem`, `seedRun`, `timedApiCall`, `pollUntil(request,path,predicate,timeoutMs,intervalMs)`, `seedAdkAgent`, `approveRunApproval`, `pollRunTerminal`, `runBounded`, `collectMetrics`, thin wrappers `reviewKnowledge/promoteKnowledge/toggleSkill/assignSkill/cancelWorkflow/retryWorkflow/deleteWorkflow/mergeWorkflows/executeYamlWorkflow`):

```typescript
import type { APIRequestContext } from '@playwright/test';

/**
 * Phase E shared fixtures.
 *
 * ALL seeding goes through the REST API via Playwright's APIRequestContext —
 * never through the UI. API_URL parameterizes the backend for isolated stacks
 * (worktrees / CI compose), matching the existing workflow-lifecycle specs.
 */
export const BACKEND = `${process.env.API_URL || 'http://localhost:8080'}/api/v1`;

export interface ApiResult<T = any> {
  status: number;
  data: T;
}

export async function apiCall(
  request: APIRequestContext,
  method: string,
  path: string,
  body?: object,
): Promise<ApiResult> {
  const resp = await request.fetch(`${BACKEND}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    data: body ? JSON.stringify(body) : undefined,
  });
  const data = await resp.json().catch(() => null);
  return { status: resp.status(), data };
}
```

The shared `pollUntil` from fixtures (lines 175-193) — signature: `pollUntil<T = any>(request, path, predicate: (data: T) => boolean, timeoutMs = 60_000, intervalMs = 2_000): Promise<T>`.

### 7c. `agent-control-tower/act-dashboard/playwright.config.ts` — FULL (43 lines), VERBATIM

```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  // Default tier: 2 min covers every mock/no-LLM spec. Specs that drive a real
  // LLM opt into 300s/600s via test.describe.configure({ timeout }) in-file.
  timeout: 120_000,
  fullyParallel: false,
  // CI-only single retry: quarantines transient infra flakes; local runs stay strict.
  retries: process.env.CI ? 1 : 0,
  // JSON reporter feeds the evidence pipeline (scripts/run-e2e-evidence.ps1).
  reporter: [['list'], ['json', { outputFile: process.env.PW_JSON_OUT || 'test-results/results.json' }]],
  use: {
    // BASE_URL override enables isolated local stacks (e.g. worktrees on alternate ports).
    baseURL: process.env.BASE_URL || 'http://localhost:5173',
    channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome',
    trace: 'retain-on-failure',
    screenshot: 'on',
    headless: !!process.env.CI,
    viewport: { width: 1400, height: 900 },
    actionTimeout: 15_000,
    // Headless chromium denies clipboard access by default; specs assert copy buttons.
    permissions: ['clipboard-read', 'clipboard-write'],
  },
  projects: [
    // UI specs (drive the React dashboard through a real browser). Excludes the
    // API-layer concurrency/load harness so those don't spin up a browser.
    {
      name: 'chromium',
      testIgnore: /e2e[\\/]api[\\/]/,
      use: { ...devices['Desktop Chrome'] },
    },
    // API-layer harness: REST/WebSocket concurrency + load specs under e2e/api/.
    // No browser needed (uses APIRequestContext); long timeout covers real-LLM
    // tiers and multi-tier load ramps.
    {
      name: 'api',
      testMatch: /e2e[\\/]api[\\/].*\.spec\.ts$/,
      timeout: 600_000,
    },
  ],
});
```

**IMPORTANT: there is NO `webServer` block** — backend (:8080) and frontend (:5173) must be started externally (docker compose / scripts; CI starts the stack per memory note "CI E2E stack为h2 profile且无OpenSandbox"). An MCP E2E spec that needs the MCP endpoint will hit the SAME externally-started backend at `API_URL`. A pure-API spec placed under `e2e/api/` runs in the `api` project (no browser, 600s timeout); a UI spec stays in `e2e/` root and runs in the `chromium` project.

---

## 8. MCP server package + workspace setup

### 8a. `packages/mcp-server/package.json` — FULL:

```json
{
  "name": "@aria-conductor/mcp-server",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "main": "dist/index.js",
  "scripts": {
    "build": "tsc",
    "dev": "tsx src/index.ts",
    "mcp": "node dist/index.js",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.12.1",
    "zod": "^3.24.4"
  },
  "devDependencies": {
    "tsx": "^4.19.4",
    "typescript": "^5.7.0",
    "@types/node": "^22.15.0",
    "vitest": "^3.2.4",
    "@vitest/coverage-istanbul": "^3.2.4"
  }
}
```

**MCP SDK version in use: `@modelcontextprotocol/sdk` `^1.12.1`.**

### 8b. Workspace setup — finding

- **NO `pnpm-workspace.yaml` exists anywhere in the repo** (searched maxdepth 3, excluding node_modules). No root `package.json` either.
- `agent-control-tower/act-dashboard/package.json` is a STANDALONE pnpm project: `"packageManager": "pnpm@9.15.9"`, deps shown in full above (`@playwright/test: ^1.61.0` in devDependencies). There IS a `node_modules` at the repo root (stray, from ad-hoc installs — not a workspace root with a lockfile).
- **Conclusion for the plan:** act-dashboard CANNOT import `packages/mcp-server` via workspace protocol (`workspace:*`). To use the MCP client SDK in the E2E spec, add `@modelcontextprotocol/sdk` to `agent-control-tower/act-dashboard/devDependencies` (or `dependencies`) and run `pnpm install` in `agent-control-tower/act-dashboard`. The e2e specs import only `@playwright/test` today; MCP client usage (e.g. `StreamableHTTPClientTransport` / `Client`) would be a new devDependency import inside the spec.

---

## 9. Correlation-ID filter (ordering anchor for McpTokenFilter)

`agent-control-tower/act-dashboard-api/src/main/java/io/aria/conductor/dashboard/config/CorrelationIdFilter.java` — FULL (37 lines):

```java
package io.aria.conductor.dashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
```

**Registration mechanism: plain `@Component` + `@Order(Ordered.HIGHEST_PRECEDENCE)` — NO `FilterRegistrationBean` anywhere in the repo** (grep for `FilterRegistrationBean` over all act-* Java sources returned zero matches). A new `McpTokenFilter` should follow the same pattern (`@Component` + an explicit `@Order` after `Ordered.HIGHEST_PRECEDENCE`, e.g. `@Order(Ordered.HIGHEST_PRECEDENCE + 10)`) so it runs after the correlation filter. Note act-dashboard-api is a module scanned by act-app's component scan (`io.aria.conductor` root package).

---

## 10. Spring AI facts (for Phase 2 backend-embedded MCP endpoint)

- **Parent pom Java property:** `<java.version>21</java.version>` (root pom line 32; plus explicit `maven.compiler.source/target` 21 and `--enable-preview` compiler args).
- **Boot baseline:** repo is on `spring-boot-starter-parent 3.3.5` (Phase 1 upgrades to 3.4.x).
- **Spring AI 1.0.x supports Spring Boot 3.4.x:** per Spring AI 1.0.x reference docs — "Spring AI supports Spring Boot 3.4.x and 3.5.x." Source: https://docs.spring.io/spring-ai/reference/1.0/getting-started.html (the current unversioned docs URL now documents Spring AI 2.0.x, which targets Boot 4.x — pin the /1.0/ URL).
- **MCP server starter artifact confirmed on Maven Central:** `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` — versions 1.0.0 through **1.0.9** (latest 1.0.x patch) exist; also 1.1.x and 2.0.x lines exist. Sources: https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-mcp-server-webmvc/maven-metadata.xml and https://mvnrepository.com/artifact/org.springframework.ai/spring-ai-starter-mcp-server-webmvc/1.0.3
- **BOM artifact confirmed:** `org.springframework.ai:spring-ai-bom` — 1.0.0…**1.0.9** (latest 1.0.x), then 1.1.0-M1…1.1.8, 2.0.0-M1…2.0.1. Source: https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml (fetched live 2026-XX, `latest` = 2.0.1).
- **Pin for the plan:** BOM import `org.springframework.ai:spring-ai-bom:1.0.9` in root `dependencyManagement` + dependency `spring-ai-starter-mcp-server-webmvc` (no version, managed by BOM) in act-mcp. Spring AI 1.0.9 upgrades the underlying MCP Java SDK to 0.18.3 (per v1.0.9 release notes, https://github.com/spring-projects/spring-ai/releases, tag v1.0.9).
- Caution: do NOT jump to Spring AI 1.1.x/2.0.x — 1.0.x is the line documented to support Boot 3.4.x.

---

## Gaps / notes for the plan author

1. **act-mcp does not exist yet** — everything above describes the template it will follow (act-knowledge pom pattern; act-app dependency list insertion point; root `<modules>` insertion point).
2. **No `webServer` in playwright.config.ts** — the MCP E2E spec must assume an externally-started backend; make the spec use `API_URL` like sdd-workflow.spec.ts does.
3. **No pnpm workspace** — `@modelcontextprotocol/sdk` must be added to act-dashboard devDependencies for the E2E spec to import the MCP client (SDK version in repo today: `^1.12.1` in packages/mcp-server; align or pin exact in dashboard).
4. **`--enable-preview`** is active on the Maven compiler + surefire/failsafe argLines — verify Boot 3.4 + Java 21 preview interaction when doing Phase 1 (no change strictly required, but the plan should mention the check).
5. **springdoc 2.6.0** is pinned for Boot 3.3; Boot 3.4 typically requires springdoc 2.7.0+ — plan should include the bump (property `springdoc.version` at root pom line 36).
6. `application-test.yml` overrides `opencode.sandbox-env` with only `DEEPSEEK_API_KEY` — any new MCP env vars added to the default `opencode.sandbox-env` in application.yml will NOT be present in the test profile unless added there too.
7. `WorkflowTemplateService` has no `yamlContent` passthrough param — template YAML always comes from `KnowledgeVersion`; parameter substitution + `{branchName}` injection happen server-side.

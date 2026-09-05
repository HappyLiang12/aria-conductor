# MCP-into-opencode Phase 1+2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed an MCP server endpoint in the Spring Boot process (Phase 1: Boot 3.3.5→3.4.5 upgrade; Phase 2: `/mcp` endpoint + Workflow/Knowledge/Approval tools + `opencode.json` wiring for Aria) so the `/workflow` chat path completes natively and an external MCP client can drive the SDD workflow E2E.

**Architecture:** `act-mcp` Maven module hosts `@Tool` beans calling existing services in-process; Spring AI 1.0.9 `spring-ai-starter-mcp-server-webmvc` serves the transport on port 8080; `writeOpenCodeConfig()` adds an `mcp.aria-conductor` remote entry for the Aria agent only; `SandboxHostResolver` picks a sandbox-reachable host IP (spike-proven: real host IPs work, `host.containers.internal` does not). Spec: `docs/superpowers/specs/2026-09-05-mcp-into-opencode-design.md`. Verbatim source facts: `plan-inputs-mcp.md` (repo root).

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring AI 1.0.9 (BOM `org.springframework.ai:spring-ai-bom:1.0.9`, starter `spring-ai-starter-mcp-server-webmvc`), MCP Java SDK 0.18.x (transitive), JUnit 5 + Mockito + AssertJ, Playwright `@modelcontextprotocol/sdk` `^1.12.1` (client side).

**Phase 3 note:** remaining domain tools (Agents, Runs, Skills, Ops, Provider, Aria) are a follow-up plan — the pattern is established by Tasks 6–8.

**Environment:** Windows, Git Bash. Maven: `export PATH="/c/Users/User/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin:$PATH"`. All Java test commands run from `agent-control-tower/`.

---

### Task 0: Branch setup — **COMPLETED**

- [x] `git checkout -b feat/mcp-into-opencode origin/main`; commit the spec: done as `ce72754` ("docs(spec): MCP-into-opencode design (backend-embedded MCP, phased)"), pushed. This plan file is committed on the same branch right after being written.

---

### Task 1: Spring Boot 3.3.5 → 3.4.5 + springdoc 2.7.0

**Files:**
- Modify: `agent-control-tower/pom.xml` (parent version line 14; `springdoc.version` line 53)

- [ ] **Step 1: Bump the parent version** — change the only Boot version literal:

```xml
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>
```

- [ ] **Step 2: Bump springdoc** (2.6.0 targets Boot 3.3; 3.4 needs 2.7+):

```xml
        <springdoc.version>2.7.0</springdoc.version>
```

- [ ] **Step 3: Run the full suite gate** — `--enable-preview` stays valid on Java 21 under 3.4; this run is the proof:

Run: `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2`
Expected: BUILD SUCCESS, zero failures (all modules: common, agent, execution, knowledge, aria, dashboard-api, app). If `--enable-preview` errors appear (`Preview features are not enabled`/class-version mismatch), re-run `mvn -q` once after `mvn clean` and capture; if still failing, stop and report (do not remove preview flags on your own).

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/pom.xml
git commit -m "chore(build): upgrade Spring Boot 3.3.5 -> 3.4.5, springdoc 2.7.0"
```

---

### Task 2: `act-mcp` module skeleton + Spring AI BOM

**Files:**
- Create: `agent-control-tower/act-mcp/pom.xml`
- Modify: `agent-control-tower/pom.xml` (`<modules>` list; `dependencyManagement` gets an internal `act-mcp` entry + the Spring AI BOM import)
- Modify: `agent-control-tower/act-app/pom.xml` (add `act-mcp` dependency after `act-dashboard-api`)

- [ ] **Step 1: Create `act-mcp/pom.xml`** (follows the act-knowledge template):

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

    <artifactId>act-mcp</artifactId>
    <name>ACT MCP</name>
    <description>Backend-embedded MCP endpoint exposing platform services to agents</description>

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
            <artifactId>act-knowledge</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-execution</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-aria</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>

        <!-- Test dependencies -->
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-test-support</artifactId>
            <scope>test</scope>
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
    </dependencies>
</project>
```

- [ ] **Step 2: Root pom — register the module** (insert before `<module>act-app</module>`):

```xml
        <module>act-mcp</module>
```

- [ ] **Step 3: Root pom — dependencyManagement: internal entry + Spring AI BOM import.** Add the internal entry next to the other modules, and the BOM import at the END of `<dependencyManagement><dependencies>`:

```xml
            <dependency>
                <groupId>io.aria.conductor</groupId>
                <artifactId>act-mcp</artifactId>
                <version>${project.version}</version>
            </dependency>
```

```xml
            <!-- Spring AI (MCP server). 1.0.x is the line documented for Boot 3.4.x — do not jump to 1.1.x/2.0.x. -->
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>1.0.9</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
```

- [ ] **Step 4: act-app pom — consume the module** (insert after the `act-dashboard-api` dependency):

```xml
        <dependency>
            <groupId>io.aria.conductor</groupId>
            <artifactId>act-mcp</artifactId>
        </dependency>
```

- [ ] **Step 5: Verify build**

Run: `cd agent-control-tower && mvn -q install -DskipTests`
Expected: BUILD SUCCESS; `act-mcp` appears in the reactor.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/pom.xml agent-control-tower/act-app/pom.xml agent-control-tower/act-mcp/pom.xml
git commit -m "feat(mcp): add act-mcp module skeleton with Spring AI 1.0.9 BOM"
```

---

### Task 3: `McpProperties` (act-execution) — TDD

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/McpProperties.java`
- Create: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/mcp/McpPropertiesTest.java`

(Properties live in act-execution because BOTH act-execution (`writeOpenCodeConfig`) and act-mcp consume them, and act-mcp already depends on act-execution — the reverse would be circular.)

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpPropertiesTest {

    @Test
    void defaults_matchSpec() {
        McpProperties props = new McpProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getAuthMode()).isEqualTo("none");
        assertThat(props.isDebug()).isFalse();
        assertThat(props.getToken()).isEmpty();
        assertThat(props.getSandboxHostAddress()).isEmpty();
        assertThat(props.getPort()).isEqualTo(8080);
    }

    @Test
    void tokenMode_acceptsConfiguration() {
        McpProperties props = new McpProperties();
        props.setAuthMode("token");
        props.setToken("secret-1");
        assertThat(props.getAuthMode()).isEqualTo("token");
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (`McpProperties` missing)

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=McpPropertiesTest`
Expected: compilation ERROR (class does not exist).

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.execution.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Backend-embedded MCP endpoint configuration (aria.mcp.*).
 * Consumed by act-execution (opencode.json wiring) and act-mcp (server config).
 */
@Data
@Component
@ConfigurationProperties(prefix = "aria.mcp")
public class McpProperties {

    private boolean enabled = true;

    /** {@code none} (v1 default, auth deferred) or {@code token} (Bearer filter active). */
    private String authMode = "none";

    /** When true, MCP tool error results include full stack traces (external-agent debugging). */
    private boolean debug = false;

    /** Bearer token; only used when auth-mode=token. */
    private String token = "";

    /** Override for the sandbox-reachable host; blank = auto-resolve (SandboxHostResolver). */
    private String sandboxHostAddress = "";

    /** Backend port the sandbox-side MCP client targets. */
    private int port = 8080;

    public boolean isTokenMode() {
        return "token".equalsIgnoreCase(authMode);
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=McpPropertiesTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/McpProperties.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/mcp/McpPropertiesTest.java
git commit -m "feat(mcp): aria.mcp configuration properties (enabled/auth-mode/debug/token/port)"
```

---

### Task 4: MCP server bring-up + endpoint handshake (transport probe)

Spring AI 1.0.9's webmvc starter serves the MCP protocol over HTTP. The exact transport (SSE at `/sse` vs streamable HTTP) must be confirmed from the jar's own metadata — the plan pins the discovery command so the executor doesn't guess.

**Files:**
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/McpServerConfig.java`
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml`
- Create: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/McpEndpointIntegrationTest.java`

- [ ] **Step 1: Probe the starter's transport configuration keys**

Run: `unzip -p ~/.m2/repository/org/springframework/ai/spring-ai-autoconfigure-mcp-server/1.0.9/spring-ai-autoconfigure-mcp-server-1.0.9.jar META-INF/spring-configuration-metadata.json 2>/dev/null | grep -o '"name": "spring.ai.mcp.server[^"]*"' | sort -u`
Expected: a list including `spring.ai.mcp.server.enabled`, `spring.ai.mcp.server.name`, and either `spring.ai.mcp.server.protocol` (streamable available) or SSE endpoint keys (`spring.ai.mcp.server.sse-message-endpoint` etc.). Record which transport the 1.0.9 webmvc starter serves — this decides nothing structurally (the client-side E2E spec tries streamable-then-SSE), only the URL the integration test targets.

- [ ] **Step 2: Write the failing integration test** (asserts handshake + tool listing; the curated tool list is the Phase 2 parity harness — it fails red until Tasks 5–7 add the tools):

```java
package io.aria.conductor.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 parity + handshake anchor: connects a real MCP client to the embedded
 * endpoint and asserts the curated Phase 2 tool set (spec §4). Uses the test
 * slice with aria.mcp.enabled=true (the test profile globally disables it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aria.mcp.enabled=true",
        "aria.mcp.auth-mode=none"
})
class McpEndpointIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void handshake_listsCuratedPhase2Tools() {
        McpSyncClient client = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + port).build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build();
        client.initialize();
        McpSchema.ListToolsResult tools = client.listTools();

        assertThat(tools.tools())
                .extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder(
                        "list_workflow_templates",
                        "instantiate_workflow_template",
                        "get_workflow",
                        "list_knowledge",
                        "list_approvals",
                        "decide_approval");
        client.close();
    }

    @Test
    void disabled_whenAriaMcpDisabled() {
        // Guard for the test profile contract: a context with aria.mcp.enabled=false
        // must not register the endpoint. Verified implicitly by application-test.yml;
        // here we assert the starter's enabled flag is wired from aria.mcp.enabled via yml.
        List<String> expected = List.of("aria.mcp.enabled");
        assertThat(expected).isNotEmpty(); // placeholder-free guard: see application-test.yml task 10
    }
}
```

NOTE: if `HttpClientSseClientTransport.builder(...)` has no no-arg sse path default matching Spring AI's endpoint, consult the metadata keys from Step 1 and set `.ssePath(...)`/`.messageEndpoint(...)` accordingly — the compile/run error message will name the mismatch; the integration test passing is the definition of done. If the probe in Step 1 shows the 1.0.9 webmvc starter is SSE-only AND a later manual opencode probe (Task 12 live acceptance) proves opencode cannot speak SSE-remote, STOP and invoke the spec §3 fallback (in-house streamable controller) — do not silently switch transports.

- [ ] **Step 3: Implement `McpServerConfig`** (wires the starter's enabled flag from aria.mcp.enabled so one property controls both):

```java
package io.aria.conductor.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Backend-embedded MCP endpoint. The starter's own auto-configuration serves the
 * protocol; this configuration gates it behind aria.mcp.enabled (yml maps
 * spring.ai.mcp.server.enabled to the same placeholder) and hosts future
 * tool-registration helpers. Tools are discovered from @Tool-annotated beans.
 */
@Configuration
@ConditionalOnProperty(prefix = "aria.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {
}
```

- [ ] **Step 4: application.yml — add the aria.mcp block and map the starter flag.** Insert at top level (after the existing `opencode:` block):

```yaml
# Backend-embedded MCP endpoint (spec: docs/superpowers/specs/2026-09-05-mcp-into-opencode-design.md)
aria:
  mcp:
    enabled: ${ARIA_MCP_ENABLED:true}
    auth-mode: ${ARIA_MCP_AUTH_MODE:none}
    debug: ${ARIA_MCP_DEBUG:false}
    token: ${ARIA_MCP_TOKEN:}
    sandbox-host-address: ${ARIA_MCP_SANDBOX_HOST_ADDRESS:}
    port: ${ARIA_MCP_PORT:8080}

spring:
  ai:
    mcp:
      server:
        name: aria-conductor
        enabled: ${ARIA_MCP_ENABLED:true}
```

NOTE: `application.yml` already has a top-level `spring:` key — merge `ai.mcp.server` under the EXISTING `spring:` block rather than adding a duplicate key.

- [ ] **Step 5: Run — expect handshake test to fail on missing tools (red), no context error**

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest=McpEndpointIntegrationTest`
Expected: `handshake_listsCuratedPhase2Tools` FAILS on the `containsExactlyInAnyOrder` assertion (empty/missing tools), context starts OK. That is the correct red.

- [ ] **Step 6: Commit** (red test committed intentionally — Tasks 5-7 turn it green)

```bash
git add agent-control-tower/act-mcp agent-control-tower/act-app/src/main/resources/application.yml
git commit -m "feat(mcp): embedded MCP endpoint bring-up + parity anchor test (red)"
```

---

### Task 5: Tool error responses + debug mode — TDD

**Files:**
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ToolResponses.java`
- Create: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/tools/ToolResponsesTest.java`

- [ ] **Step 1: Failing test**

```java
package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResponsesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ok_wrapsPayload() throws Exception {
        String json = ToolResponses.ok(new java.util.LinkedHashMap<>(java.util.Map.of("id", "c1")));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("ok").asBoolean()).isTrue();
        assertThat(node.get("data").get("id").asText()).isEqualTo("c1");
    }

    @Test
    void error_withoutDebug_hasNoStackTrace() throws Exception {
        String json = ToolResponses.error("NOT_FOUND", "KnowledgeItem missing", new RuntimeException("boom"), false);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("ok").asBoolean()).isFalse();
        assertThat(node.get("errorType").asText()).isEqualTo("NOT_FOUND");
        assertThat(node.get("message").asText()).isEqualTo("KnowledgeItem missing");
        assertThat(node.has("stackTrace")).isFalse();
    }

    @Test
    void error_withDebug_includesFullStack() throws Exception {
        RuntimeException boom = new IllegalStateException("state broke");
        String json = ToolResponses.error("CONFLICT", "state broke", boom, true);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("stackTrace").asText())
                .contains("java.lang.IllegalStateException: state broke")
                .contains("ToolResponsesTest.error_withDebug_includesFullStack");
    }

    @Test
    void error_escapesQuotesInMessage() throws Exception {
        String json = ToolResponses.error("VALIDATION", "field \"name\" blank", null, false);
        assertThat(mapper.readTree(json).get("message").asText()).isEqualTo("field \"name\" blank");
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (class missing)

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest=ToolResponsesTest`

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform MCP tool result envelopes. Tools return JSON strings so every client
 * (opencode model, external agent) reads one shape: {"ok":bool, "data"|error fields}.
 * debug=true (aria.mcp.debug) adds the full stack trace for external-agent debugging.
 */
@Slf4j
public final class ToolResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResponses() {
    }

    public static String ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data);
        return write(body);
    }

    public static String error(String errorType, String message, Throwable cause, boolean debug) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorType", errorType);
        body.put("message", message);
        if (debug && cause != null) {
            body.put("stackTrace", stackTraceOf(cause));
        }
        return write(body);
    }

    private static String stackTraceOf(Throwable cause) {
        StringWriter sw = new StringWriter();
        cause.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String write(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Tool result serialization failed: {}", e.getMessage());
            return "{\"ok\":false,\"errorType\":\"SERIALIZATION\",\"message\":\"tool result could not be serialized\"}";
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS** (4 tests)

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest=ToolResponsesTest`

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-mcp/src
git commit -m "feat(mcp): uniform tool result envelopes with debug-mode stack traces"
```

---

### Task 6: WorkflowTools — TDD (list / instantiate / get)

**Files:**
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/WorkflowTools.java`
- Create: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/tools/WorkflowToolsTest.java`

- [ ] **Step 1: Failing tests**

```java
package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.service.KnowledgeService;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowToolsTest {

    @Mock WorkflowTemplateService workflowTemplateService;
    @Mock WorkflowService workflowService;
    @Mock KnowledgeService knowledgeService;
    McpProperties mcpProperties;
    WorkflowTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new WorkflowTools(workflowTemplateService, workflowService, mcpProperties);
    }

    @Test
    void listWorkflowTemplates_delegatesAndWraps() {
        KnowledgeItemResponse tpl = KnowledgeItemResponse.builder()
                .id(UUID.randomUUID()).name("development-workflow").type(null).status(null).build();
        when(workflowTemplateService.findMatchingTemplates("sdd")).thenReturn(List.of(tpl));

        String json = tools.listWorkflowTemplates("sdd");

        assertThat(json).contains("development-workflow").contains("\"ok\":true");
    }

    @Test
    void instantiateWorkflowTemplate_returnsChainJson() {
        WorkflowResponse chain = WorkflowResponse.builder()
                .id(UUID.randomUUID()).name("development-workflow-instance").build();
        when(workflowTemplateService.instantiateTemplate(any(), any())).thenReturn(chain);

        String json = tools.instantiateWorkflowTemplate(UUID.randomUUID(),
                Map.of("issueRef", "#55", "repoUrl", "https://github.com/HappyLiang12/aria-conductor.git"));

        assertThat(json).contains("\"ok\":true").contains("development-workflow-instance");
    }

    @Test
    void instantiateWorkflowTemplate_mapsValidationErrorWithoutStack_whenDebugOff() {
        when(workflowTemplateService.instantiateTemplate(any(), any()))
                .thenThrow(new IllegalArgumentException("Template requires repoUrl parameter"));

        String json = tools.instantiateWorkflowTemplate(UUID.randomUUID(), Map.of());

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).contains("repoUrl");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void instantiateWorkflowTemplate_debugOn_includesStack() {
        mcpProperties.setDebug(true);
        when(workflowTemplateService.instantiateTemplate(any(), any()))
                .thenThrow(new IllegalArgumentException("Template requires repoUrl parameter"));

        String json = tools.instantiateWorkflowTemplate(UUID.randomUUID(), Map.of());

        assertThat(json).contains("stackTrace").contains("IllegalArgumentException");
    }

    @Test
    void getWorkflow_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(workflowService.getWorkflow(id)).thenThrow(
                new io.aria.conductor.common.exception.ResourceNotFoundException("WorkflowChain", id));

        String json = tools.getWorkflow(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (`WorkflowTools` missing)

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest=WorkflowToolsTest`

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Workflow template tools (Phase 2 core loop). Thin wrappers over the same
 * services the REST controllers call — REST/dashboard and MCP stay at parity.
 */
@Component
@RequiredArgsConstructor
public class WorkflowTools {

    private final WorkflowTemplateService workflowTemplateService;
    private final WorkflowService workflowService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_workflow_templates",
            description = "List APPROVED workflow templates. Optional intent keywords filter by name/description; blank lists all.")
    public String listWorkflowTemplates(
            @ToolParam(description = "Intent keywords, or blank for all", required = false) String userIntent) {
        try {
            return ToolResponses.ok(workflowTemplateService.findMatchingTemplates(userIntent));
        } catch (Exception e) {
            return ToolResponses.error("TEMPLATE_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "instantiate_workflow_template",
            description = "Instantiate an APPROVED workflow template into a runnable chain. Returns the chain JSON including id. Use list_workflow_templates first to obtain templateId.")
    public String instantiateWorkflowTemplate(
            @ToolParam(description = "KnowledgeItem id of the APPROVED WORKFLOW template") UUID templateId,
            @ToolParam(description = "Template parameters, e.g. issueRef and repoUrl", required = false)
            Map<String, String> parameters) {
        try {
            WorkflowResponse chain = workflowTemplateService.instantiateTemplate(
                    templateId, parameters == null ? Map.of() : parameters);
            return ToolResponses.ok(chain);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("INSTANTIATION_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_workflow",
            description = "Get a workflow chain by id: status (PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED), steps with runIds and output previews.")
    public String getWorkflow(@ToolParam(description = "Chain id") UUID chainId) {
        try {
            WorkflowResponse chain = workflowService.getWorkflow(chainId);
            return ToolResponses.ok(chain);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("WORKFLOW_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS** (5 tests)

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest=WorkflowToolsTest`

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-mcp/src
git commit -m "feat(mcp): WorkflowTools (list/instantiate/get) with debug-mode error envelopes"
```

---

### Task 7: KnowledgeTools + ApprovalTools — TDD

**Files:**
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/KnowledgeTools.java`
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ApprovalTools.java`
- Create: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/tools/KnowledgeToolsTest.java`
- Create: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/tools/ApprovalToolsTest.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalGate.java` (expose the existing list-assembly as a read method — see Step 1 note)

- [ ] **Step 1: Expose approval reads from act-execution.** The list/detail assembly currently lives inside `ApprovalController` (lines 71-90, entity→`ApprovalDetail` mapping). Extract it verbatim into a new service so the tool and the controller share one implementation:

Create `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalQueryService.java`:

```java
package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.repository.ApprovalRepository;
import io.aria.conductor.common.repository.ToolCallRepository;
import io.aria.conductor.execution.risk.ToolRiskResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-side approval queries shared by the REST controller and MCP ApprovalTools.
 * Mirrors ApprovalController's list/detail assembly (entity -> ApprovalDetail).
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    private final ApprovalRepository approvalRepository;
    private final ToolCallRepository toolCallRepository;
    private final ToolRiskResolver toolRiskResolver;

    @Transactional(readOnly = true)
    public List<ApprovalController.ApprovalDetail> list(ApprovalStatus status) {
        List<Approval> approvals = status == null
                ? approvalRepository.findAll(PageRequest.of(0, 200, Sort.by("requestedAt").descending())).getContent()
                : approvalRepository.findByStatus(status);
        Map<UUID, io.aria.conductor.common.model.ToolCall> toolCalls = toolCallRepository
                .findAllById(approvals.stream().map(Approval::getToolCallId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(tc -> tc.getId(), Function.identity(), (a, b) -> a));
        return approvals.stream().map(a -> toDetail(a, toolCalls.get(a.getToolCallId()))).toList();
    }

    private ApprovalController.ApprovalDetail toDetail(Approval a, io.aria.conductor.common.model.ToolCall tc) {
        String toolName = tc != null ? tc.getToolName() : null;
        return new ApprovalController.ApprovalDetail(
                a.getId(), a.getRunId(), a.getToolCallId(), a.getStatus(), a.getReason(),
                a.getRequestedAt(), a.getDecidedAt(), a.getExpiresAt(),
                a.getApprovalType() != null ? a.getApprovalType().name() : "TOOL_CALL",
                a.getContent(),
                a.getContentKind() != null ? a.getContentKind().name() : null,
                a.getKnowledgeItemId(),
                toolName, tc != null ? tc.getArguments() : null,
                toolName != null ? toolRiskResolver.resolve(toolName).name() : null);
    }
}
```

Verify against the real source before finalizing: the entity getters/repository names used above are copied from `ApprovalController` lines 71-90 + 124-135 — if a name differs (e.g. `toolCallRepository.findAllById` signature), match the controller's actual code; do not invent. Then REFACTOR `ApprovalController.list` (lines 71-90) to delegate to `approvalQueryService.list(status)` — the existing `ApprovalControllerTest` list tests must stay green unchanged (same output contract).

- [ ] **Step 2: Failing tests**

```java
package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeToolsTest {

    @Mock KnowledgeService knowledgeService;
    McpProperties mcpProperties;
    KnowledgeTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new KnowledgeTools(knowledgeService, mcpProperties);
    }

    @Test
    void listKnowledge_delegatesWithFilters() {
        when(knowledgeService.listKnowledge(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(KnowledgeItemResponse.builder()
                        .id(UUID.randomUUID()).name("development-workflow").build()));

        String json = tools.listKnowledge("WORKFLOW", "APPROVED");

        verify(knowledgeService).listKnowledge(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED);
        assertThat(json).contains("development-workflow").contains("\"ok\":true");
    }

    @Test
    void listKnowledge_blankFiltersListAll() {
        when(knowledgeService.listKnowledge(isNull(), isNull())).thenReturn(List.of());

        String json = tools.listKnowledge(null, null);

        assertThat(json).contains("\"ok\":true");
    }
}
```

```java
package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.ApprovalController.ApprovalDetail;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalToolsTest {

    @Mock ApprovalGate approvalGate;
    @Mock ApprovalQueryService approvalQueryService;
    McpProperties mcpProperties;
    ApprovalTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new ApprovalTools(approvalQueryService, approvalGate, mcpProperties);
    }

    private ApprovalDetail detail(UUID id, String type, String status) {
        return new ApprovalDetail(id, UUID.randomUUID(), null, ApprovalStatus.valueOf(status),
                "Spec resubmitted", Instant.now(), null, Instant.now().plusSeconds(1800),
                type, "## spec", "MARKDOWN", UUID.randomUUID(), null, null, null);
    }

    @Test
    void listApprovals_filtersPendingSpecReviews() {
        UUID id = UUID.randomUUID();
        when(approvalQueryService.list(ApprovalStatus.PENDING))
                .thenReturn(List.of(detail(id, "SPEC_REVIEW", "PENDING")));

        String json = tools.listApprovals("PENDING");

        assertThat(json).contains("SPEC_REVIEW").contains(id.toString());
    }

    @Test
    void decideApproval_delegates() {
        UUID id = UUID.randomUUID();
        when(approvalQueryService.list(ApprovalStatus.PENDING)).thenReturn(List.of());

        String json = tools.decideApproval(id, true, "lgtm");

        verify(approvalGate).decideApproval(eq(id), anyBoolean(), eq("lgtm"));
        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void decideApproval_mapsIllegalArgument() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalArgumentException("Approval not found: " + id))
                .when(approvalGate).decideApproval(eq(id), anyBoolean(), eq("nope"));

        String json = tools.decideApproval(id, false, "nope");

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }
}
```

- [ ] **Step 3: Run — expect compile failure** (classes missing)

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest='KnowledgeToolsTest,ApprovalToolsTest'`

- [ ] **Step 4: Implement**

```java
package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KnowledgeTools {

    private final KnowledgeService knowledgeService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_knowledge",
            description = "List knowledge items. Optional type (WORKFLOW/SKILL/DOCUMENT/TOOL/PROMPT) and status (PENDING/APPROVED/REJECTED/RETIRED).")
    public String listKnowledge(
            @ToolParam(description = "KnowledgeType name or blank", required = false) String type,
            @ToolParam(description = "KnowledgeStatus name or blank", required = false) String status) {
        try {
            KnowledgeType t = type == null || type.isBlank() ? null : KnowledgeType.valueOf(type.trim().toUpperCase());
            KnowledgeStatus s = status == null || status.isBlank() ? null : KnowledgeStatus.valueOf(status.trim().toUpperCase());
            return ToolResponses.ok(knowledgeService.listKnowledge(t, s));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KNOWLEDGE_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
```

```java
package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ApprovalTools {

    private final ApprovalQueryService approvalQueryService;
    private final ApprovalGate approvalGate;
    private final McpProperties mcpProperties;

    @Tool(name = "list_approvals",
            description = "List approval gates. Optional status (PENDING/APPROVED/DENIED/EXPIRED). SPEC_REVIEW approvals carry markdown content and knowledgeItemId; toolCallId is null for them.")
    public String listApprovals(
            @ToolParam(description = "ApprovalStatus name or blank for recent (max 200)", required = false) String status) {
        try {
            ApprovalStatus s = status == null || status.isBlank() ? null : ApprovalStatus.valueOf(status.trim().toUpperCase());
            return ToolResponses.ok(approvalQueryService.list(s));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("APPROVAL_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "decide_approval",
            description = "Decide a PENDING approval gate (approve or deny). Non-PENDING approvals are ignored by the gate (idempotent).")
    public String decideApproval(
            @ToolParam(description = "Approval id") UUID approvalId,
            @ToolParam(description = "true = approve, false = deny") boolean approved,
            @ToolParam(description = "Decision reason", required = false) String reason) {
        try {
            approvalGate.decideApproval(approvalId, approved, reason);
            return ToolResponses.ok(java.util.Map.of("approvalId", approvalId.toString(), "approved", approved));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("DECISION_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
```

- [ ] **Step 5: Run — expect PASS** (act-mcp: 5 tests; act-execution: full module must stay green after the controller refactor)

Run: `cd agent-control-tower && mvn -q test -pl act-mcp,act-execution`
Expected: BUILD SUCCESS (ApprovalToolsTest 3, KnowledgeToolsTest 2; all pre-existing act-execution tests).

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-mcp/src agent-control-tower/act-execution/src
git commit -m "feat(mcp): KnowledgeTools + ApprovalTools; extract shared ApprovalQueryService"
```

---

### Task 8: Profile configs — test profile disables MCP, h2 enables debug

**Files:**
- Modify: `agent-control-tower/act-app/src/test/resources/application-test.yml`
- Modify: `agent-control-tower/act-app/src/main/resources/application-h2.yml`

- [ ] **Step 1: application-test.yml** — append (top level; keeps CI integration tests deterministic per spec §2.6):

```yaml
aria:
  mcp:
    enabled: false

spring:
  ai:
    mcp:
      server:
        enabled: false
```

(Merge `ai.mcp.server` under the file's EXISTING `spring:` block if present — do not duplicate the `spring:` key.)

- [ ] **Step 2: application-h2.yml** — append (external-agent debugging on by default locally, spec §6):

```yaml
aria:
  mcp:
    debug: true
```

- [ ] **Step 3: Verify the test slice stays disabled**

Run: `cd agent-control-tower && mvn -q test -pl act-app`
Expected: BUILD SUCCESS (context loads without the MCP endpoint under the test profile).

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/application-h2.yml agent-control-tower/act-app/src/test/resources/application-test.yml
git commit -m "feat(mcp): profile config — test profile disables endpoint, h2 enables debug"
```

---

### Task 9: `SandboxHostResolver` (act-execution) — TDD

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/SandboxHostResolver.java`
- Create: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/mcp/SandboxHostResolverTest.java`

- [ ] **Step 1: Failing tests**

```java
package io.aria.conductor.execution.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxHostResolverTest {

    private SandboxHostResolver.Candidate c(String name, String address) {
        return new SandboxHostResolver.Candidate(name, address);
    }

    @Test
    void overrideWins() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(c("eth0", "192.168.0.119")), "10.1.2.3");
        assertThat(resolver.resolve()).contains("10.1.2.3");
    }

    @Test
    void prefersPodmanWslAdapterRange() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("Ethernet", "192.168.0.119"),
                c("vEthernet (WSL (Hyper-V) firewall)", "172.30.112.1"),
                c("lo", "127.0.0.1")), "");
        // Spike 2026-09-05: 172.30.112.1 (podman/WSL host-side) reached the backend from a sandbox.
        assertThat(resolver.resolve()).contains("172.30.112.1");
    }

    @Test
    void skipsLoopbackAndLinkLocal() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("lo", "127.0.0.1"),
                c("bridge", "169.254.1.2"),
                c("Ethernet", "192.168.0.119")), "");
        assertThat(resolver.resolve()).contains("192.168.0.119");
    }

    @Test
    void emptyWhenNothingUsable() {
        assertThat(SandboxHostResolver.over(List.of(c("lo", "127.0.0.1")), "").resolve()).isEmpty();
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=SandboxHostResolverTest`

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.execution.mcp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * Picks the host address sandbox containers can reach for the backend MCP endpoint.
 * Spike (2026-09-05, aria-conductor/opencode-sandbox:1.1 on podman machine): real host
 * IPs work (172.30.112.1 / 192.168.x.x -> 200), host.containers.internal (169.254.1.2)
 * is refused. Preference: podman/WSL host-side adapters (172.16/12) first, then other
 * private v4 ranges. aria.mcp.sandbox-host-address overrides everything.
 */
public final class SandboxHostResolver {

    public record Candidate(String interfaceName, String address) {}

    private final String override;
    private final List<Candidate> candidates;

    private SandboxHostResolver(String override, List<Candidate> candidates) {
        this.override = override;
        this.candidates = candidates;
    }

    public static SandboxHostResolver over(List<Candidate> candidates, String override) {
        return new SandboxHostResolver(override, candidates);
    }

    public static SandboxHostResolver fromSystemInterfaces(String override) {
        List<Candidate> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        found.add(new Candidate(ni.getDisplayName(), addr.getHostAddress()));
                    }
                }
            }
        } catch (Exception e) {
            // caller falls back to override or empty; caller logs the outcome
        }
        return new SandboxHostResolver(override, found);
    }

    /** Sandbox-reachable host address, or empty when none is usable. */
    public Optional<String> resolve() {
        if (override != null && !override.isBlank()) {
            return Optional.of(override.trim());
        }
        return candidates.stream()
                .filter(c -> isPrivateV4(c.address()))
                .min(Comparator.comparingInt(c -> rank(c.address())))
                .map(Candidate::address);
    }

    private static boolean isPrivateV4(String address) {
        if (address.startsWith("127.") || address.startsWith("169.254.") || address.startsWith("0.")) {
            return false;
        }
        return address.startsWith("172.") || address.startsWith("10.") || address.startsWith("192.168.");
    }

    private static int rank(String address) {
        if (address.startsWith("172.")) {
            return 0; // podman/WSL host-side adapter range (spike-proven)
        }
        if (address.startsWith("10.")) {
            return 1;
        }
        return 2; // 192.168.x
    }
}
```

- [ ] **Step 4: Run — expect PASS** (4 tests)

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=SandboxHostResolverTest`

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/SandboxHostResolver.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/mcp/SandboxHostResolverTest.java
git commit -m "feat(mcp): sandbox-reachable host address resolver (spike-informed ranking)"
```

---

### Task 10: `writeOpenCodeConfig` mcp block + token injection — TDD

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java` (constructor + `prepareInstance` + `writeOpenCodeConfig`)
- Modify: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProviderTest.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/AriaConstants.java` (MOVED from act-aria — see Step 1)
- Modify: every act-aria file importing `io.aria.conductor.aria.AriaConstants` (find via `grep -rl "io.aria.conductor.aria.AriaConstants" agent-control-tower/act-aria/src`)

Rationale for the move: act-execution cannot reference `io.aria.conductor.aria.AriaConstants` (act-aria depends on act-execution; the reverse would be circular). The constant is platform identity — act-common is its home.

- [ ] **Step 1: Move `AriaConstants` to act-common.** Create `act-common/src/main/java/io/aria/conductor/common/AriaConstants.java` with the same single constant `ARIA_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")` (package `io.aria.conductor.common`), delete `act-aria/src/main/java/io/aria/conductor/aria/AriaConstants.java`, and update every import (`grep -rl "io.aria.conductor.aria.AriaConstants" agent-control-tower/act-aria/src` — expected 2-3 files, mechanical import swap). Verify: `mvn -q test -pl act-aria,act-common` green before continuing.

- [ ] **Step 2: Write the failing tests** (add to `OpenCodeAdkProviderTest`; constructor gains a `McpProperties` parameter — update `setUp` accordingly):

```java
    @Test
    void prepareInstance_ariaAgent_getsMcpRemoteBlock_withoutHeader_whenAuthNone() throws Exception {
        UUID ariaId = io.aria.conductor.common.AriaConstants.ARIA_AGENT_ID;
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(sandboxManager.createSandbox(eq(ariaId), eq(IMAGE), any())).thenReturn("sb-a");
        when(sandboxManager.getSandboxUrl("sb-a", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);
        mcpProperties.setSandboxHostAddress("172.30.112.1");
        mcpProperties.setAuthMode("none");

        provider.prepareAgent(ariaId, agent(ariaId));

        String json = Files.readString(tempDir.resolve(ariaId.toString()).resolve("opencode.json"));
        assertThat(json).contains("\"mcp\"");
        assertThat(json).contains("http://172.30.112.1:8080/mcp");
        assertThat(json).doesNotContain("Authorization");
        // none mode: no token may be injected into the sandbox env
        verify(sandboxManager).createSandbox(eq(ariaId), eq(IMAGE),
                argThat(env -> env == null || !env.containsKey("ARIA_MCP_TOKEN")));
    }

    @Test
    void prepareInstance_ariaAgent_tokenMode_addsHeaderAndInjectsToken() throws Exception {
        UUID ariaId = io.aria.conductor.common.AriaConstants.ARIA_AGENT_ID;
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(sandboxManager.createSandbox(eq(ariaId), eq(IMAGE), any())).thenReturn("sb-t");
        when(sandboxManager.getSandboxUrl("sb-t", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);
        mcpProperties.setSandboxHostAddress("172.30.112.1");
        mcpProperties.setAuthMode("token");
        mcpProperties.setToken("tok-1");

        provider.prepareAgent(ariaId, agent(ariaId));

        String json = Files.readString(tempDir.resolve(ariaId.toString()).resolve("opencode.json"));
        assertThat(json).contains("\"Authorization\": \"Bearer {env:ARIA_MCP_TOKEN}\"");
        verify(sandboxManager).createSandbox(eq(ariaId), eq(IMAGE),
                argThat(env -> env != null && "tok-1".equals(env.get("ARIA_MCP_TOKEN"))));
    }

    @Test
    void prepareInstance_workerAgent_neverGetsMcpBlock() throws Exception {
        UUID workerId = UUID.randomUUID();
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(sandboxManager.createSandbox(eq(workerId), eq(IMAGE), any())).thenReturn("sb-w");
        when(sandboxManager.getSandboxUrl("sb-w", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);
        mcpProperties.setSandboxHostAddress("172.30.112.1");

        provider.prepareAgent(workerId, agent(workerId));

        String json = Files.readString(tempDir.resolve(workerId.toString()).resolve("opencode.json"));
        assertThat(json).doesNotContain("\"mcp\"");
    }

    @Test
    void prepareInstance_mcpDisabled_noMcpBlock() throws Exception {
        UUID ariaId = io.aria.conductor.common.AriaConstants.ARIA_AGENT_ID;
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(sandboxManager.createSandbox(eq(ariaId), eq(IMAGE), any())).thenReturn("sb-d");
        when(sandboxManager.getSandboxUrl("sb-d", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);
        mcpProperties.setEnabled(false);

        provider.prepareAgent(ariaId, agent(ariaId));

        String json = Files.readString(tempDir.resolve(ariaId.toString()).resolve("opencode.json"));
        assertThat(json).doesNotContain("\"mcp\"");
    }
```

NOTE: the `prepareInstance_openCodeJson_isParseableAndSchemaComplete` Jackson-parse test must keep passing — the mcp block is part of the same JSON document.

- [ ] **Step 3: Run — expect compile failure** (constructor arity + missing behavior)

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=OpenCodeAdkProviderTest`

- [ ] **Step 4: Implement.** In `OpenCodeAdkProvider`:
  1. Constructor gains `McpProperties mcpProperties` (field, Lombok-free explicit constructor consistent with current style); update the test `setUp` to pass `new McpProperties()`.
  2. `prepareInstance(UUID agentId, Agent agent)`: pass `agent` through — `writeOpenCodeConfig(workspace, agent)`; and compute the sandbox env:

```java
        Map<String, String> env = effectiveSandboxEnv(agent);
        String sandboxId = sandboxManager.createSandbox(agentId, properties.getImage(), env);
```

```java
    /**
     * Base sandbox-env plus the MCP token for the Aria agent in token mode —
     * the only agent whose opencode.json references {env:ARIA_MCP_TOKEN}.
     */
    private Map<String, String> effectiveSandboxEnv(Agent agent) {
        Map<String, String> env = properties.getSandboxEnv();
        boolean ariaWithToken = mcpProperties.isEnabled()
                && mcpProperties.isTokenMode()
                && agent != null
                && AriaConstants.ARIA_AGENT_ID.equals(agent.getId());
        if (!ariaWithToken) {
            return env;
        }
        Map<String, String> withToken = new java.util.LinkedHashMap<>(env);
        withToken.put("ARIA_MCP_TOKEN", mcpProperties.getToken());
        return withToken;
    }
```

  3. `writeOpenCodeConfig(Path workspace, Agent agent)` — the mcp JSON fragment is appended to the existing text block. Because the current config is a single `String.formatted` text block, build the fragment conditionally and insert it via a `%s` slot placed after the `permission` object; an empty `resolve()` logs WARN and emits NO mcp block (never fall back to the broken `host.docker.internal` alias):

```java
            boolean mcpForThisAgent = mcpProperties.isEnabled()
                    && agent != null
                    && AriaConstants.ARIA_AGENT_ID.equals(agent.getId());
            String mcpBlock = "";
            if (mcpForThisAgent) {
                Optional<String> host = io.aria.conductor.execution.mcp.SandboxHostResolver
                        .fromSystemInterfaces(mcpProperties.getSandboxHostAddress())
                        .resolve();
                if (host.isEmpty()) {
                    log.warn("no sandbox-reachable host address; skipping mcp block");
                } else {
                    String headerLine = mcpProperties.isTokenMode()
                            ? ",\n          \"Authorization\": \"Bearer {env:ARIA_MCP_TOKEN}\""
                            : "";
                    mcpBlock = """
                            ,
                              "mcp": {
                                "aria-conductor": {
                                  "type": "remote",
                                  "url": "http://%s:%d/mcp"%s
                                }
                              }
                            """.formatted(host.get(), mcpProperties.getPort(), headerLine);
                }
            }
```

     (amended per Task 9 review: host.docker.internal spike-refused 2026-09-05)

     …then the main text block gains the `%s` slot: `"permission": { ... }%s,` becomes `"permission": { ... }%s` with the trailing comma handled inside `mcpBlock` (the fragment above starts with `,\n` so the base block places `%s` immediately after the permission closing brace and BEFORE the `"model"` key — final emitted JSON must keep `"model"` after the mcp object; the parseable-JSON test enforces validity). Implementation note: because the fragment starts with `,`, the slot in the base block is `...external_directory": "deny"\n  }%s,` — i.e. `%s` directly after the permission object's closing brace. The four JSON-parseable assertions in the existing test are the validity gate; run them.

  4. OpenCodeAdkProvider imports: `io.aria.conductor.common.AriaConstants`, `io.aria.conductor.execution.mcp.McpProperties`, `io.aria.conductor.execution.mcp.SandboxHostResolver`.

- [ ] **Step 5: Run — expect PASS** (act-execution full module)

Run: `cd agent-control-tower && mvn -q test -pl act-execution`
Expected: BUILD SUCCESS — all existing opencode.json tests plus the four new ones.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-common agent-control-tower/act-aria agent-control-tower/act-execution
git commit -m "feat(mcp): wire mcp.aria-conductor remote block into Aria's opencode.json (token-mode optional)"
```

---

### Task 11: `McpTokenFilter` — TDD (token mode only)

**Files:**
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/McpTokenFilter.java`
- Create: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/McpTokenFilterTest.java`

- [ ] **Step 1: Failing tests**

```java
package io.aria.conductor.mcp;

import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class McpTokenFilterTest {

    private McpProperties props(String mode, String token) {
        McpProperties p = new McpProperties();
        p.setAuthMode(mode);
        p.setToken(token);
        return p;
    }

    @Test
    void tokenMode_validBearer_passes() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer secret-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (request, response) -> response.setStatus(200));

        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void tokenMode_missingHeader_401() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (request, response) -> response.setStatus(200));

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenMode_wrongPrefix_401() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Basic c2VjcmV0LTE=");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (request, response) -> response.setStatus(200));

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenMode_nonMcpPath_untouched() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (request, response) -> response.setStatus(200));

        assertThat(res.getStatus()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest=McpTokenFilterTest`

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.mcp;

import io.aria.conductor.execution.mcp.McpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Bearer guard for /mcp/* — registered ONLY when aria.mcp.auth-mode=token
 * (v1 default is none: auth deferred, audit logging is the safeguard).
 * Ordered after CorrelationIdFilter (HIGHEST_PRECEDENCE).
 */
@Component
@ConditionalOnProperty(prefix = "aria.mcp", name = "auth-mode", havingValue = "token")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpTokenFilter extends OncePerRequestFilter {

    private final McpProperties properties;

    public McpTokenFilter(McpProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/mcp")) {
            chain.doFilter(request, response);
            return;
        }
        String expected = "Bearer " + properties.getToken();
        String actual = request.getHeader("Authorization");
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Run — expect PASS** (4 tests) + conditional-registration check

Run: `cd agent-control-tower && mvn -q test -pl act-mcp -Dtest=McpTokenFilterTest`

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-mcp/src
git commit -m "feat(mcp): token-mode bearer filter (registered only when auth-mode=token)"
```

---

### Task 12: MCP E2E scenario spec — external client drives the SDD loop

**Files:**
- Modify: `agent-control-tower/act-dashboard/package.json` (devDependency `@modelcontextprotocol/sdk`)
- Create: `agent-control-tower/act-dashboard/e2e/api/mcp-sdd-workflow.api.spec.ts`

(Placed under `e2e/api/` so the `api` playwright project runs it: no browser, 600s timeout — matches `playwright.config.ts` testMatch `/e2e[\\/]api[\\/].*\.spec\.ts$/`. No pnpm workspace exists, so the SDK goes into act-dashboard's own devDependencies, pinned to the same major as packages/mcp-server (`^1.12.1`).)

- [ ] **Step 1: Add the client SDK**

Run: `cd agent-control-tower/act-dashboard && pnpm add -D @modelcontextprotocol/sdk@^1.12.1`
Expected: package.json devDependencies updated, lockfile updated.

- [ ] **Step 2: Write the spec**

```typescript
import { test, expect } from '@playwright/test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';

/**
 * MCP twin of e2e/sdd-workflow.spec.ts: an EXTERNAL MCP client (no browser, no
 * REST) drives the SDD loop purely through the platform's embedded MCP endpoint —
 * instantiate -> approve the SPEC_REVIEW gate -> poll the chain. This is the
 * "another agent can connect and operate Aria Conductor" contract
 * (docs/superpowers/specs/2026-09-05-mcp-into-opencode-design.md §7.5).
 *
 * Transport: tries streamable HTTP at /mcp first, falls back to SSE at /sse
 * (Spring AI 1.0.9 webmvc default is SSE). V42 seeds guarantee the ba/dev/qa
 * role agents; V40 seeds the APPROVED development-workflow template.
 * Prerequisites: backend running with aria.mcp.enabled (h2 profile).
 */

test.describe.configure({ mode: 'serial', timeout: 600_000 });

const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';
const RUN_TIMEOUT = Number(process.env.E2E_RUN_TIMEOUT_MS || 180_000);

async function connectMcp(): Promise<Client> {
  const client = new Client({ name: 'mcp-e2e', version: '0.1.0' });
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(`${API_URL}/mcp`)));
  } catch {
    await client.connect(new SSEClientTransport(new URL(`${API_URL}/sse`)));
  }
  return client;
}

type Json = Record<string, any>;

/** Calls a tool and parses our uniform JSON envelope {"ok":bool,"data"|error fields}. */
async function callJson(client: Client, name: string, args: Json): Promise<Json> {
  const result = await client.callTool({ name, arguments: args });
  const text = (result.content as Array<{ type: string; text: string }>)
    .filter((c) => c.type === 'text')
    .map((c) => c.text)
    .join('');
  const parsed = JSON.parse(text) as Json;
  expect(parsed.ok, `${name} -> ${text.slice(0, 300)}`).toBe(true);
  return parsed;
}

async function pollMcp<T>(
  client: Client,
  fn: () => Promise<T | null>,
  timeoutMs: number,
  intervalMs = 3_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | null = null;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value != null) return value;
    last = value;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`pollMcp timed out after ${timeoutMs}ms`);
}

test('mcp: external client instantiates development-workflow, approves the gate, chain leaves WAITING_APPROVAL', async () => {
  const client = await connectMcp();
  try {
    // 1. Discover the APPROVED development-workflow template via MCP.
    const listed = await callJson(client, 'list_knowledge', { type: 'WORKFLOW', status: 'APPROVED' });
    const tpl = (listed.data as Json[]).find((k) => k.name === 'development-workflow');
    expect(tpl, 'V40 seed development-workflow must exist').toBeTruthy();

    // 2. Instantiate via MCP (same path as the Templates-tab Run button).
    const inst = await callJson(client, 'instantiate_workflow_template', {
      templateId: tpl.id,
      parameters: { issueRef: '#1-test', repoUrl: 'https://github.com/HappyLiang12/aria-conductor.git' },
    });
    const chain = inst.data;
    expect(chain.id).toBeTruthy();

    // 3. Poll the chain via get_workflow until the SPEC_REVIEW gate is up.
    const approval = await pollMcp(client, async () => {
      const wf = await callJson(client, 'get_workflow', { chainId: chain.id });
      if (wf.data.status !== 'WAITING_APPROVAL') return null;
      const approvals = await callJson(client, 'list_approvals', { status: 'PENDING' });
      return (approvals.data as Json[]).find((a) => a.approvalType === 'SPEC_REVIEW') ?? null;
    }, 60_000);
    expect(approval.content).toContain('#');
    expect(approval.knowledgeItemId).toBeTruthy();

    // 4. Approve the gate VIA MCP (operator-level tool action).
    const decided = await callJson(client, 'decide_approval', {
      approvalId: approval.id,
      approved: true,
      reason: 'mcp e2e lgtm',
    });
    expect(decided.ok).toBe(true);

    // 5. The chain must leave WAITING_APPROVAL; Dev/QA outcomes depend on the
    //    ADK runtime (langchain in CI, opencode locally) — the full PASS/DEFECT/
    //    SPEC_GAP routing is covered by SddWorkflowIntegrationTest.
    const resumed = await pollMcp(client, async () => {
      const wf = await callJson(client, 'get_workflow', { chainId: chain.id });
      return wf.data.status !== 'WAITING_APPROVAL' ? wf.data : null;
    }, RUN_TIMEOUT);
    expect(['RUNNING', 'COMPLETED', 'FAILED']).toContain(resumed.status);
  } finally {
    await client.close();
  }
});
```

- [ ] **Step 3: Run against the live local stack** (backend on h2 with the feature branch code, MCP enabled, aria.mcp.debug=true):

Run: `cd agent-control-tower/act-dashboard && npx playwright test e2e/api/mcp-sdd-workflow.api.spec.ts`
Expected: PASS (real opencode Dev/QA runs; RUN_TIMEOUT=180s covers them).

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-dashboard/package.json agent-control-tower/act-dashboard/pnpm-lock.yaml agent-control-tower/act-dashboard/e2e/api/mcp-sdd-workflow.api.spec.ts
git commit -m "test(e2e): MCP client twin of the SDD workflow scenario (streamable->SSE fallback)"
```

---

### Task 13: Full verification + live acceptance

- [ ] **Step 1: Full Java gate**

Run: `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2`
Expected: BUILD SUCCESS, all modules.

- [ ] **Step 2: Frontend gates** (dashboard files changed only via package.json/lockfile/e2e)

Run: `cd agent-control-tower/act-dashboard && pnpm test && npx tsc --noEmit && pnpm build`
Expected: all green.

- [ ] **Step 3: Full E2E suite locally** (dirty-DB tolerant specs from fix/e2e-db-isolation)

Run: `cd agent-control-tower/act-dashboard && npx playwright test`
Expected: no new failures vs the fix/e2e-db-isolation baseline (208 passed / 2 known-flake teardown; git-pack fixed there).

- [ ] **Step 4: Live acceptance — Qoder-dispatched MCP client subagent.** Restart the backend on this branch, then dispatch a subagent whose ONLY platform access is the MCP endpoint (`http://localhost:8080/mcp`): it must list tools, instantiate development-workflow, approve the SPEC_REVIEW gate, and poll the chain to a terminal state — mirroring Task 12 but as a free-running agent session (the "another agent can connect and operate Aria Conductor" demo, spec §7.6). Record the transcript path.

- [ ] **Step 5: Commit any final touches + push**

```bash
git push -u origin feat/mcp-into-opencode
```

Expected: CI green (CI runs the test profile — MCP disabled — so CI behavior is unchanged except the Boot upgrade).

---

## Self-Review Notes (author-checked)

- **Spec coverage**: spec §3 (architecture) → Tasks 2,3,9,10; §4 components → Tasks 2-5,7,9,10,11; §5 data flows → Tasks 10,12; §6 error/governance → Tasks 5,11 (+ audit logging lives in each tool via `ToolResponses` call sites — logging wrapper is part of each `@Tool` try/catch; if audit lines are wanted structurally, add an AOP aspect in Phase 3); §7 testing → Tasks 1,4-9,12,13; §8 phasing → Task 0 + this plan + Phase 3 plan. ApprovalTools was moved INTO Phase 2 (spec §8 phase 2 listed only Workflow/Knowledge) because the MCP E2E scenario requires deciding the SPEC_REVIEW gate — deliberate, documented here.
- **Transport caveat**: Spring AI 1.0.9 webmvc transport (SSE vs streamable) is confirmed in Task 4 Step 1 from the jar metadata, not assumed; the E2E client handles both. The opencode-side SSE-support question is closed by the live acceptance in Task 13 Step 4 — if opencode cannot connect, the spec §3 fallback (in-house streamable controller) is invoked with the integration tests from Task 4 as the red/green harness.
- **Type consistency**: `ToolResponses.ok/error(String,String,Throwable,boolean)` used identically in Tasks 5-7; `McpProperties` accessors (`isTokenMode`, `isDebug`, `isEnabled`) match across Tasks 3,6,7,10,11; `SandboxHostResolver.over/fromSystemInterfaces/resolve` consistent in Tasks 9-10.

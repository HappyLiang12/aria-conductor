# Memory Hub Phase 0 Read Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire Aria's context pipeline to the verified `aria-memory-vault` through qmd, for read-only memory and knowledge, with qmd disabled by default and graceful fallback.

**Architecture:** Add a small shared qmd REST client in `act-common`, a `memory:qmd` contributor in `act-memory`, and make the existing knowledge contributor qmd-first in `act-knowledge`. The vault is the only source; `pending/` is never indexed. Aria calls qmd's `POST /query` REST endpoint, not MCP; Qoder uses the qmd HTTP MCP endpoint directly (already wired in `scripts/qmd-runtime/qoder-settings.example.json`).

**Tech Stack:** Java 21, Spring Boot, Jackson, `java.net.http.HttpClient`, JUnit 5, Mockito, AssertJ. qmd 2.9.0 in a pinned container.

**Spec:** `docs/superpowers/specs/2026-11-24-memory-knowledge-hub-design.md`

**Base commit:** `2f429e0e` (`feat(memory): add portable qmd runtime and phase 0 design`)

## Global Constraints

- Read-only Phase 0: no approval gate, no `pending/` writes, no post-run extractor, no WeKnora.
- qmd is disabled by default; CI and unit tests must not require a running qmd.
- Only `memory/verified/` and `knowledge/verified/` are queried. `pending/` is never read.
- Shared vault records are `INTERNAL`; the client still enforces the request's sensitivity ceiling and drops higher-sensitivity hits.
- Do not edit `ChatPage.tsx`, `AriaPanel.tsx`, `api/aria.ts`, `DashboardController`, `RunsPage`, token-meter files, or `AgentLoopEngine` SSE emission.
- No Python runtime in production code. Python in this plan is only for the one-off Qoder settings merge.
- One commit per task; conventional commit messages.

---

### Task 1: Shared query text and workspace-scope primitives

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/ContextQueryText.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdScope.java`
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/ContextQueryTextTest.java`
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/qmd/QmdScopeTest.java`

**Interfaces:**
- Produces: `ContextQueryText.of(ContextRequest): String`
- Produces: `QmdScope.projectOf(String workspaceRef): String` returning `project:<name>` or `null`

- [ ] **Step 1: Write the failing tests**

`ContextQueryTextTest.java`:

```java
package io.aria.conductor.common.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextQueryTextTest {

    @Test
    void goalAndOnlyTheLastThreeUserTurnsAreKept() {
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(), "c", UUID.randomUUID(), ContextAdapter.CORE, 5,
                "goal text", null, null, 24_000, "D:/repo", "",
                List.of(
                        new ContextMessage("user", "turn-1", null),
                        new ContextMessage("user", "turn-2", null),
                        new ContextMessage("user", "turn-3", null),
                        new ContextMessage("assistant", "skip", null),
                        new ContextMessage("user", "turn-4", null),
                        new ContextMessage("user", "turn-5", null)));

        assertThat(ContextQueryText.of(request))
                .contains("goal text", "turn-3", "turn-4", "turn-5")
                .doesNotContain("turn-1", "turn-2", "skip");
    }

    @Test
    void nullRequestIsEmpty() {
        assertThat(ContextQueryText.of(null)).isEmpty();
    }
}
```

`QmdScopeTest.java`:

```java
package io.aria.conductor.common.context.qmd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QmdScopeTest {

    @Test
    void canonicalProjectNamesAreRecovered() {
        assertThat(QmdScope.projectOf("/Users/happy/Documents/project/aria-conductor"))
                .isEqualTo("project:aria-conductor");
        assertThat(QmdScope.projectOf("/Users/happy/aria-conductor-a3c9ff"))
                .isEqualTo("project:aria-conductor");
        assertThat(QmdScope.projectOf("D:/repo/eqm_root")).isEqualTo("project:eqm-root");
        assertThat(QmdScope.projectOf("C:/work/harness-poc")).isEqualTo("project:harness-poc");
    }

    @Test
    void blankWorkspaceIsNull() {
        assertThat(QmdScope.projectOf(null)).isNull();
        assertThat(QmdScope.projectOf("  ")).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower && mvn test -pl act-common -Dtest=ContextQueryTextTest,QmdScopeTest -Dspring.profiles.active=h2`

Expected: compilation failures for the missing classes.

- [ ] **Step 3: Write the implementations**

`ContextQueryText.java`:

```java
package io.aria.conductor.common.context;

import java.util.ArrayList;
import java.util.List;

/** The query text shared by the qmd memory and knowledge contributors. */
public final class ContextQueryText {

    private ContextQueryText() {
    }

    public static String of(ContextRequest request) {
        if (request == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(request.goal() == null ? "" : request.goal());
        List<String> userTurns = new ArrayList<>();
        for (ContextMessage message : request.priorHistory()) {
            if (message != null && "user".equalsIgnoreCase(message.role())) {
                userTurns.add(message.content());
            }
        }
        for (String turn : userTurns.subList(Math.max(0, userTurns.size() - 3), userTurns.size())) {
            text.append('\n').append(turn);
        }
        return text.toString().trim();
    }
}
```

`QmdScope.java`:

```java
package io.aria.conductor.common.context.qmd;

import java.util.Locale;

/** Maps a workspace reference to the project scope used by the shared vault. */
public final class QmdScope {

    private QmdScope() {
    }

    public static String projectOf(String workspaceRef) {
        if (workspaceRef == null || workspaceRef.isBlank()) {
            return null;
        }
        String normalized = workspaceRef.replace('\\', '/');
        String base = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (base.isBlank()) {
            return null;
        }
        if (base.contains("eqm-root") || base.contains("eqm_root")) {
            return "project:eqm-root";
        }
        if (base.contains("clcm-root")) {
            return "project:clcm-root";
        }
        if (base.contains("aria-conductor")) {
            return "project:aria-conductor";
        }
        if (base.contains("harness-poc")) {
            return "project:harness-poc";
        }
        if (base.contains("agent-control-tower")) {
            return "project:agent-control-tower";
        }
        return "project:" + base.replaceAll("-[0-9a-f]{6}$", "");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn test -pl act-common -Dtest=ContextQueryTextTest,QmdScopeTest -Dspring.profiles.active=h2`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/ContextQueryText.java \
  agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdScope.java \
  agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/ContextQueryTextTest.java \
  agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/qmd/QmdScopeTest.java
git commit -m "feat(memory): add shared qmd query text and scope mapping"
```

---

### Task 2: qmd REST client

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdQuery.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdHit.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdResult.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdClient.java`
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/qmd/QmdClientTest.java`

**Interfaces:**
- Consumes: `QmdScope`, `Sensitivity`
- Produces:
  - `QmdQuery(String url, List<String> collections, String text, String scope, Sensitivity ceiling, int limit, double minScore, long timeoutMillis, String vaultDir)`
  - `QmdHit(String title, String file, String snippet, double score, Sensitivity sensitivity)`
  - `QmdResult(List<QmdHit> hits, String degradedReason)`, `empty()`, `degraded(String)`
  - `QmdClient.query(QmdQuery): QmdResult`

- [ ] **Step 1: Write the failing tests**

`QmdClientTest.java`:

```java
package io.aria.conductor.common.context.qmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QmdClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void hitsCarrySensitivityAndTheRequestBodyCarriesScopeAndCeiling() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/query", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"results":[{"title":"t","file":"qmd://memory-verified/a.md",
                    "snippet":"body","score":0.9,"metadata":{"sensitivity":"INTERNAL"}}]}
                    """);
        });

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-verified"), "query", "project:aria-conductor",
                Sensitivity.INTERNAL, 4, 0.15, 1_000, ""));

        assertThat(result.degradedReason()).isNull();
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.title()).isEqualTo("t");
                    assertThat(hit.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
                });
        JsonNode request = JSON.readTree(body.get());
        assertThat(request.path("filter").toString())
                .contains("project:aria-conductor", "sensitivity", "INTERNAL");
        assertThat(request.path("rerank").asBoolean()).isFalse();
    }

    @Test
    void aCeilingAboveTheHitDropsTheHit() throws Exception {
        server.createContext("/query", exchange -> respond(exchange, 200, """
                {"results":[{"title":"t","file":"qmd://memory-verified/a.md",
                "snippet":"body","score":0.9,"metadata":{"sensitivity":"CONFIDENTIAL"}}]}
                """));

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-verified"), "query", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, ""));

        assertThat(result.hits()).isEmpty();
        assertThat(result.degradedReason()).isNull();
    }

    @Test
    void bodyIsReadFromTheVaultAndTraversalIsRejected(@TempDir Path vault) throws Exception {
        Files.createDirectories(vault.resolve("memory/verified/projects/demo"));
        Files.createDirectories(vault.resolve("memory/pending"));
        Files.writeString(vault.resolve("memory/verified/projects/demo/a.md"),
                "---\ntitle: t\n---\nverified body");
        Files.writeString(vault.resolve("memory/pending/secret.md"), "do not read");

        server.createContext("/query", exchange -> respond(exchange, 200, """
                {"results":[
                  {"title":"a","file":"qmd://memory-verified/projects/demo/a.md",
                   "snippet":"frontmatter-only","score":0.9,"metadata":{"sensitivity":"INTERNAL"}},
                  {"title":"secret","file":"qmd://memory-verified/../pending/secret.md",
                   "snippet":"safe snippet","score":0.8,"metadata":{"sensitivity":"INTERNAL"}}
                ]}
                """));

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-verified"), "query", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, vault.toString()));

        assertThat(result.hits().get(0).snippet()).isEqualTo("verified body");
        assertThat(result.hits().get(1).snippet()).isEqualTo("safe snippet");
    }

    @Test
    void timeoutAndNon2xxDegrade() throws Exception {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"results\":[]}");
        });
        server.createContext("/bad", exchange -> respond(exchange, 500, "nope"));

        assertThat(new QmdClient().query(new QmdQuery(
                baseUrl + "/slow", List.of("memory-verified"), "q", null,
                Sensitivity.INTERNAL, 4, 0.15, 50, "")).degradedReason())
                .isEqualTo("qmd-timeout");
        assertThat(new QmdClient().query(new QmdQuery(
                baseUrl + "/bad", List.of("memory-verified"), "q", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, "")).degradedReason())
                .isEqualTo("qmd-http-500");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-common -Dtest=QmdClientTest -Dspring.profiles.active=h2`

Expected: compilation failures for the missing records/client.

- [ ] **Step 3: Write the records and client**

`QmdQuery.java`:

```java
package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.model.Sensitivity;

import java.util.List;

public record QmdQuery(
        String url,
        List<String> collections,
        String text,
        String scope,
        Sensitivity ceiling,
        int limit,
        double minScore,
        long timeoutMillis,
        String vaultDir) {

    public QmdQuery {
        collections = collections == null ? List.of() : List.copyOf(collections);
    }
}
```

`QmdHit.java`:

```java
package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.model.Sensitivity;

public record QmdHit(String title, String file, String snippet, double score, Sensitivity sensitivity) {
}
```

`QmdResult.java`:

```java
package io.aria.conductor.common.context.qmd;

import java.util.List;

public record QmdResult(List<QmdHit> hits, String degradedReason) {

    public QmdResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static QmdResult empty() {
        return new QmdResult(List.of(), null);
    }

    public static QmdResult degraded(String reason) {
        return new QmdResult(List.of(), reason);
    }
}
```

`QmdClient.java`:

```java
package io.aria.conductor.common.context.qmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Minimal qmd REST client for the verified memory/knowledge vault. */
@Component
public class QmdClient {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final HttpClient http;

    public QmdClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    QmdClient(HttpClient http) {
        this.http = Objects.requireNonNull(http);
    }

    public QmdResult query(QmdQuery query) {
        if (query == null || query.text() == null || query.text().isBlank()
                || query.url() == null || query.url().isBlank()
                || query.collections().isEmpty()) {
            return QmdResult.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(query.url()) + "/query"))
                    .timeout(Duration.ofMillis(Math.max(1, query.timeoutMillis())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(requestBody(query)), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return QmdResult.degraded("qmd-http-" + response.statusCode());
            }
            return parse(response.body(), query);
        } catch (HttpTimeoutException e) {
            return QmdResult.degraded("qmd-timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return QmdResult.degraded("qmd-interrupted");
        } catch (Exception e) {
            return QmdResult.degraded("qmd-error");
        }
    }

    private static ObjectNode requestBody(QmdQuery query) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode searches = body.putArray("searches");
        searches.addObject().put("type", "lex").put("query", query.text());
        searches.addObject().put("type", "vec").put("query", query.text());
        ArrayNode collections = body.putArray("collections");
        query.collections().forEach(collections::add);
        body.put("limit", Math.max(1, query.limit()));
        body.put("minScore", query.minScore());
        body.put("rerank", false);
        body.set("filter", filter(query.scope(), query.ceiling()));
        return body;
    }

    private static ObjectNode filter(String scope, Sensitivity ceiling) {
        Sensitivity limit = ceiling == null ? Sensitivity.INTERNAL : ceiling;
        ObjectNode root = JSON.createObjectNode();
        root.put("operator", "and");
        ArrayNode operands = root.putArray("operands");
        if (scope == null || scope.isBlank()) {
            operands.addObject().put("field", "scope").put("operator", "eq").put("value", "user");
        } else if (scope.startsWith("project:")) {
            ObjectNode any = operands.addObject();
            any.put("operator", "or");
            ArrayNode scopes = any.putArray("operands");
            scopes.addObject().put("field", "scope").put("operator", "eq").put("value", "user");
            scopes.addObject().put("field", "scope").put("operator", "eq").put("value", scope);
        } else {
            operands.addObject().put("field", "scope").put("operator", "eq").put("value", scope);
        }
        ObjectNode sensitivity = operands.addObject();
        sensitivity.put("field", "sensitivity").put("operator", "in");
        ArrayNode values = sensitivity.putArray("value");
        for (Sensitivity candidate : Sensitivity.values()) {
            if (candidate.ordinal() <= limit.ordinal()) {
                values.add(candidate.name());
            }
        }
        return root;
    }

    private static QmdResult parse(String raw, QmdQuery query) throws IOException {
        JsonNode results = JSON.readTree(raw).path("results");
        if (!results.isArray()) {
            return QmdResult.empty();
        }
        Sensitivity ceiling = query.ceiling() == null ? Sensitivity.INTERNAL : query.ceiling();
        List<QmdHit> hits = new ArrayList<>();
        for (JsonNode node : results) {
            Sensitivity sensitivity = sensitivityOf(node.path("metadata").path("sensitivity").asText(null));
            if (sensitivity.ordinal() > ceiling.ordinal()) {
                continue;
            }
            String file = node.path("file").asText("");
            String snippet = body(query.vaultDir(), file);
            if (snippet.isBlank()) {
                snippet = node.path("snippet").asText("");
            }
            hits.add(new QmdHit(node.path("title").asText(file), file, snippet,
                    node.path("score").asDouble(0), sensitivity));
        }
        return new QmdResult(hits, null);
    }

    private static String body(String vaultDir, String file) {
        if (vaultDir == null || vaultDir.isBlank() || file == null || !file.startsWith("qmd://")) {
            return "";
        }
        String raw = file.substring("qmd://".length());
        int slash = raw.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String collection = raw.substring(0, slash);
        String relative = raw.substring(slash + 1);
        Path root = switch (collection) {
            case "memory-verified" -> Path.of(expand(vaultDir), "memory", "verified");
            case "knowledge-verified" -> Path.of(expand(vaultDir), "knowledge", "verified");
            default -> null;
        };
        if (root == null) {
            return "";
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root.normalize())) {
            return "";
        }
        try {
            return compact(stripFrontmatter(Files.readString(candidate, StandardCharsets.UTF_8)), 300);
        } catch (IOException e) {
            return "";
        }
    }

    private static String stripFrontmatter(String text) {
        if (!text.startsWith("---")) {
            return text;
        }
        int end = text.indexOf("\n---", 3);
        return end < 0 ? text : text.substring(end + 4).stripLeading();
    }

    private static String compact(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    private static String expand(String path) {
        return path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path;
    }

    private static Sensitivity sensitivityOf(String raw) {
        try {
            return raw == null ? Sensitivity.INTERNAL : Sensitivity.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Sensitivity.INTERNAL;
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd agent-control-tower && mvn test -pl act-common -Dtest=QmdClientTest -Dspring.profiles.active=h2`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd \
  agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/qmd/QmdClientTest.java
git commit -m "feat(memory): add qmd REST client for the verified vault"
```

---

### Task 3: qmd section renderer

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdSectionRenderer.java`
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/qmd/QmdSectionRendererTest.java`

**Interfaces:**
- Consumes: `QmdResult`, `QmdHit`, `ContextSection`
- Produces: `QmdSectionRenderer.render(String id, String title, QmdResult result, int maxChars): Optional<ContextSection>`

- [ ] **Step 1: Write the failing test**

`QmdSectionRendererTest.java`:

```java
package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QmdSectionRendererTest {

    @Test
    void rendersProvenanceAndTheHighestSensitivity() {
        QmdResult result = new QmdResult(List.of(
                new QmdHit("a", "qmd://memory-verified/a.md", "body a", 0.9, Sensitivity.INTERNAL),
                new QmdHit("b", "qmd://memory-verified/b.md", "body b", 0.8, Sensitivity.CONFIDENTIAL)),
                null);

        ContextSection section = QmdSectionRenderer
                .render("memory:qmd", "Verified memory", result, 4_000)
                .orElseThrow();

        assertThat(section.content()).contains("a [qmd://memory-verified/a.md]: body a");
        assertThat(section.sourceIds()).containsExactly(
                "qmd://memory-verified/a.md", "qmd://memory-verified/b.md");
        assertThat(section.sensitivity()).isEqualTo(Sensitivity.CONFIDENTIAL);
        assertThat(section.degradedReason()).isNull();
    }

    @Test
    void emptyOrDegradedResultsRenderNothing() {
        assertThat(QmdSectionRenderer.render("memory:qmd", "Memory", QmdResult.empty(), 4_000)).isEmpty();
        assertThat(QmdSectionRenderer.render("memory:qmd", "Memory", QmdResult.degraded("x"), 4_000)).isEmpty();
    }

    @Test
    void theCapTruncatesAtALineBoundary() {
        QmdResult result = new QmdResult(List.of(
                new QmdHit("a", "f", "x".repeat(80), 0.9, Sensitivity.INTERNAL),
                new QmdHit("b", "f", "y".repeat(80), 0.9, Sensitivity.INTERNAL)),
                null);

        ContextSection section = QmdSectionRenderer
                .render("memory:qmd", "Memory", result, 120)
                .orElseThrow();

        assertThat(section.content().length()).isLessThanOrEqualTo(120);
        assertThat(section.content()).contains("a [f]");
        assertThat(section.truncated()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-common -Dtest=QmdSectionRendererTest -Dspring.profiles.active=h2`

Expected: compilation failure for the missing renderer.

- [ ] **Step 3: Write the renderer**

`QmdSectionRenderer.java`:

```java
package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared rendering for the qmd memory and knowledge sections. */
public final class QmdSectionRenderer {

    private QmdSectionRenderer() {
    }

    public static Optional<ContextSection> render(String id, String title, QmdResult result, int maxChars) {
        if (result == null || result.hits().isEmpty()) {
            return Optional.empty();
        }
        int cap = maxChars <= 0 ? 4_000 : maxChars;
        StringBuilder content = new StringBuilder();
        List<String> sources = new ArrayList<>();
        Sensitivity sensitivity = Sensitivity.PUBLIC;
        boolean truncated = false;
        for (QmdHit hit : result.hits()) {
            String line = "- " + hit.title() + " [" + hit.file() + "]"
                    + (hit.snippet() == null || hit.snippet().isBlank() ? "" : ": " + hit.snippet());
            int extra = content.length() == 0 ? line.length() : line.length() + 1;
            if (content.length() + extra > cap) {
                truncated = true;
                break;
            }
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(line);
            sources.add(hit.file());
            if (hit.sensitivity().ordinal() > sensitivity.ordinal()) {
                sensitivity = hit.sensitivity();
            }
        }
        if (content.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ContextSection(id, title, content.toString(), sensitivity,
                List.copyOf(sources), truncated, null));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd agent-control-tower && mvn test -pl act-common -Dtest=QmdSectionRendererTest -Dspring.profiles.active=h2`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/context/qmd/QmdSectionRenderer.java \
  agent-control-tower/act-common/src/test/java/io/aria/conductor/common/context/qmd/QmdSectionRendererTest.java
git commit -m "feat(memory): render qmd hits as a bounded context section"
```

---

### Task 4: Memory contributor

**Files:**
- Modify: `agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/service/MemoryContextContributor.java:250-266` (make `queryText` package-private and delegate to `ContextQueryText`)
- Create: `agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/service/QmdMemoryContextContributor.java`
- Test: `agent-control-tower/act-memory/src/test/java/io/aria/conductor/memory/service/QmdMemoryContextContributorTest.java`

**Interfaces:**
- Consumes: `QmdClient`, `QmdQuery`, `QmdResult`, `QmdScope`, `QmdSectionRenderer`, `ContextQueryText`
- Produces: a `ContextContributor` with id `memory:qmd`, order 39, optional.

- [ ] **Step 1: Write the failing test**

`QmdMemoryContextContributorTest.java`:

```java
package io.aria.conductor.memory.service;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.context.qmd.QmdClient;
import io.aria.conductor.common.context.qmd.QmdHit;
import io.aria.conductor.common.context.qmd.QmdResult;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QmdMemoryContextContributorTest {

    @Mock
    QmdClient client;

    @Test
    void identityIsMemoryQmdAtOrderThirtyNineAndOptional() {
        QmdMemoryContextContributor contributor = contributor(true);
        assertThat(contributor.id()).isEqualTo("memory:qmd");
        assertThat(contributor.order()).isEqualTo(39);
        assertThat(contributor.required()).isFalse();
    }

    @Test
    void hitsBecomeOneSectionWithProvenance() {
        when(client.query(any())).thenReturn(new QmdResult(List.of(
                new QmdHit("t", "qmd://memory-verified/a.md", "body", 0.9, Sensitivity.INTERNAL)), null));

        List<ContextSection> sections = contributor(true).contribute(request("operator timezone"));

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("memory:qmd");
            assertThat(section.title()).isEqualTo("Verified memory");
            assertThat(section.content()).contains("t [qmd://memory-verified/a.md]: body");
            assertThat(section.sourceIds()).containsExactly("qmd://memory-verified/a.md");
            assertThat(section.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
            assertThat(section.degradedReason()).isNull();
        });
    }

    @Test
    void emptyRecallProducesNoSection() {
        when(client.query(any())).thenReturn(QmdResult.empty());
        assertThat(contributor(true).contribute(request("nothing"))).isEmpty();
    }

    @Test
    void aQmdFailureDegradesInsteadOfThrowing() {
        when(client.query(any())).thenReturn(QmdResult.degraded("qmd-timeout"));
        ContextSection section = contributor(true).contribute(request("operator")).get(0);
        assertThat(section.content()).isEmpty();
        assertThat(section.degradedReason()).isEqualTo("qmd-timeout");
    }

    @Test
    void disabledMeansNoQuery() {
        QmdMemoryContextContributor contributor = contributor(false);
        assertThat(contributor.appliesTo(request("operator"))).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void aBlankQueryNeverCallsQmd() {
        assertThat(contributor(true).contribute(request("  "))).isEmpty();
        verifyNoInteractions(client);
    }

    private QmdMemoryContextContributor contributor(boolean enabled) {
        return new QmdMemoryContextContributor(client, enabled,
                "http://127.0.0.1:8181", "memory-verified",
                "", 1_500, 4, 0.15);
    }

    private static ContextRequest request(String goal) {
        return new ContextRequest(UUID.randomUUID(), "conversation-1", UUID.randomUUID(),
                ContextAdapter.CORE, 1, goal, null, Sensitivity.INTERNAL, 24_000,
                "D:/repo/aria-conductor", "", List.of());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-memory -Dtest=QmdMemoryContextContributorTest -Dspring.profiles.active=h2`

Expected: compilation failure for the missing contributor.

- [ ] **Step 3: Write the contributor and refactor `queryText`**

In `MemoryContextContributor.java`, replace the private `queryText` body with a package-private delegate (keep the method name):

```java
    static String queryText(ContextRequest request) {
        return ContextQueryText.of(request);
    }
```

Add `import io.aria.conductor.common.context.ContextQueryText;`.

`QmdMemoryContextContributor.java`:

```java
package io.aria.conductor.memory.service;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.context.qmd.QmdClient;
import io.aria.conductor.common.context.qmd.QmdQuery;
import io.aria.conductor.common.context.qmd.QmdResult;
import io.aria.conductor.common.context.qmd.QmdScope;
import io.aria.conductor.common.context.qmd.QmdSectionRenderer;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Verified shared memory from qmd, rendered before the DB memory fallback. */
@Component
public class QmdMemoryContextContributor implements ContextContributor {

    static final String SECTION_ID = "memory:qmd";
    static final String TITLE = "Verified memory";
    static final int ORDER = 39;
    static final int MAX_CONTENT_CHARS = 4_000;

    private final QmdClient client;
    private final boolean enabled;
    private final String url;
    private final List<String> collections;
    private final String vaultDir;
    private final long timeoutMillis;
    private final int maxResults;
    private final double minScore;

    public QmdMemoryContextContributor(QmdClient client,
            @Value("${aria.memory.qmd.enabled:false}") boolean enabled,
            @Value("${aria.memory.qmd.url:http://127.0.0.1:8181}") String url,
            @Value("${aria.memory.qmd.collections:memory-verified}") String collections,
            @Value("${aria.memory.qmd.vault-dir:}") String vaultDir,
            @Value("${aria.memory.qmd.timeout-ms:1500}") long timeoutMillis,
            @Value("${aria.memory.qmd.max-results:4}") int maxResults,
            @Value("${aria.memory.qmd.min-score:0.15}") double minScore) {
        this.client = client;
        this.enabled = enabled;
        this.url = url;
        this.collections = split(collections);
        this.vaultDir = vaultDir;
        this.timeoutMillis = timeoutMillis;
        this.maxResults = Math.max(1, maxResults);
        this.minScore = minScore;
    }

    @Override
    public String id() {
        return SECTION_ID;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean appliesTo(ContextRequest request) {
        return enabled;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        if (request == null) {
            return List.of();
        }
        String text = MemoryContextContributor.queryText(request);
        if (text.isBlank()) {
            return List.of();
        }
        QmdResult result = client.query(new QmdQuery(url, collections, text,
                QmdScope.projectOf(request.workspaceRef()), request.maxSensitivity(),
                maxResults, minScore, timeoutMillis, vaultDir));
        if (result.degradedReason() != null) {
            return List.of(new ContextSection(SECTION_ID, TITLE, "", Sensitivity.INTERNAL,
                    List.of(), false, result.degradedReason()));
        }
        return QmdSectionRenderer.render(SECTION_ID, TITLE, result, MAX_CONTENT_CHARS)
                .map(List::of).orElseGet(List::of);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim)
                .filter(part -> !part.isBlank()).distinct().toList();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd agent-control-tower && mvn test -pl act-memory -Dtest=QmdMemoryContextContributorTest,MemoryContextContributorTest -Dspring.profiles.active=h2`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/service/MemoryContextContributor.java \
  agent-control-tower/act-memory/src/main/java/io/aria/conductor/memory/service/QmdMemoryContextContributor.java \
  agent-control-tower/act-memory/src/test/java/io/aria/conductor/memory/service/QmdMemoryContextContributorTest.java
git commit -m "feat(memory): inject verified qmd memory before DB recall"
```

---

### Task 5: Knowledge contributor qmd-first

**Files:**
- Modify: `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/context/KnowledgeContextContributor.java`
- Test: `agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/context/KnowledgeContextContributorTest.java`

**Interfaces:**
- Consumes: `QmdClient`, `QmdQuery`, `QmdResult`, `QmdScope`, `QmdSectionRenderer`, `ContextQueryText`
- Produces: the same contributor id `knowledge`, order 50, optional; qmd-first when enabled.

- [ ] **Step 1: Replace the test with the qmd-first cases**

`KnowledgeContextContributorTest.java`:

```java
package io.aria.conductor.knowledge.context;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.context.qmd.QmdClient;
import io.aria.conductor.common.context.qmd.QmdHit;
import io.aria.conductor.common.context.qmd.QmdResult;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeContextContributorTest {

    @Mock
    KnowledgeService knowledgeService;

    @Mock
    QmdClient qmd;

    @Test
    void isOptionalAndOrderedAfterMemory() {
        KnowledgeContextContributor contributor = contributor(false);
        assertThat(contributor.id()).isEqualTo("knowledge");
        assertThat(contributor.order()).isEqualTo(50);
        assertThat(contributor.required()).isFalse();
    }

    @Test
    void disabledFallsBackToTheExistingNewestFive() {
        ContextSection fallback = new ContextSection("knowledge:1", "deploy", "blue-green",
                Sensitivity.INTERNAL, List.of(UUID.randomUUID().toString()), false, null);
        when(knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL))
                .thenReturn(List.of(fallback));

        assertThat(contributor(false).contribute(request())).containsExactly(fallback);
        verifyNoInteractions(qmd);
    }

    @Test
    void qmdHitsReplaceTheNewestFive() {
        when(qmd.query(any())).thenReturn(new QmdResult(List.of(
                new QmdHit("t", "qmd://knowledge-verified/a.md", "body", 0.9, Sensitivity.INTERNAL)), null));

        List<ContextSection> sections = contributor(true).contribute(request());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("knowledge:qmd");
            assertThat(section.content()).contains("t [qmd://knowledge-verified/a.md]: body");
        });
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void cleanEmptyQmdDoesNotInjectIrrelevantKnowledge() {
        when(qmd.query(any())).thenReturn(QmdResult.empty());
        assertThat(contributor(true).contribute(request())).isEmpty();
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void qmdFailureFallsBackAndReportsTheDegradation() {
        ContextSection fallback = new ContextSection("knowledge:1", "deploy", "blue-green",
                Sensitivity.INTERNAL, List.of(UUID.randomUUID().toString()), false, null);
        when(qmd.query(any())).thenReturn(QmdResult.degraded("qmd-timeout"));
        when(knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL))
                .thenReturn(List.of(fallback));

        List<ContextSection> sections = contributor(true).contribute(request());

        assertThat(sections).contains(fallback);
        assertThat(sections).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("knowledge:qmd");
            assertThat(section.degradedReason()).isEqualTo("qmd-timeout");
        });
    }

    private KnowledgeContextContributor contributor(boolean enabled) {
        return new KnowledgeContextContributor(knowledgeService, qmd, enabled,
                "http://127.0.0.1:8181", "knowledge-verified", "", 1_500, 4, 0.15);
    }

    private static ContextRequest request() {
        return new ContextRequest(UUID.randomUUID(), "conv-1", UUID.randomUUID(), ContextAdapter.SYNC,
                1, "ship it", null, Sensitivity.INTERNAL, 24_000,
                "D:/repo/aria-conductor", "", List.of());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=KnowledgeContextContributorTest -Dspring.profiles.active=h2`

Expected: compilation failure for the changed constructor.

- [ ] **Step 3: Replace the contributor**

`KnowledgeContextContributor.java`:

```java
package io.aria.conductor.knowledge.context;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextQueryText;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.context.qmd.QmdClient;
import io.aria.conductor.common.context.qmd.QmdQuery;
import io.aria.conductor.common.context.qmd.QmdResult;
import io.aria.conductor.common.context.qmd.QmdScope;
import io.aria.conductor.common.context.qmd.QmdSectionRenderer;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** qmd-first knowledge; the newest-five DB path is the disabled/failure fallback. */
@Component
public class KnowledgeContextContributor implements ContextContributor {

    static final String SECTION_ID = "knowledge:qmd";
    static final String TITLE = "Knowledge (shared vault)";
    static final int ORDER = 50;
    static final int LIMIT = 5;
    static final int MAX_CONTENT_CHARS = 4_000;

    private final KnowledgeService knowledgeService;
    private final QmdClient qmd;
    private final boolean enabled;
    private final String url;
    private final List<String> collections;
    private final String vaultDir;
    private final long timeoutMillis;
    private final int maxResults;
    private final double minScore;

    public KnowledgeContextContributor(KnowledgeService knowledgeService,
            QmdClient qmd,
            @Value("${aria.knowledge.qmd.enabled:false}") boolean enabled,
            @Value("${aria.knowledge.qmd.url:http://127.0.0.1:8181}") String url,
            @Value("${aria.knowledge.qmd.collections:knowledge-verified}") String collections,
            @Value("${aria.knowledge.qmd.vault-dir:}") String vaultDir,
            @Value("${aria.knowledge.qmd.timeout-ms:1500}") long timeoutMillis,
            @Value("${aria.knowledge.qmd.max-results:4}") int maxResults,
            @Value("${aria.knowledge.qmd.min-score:0.15}") double minScore) {
        this.knowledgeService = knowledgeService;
        this.qmd = qmd;
        this.enabled = enabled;
        this.url = url;
        this.collections = split(collections);
        this.vaultDir = vaultDir;
        this.timeoutMillis = timeoutMillis;
        this.maxResults = Math.max(1, maxResults);
        this.minScore = minScore;
    }

    @Override
    public String id() {
        return "knowledge";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        if (!enabled) {
            return knowledgeService.buildKnowledgeSections(LIMIT, request.maxSensitivity());
        }
        String text = ContextQueryText.of(request);
        if (text.isBlank()) {
            return List.of();
        }
        QmdResult result = qmd.query(new QmdQuery(url, collections, text,
                QmdScope.projectOf(request.workspaceRef()), request.maxSensitivity(),
                maxResults, minScore, timeoutMillis, vaultDir));
        if (result.degradedReason() == null) {
            return QmdSectionRenderer.render(SECTION_ID, TITLE, result, MAX_CONTENT_CHARS)
                    .map(List::of).orElseGet(List::of);
        }
        List<ContextSection> sections = new ArrayList<>(
                knowledgeService.buildKnowledgeSections(LIMIT, request.maxSensitivity()));
        sections.add(new ContextSection(SECTION_ID, TITLE, "", Sensitivity.INTERNAL,
                List.of(), false, result.degradedReason()));
        return sections;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim)
                .filter(part -> !part.isBlank()).distinct().toList();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=KnowledgeContextContributorTest -Dspring.profiles.active=h2`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/context/KnowledgeContextContributor.java \
  agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/context/KnowledgeContextContributorTest.java
git commit -m "feat(knowledge): make the context contributor qmd-first"
```

---

### Task 6: Configuration and module validation

**Files:**
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml` (insert under `aria:`, before `  cores:`)

**Interfaces:**
- Consumes: none
- Produces: `aria.memory.qmd.*`, `aria.knowledge.qmd.*` with env overrides; defaults disabled.

- [ ] **Step 1: Insert the configuration block**

Insert before `  cores:` (around line 99):

```yaml
  memory:
    qmd:
      enabled: ${ARIA_MEMORY_QMD_ENABLED:false}
      url: ${ARIA_MEMORY_QMD_URL:http://127.0.0.1:8181}
      collections: ${ARIA_MEMORY_QMD_COLLECTIONS:memory-verified}
      vault-dir: ${ARIA_MEMORY_QMD_VAULT_DIR:}
      timeout-ms: ${ARIA_MEMORY_QMD_TIMEOUT_MS:1500}
      max-results: ${ARIA_MEMORY_QMD_MAX_RESULTS:4}
      min-score: ${ARIA_MEMORY_QMD_MIN_SCORE:0.15}
  knowledge:
    qmd:
      enabled: ${ARIA_KNOWLEDGE_QMD_ENABLED:false}
      url: ${ARIA_KNOWLEDGE_QMD_URL:http://127.0.0.1:8181}
      collections: ${ARIA_KNOWLEDGE_QMD_COLLECTIONS:knowledge-verified}
      vault-dir: ${ARIA_KNOWLEDGE_QMD_VAULT_DIR:}
      timeout-ms: ${ARIA_KNOWLEDGE_QMD_TIMEOUT_MS:1500}
      max-results: ${ARIA_KNOWLEDGE_QMD_MAX_RESULTS:4}
      min-score: ${ARIA_KNOWLEDGE_QMD_MIN_SCORE:0.15}
```

- [ ] **Step 2: Run the module gates**

Run:

```bash
bash scripts/mvn-lane.sh gate act-common
bash scripts/mvn-lane.sh gate act-memory
bash scripts/mvn-lane.sh gate act-knowledge
```

Expected: all tests pass and coverage meets the module ratchet.

- [ ] **Step 3: Verify the application context still starts with defaults**

Run: `cd agent-control-tower && mvn test -pl act-app -Dspring.profiles.active=h2`

Expected: PASS (the full act-app test module loads the application context; qmd defaults to disabled).

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/application.yml
git commit -m "feat(memory): configure qmd read paths as opt-in"
```

---

### Task 7: Qoder wiring and live acceptance

**Files:**
- Modify (user config, not repo): `~/.qoder/settings.json`
- Copy: `scripts/qmd-runtime/qoder-memory-hook.mjs` to `~/.qoder/hooks/qmd-memory.mjs`

**Interfaces:**
- Consumes: running qmd container on `127.0.0.1:8181`, vault at `~/aria-memory-vault`
- Produces: Qoder `SessionStart`/`UserPromptSubmit` context injection and qmd MCP connector

- [ ] **Step 1: Back up the Qoder settings and install the hook**

```bash
mkdir -p ~/.qoder/hooks
cp /Users/happy/Documents/project/aria-conductor-memory/scripts/qmd-runtime/qoder-memory-hook.mjs ~/.qoder/hooks/qmd-memory.mjs
cp ~/.qoder/settings.json ~/.qoder/settings.json.bak-$(date +%s) 2>/dev/null || true
```

- [ ] **Step 2: Merge the MCP connector and hooks**

Use this merge script (preserves `enabledPlugins` and any existing keys):

```bash
python3 - <<'PY'
import json
from pathlib import Path

path = Path.home() / ".qoder/settings.json"
data = json.loads(path.read_text()) if path.exists() else {}
data.setdefault("mcpServers", {})["qmd-memory"] = {
    "url": "http://127.0.0.1:8181/mcp",
    "timeout": 60000,
}
hooks = data.setdefault("hooks", {})
command = 'node "$HOME/.qoder/hooks/qmd-memory.mjs"'
for event in ("SessionStart", "UserPromptSubmit"):
    hooks.setdefault(event, [{
        "hooks": [{"type": "command", "command": command, "timeout": 15}],
    }])
path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
print(path)
PY
python3 -m json.tool ~/.qoder/settings.json >/dev/null
```

- [ ] **Step 3: Verify the hook directly**

```bash
printf '%s' '{"hook_event_name":"UserPromptSubmit","cwd":"/Users/happy/Documents/project/aria-conductor","prompt":"Aria Conductor 測試策略"}' \
  | node ~/.qoder/hooks/qmd-memory.mjs | head -10
```

Expected: the verified memory header and at least one body excerpt. Stop the qmd container and rerun; expected: empty output and exit 0.

- [ ] **Step 4: Start Aria with qmd enabled and verify the context manifest**

```bash
scripts/qmd-runtime/start-qmd.sh start
export ARIA_MEMORY_QMD_ENABLED=true
export ARIA_MEMORY_QMD_VAULT_DIR="$HOME/aria-memory-vault"
export ARIA_KNOWLEDGE_QMD_ENABLED=true
export ARIA_KNOWLEDGE_QMD_VAULT_DIR="$HOME/aria-memory-vault"
bash scripts/start-backend.sh --skip-sandbox
```

Run one Aria chat/run with a question about Aria's test strategy, then read its context manifest with the MCP tool `get_context_manifest` (or the REST twin) and verify:

- a `memory:qmd` section exists with file source ids;
- a `knowledge:qmd` section exists for a knowledge question;
- a sentinel created under `memory/pending/` is absent;
- stopping qmd and rerunning produces a degraded reason, not a failed run.

- [ ] **Step 5: Commit only repo changes**

The Qoder settings are user state and are not committed. Commit any remaining repo changes:

```bash
git status --short
```

Expected: clean. If `scripts/qmd-runtime/qoder-settings.example.json` changed, commit it with `git add scripts/qmd-runtime/qoder-settings.example.json && git commit -m "docs(memory): update Qoder settings example"`.

---

## Self-Review

- **Spec coverage:** Phase 0 read-only scope, qmd memory/knowledge, verified-only vault, qmd disabled by default, graceful degradation, Qoder hook/MCP, and no approval gate/WeKnora are each covered by Tasks 1-7.
- **Placeholder scan:** every code/test/config step contains actual code or actual commands; no TBDs.
- **Type consistency:** `QmdQuery`, `QmdHit`, `QmdResult`, `QmdClient.query`, `QmdSectionRenderer.render`, `ContextQueryText.of`, and `QmdScope.projectOf` are defined before their consumers; contributor constructor arguments match the tests.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-11-24-memory-hub-phase0-read.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task with review between tasks.
2. **Inline Execution** - execute tasks in this session with checkpoints.

Which approach?

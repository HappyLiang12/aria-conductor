package io.aria.conductor.common.context.qmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
        JsonNode filter = request.path("filter");
        assertThat(filter.toString()).contains("project:aria-conductor", "sensitivity");
        assertThat(filter.toString()).doesNotContain("CONFIDENTIAL", "RESTRICTED");

        JsonNode sensitivityOperand = null;
        for (JsonNode operand : filter.path("operands")) {
            if ("sensitivity".equals(operand.path("field").asText())) {
                sensitivityOperand = operand;
            }
        }
        assertThat(sensitivityOperand).isNotNull();
        List<String> values = new ArrayList<>();
        sensitivityOperand.path("value").forEach(node -> values.add(node.asText()));
        assertThat(values).containsExactly("PUBLIC", "INTERNAL");

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

    @Test
    void sensitivityIsTrimmedAndUnknownValuesFailClosed() throws Exception {
        server.createContext("/query", exchange -> respond(exchange, 200, """
                {"results":[
                  {"title":"ok","file":"qmd://memory-verified/ok.md",
                   "snippet":"ok body","score":0.9,"metadata":{"sensitivity":"INTERNAL "}},
                  {"title":"weird","file":"qmd://memory-verified/weird.md",
                   "snippet":"weird body","score":0.8,"metadata":{"sensitivity":"weird"}}
                ]}
                """));

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-verified"), "query", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, ""));

        assertThat(result.hits()).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.title()).isEqualTo("ok");
                    assertThat(hit.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
                });
    }

    @Test
    void aResultFromADisallowedCollectionIsDropped() throws Exception {
        server.createContext("/query", exchange -> respond(exchange, 200, """
                {"results":[{"title":"secret","file":"qmd://memory-pending/secret.md",
                "snippet":"leaked snippet","score":0.9,"metadata":{"sensitivity":"PUBLIC"}}]}
                """));

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-verified"), "query", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, ""));

        assertThat(result.hits()).isEmpty();
        assertThat(result.degradedReason()).isNull();
    }

    @Test
    void aDisallowedQueryCollectionDegradesWithoutACall() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        server.createContext("/query", exchange -> {
            called.set(true);
            respond(exchange, 200, "{\"results\":[]}");
        });

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-pending"), "query", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, ""));

        assertThat(result.degradedReason()).isEqualTo("qmd-collection");
        assertThat(result.hits()).isEmpty();
        assertThat(called).isFalse();
    }

    @Test
    void anOversizedFileFallsBackToTheServerSnippet(@TempDir Path vault) throws Exception {
        Files.createDirectories(vault.resolve("memory/verified"));
        Files.writeString(vault.resolve("memory/verified/huge.md"), "x".repeat(64_001));

        server.createContext("/query", exchange -> respond(exchange, 200, """
                {"results":[{"title":"h","file":"qmd://memory-verified/huge.md",
                "snippet":"server snippet","score":0.9,"metadata":{"sensitivity":"INTERNAL"}}]}
                """));

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-verified"), "query", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, vault.toString()));

        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.snippet()).isEqualTo("server snippet"));
    }

    @Test
    void aSymlinkInsideTheVerifiedTreeIsNotFollowed(@TempDir Path vault) throws Exception {
        Files.createDirectories(vault.resolve("memory/verified"));
        Files.createDirectories(vault.resolve("memory/pending"));
        Path target = vault.resolve("memory/pending/secret.md");
        Files.writeString(target, "do not read");
        Path link = vault.resolve("memory/verified/link.md");
        boolean created;
        try {
            Files.createSymbolicLink(link, target);
            created = true;
        } catch (IOException | UnsupportedOperationException e) {
            created = false;
        }
        Assumptions.assumeTrue(created, "symbolic links are not permitted here");

        server.createContext("/query", exchange -> respond(exchange, 200, """
                {"results":[{"title":"linked","file":"qmd://memory-verified/link.md",
                "snippet":"server snippet","score":0.9,"metadata":{"sensitivity":"INTERNAL"}}]}
                """));

        QmdResult result = new QmdClient().query(new QmdQuery(
                baseUrl, List.of("memory-verified"), "query", null,
                Sensitivity.INTERNAL, 4, 0.15, 1_000, vault.toString()));

        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.snippet()).isEqualTo("server snippet"));
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

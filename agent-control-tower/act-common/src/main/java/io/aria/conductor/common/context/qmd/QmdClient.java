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

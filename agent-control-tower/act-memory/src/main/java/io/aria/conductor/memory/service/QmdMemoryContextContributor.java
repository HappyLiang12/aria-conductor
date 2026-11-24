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

/**
 * Verified shared memory from qmd, rendered before the DB memory fallback (order 39,
 * optional). Disabled by default: when {@code aria.memory.qmd.enabled} is false the
 * contributor never applies and never queries qmd, so CI needs no running qmd.
 * <p>
 * Only the {@code memory-verified} collection is queried; the client enforces both the
 * collection allow-list and the sensitivity ceiling on every hit, so a shared vault record
 * never outranks the request's ceiling.
 */
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
    private final String projectOverride;
    private final String vaultDir;
    private final long timeoutMillis;
    private final int maxResults;
    private final double minScore;

    public QmdMemoryContextContributor(QmdClient client,
            @Value("${aria.memory.qmd.enabled:false}") boolean enabled,
            @Value("${aria.memory.qmd.url:http://127.0.0.1:8181}") String url,
            @Value("${aria.memory.qmd.collections:memory-verified}") String collections,
            @Value("${aria.memory.qmd.project:}") String projectOverride,
            @Value("${aria.memory.qmd.vault-dir:}") String vaultDir,
            @Value("${aria.memory.qmd.timeout-ms:1500}") long timeoutMillis,
            @Value("${aria.memory.qmd.max-results:4}") int maxResults,
            @Value("${aria.memory.qmd.min-score:0.15}") double minScore) {
        this.client = client;
        this.enabled = enabled;
        this.url = url;
        this.collections = split(collections);
        this.projectOverride = projectOverride;
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
                scopeOf(request), request.maxSensitivity(),
                maxResults, minScore, timeoutMillis, vaultDir));
        if (result.degradedReason() != null) {
            return List.of(new ContextSection(SECTION_ID, TITLE, "", Sensitivity.INTERNAL,
                    List.of(), false, result.degradedReason()));
        }
        return QmdSectionRenderer.render(SECTION_ID, TITLE, result, MAX_CONTENT_CHARS)
                .map(List::of).orElseGet(List::of);
    }

    /**
     * The vault project scope. A real run's {@code workspaceRef} is {@code MODE@baseRef}
     * (for example {@code HOST@main}), not a path, so {@link QmdScope#projectOf} returns
     * {@code null} there and the configured single-project override applies instead.
     */
    private String scopeOf(ContextRequest request) {
        String scope = QmdScope.projectOf(request.workspaceRef());
        if (scope == null && projectOverride != null && !projectOverride.isBlank()) {
            return "project:" + projectOverride.strip();
        }
        return scope;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim)
                .filter(part -> !part.isBlank()).distinct().toList();
    }
}

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

/**
 * qmd-first verified knowledge (order 50, optional); the newest-five DB path is the
 * disabled/failure fallback. When {@code aria.knowledge.qmd.enabled} is false the
 * contributor keeps the pre-qmd behaviour and never queries qmd, so CI needs no
 * running qmd.
 * <p>
 * Only the {@code knowledge-verified} collection is queried; the client enforces both
 * the collection allow-list and the sensitivity ceiling on every hit. A clean empty
 * result yields no section rather than the irrelevant newest-five.
 */
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
    private final String projectOverride;
    private final String vaultDir;
    private final long timeoutMillis;
    private final int maxResults;
    private final double minScore;

    public KnowledgeContextContributor(KnowledgeService knowledgeService,
            QmdClient qmd,
            @Value("${aria.knowledge.qmd.enabled:false}") boolean enabled,
            @Value("${aria.knowledge.qmd.url:http://127.0.0.1:8181}") String url,
            @Value("${aria.knowledge.qmd.collections:knowledge-verified}") String collections,
            @Value("${aria.knowledge.qmd.project:}") String projectOverride,
            @Value("${aria.knowledge.qmd.vault-dir:}") String vaultDir,
            @Value("${aria.knowledge.qmd.timeout-ms:1500}") long timeoutMillis,
            @Value("${aria.knowledge.qmd.max-results:4}") int maxResults,
            @Value("${aria.knowledge.qmd.min-score:0.15}") double minScore) {
        this.knowledgeService = knowledgeService;
        this.qmd = qmd;
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
                scopeOf(request), request.maxSensitivity(),
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

    /**
     * The vault project scope. A real Aria run's {@code workspaceRef} is {@code MODE@baseRef}
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

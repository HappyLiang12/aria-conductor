package io.aria.conductor.memory.service;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Contributes the ranked recall slice as the optional {@code memory} section (order 40,
 * spec §8.1 "Memory" and §8.4). The section carries provenance ids, the maximum sensitivity
 * of its items, a bounded body (12 entity lines, 40 fact lines, 4,000 characters), and a
 * degraded reason when recall could not produce memory.
 * <p>
 * Recall runs on a shared single-thread executor with a 200 ms budget: a slow repository
 * returns an empty degraded section and never blocks the run. Any failure inside this
 * contributor — timeout, repository exception, render exception, empty result — is recorded
 * as a degraded reason; nothing is thrown into the assembler and nothing disappears
 * silently.
 */
@Component
public class MemoryContextContributor implements ContextContributor {

    static final String SECTION_ID = "memory";
    static final String TITLE = "Memory";
    static final int ORDER = 40;
    static final int MAX_ENTITY_LINES = 12;
    static final int MAX_FACT_LINES = 40;

    /** The memory section's share of the envelope (spec §8.2 step 5). */
    static final int MAX_CONTENT_CHARS = 4_000;

    /** Recall budget: a timeout degrades the section (spec §8.4 step 7). */
    static final long RECALL_TIMEOUT_MILLIS = 200;

    private static final ThreadFactory RECALL_THREADS = runnable -> {
        Thread thread = new Thread(runnable, "memory-recall");
        thread.setDaemon(true);
        return thread;
    };

    private final MemoryRecallService recall;
    private final ExecutorService executor;
    private final long timeoutMillis;

    @Autowired
    public MemoryContextContributor(MemoryRecallService recall) {
        this(recall, Executors.newSingleThreadExecutor(RECALL_THREADS), RECALL_TIMEOUT_MILLIS);
    }

    MemoryContextContributor(MemoryRecallService recall, ExecutorService executor, long timeoutMillis) {
        this.recall = recall;
        this.executor = executor;
        this.timeoutMillis = timeoutMillis;
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
    public List<ContextSection> contribute(ContextRequest request) {
        String text = queryText(request);
        String focus = request == null || request.workspaceRef() == null ? "" : request.workspaceRef();
        if (text.isBlank() && focus.isBlank()) {
            return List.of();
        }
        try {
            RecallResult result = recallWithinBudget(
                    new RecallQuery(text, request.maxSensitivity(), focus));
            return List.of(sectionOf(result));
        } catch (RecallDegradedException e) {
            return List.of(degradedSection(e.reason));
        } catch (RuntimeException e) {
            return List.of(degradedSection("memory-recall-error"));
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private RecallResult recallWithinBudget(RecallQuery query) {
        Future<RecallResult> future;
        try {
            future = executor.submit(() -> recall.recall(query));
        } catch (RejectedExecutionException e) {
            throw new RecallDegradedException("memory-recall-error", e);
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RecallDegradedException("memory-recall-timeout", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RecallDegradedException("memory-recall-interrupted", e);
        } catch (ExecutionException e) {
            throw new RecallDegradedException("memory-recall-error", e);
        }
    }

    private static ContextSection sectionOf(RecallResult result) {
        if (result == null || result.isEmpty()) {
            return degradedSection("memory-recall-empty");
        }
        Rendered rendered = render(result);
        if (rendered.content().isEmpty()) {
            return degradedSection("memory-recall-empty");
        }
        return new ContextSection(SECTION_ID, TITLE, rendered.content(), rendered.sensitivity(),
                rendered.sourceIds(), rendered.truncated(), null);
    }

    /** Deterministic layout: every entity line, then every fact line, then the char cap. */
    private static Rendered render(RecallResult result) {
        Map<String, MemoryEntityView> entitiesById = new LinkedHashMap<>();
        for (MemoryEntityView entity : result.entities()) {
            if (entity != null && entity.id() != null) {
                entitiesById.putIfAbsent(entity.id(), entity);
            }
        }
        StringBuilder content = new StringBuilder();
        Set<String> sourceIds = new LinkedHashSet<>();
        Sensitivity sensitivity = null;
        boolean truncated = result.truncated();
        int entityLines = 0;
        int factLines = 0;

        for (MemoryEntityView entity : result.entities()) {
            if (entity == null || entityLines >= MAX_ENTITY_LINES) {
                truncated |= entity != null;
                continue;
            }
            if (!append(content, entityLine(entity))) {
                truncated = true;
                continue;
            }
            entityLines++;
            sourceIds.add(entity.id());
            sensitivity = higher(sensitivity, sensitivityOf(entity.sensitivity()));
        }
        for (MemoryFactView fact : result.facts()) {
            if (fact == null || factLines >= MAX_FACT_LINES) {
                truncated |= fact != null;
                continue;
            }
            if (!append(content, factLine(fact, entitiesById.get(fact.entityId())))) {
                truncated = true;
                continue;
            }
            factLines++;
            sourceIds.add(fact.id());
            sensitivity = higher(sensitivity, sensitivityOf(fact.sensitivity()));
        }
        return new Rendered(content.toString(), List.copyOf(sourceIds),
                sensitivity == null ? Sensitivity.INTERNAL : sensitivity, truncated);
    }

    private static boolean append(StringBuilder content, String line) {
        int extra = content.length() == 0 ? line.length() : line.length() + 1;
        if (content.length() + extra > MAX_CONTENT_CHARS) {
            return false;
        }
        if (content.length() > 0) {
            content.append('\n');
        }
        content.append(line);
        return true;
    }

    private static String entityLine(MemoryEntityView entity) {
        return "- " + entity.kind() + "/" + entityName(entity) + " [entity " + entity.id() + "]";
    }

    private static String factLine(MemoryFactView fact, MemoryEntityView entity) {
        String owner = entity == null
                ? String.valueOf(fact.entityId())
                : entity.kind() + "/" + entityName(entity);
        String since = fact.validFrom() == null ? "unknown" : fact.validFrom().toString();
        return "  - " + owner + ": " + fact.factKey() + " = " + fact.valueText()
                + " (" + fact.origin() + ", valid since " + since + ") [fact " + fact.id() + "]";
    }

    private static String entityName(MemoryEntityView entity) {
        if (entity.displayName() != null && !entity.displayName().isBlank()) {
            return entity.displayName();
        }
        return entity.canonicalKey() == null ? String.valueOf(entity.id()) : entity.canonicalKey();
    }

    private static Sensitivity sensitivityOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Sensitivity.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Sensitivity higher(Sensitivity left, Sensitivity right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /** The goal plus the last three user turns (spec §8.4 step 1). */
    private static String queryText(ContextRequest request) {
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

    private static ContextSection degradedSection(String reason) {
        return new ContextSection(SECTION_ID, TITLE, "", Sensitivity.INTERNAL, List.of(), false, reason);
    }

    private record Rendered(String content, List<String> sourceIds, Sensitivity sensitivity, boolean truncated) {
    }

    /** A degraded recall outcome; carries the reason code that becomes the section reason. */
    private static final class RecallDegradedException extends RuntimeException {

        private final String reason;

        private RecallDegradedException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }
    }
}

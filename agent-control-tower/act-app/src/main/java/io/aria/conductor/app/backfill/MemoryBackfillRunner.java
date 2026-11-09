package io.aria.conductor.app.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.SystemConfig;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One-time memory backfill: it seeds the memory ontology from rows the system already
 * owns, and never derives a fact or a relation from free text.
 * <p>
 * It creates one ACTIVE {@code AGENT} entity per non-retired agent (canonical key
 * {@code agent:<uuid>}) and one ACTIVE {@code REPO} entity per distinct repository URL
 * observed in {@code workflow_chains.template_params} or {@code workflow_chains.steps_json}
 * (canonical key = normalized URL). Existing ACTIVE entities receive a PROPOSED knowledge
 * link only when a knowledge item's name is exactly the entity's display name. Nothing is
 * promoted: an operator decides links later.
 * <p>
 * Completion is recorded in {@code system_config} under {@value #COMPLETION_KEY}, so the
 * next boot is a no-op. Even without that marker the pass is row-idempotent: every
 * candidate is skipped when its {@code (kind, canonical_key)} already exists and every
 * link candidate is skipped when the {@code (entity_id, knowledge_item_id)} pair already
 * exists. The unique constraints are the backstop, never the primary check.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class MemoryBackfillRunner implements ApplicationRunner {

    /** Completion marker read at boot and written after the first pass. */
    static final String COMPLETION_KEY = "memory.backfill.completed";

    static final String KIND_AGENT = "AGENT";
    static final String KIND_REPO = "REPO";

    /** Repository identity observed on a workflow chain, in template params and step JSON. */
    private static final Set<String> REPO_PARAM_KEYS = Set.of("repoUrl", "issueRepo");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRepository agents;
    private final WorkflowChainRepository chains;
    private final KnowledgeItemRepository knowledge;
    private final MemoryEntityRepository entities;
    private final MemoryKnowledgeLinkRepository links;
    private final SystemConfigRepository systemConfig;
    private final boolean enabled;

    /**
     * A no-op guard for the current JVM. The durable guard is
     * {@link #COMPLETION_KEY}; this flag only avoids re-reading it when a caller runs the
     * pass twice in one process.
     */
    private volatile boolean completed;

    public MemoryBackfillRunner(AgentRepository agents,
                                WorkflowChainRepository chains,
                                KnowledgeItemRepository knowledge,
                                MemoryEntityRepository entities,
                                MemoryKnowledgeLinkRepository links,
                                SystemConfigRepository systemConfig,
                                @Value("${aria.memory.backfill.enabled:true}") boolean enabled) {
        this.agents = agents;
        this.chains = chains;
        this.knowledge = knowledge;
        this.entities = entities;
        this.links = links;
        this.systemConfig = systemConfig;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Memory backfill disabled (aria.memory.backfill.enabled=false)");
            return;
        }
        try {
            BackfillReport report = run();
            if (report.equals(BackfillReport.noop())) {
                log.info("Memory backfill already completed; nothing to do");
            }
        } catch (RuntimeException e) {
            // The pass is idempotent and completion is recorded last, so leaving the marker
            // unset means the next boot retries instead of failing startup forever.
            log.error("Memory backfill failed; it stays unmarked and is retried on the next boot: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * One deterministic pass. Idempotent: a second pass over the same rows writes nothing
     * (and returns a no-op report) whether or not the completion marker is present.
     */
    public synchronized BackfillReport run() {
        if (!enabled || completed || completionRecorded()) {
            return BackfillReport.noop();
        }
        Instant now = Instant.now();

        List<MemoryEntity> allEntities = entities.findAll();
        Set<EntityKey> existingKeys = allEntities.stream()
                .map(entity -> new EntityKey(entity.getKind(), entity.getCanonicalKey()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, MemoryEntity> linkTargets = new LinkedHashMap<>();
        for (MemoryEntity entity : allEntities) {
            if (entity.getStatus() == EntityStatus.ACTIVE) {
                linkTargets.put(entity.getId(), entity);
            }
        }

        int agentsCreated = 0;
        List<Agent> activeAgents = agents.findAll().stream()
                .filter(agent -> agent.getRetiredAt() == null)
                .sorted(Comparator.comparing(agent -> agent.getId().toString()))
                .toList();
        for (Agent agent : activeAgents) {
            String canonicalKey = "agent:" + agent.getId();
            if (!existingKeys.add(new EntityKey(KIND_AGENT, canonicalKey))) {
                continue;
            }
            MemoryEntity entity = newEntity(KIND_AGENT, canonicalKey, agent.getName(), now);
            entities.save(entity);
            linkTargets.put(entity.getId(), entity);
            agentsCreated++;
        }

        Set<String> repoKeys = new TreeSet<>();
        for (WorkflowChain chain : chains.findAll()) {
            collectRepoKeys(chain.getTemplateParams(), repoKeys);
            collectRepoKeys(chain.getStepsJson(), repoKeys);
        }
        int reposCreated = 0;
        for (String repoKey : repoKeys) {
            if (!existingKeys.add(new EntityKey(KIND_REPO, repoKey))) {
                continue;
            }
            MemoryEntity entity = newEntity(KIND_REPO, repoKey, repoKey, now);
            entities.save(entity);
            linkTargets.put(entity.getId(), entity);
            reposCreated++;
        }

        int linksProposed = proposeKnowledgeLinks(linkTargets, now);

        systemConfig.save(SystemConfig.builder()
                .configKey(COMPLETION_KEY)
                .configValue("true")
                .description("One-time memory backfill from non-retired agents and observed repository URLs")
                .updatedAt(now)
                .build());
        completed = true;

        log.info("Memory backfill complete: agentsCreated={}, reposCreated={}, linksProposed={}",
                agentsCreated, reposCreated, linksProposed);
        return new BackfillReport(agentsCreated, reposCreated, linksProposed);
    }

    private int proposeKnowledgeLinks(Map<UUID, MemoryEntity> linkTargets, Instant now) {
        List<KnowledgeItem> items = new ArrayList<>(knowledge.findAll());
        items.sort(Comparator.comparing(KnowledgeItem::getName)
                .thenComparing(item -> String.valueOf(item.getId())));

        Set<LinkKey> existingLinks = links.findAll().stream()
                .map(link -> new LinkKey(link.getEntityId(), link.getKnowledgeItemId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MemoryEntity> targets = linkTargets.values().stream()
                .filter(entity -> entity.getDisplayName() != null)
                .sorted(Comparator.comparing(MemoryEntity::getCanonicalKey)
                        .thenComparing(entity -> entity.getId().toString()))
                .toList();

        int proposed = 0;
        for (MemoryEntity entity : targets) {
            for (KnowledgeItem item : items) {
                if (!entity.getDisplayName().equals(item.getName())) {
                    continue;
                }
                if (!existingLinks.add(new LinkKey(entity.getId(), item.getId()))) {
                    continue;
                }
                links.save(MemoryKnowledgeLink.builder()
                        .entityId(entity.getId())
                        .knowledgeItemId(item.getId())
                        .status(MemoryLinkStatus.PROPOSED)
                        .createdAt(now)
                        .build());
                proposed++;
            }
        }
        return proposed;
    }

    private static MemoryEntity newEntity(String kind, String canonicalKey, String displayName, Instant now) {
        return MemoryEntity.builder()
                .id(UUID.randomUUID())
                .kind(kind)
                .canonicalKey(canonicalKey)
                .displayName(displayName)
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                // Backfill writes ACTIVE entities, which the governance matrix allows only for
                // operator authority; EXTRACTION would imply a PENDING proposal.
                .origin(MemoryOrigin.OPERATOR)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private boolean completionRecorded() {
        Optional<SystemConfig> completion = systemConfig.findByConfigKey(COMPLETION_KEY);
        return completion.isPresent();
    }

    private static void collectRepoKeys(String json, Set<String> out) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            // A chain may hold truncated or hand-edited JSON; skipping it is safer than
            // guessing a repository identity from a fragment.
            return;
        }
        collectRepoKeys(root, out);
    }

    private static void collectRepoKeys(JsonNode node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (REPO_PARAM_KEYS.contains(field.getKey())) {
                    addNormalizedRepoKey(field.getValue(), out);
                }
                collectRepoKeys(field.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectRepoKeys(child, out));
        }
    }

    private static void addNormalizedRepoKey(JsonNode value, Set<String> out) {
        if (value == null || !value.isTextual()) {
            return;
        }
        String normalized = normalizeRepoKey(value.asText());
        if (normalized != null) {
            out.add(normalized);
        }
    }

    /** Deterministic identity of a repository URL: trimmed, without trailing slashes. */
    static String normalizeRepoKey(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private record EntityKey(String kind, String canonicalKey) {
        private EntityKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(canonicalKey, "canonicalKey");
        }
    }

    private record LinkKey(UUID entityId, UUID knowledgeItemId) {
    }
}

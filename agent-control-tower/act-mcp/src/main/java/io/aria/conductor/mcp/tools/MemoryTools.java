package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.memory.dto.AddFactCommand;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.service.MemoryCommandService;
import io.aria.conductor.memory.service.MemoryConflictException;
import io.aria.conductor.memory.service.MemoryNotFoundException;
import io.aria.conductor.memory.service.MemoryProposalService;
import io.aria.conductor.memory.service.MemoryQueryService;
import io.aria.conductor.memory.service.MemoryRecallService;
import io.aria.conductor.memory.service.MemoryValidationException;
import io.aria.conductor.mcp.McpActorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The memory surfaces of the platform MCP endpoint (spec §9). Thin wrappers over the
 * merged act-memory services: the write path, the proposal review path, the read model,
 * recall, and the run's own context manifest.
 *
 * <p>Identity is transport-owned. A worker calling {@code remember_fact} is an EXTRACTION
 * actor, so the service writes a PENDING proposal; the operator credential is an OPERATOR
 * actor, so the same call writes ACTIVE memory. {@code decide_memory_proposal} and
 * {@code forget_fact} are OPERATOR_ONLY in the reviewed catalog: the tool re-checks the
 * policy against the transport actor and refuses a run-scoped worker before any memory
 * service is reached (a worker may never approve its own proposal).
 *
 * <p>Every response is the uniform {@code ok/errorType/message} envelope and carries ids,
 * provenance, sensitivity and validity; error messages name the field, never a value, so a
 * secret-like fact can never become the error that leaks it. The manifest tool returns
 * section ids, drops and degraded reasons only — the prompt content is never stored, and
 * the projection here is a whitelist so an unexpected field cannot travel through.
 */
@Component
@RequiredArgsConstructor
public class MemoryTools implements McpTool {

    static final String REMEMBER_FACT = "remember_fact";
    static final String RECALL_MEMORY = "recall_memory";
    static final String LIST_MEMORY_ENTITIES = "list_memory_entities";
    static final String GET_MEMORY_ENTITY = "get_memory_entity";
    static final String FORGET_FACT = "forget_fact";
    static final String LIST_MEMORY_PROPOSALS = "list_memory_proposals";
    static final String DECIDE_MEMORY_PROPOSAL = "decide_memory_proposal";
    static final String GET_CONTEXT_MANIFEST = "get_context_manifest";

    /** Provenance recorded when the caller supplies none. */
    private static final String DEFAULT_SOURCE_REF = "mcp:memory";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MemoryCommandService commands;
    private final MemoryProposalService proposals;
    private final MemoryQueryService queries;
    private final MemoryRecallService recall;
    private final ContextManifestRepository manifests;
    private final MemoryFactRepository facts;
    private final MemoryAssertionRepository assertions;
    private final ToolPolicyRegistry toolPolicies;
    private final McpProperties mcpProperties;

    @Tool(name = "remember_fact",
            description = "Record one fact about a memory entity. A worker call is recorded as a PENDING extraction proposal that an operator must approve; an operator call writes ACTIVE memory. Returns the fact with id, status, origin, sensitivity and validity.")
    public String rememberFact(
            @ToolParam(description = "Memory entity id (UUID)") UUID entityId,
            @ToolParam(description = "Fact key, e.g. timezone or preferred_editor") String factKey,
            @ToolParam(description = "Fact value; credential-like values are rejected") String valueText,
            @ToolParam(description = "Provenance pointer (tool call, message, extraction job)", required = false)
            String sourceRef,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = McpActorContext.require(toolContext);
            toolPolicies.requireAuthority(REMEMBER_FACT, actor);
            AddFactCommand command = new AddFactCommand(factKey, valueText, null, null,
                    sourceRef == null || sourceRef.isBlank() ? DEFAULT_SOURCE_REF : sourceRef);
            MemoryFact fact = commands.addFact(entityId, command, memoryActor(actor));
            return ToolResponses.ok(MemoryFactView.from(fact));
        } catch (SecurityException e) {
            return ToolResponses.error("FORBIDDEN", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryValidationException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryConflictException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("REMEMBER_FACT_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "recall_memory",
            description = "Recall ranked memory for a piece of text: matched entities and their current ACTIVE facts, filtered by the fail-closed INTERNAL sensitivity ceiling, each carrying id, origin, sensitivity and validity. Deterministic for the same state and query; never returns PENDING proposals.")
    public String recallMemory(
            @ToolParam(description = "Text to recall memory for (goal or conversation excerpt)") String text,
            @ToolParam(description = "Workspace or repository reference that names an entity", required = false)
            String focusRef) {
        try {
            return ToolResponses.ok(recall.recall(new RecallQuery(text, null, focusRef)));
        } catch (MemoryValidationException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RECALL_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_memory_entities",
            description = "List memory entities. Optional kind (e.g. PERSON, REPO, AGENT) and status (ACTIVE/MERGED/RETIRED; default ACTIVE); limit defaults to 50 and is capped at 200.")
    public String listMemoryEntities(
            @ToolParam(description = "Entity kind or blank", required = false) String kind,
            @ToolParam(description = "ACTIVE, MERGED or RETIRED; default ACTIVE", required = false) String status,
            @ToolParam(description = "Page size, default 50, max 200", required = false) Integer limit) {
        try {
            EntityStatus parsed = status == null || status.isBlank() ? null : parseEntityStatus(status);
            return ToolResponses.ok(queries.listEntities(kind, parsed, limit == null ? 0 : limit));
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("MEMORY_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_memory_entity",
            description = "Get one memory entity with its current ACTIVE facts: kind, canonical key, status, origin, sensitivity, version, and each fact's id, origin, sensitivity and validity.")
    public String getMemoryEntity(@ToolParam(description = "Memory entity id (UUID)") UUID entityId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("entity", queries.getEntity(entityId));
            payload.put("facts", queries.currentFacts(entityId));
            return ToolResponses.ok(payload);
        } catch (MemoryNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("MEMORY_GET_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "forget_fact",
            description = "Retire one fact (valid_to=now) and record the retire assertion. Operator authority only: a run-scoped worker credential is refused with FORBIDDEN and the fact stays current.")
    @Transactional
    public String forgetFact(
            @ToolParam(description = "Memory fact id (UUID)") UUID factId,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = McpActorContext.require(toolContext);
            toolPolicies.requireAuthority(FORGET_FACT, actor);
            actor.requireOperator();
            MemoryFact fact = facts.findById(factId)
                    .orElseThrow(() -> new MemoryNotFoundException("memory fact " + factId + " not found"));
            if (fact.getStatus() == FactStatus.RETIRED || fact.getValidTo() != null) {
                // Already forgotten: no second retire assertion is written.
                return ToolResponses.ok(MemoryFactView.from(fact));
            }
            Instant now = Instant.now();
            String before = audit("status", name(fact.getStatus()), "factKey", fact.getFactKey());
            fact.setStatus(FactStatus.RETIRED);
            fact.setValidTo(now);
            facts.save(fact);
            assertions.save(MemoryAssertion.builder()
                    .id(UUID.randomUUID())
                    .subjectType(MemorySubjectType.FACT)
                    .subjectId(fact.getId())
                    .action(MemoryAction.RETIRE)
                    .actorType(MemoryActorType.OPERATOR)
                    .actorRef(actor.runId() == null ? null : actor.runId().toString())
                    .sourceRef(DEFAULT_SOURCE_REF)
                    .beforeJson(before)
                    .afterJson(audit("status", FactStatus.RETIRED.name(), "factKey", fact.getFactKey()))
                    .createdAt(now)
                    .build());
            // Surface a stale-version or write failure inside the envelope instead of at
            // commit, after the tool has already answered ok.
            facts.flush();
            return ToolResponses.ok(MemoryFactView.from(fact));
        } catch (SecurityException e) {
            return ToolResponses.error("FORBIDDEN", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryConflictException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            return ToolResponses.error("CONFLICT", "the fact changed while it was being retired; re-read before retrying",
                    e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("FORGET_FACT_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_memory_proposals",
            description = "List memory proposals awaiting review: PENDING facts and relations newest first, each with subjectType, id, origin, sensitivity and creation time. status defaults to PENDING.")
    public String listMemoryProposals(
            @ToolParam(description = "PENDING, ACTIVE or RETIRED; default PENDING", required = false) String status) {
        try {
            FactStatus parsed = status == null || status.isBlank() ? FactStatus.PENDING : parseFactStatus(status);
            return ToolResponses.ok(proposals.listProposals(parsed));
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROPOSAL_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "decide_memory_proposal",
            description = "Approve or reject a PENDING memory proposal. Operator authority only: the transport-resolved caller must be the authenticated OPERATOR — a run-scoped worker credential is refused with FORBIDDEN and no proposal changes state. Approval promotes the subject to ACTIVE and retires a conflicting current value.")
    public String decideMemoryProposal(
            @ToolParam(description = "Subject id of the PENDING proposal (fact or relation)") UUID proposalId,
            @ToolParam(description = "true = approve, false = reject") boolean approve,
            @ToolParam(description = "Decision note (audit only)", required = false) String reason,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = McpActorContext.require(toolContext);
            toolPolicies.requireAuthority(DECIDE_MEMORY_PROPOSAL, actor);
            actor.requireOperator();
            MemoryProposalView decided = proposals.decideProposal(proposalId,
                    approve ? ProposalDecision.APPROVE : ProposalDecision.REJECT,
                    memoryActor(actor));
            return ToolResponses.ok(decided);
        } catch (SecurityException e) {
            return ToolResponses.error("FORBIDDEN", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryConflictException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROPOSAL_DECIDE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_context_manifest",
            description = "Read every context assembly recorded for a run, in turn order: turnNumber, adapter, section ids with source ids and estimates, dropped sections with reasons, and degraded sections with reasons. Metadata only — prompt content is never stored or returned.")
    public String getContextManifest(@ToolParam(description = "Run id (UUID)") UUID runId) {
        try {
            List<ContextManifest> rows = manifests.findByRunIdOrderByTurnNumberAsc(runId);
            List<Map<String, Object>> turns = new ArrayList<>();
            for (ContextManifest row : rows == null ? List.<ContextManifest>of() : rows) {
                if (row == null) {
                    continue;
                }
                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("turnNumber", row.getTurnNumber());
                turn.put("adapter", row.getAdapter());
                turn.put("sections", sections(row.getSectionsJson()));
                turn.put("dropped", strings(row.getDroppedJson()));
                turn.put("degraded", strings(row.getDegradedJson()));
                turn.put("maxChars", row.getMaxChars());
                turn.put("estimatedTokens", row.getEstimatedTokens());
                turn.put("createdAt", row.getCreatedAt());
                turns.add(turn);
            }
            return ToolResponses.ok(turns);
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("MANIFEST_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    /**
     * The actor's memory authority: the operator credential is OPERATOR (ACTIVE memory),
     * every run-scoped worker is EXTRACTION (a PENDING proposal). The worker's run id is
     * the audit reference, never caller-supplied JSON.
     */
    private static MemoryActor memoryActor(ActorPrincipal actor) {
        if (actor.role() == ActorPrincipal.Role.OPERATOR) {
            return new MemoryActor(MemoryActorType.OPERATOR, null);
        }
        return new MemoryActor(MemoryActorType.EXTRACTION,
                actor.runId() == null ? null : actor.runId().toString());
    }

    /**
     * Whitelist projection of {@code sections_json}: only the manifest metadata fields are
     * copied, so an unexpected field (in particular prompt text) can never reach the client.
     */
    private static List<Map<String, Object>> sections(String raw) {
        JsonNode root = parse(raw);
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> projected = new ArrayList<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                continue;
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("id", text(node, "id"));
            section.put("sources", strings(node.get("sources")));
            section.put("chars", node.path("chars").asInt(0));
            section.put("tokens", node.path("tokens").asInt(0));
            section.put("truncated", node.path("truncated").asBoolean(false));
            projected.add(section);
        }
        return projected;
    }

    /** The dropped/degraded lists are {@code sectionId:reason} strings written by the assembler. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(String raw) {
        JsonNode root = parse(raw);
        if (root == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (node != null && node.isValueNode()) {
                    values.add(node.asText());
                }
            }
        } else if (root.isValueNode() && !root.isNull()) {
            values.add(root.asText());
        }
        return values;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry != null && entry.isValueNode()) {
                values.add(entry.asText());
            }
        }
        return values;
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("context manifest JSON is unreadable");
        }
    }

    /** A small JSON audit payload, built from alternating key/value arguments. */
    private static String audit(Object... keysAndValues) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            snapshot.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (Exception e) {
            return null;
        }
    }

    private static EntityStatus parseEntityStatus(String raw) {
        try {
            return EntityStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status '" + raw + "'. Valid: ACTIVE, MERGED, RETIRED");
        }
    }

    private static FactStatus parseFactStatus(String raw) {
        try {
            return FactStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status '" + raw + "'. Valid: PENDING, ACTIVE, RETIRED");
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

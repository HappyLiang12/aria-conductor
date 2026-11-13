package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.memory.dto.AddFactCommand;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.service.MemoryCommandService;
import io.aria.conductor.memory.service.MemoryConflictException;
import io.aria.conductor.memory.service.MemoryNotFoundException;
import io.aria.conductor.memory.service.MemoryProposalService;
import io.aria.conductor.memory.service.MemoryQueryService;
import io.aria.conductor.memory.service.MemoryRecallService;
import io.aria.conductor.memory.service.MemoryValidationException;
import io.aria.conductor.mcp.McpActorContext;
import io.aria.conductor.mcp.catalog.ToolCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

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
 *
 * <p>Every read is clamped to the deployment ceiling {@link #READ_CEILING} (INTERNAL):
 * facts, relations and proposals above it, and entities themselves above it, are removed
 * before the response is built. The actor of the call may only lower that ceiling — the
 * operator credential is the strongest authority on this surface and still reads at
 * INTERNAL — so no credential and no tool argument can widen a read.
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

    /**
     * The deployment ceiling of every memory read on this surface: worker-facing data
     * stops at INTERNAL (spec §9). It is a constant, not a caller input — an actor can
     * only lower it, never raise it.
     */
    static final Sensitivity READ_CEILING = Sensitivity.INTERNAL;

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MemoryCommandService commands;
    private final MemoryProposalService proposals;
    private final MemoryQueryService queries;
    private final MemoryRecallService recall;
    private final ContextManifestRepository manifests;
    private final ToolPolicyRegistry toolPolicies;
    private final McpProperties mcpProperties;

    @Tool(name = "remember_fact",
            description = "Record one fact about a memory entity, in exactly one of two shapes: (entityId) adds the fact to an existing entity, (kind, canonicalKey) resolves the key first — kind is case-insensitive (person and PERSON name the same entity kind; accepted kinds PERSON, PROJECT, REPO, MACHINE, AGENT, TASK, ORG; an unknown kind is refused with VALIDATION); a new key creates the entity and its first fact as one proposal batch (the fact proposal commits in its own transaction); an existing ACTIVE key adds the fact to that entity, and an existing PENDING key takes only a worker's PENDING proposal (an operator call on a PENDING key is refused as a conflict). A RETIRED or MERGED key is re-proposed to PENDING by a worker's call, which retires that key's live ACTIVE and PENDING facts and ACTIVE relations so the new proposal decides only what it adds; an operator call on a decided key is refused as a conflict. A worker call is recorded as a PENDING extraction proposal that an operator must approve; an operator call writes ACTIVE memory. One operator decision on the entity proposal promotes the new entity and its first fact together. Returns the fact with id, status, origin, sensitivity and validity.")
    public String rememberFact(
            @ToolParam(description = "Existing memory entity id (UUID); supply this or kind + canonicalKey, not both",
                    required = false)
            UUID entityId,
            @ToolParam(description = "Fact key, e.g. timezone or preferred_editor") String factKey,
            @ToolParam(description = "Fact value; credential-like values are rejected") String valueText,
            @ToolParam(description = "Provenance pointer (tool call, message, extraction job)", required = false)
            String sourceRef,
            @ToolParam(description = "Kind of the entity, case-insensitive (e.g. person or PERSON); requires "
                    + "canonicalKey. An existing kind + canonicalKey reuses the stored entity instead of creating "
                    + "one; an unknown kind is refused", required = false)
            String kind,
            @ToolParam(description = "Canonical key of that entity, e.g. person:happy; requires kind. "
                    + "A key that already exists resolves to its entity; a new key creates it",
                    required = false)
            String canonicalKey,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = McpActorContext.require(toolContext);
            toolPolicies.requireAuthority(REMEMBER_FACT, actor);
            requireExactlyOneShape(entityId, kind, canonicalKey);
            AddFactCommand command = new AddFactCommand(factKey, valueText, null, null,
                    sourceRef == null || sourceRef.isBlank() ? DEFAULT_SOURCE_REF : sourceRef);
            MemoryFact fact = entityId == null
                    ? commands.addFactByKey(kind, canonicalKey, command, memoryActor(actor))
                    : commands.addFact(entityId, command, memoryActor(actor));
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
            String focusRef,
            ToolContext toolContext) {
        try {
            Sensitivity ceiling = readCeiling(actorOf(toolContext));
            RecallResult result = recall.recall(new RecallQuery(text, ceiling, focusRef));
            List<MemoryEntityView> entities = safe(result.entities()).stream()
                    .filter(entity -> withinCeiling(entity.sensitivity(), ceiling))
                    .toList();
            List<MemoryFactView> facts = safe(result.facts()).stream()
                    .filter(fact -> withinCeiling(fact.sensitivity(), ceiling))
                    .toList();
            return ToolResponses.ok(new RecallResult(entities, facts, result.truncated()));
        } catch (MemoryValidationException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RECALL_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_memory_entities",
            description = "List memory entities at or below the INTERNAL sensitivity ceiling. Optional kind, case-insensitive (e.g. person or PERSON; accepted kinds PERSON, PROJECT, REPO, MACHINE, AGENT, TASK, ORG) and status (ACTIVE/MERGED/RETIRED; default ACTIVE); limit defaults to 50 and is capped at 200. A blank kind lists every entity; an unknown kind is refused with VALIDATION rather than answered as an empty list, and a known kind with no matching rows is an empty list. A PENDING entity is review material: the explicit status=PENDING listing is operator authority only, and a run-scoped worker credential is refused with FORBIDDEN.")
    public String listMemoryEntities(
            @ToolParam(description = "Entity kind, case-insensitive (e.g. person or PERSON); blank lists every "
                    + "entity; an unknown kind is refused", required = false) String kind,
            @ToolParam(description = "ACTIVE, MERGED, RETIRED or PENDING (PENDING is operator authority only); "
                    + "default ACTIVE", required = false) String status,
            @ToolParam(description = "Page size, default 50, max 200", required = false) Integer limit,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = actorOf(toolContext);
            Sensitivity ceiling = readCeiling(actor);
            EntityStatus parsed = status == null || status.isBlank() ? null : parseEntityStatus(status);
            if (parsed == EntityStatus.PENDING) {
                // A pending entity is review material, not a read: the row exists and the
                // operator reviews it, so a worker gets an actionable refusal rather than
                // an empty list it might report as "nothing exists".
                ToolResponses.requireOperator(actor, "listing pending memory entities",
                        "Review them on the Memory page or in the Review Queue.");
            }
            List<MemoryEntityView> entities = safe(queries.listEntities(kind, parsed, limit == null ? 0 : limit))
                    .stream()
                    .filter(entity -> withinCeiling(entity.sensitivity(), ceiling))
                    .toList();
            return ToolResponses.ok(entities);
        } catch (SecurityException e) {
            return ToolResponses.error("FORBIDDEN", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("MEMORY_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_memory_entity",
            description = "Get one memory entity with its current ACTIVE facts at or below the INTERNAL sensitivity ceiling: kind, canonical key, status, origin, sensitivity, version, and each fact's id, origin, sensitivity and validity. A PENDING entity is review material: this agent-facing detail read reports it as not found, and the operator reviews it with the explicit status=PENDING detail filter on the Memory page. Should a PENDING row still reach this read, an operator credential may see it and any other credential is refused with FORBIDDEN.")
    public String getMemoryEntity(
            @ToolParam(description = "Memory entity id (UUID)") UUID entityId,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = actorOf(toolContext);
            Sensitivity ceiling = readCeiling(actor);
            MemoryEntityView entity = queries.getEntity(entityId);
            if ("PENDING".equals(entity.status())) {
                // Defence in depth over the service's own PENDING exclusion: if a pending
                // row ever reached this read, only the operator may see it, and everyone
                // else gets the review surface rather than review material.
                ToolResponses.requireOperator(actor, "reading a pending memory entity",
                        "Review it on the Memory page or in the Review Queue.");
            }
            if (!withinCeiling(entity.sensitivity(), ceiling)) {
                // Over-ceiling entities are indistinguishable from missing ones: the
                // refusal must not confirm that a CONFIDENTIAL entity exists.
                throw new MemoryNotFoundException("memory entity " + entityId + " not found");
            }
            List<MemoryFactView> facts = safe(queries.currentFacts(entityId)).stream()
                    .filter(fact -> withinCeiling(fact.sensitivity(), ceiling))
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("entity", entity);
            payload.put("facts", facts);
            return ToolResponses.ok(payload);
        } catch (SecurityException e) {
            return ToolResponses.error("FORBIDDEN", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("MEMORY_GET_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    /**
     * The governed forget: the mutation is the command service's transactional
     * {@code retireFact}, never an inline repository write, so the status check, the
     * RETIRE assertion, the version flush and the conflict translation all happen before
     * this method answers and a conflict can never ride a transaction that still commits.
     * <p>
     * The acknowledgement is metadata only — id, provenance, sensitivity and validity,
     * never the value. The caller already holds the fact id, so echoing the retired value
     * would be a value read above the deployment ceiling by another name.
     */
    @Tool(name = "forget_fact",
            description = "Retire one fact (valid_to=now) and record the retire assertion. Operator authority only: a run-scoped worker credential is refused with FORBIDDEN and the fact stays current."
                    + ToolCatalog.OPERATOR_SESSION_CLAUSE)
    public String forgetFact(
            @ToolParam(description = "Memory fact id (UUID)") UUID factId,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = McpActorContext.require(toolContext);
            ToolResponses.requireOperator(actor, "retiring a fact", "Retire it on the Memory page.");
            toolPolicies.requireAuthority(FORGET_FACT, actor);
            MemoryFact fact = commands.retireFact(factId, memoryActor(actor));
            return ToolResponses.ok(retireAck(fact));
        } catch (SecurityException e) {
            return ToolResponses.error("FORBIDDEN", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (MemoryConflictException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("FORGET_FACT_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_memory_proposals",
            description = "List memory proposals awaiting review at or below the INTERNAL sensitivity ceiling: PENDING facts and relations newest first, each with subjectType, id, origin, sensitivity and creation time. status defaults to PENDING. An entity row is listed for status ACTIVE or RETIRED to any credential; a PENDING entity row, with its canonical key in factKey, is returned to an operator credential only.")
    public String listMemoryProposals(
            @ToolParam(description = "PENDING, ACTIVE or RETIRED; default PENDING", required = false) String status,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = actorOf(toolContext);
            Sensitivity ceiling = readCeiling(actor);
            FactStatus parsed = status == null || status.isBlank() ? FactStatus.PENDING : parseFactStatus(status);
            // T11 C1: the inbox hides a PENDING entity's canonical key and display name from every
            // non-operator read. This tool is an AUTO-APPROVED worker read, so it must hand the
            // service the transport identity (the worker's run id) rather than let the read default
            // to the operator view: list_memory_entities(status=PENDING) refuses the same rows.
            MemoryActor reviewer = actor == null ? null : memoryActor(actor);
            List<MemoryProposalView> inbox = safe(proposals.listProposals(parsed, reviewer)).stream()
                    .filter(proposal -> withinCeiling(proposal.sensitivity(), ceiling))
                    .toList();
            return ToolResponses.ok(inbox);
        } catch (MemoryValidationException | IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROPOSAL_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "decide_memory_proposal",
            description = "Approve or reject a PENDING memory proposal. Operator authority only: the transport-resolved caller must be the authenticated OPERATOR — a run-scoped worker credential is refused with FORBIDDEN and no proposal changes state. Approval promotes the subject to ACTIVE and retires a conflicting current value."
                    + ToolCatalog.OPERATOR_SESSION_CLAUSE)
    public String decideMemoryProposal(
            @ToolParam(description = "Subject id of the PENDING proposal (fact or relation)") UUID proposalId,
            @ToolParam(description = "true = approve, false = reject") boolean approve,
            // Accepted for client compatibility only: MemoryProposalService has no reason
            // channel, so the note is neither persisted nor audited. It is not "audit only".
            @ToolParam(description = "Decision note; accepted for compatibility and not persisted "
                    + "(the memory service has no reason channel)", required = false) String reason,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = McpActorContext.require(toolContext);
            ToolResponses.requireOperator(actor, "approving a memory proposal",
                    "Approve it on the proposal card in the Aria conversation or on the Memory page.");
            toolPolicies.requireAuthority(DECIDE_MEMORY_PROPOSAL, actor);
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

    /**
     * Context-assembly metadata for a run: section ids with source ids and sizes, drops and
     * degraded reasons. The projection is a whitelist, so an unexpected field — in
     * particular prompt text, which is never stored here — cannot travel through.
     * <p>
     * Decision (2026-11 review, reaffirmed by the 2026-11-11 operator amendment): the manifest
     * stays <em>global metadata</em> — any caller the tool policy admits may read any run's
     * manifest, not only the actor's own run. The read therefore resolves <em>no</em> actor on
     * purpose: an actor would not narrow the answer, so identity here would be theatre, and
     * admission is the reviewed {@code WORKER_READ} classification ({@code ToolCatalog}, the
     * auto-approved memory reads) plus the reviewed auto-approve list
     * ({@code McpProperties.autoApproveReadTools}) at the execution boundary. A caller with no
     * credential reads the same metadata as an operator.
     * <p>
     * That is safe only because nothing per-run and secret is in the answer, VERIFIED at three
     * points rather than assumed: <ol>
     *   <li>the producer stores no text — {@code ContextManifestService.record} copies only
     *       id/sources/chars/tokens/truncated per section plus the dropped/degraded reason strings
     *       and counts, never {@code ContextSection.content}, pinned by
     *       {@code ContextManifestServiceTest.theRecordCarriesIdsCountsReasonsAndNeverThePromptText};</li>
     *   <li>{@code sources} are opaque ids only — memory entities/facts add their UUIDs
     *       ({@code MemoryContextContributor}), never a key, display name or value;</li>
     *   <li>this method's {@link #sections(String)} (like the REST twin's
     *       {@code MemoryManifestView}) projects a fixed key set, so a field the assembler never
     *       writes could not leak either.</li>
     * </ol>
     * The REST twin is operator-gated because it is the dashboard's operator surface, not because
     * it returns more. If per-run scoping is ever decided, the seam is
     * {@code McpActorContext.require(toolContext)} plus a filter on {@code actor.runId()}; until
     * then the cross-run read is the reviewed behaviour, not an oversight.
     */
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
     * The ceiling one read applies: the deployment ceiling, lowered by the caller's own
     * grant when it is narrower. Both authorities hold the deployment ceiling today, so a
     * run-scoped worker, the operator credential and the anonymous auth-mode=none session
     * read identically — the clamp is the invariant, and it is the only place a future
     * per-actor grant could narrow a read.
     */
    static Sensitivity readCeiling(ActorPrincipal actor) {
        Sensitivity grant = actor == null ? READ_CEILING : grantOf(actor);
        return grant.ordinal() < READ_CEILING.ordinal() ? grant : READ_CEILING;
    }

    /** The reviewed grant table: no authority holds more than the deployment ceiling yet. */
    private static Sensitivity grantOf(ActorPrincipal actor) {
        return switch (actor.role()) {
            case OPERATOR, WORKER -> READ_CEILING;
        };
    }

    /**
     * Best-effort transport actor. A read does not require one — auth-mode=none publishes
     * no actor and the read still runs at the deployment ceiling — and identity never
     * comes from the tool arguments, so an absent actor cannot widen or narrow the result.
     */
    private static ActorPrincipal actorOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        try {
            return McpActorContext.require(toolContext);
        } catch (SecurityException e) {
            return null;
        }
    }

    /**
     * Fail closed: an unlabelled or unreadable sensitivity is treated as RESTRICTED, so
     * missing metadata can never widen what a read returns.
     */
    private static boolean withinCeiling(String sensitivity, Sensitivity ceiling) {
        Sensitivity effective;
        try {
            effective = sensitivity == null ? Sensitivity.RESTRICTED : Sensitivity.valueOf(sensitivity);
        } catch (IllegalArgumentException e) {
            effective = Sensitivity.RESTRICTED;
        }
        return effective.ordinal() <= ceiling.ordinal();
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
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
     * Whitelist projection of a retired fact: every field a caller needs to confirm the
     * retire, and deliberately no {@code valueText}. Keep it a whitelist — a new field on
     * {@link MemoryFact} must be reviewed before it can travel through this surface.
     */
    private static Map<String, Object> retireAck(MemoryFact fact) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("id", fact.getId() == null ? null : fact.getId().toString());
        ack.put("entityId", fact.getEntityId() == null ? null : fact.getEntityId().toString());
        ack.put("factKey", fact.getFactKey());
        ack.put("valueType", fact.getValueType());
        ack.put("status", fact.getStatus() == null ? null : fact.getStatus().name());
        ack.put("origin", fact.getOrigin() == null ? null : fact.getOrigin().name());
        ack.put("sensitivity", fact.getSensitivity() == null ? null : fact.getSensitivity().name());
        ack.put("confidence", fact.getConfidence());
        ack.put("validFrom", fact.getValidFrom());
        ack.put("validTo", fact.getValidTo());
        ack.put("version", fact.getVersion());
        return ack;
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

    /**
     * The two shapes of {@code remember_fact}: an existing entity id, or the {@code (kind,
     * canonicalKey)} pair that creates the entity with its first fact. Exactly one must be
     * supplied; anything else names both shapes rather than guessing which one was meant.
     */
    private static void requireExactlyOneShape(UUID entityId, String kind, String canonicalKey) {
        boolean hasEntity = entityId != null;
        boolean hasKind = !isBlank(kind);
        boolean hasKey = !isBlank(canonicalKey);
        if (hasEntity && (hasKind || hasKey)) {
            throw new MemoryValidationException("remember_fact accepts exactly one shape: entityId (an existing "
                    + "entity) or kind + canonicalKey (a new entity); both were supplied");
        }
        if (!hasEntity && !hasKind && !hasKey) {
            throw new MemoryValidationException("remember_fact accepts exactly one shape: entityId (an existing "
                    + "entity) or kind + canonicalKey (a new entity); neither was supplied");
        }
        if (!hasEntity && !(hasKind && hasKey)) {
            throw new MemoryValidationException("remember_fact needs both kind and canonicalKey to target a new "
                    + "entity; " + (hasKind ? "canonicalKey" : "kind") + " is missing");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static EntityStatus parseEntityStatus(String raw) {
        try {
            return EntityStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid status '" + raw + "'. Valid: ACTIVE, PENDING, MERGED, RETIRED");
        }
    }

    private static FactStatus parseFactStatus(String raw) {
        try {
            return FactStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status '" + raw + "'. Valid: PENDING, ACTIVE, RETIRED");
        }
    }
}

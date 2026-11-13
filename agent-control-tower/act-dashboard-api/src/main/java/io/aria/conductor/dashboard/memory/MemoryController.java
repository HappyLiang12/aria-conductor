package io.aria.conductor.dashboard.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.dashboard.memory.dto.AddFactRequest;
import io.aria.conductor.dashboard.memory.dto.CreateEntityRequest;
import io.aria.conductor.dashboard.memory.dto.DecideProposalRequest;
import io.aria.conductor.dashboard.memory.dto.MemoryEntityDetail;
import io.aria.conductor.dashboard.memory.dto.MemoryManifestView;
import io.aria.conductor.dashboard.memory.dto.MemoryRecallPreview;
import io.aria.conductor.dashboard.memory.dto.MemoryRelationView;
import io.aria.conductor.dashboard.memory.dto.PatchEntityRequest;
import io.aria.conductor.dashboard.memory.dto.RetireFactRequest;
import io.aria.conductor.dashboard.memory.dto.SupersedeFactRequest;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import io.aria.conductor.memory.service.MemoryCommandService;
import io.aria.conductor.memory.service.MemoryConflictException;
import io.aria.conductor.memory.service.MemoryProposalService;
import io.aria.conductor.memory.service.MemoryQueryService;
import io.aria.conductor.memory.service.MemoryRecallService;
import io.aria.conductor.memory.service.MemoryValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The memory operator surface (spec §9). All reads are deterministic views over the merged
 * act-memory services. Every route resolves operator authority before it reaches a service — reads
 * as a read, writes as a mutation — so a worker credential, a failed origin/CSRF check, or an
 * anonymous remote caller gets 403 {@code FORBIDDEN}. That includes the entity detail read,
 * {@code GET /entities/{id}}: it resolves authority on every request, because it also returns the
 * entity's whole fact history, PENDING facts and over-ceiling values included. What the default
 * detail read does withhold is the PENDING <em>entity</em> row, which answers 404 — the guard is
 * "not PENDING", not "ACTIVE-only": a MERGED or RETIRED row is read like an ACTIVE one. An explicit
 * {@code status=PENDING} selects the operator's review read of a PENDING entity row (spec :309-313:
 * the operator cannot approve what they cannot see); it is the only status value this route
 * honours, and any other value is a VALIDATION error. Responses carry ids, provenance, sensitivity
 * and validity — never credentials and, on the manifest route, never prompt content.
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int DEFAULT_RECALL_LIMIT = 20;
    private static final int MAX_RECALL_LIMIT = 40;

    /**
     * The actor of the review-inbox read. The route resolves the caller's authority before it
     * gets here, and this constant then names the operator the route serves: the service only
     * withholds a PENDING entity's identity from a non-operator actor (T11 C1). It writes
     * nothing, so it carries no audit reference.
     */
    private static final MemoryActor INBOX_REVIEWER = new MemoryActor(MemoryActorType.OPERATOR, null);

    private final MemoryCommandService command;
    private final MemoryQueryService query;
    private final MemoryProposalService proposals;
    private final MemoryRecallService recall;
    private final MemoryLifecycleService lifecycle;
    private final ContextManifestRepository manifests;
    private final MemoryRelationRepository relations;
    private final MemoryKnowledgeLinkRepository links;
    private final MemoryAssertionRepository assertions;
    private final OperatorAuthorityResolver operatorAuthority;
    private final ObjectMapper mapper;

    public MemoryController(MemoryCommandService command,
                            MemoryQueryService query,
                            MemoryProposalService proposals,
                            MemoryRecallService recall,
                            MemoryLifecycleService lifecycle,
                            ContextManifestRepository manifests,
                            MemoryRelationRepository relations,
                            MemoryKnowledgeLinkRepository links,
                            MemoryAssertionRepository assertions,
                            OperatorAuthorityResolver operatorAuthority,
                            ObjectMapper mapper) {
        this.command = command;
        this.query = query;
        this.proposals = proposals;
        this.recall = recall;
        this.lifecycle = lifecycle;
        this.manifests = manifests;
        this.relations = relations;
        this.links = links;
        this.assertions = assertions;
        this.operatorAuthority = operatorAuthority;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- entities

    @GetMapping("/entities")
    public List<MemoryEntityView> listEntities(@RequestParam(required = false) String kind,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) EntityStatus status,
                                               @RequestParam(required = false) Integer limit,
                                               HttpServletRequest request) {
        // A status=PENDING page is review material (spec :309-313): refuse a worker credential
        // before any row is read rather than serving it the extraction's proposals.
        operatorAuthority.resolveOperator(request, false);
        int pageSize = pageSize(limit);
        boolean hasQuery = q != null && !q.isBlank();
        // The merged read model has no text predicate: a text query reads the bounded
        // maximum page, filters canonical key and display name, then applies the caller's
        // limit. Without a query the page limit goes straight to the repository.
        List<MemoryEntityView> rows = query.listEntities(kind, status, hasQuery ? MAX_LIST_LIMIT : pageSize);
        if (!hasQuery) {
            return rows;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return rows.stream()
                .filter(view -> contains(view.canonicalKey(), needle) || contains(view.displayName(), needle))
                .limit(pageSize)
                .toList();
    }

    @PostMapping("/entities")
    public ResponseEntity<MemoryEntityView> createEntity(@RequestBody CreateEntityRequest body,
                                                         HttpServletRequest request) {
        MemoryEntity entity = command.createEntity(body.toCommand(), operator(request));
        MemoryEntityView view = MemoryEntityView.from(entity);
        // A create that resolves an existing non-ACTIVE row created nothing: a PENDING row is still
        // awaiting its proposal decision, and a RETIRED/MERGED row stays decided unless a worker
        // re-proposes it. Answering 201 with that row would tell the operator an edit landed, and the
        // PATCH remedy below is refused for every non-ACTIVE row, so name the route that can work.
        if (!"ACTIVE".equals(view.status())) {
            String remedy = "PENDING".equals(view.status())
                    ? "approve or reject it in the Memory proposal card instead of creating it again"
                    : "a decided key is reopened only by a worker's re-proposal, which retires the old "
                            + "live graph and then waits for your approval; PATCH cannot change a non-ACTIVE entity";
            throw new MemoryConflictException("an entity with kind " + view.kind()
                    + " and canonical key '" + view.canonicalKey() + "' is " + view.status()
                    + "; nothing was created: " + remedy);
        }
        // For an existing ACTIVE key `createEntity` returns the stored row unchanged (the
        // re-propose path applies the fields; a new row carries them). So a request that names a
        // display name or sensitivity the stored row does not carry created nothing and applied
        // nothing, and answering 201 with the old row told the operator their edit had landed.
        // The honest answer is the governed conflict — the closed CONFLICT code, no new field —
        // that points at the route which can actually make that change.
        boolean ignoredDisplayName = body.displayName() != null && !body.displayName().isBlank()
                && !body.displayName().equals(view.displayName());
        boolean ignoredSensitivity = body.sensitivity() != null
                && !body.sensitivity().name().equals(view.sensitivity());
        if (ignoredDisplayName || ignoredSensitivity) {
            throw new MemoryConflictException("an entity with kind " + view.kind()
                    + " and canonical key '" + view.canonicalKey() + "' already exists; PATCH it to "
                    + "change its display name or sensitivity");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * The detail read, operator-only like every other route: authority is resolved before anything
     * is read, so a worker credential is refused instead of being served the entity and its whole
     * history. A PENDING entity row is review material and answers 404 on the default read; an
     * explicit {@code status=PENDING} is the operator's review read (spec :309-313: the operator
     * cannot approve what they cannot see) and includes that row.
     * <p>
     * {@code PENDING} is the only status this route honours. The parameter is typed as
     * {@link EntityStatus}, but a detail read of an ACTIVE/MERGED/RETIRED row is just the detail
     * read — accepting the word silently told the caller that {@code status=MERGED} had selected
     * MERGED rows when it selected nothing. Any other value is therefore a VALIDATION error that
     * names the only supported value; the dashboard already sends exactly {@code PENDING}.
     */
    @GetMapping("/entities/{id}")
    public MemoryEntityDetail getEntity(@PathVariable UUID id,
                                        @RequestParam(required = false) EntityStatus status,
                                        HttpServletRequest request) {
        // The detail read is the operator surface whether or not a status is asked for. Resolving
        // authority only on the PENDING branch left the default read open to a worker credential,
        // which then read the whole fact history — PENDING review material and over-ceiling values
        // included — and made this route disagree with its fail-closed MCP twin.
        operatorAuthority.resolveOperator(request, false);
        if (status != null && status != EntityStatus.PENDING) {
            throw new MemoryValidationException("status=" + status
                    + " is not supported by this route: the only supported value is PENDING");
        }
        boolean includePending = status == EntityStatus.PENDING;
        List<MemoryRelationView> entityRelations = new ArrayList<>();
        for (MemoryRelation relation : safe(relations.findByFromEntityIdAndStatus(id, FactStatus.ACTIVE))) {
            entityRelations.add(MemoryMapper.relationView(relation));
        }
        for (MemoryRelation relation : safe(relations.findByToEntityIdAndStatus(id, FactStatus.ACTIVE))) {
            entityRelations.add(MemoryMapper.relationView(relation));
        }
        entityRelations.sort(Comparator.comparing(MemoryRelationView::id,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<MemoryKnowledgeLink> entityLinks = safe(links.findByEntityId(id));
        List<MemoryAssertion> timeline =
                safe(assertions.findBySubjectTypeAndSubjectIdOrderByCreatedAtAsc(MemorySubjectType.ENTITY, id));

        return new MemoryEntityDetail(
                query.getEntity(id, includePending),
                query.factHistory(id),
                entityRelations,
                entityLinks.stream().map(MemoryMapper::linkView).toList(),
                timeline.stream().map(MemoryMapper::assertionView).toList());
    }

    /**
     * Rename, alias, merge, or retire. Exactly one operation per request so the audit trail
     * records one intent, and merge stays an explicit operator decision through
     * {@link MemoryCommandService#mergeEntities}.
     */
    @PatchMapping("/entities/{id}")
    public MemoryEntityView patchEntity(@PathVariable UUID id,
                                        @RequestBody PatchEntityRequest body,
                                        HttpServletRequest request) {
        MemoryActor actor = operator(request);
        boolean rename = body.displayName() != null;
        boolean alias = body.aliases() != null && !body.aliases().isEmpty();
        boolean merge = body.mergeInto() != null;
        boolean retire = Boolean.TRUE.equals(body.retire());
        int operations = (rename ? 1 : 0) + (alias ? 1 : 0) + (merge ? 1 : 0) + (retire ? 1 : 0);
        if (operations != 1) {
            throw new MemoryValidationException(
                    "exactly one of displayName, aliases, mergeInto, retire is required");
        }
        MemoryEntity changed;
        if (merge) {
            changed = command.mergeEntities(id, body.mergeInto(), actor);
        } else if (rename) {
            changed = lifecycle.renameEntity(id, body.displayName(), actor);
        } else if (alias) {
            changed = lifecycle.addAliases(id, body.aliases(), actor);
        } else {
            changed = lifecycle.retireEntity(id, actor);
        }
        return MemoryEntityView.from(changed);
    }

    // ---------------------------------------------------------------- facts

    @PostMapping("/entities/{id}/facts")
    public ResponseEntity<MemoryFactView> addFact(@PathVariable UUID id,
                                                  @RequestBody AddFactRequest body,
                                                  HttpServletRequest request) {
        MemoryFact fact = command.addFact(id, body.toCommand(), operator(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(MemoryFactView.from(fact));
    }

    @PostMapping("/facts/{id}/supersede")
    public MemoryFactView supersedeFact(@PathVariable UUID id,
                                        @RequestBody SupersedeFactRequest body,
                                        HttpServletRequest request) {
        return MemoryFactView.from(command.supersedeFact(id, body.toCommand(), operator(request)));
    }

    @PostMapping("/facts/{id}/retire")
    public MemoryFactView retireFact(@PathVariable UUID id,
                                     @RequestBody(required = false) RetireFactRequest body,
                                     HttpServletRequest request) {
        String sourceRef = body == null ? null : body.sourceRef();
        return MemoryFactView.from(lifecycle.retireFact(id, sourceRef, operator(request)));
    }

    // ---------------------------------------------------------------- proposals

    /**
     * The operator's review inbox, and the source of the dashboard proposal card. The read is an
     * actor-scoped service call since T11 C1 (a non-operator read omits a PENDING entity's
     * canonical key and display name), so this route first resolves the caller's operator
     * authority — a worker's own read of the same rows goes through the MCP tool, which passes
     * the transport actor instead — and then names the operator it serves. Passing the operator
     * keeps the entity card on the dashboard.
     * <p>
     * {@code entityId} is the ENTITY card's read (UI surface review M1): it narrows the same route
     * to one entity's own batch, applied in the query and never as a slice of the merged inbox
     * page, and it composes with {@code status} (default PENDING). The response shape is the same
     * list of proposal rows; the filtered read is complete rather than page-limited because the
     * entity decision promotes the whole batch.
     */
    @GetMapping("/proposals")
    public List<MemoryProposalView> listProposals(
            @RequestParam(required = false, defaultValue = "PENDING") FactStatus status,
            @RequestParam(required = false) UUID entityId,
            HttpServletRequest request) {
        operatorAuthority.resolveOperator(request, false);
        return entityId == null
                ? proposals.listProposals(status, INBOX_REVIEWER)
                : proposals.listProposalsForEntity(status, entityId, INBOX_REVIEWER);
    }

    @PostMapping("/proposals/{id}/decide")
    public MemoryProposalView decideProposal(@PathVariable UUID id,
                                             @RequestBody DecideProposalRequest body,
                                             HttpServletRequest request) {
        return proposals.decideProposal(id, body.decision(), operator(request));
    }

    // ---------------------------------------------------------------- recall

    /**
     * The preview applies the caller's page limit to <em>both</em> lists. Recall itself caps
     * entities at 12 and facts at 40; a caller limit below that is a preview cap, so the
     * response says {@code truncated} up front instead of silently returning a shorter page.
     */
    @GetMapping("/recall")
    public MemoryRecallPreview recall(@RequestParam(required = false, defaultValue = "") String q,
                                      @RequestParam(required = false) Integer limit,
                                      HttpServletRequest request) {
        operatorAuthority.resolveOperator(request, false);
        RecallResult result = recall.recall(new RecallQuery(q, null, null));
        int cap = limit == null || limit <= 0 ? DEFAULT_RECALL_LIMIT : Math.min(limit, MAX_RECALL_LIMIT);
        List<MemoryEntityView> entities = result.entities().stream().limit(cap).toList();
        List<MemoryFactView> facts = result.facts().stream().limit(cap).toList();
        boolean trimmed = entities.size() < result.entities().size()
                || facts.size() < result.facts().size();
        return new MemoryRecallPreview(entities, facts, result.truncated() || trimmed);
    }

    // ---------------------------------------------------------------- manifest

    @GetMapping("/runs/{id}/context-manifest")
    public List<MemoryManifestView> contextManifest(@PathVariable UUID id,
                                                    HttpServletRequest request) {
        operatorAuthority.resolveOperator(request, false);
        return manifests.findByRunIdOrderByTurnNumberAsc(id).stream()
                .map(row -> MemoryMapper.manifestView(row, mapper))
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The existing operator authority resolution: operator bearer credential, operator
     * session (with mutation Origin/CSRF validation), or loopback local operator. Anything
     * else throws {@link SecurityException}, which the memory advice maps to 403
     * {@code FORBIDDEN}.
     */
    private MemoryActor operator(HttpServletRequest request) {
        ActorPrincipal principal = operatorAuthority.resolveOperator(request, true);
        return new MemoryActor(MemoryActorType.OPERATOR, operatorRef(principal));
    }

    private static String operatorRef(ActorPrincipal principal) {
        return principal.expiresAt() == null ? "operator-bearer" : "operator-session";
    }

    private static int pageSize(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}

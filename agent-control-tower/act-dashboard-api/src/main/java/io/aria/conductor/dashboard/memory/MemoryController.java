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
 * act-memory services; every write resolves operator authority first, so a worker
 * credential, a failed origin/CSRF check, or an anonymous remote caller gets 403
 * {@code FORBIDDEN}. Responses carry ids, provenance, sensitivity and validity — never
 * credentials and, on the manifest route, never prompt content.
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int DEFAULT_RECALL_LIMIT = 20;
    private static final int MAX_RECALL_LIMIT = 40;

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
                                               @RequestParam(required = false) Integer limit) {
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
        return ResponseEntity.status(HttpStatus.CREATED).body(MemoryEntityView.from(entity));
    }

    @GetMapping("/entities/{id}")
    public MemoryEntityDetail getEntity(@PathVariable UUID id) {
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
                query.getEntity(id),
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

    @GetMapping("/proposals")
    public List<MemoryProposalView> listProposals(
            @RequestParam(required = false, defaultValue = "PENDING") FactStatus status) {
        return proposals.listProposals(status);
    }

    @PostMapping("/proposals/{id}/decide")
    public MemoryProposalView decideProposal(@PathVariable UUID id,
                                             @RequestBody DecideProposalRequest body,
                                             HttpServletRequest request) {
        return proposals.decideProposal(id, body.decision(), operator(request));
    }

    // ---------------------------------------------------------------- recall

    @GetMapping("/recall")
    public MemoryRecallPreview recall(@RequestParam(required = false, defaultValue = "") String q,
                                      @RequestParam(required = false) Integer limit) {
        RecallResult result = recall.recall(new RecallQuery(q, null, null));
        int cap = limit == null || limit <= 0 ? DEFAULT_RECALL_LIMIT : Math.min(limit, MAX_RECALL_LIMIT);
        return new MemoryRecallPreview(result.entities(), result.facts().stream().limit(cap).toList(),
                result.truncated());
    }

    // ---------------------------------------------------------------- manifest

    @GetMapping("/runs/{id}/context-manifest")
    public List<MemoryManifestView> contextManifest(@PathVariable UUID id) {
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

package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD + lifecycle service for {@link KanbanItem}.
 *
 * <p>Status transitions are validated via {@link #isValidTransition}; invalid
 * transitions throw {@link IllegalArgumentException} (mapped to HTTP 400 by
 * the global exception handler). {@link #create} validates its birth state and
 * its links the same way: an ineligible dispatch target is the one rejection
 * that answers with 409 instead.
 */
@Slf4j
@Service
public class KanbanService {

    private static final Map<KanbanStatus, Set<KanbanStatus>> ALLOWED_TRANSITIONS;

    static {
        EnumMap<KanbanStatus, Set<KanbanStatus>> map = new EnumMap<>(KanbanStatus.class);
        map.put(KanbanStatus.BACKLOG, EnumSet.of(KanbanStatus.TODO, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.TODO, EnumSet.of(KanbanStatus.IN_PROGRESS, KanbanStatus.BACKLOG, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.IN_PROGRESS, EnumSet.of(KanbanStatus.TODO, KanbanStatus.BACKLOG,
                KanbanStatus.REVIEW, KanbanStatus.DONE, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.REVIEW, EnumSet.of(KanbanStatus.IN_PROGRESS, KanbanStatus.TODO,
                KanbanStatus.DONE, KanbanStatus.CANCELLED));
        // DONE is re-doable (operator defect D3): redo re-enters the flow at
        // Backlog or Todo and must be dispatched again explicitly — a finished
        // card never jumps straight back into execution.
        map.put(KanbanStatus.DONE, EnumSet.of(KanbanStatus.BACKLOG, KanbanStatus.TODO));
        map.put(KanbanStatus.CANCELLED, EnumSet.noneOf(KanbanStatus.class));
        // BLOCKED is retired: no outgoing transitions; V52 migrated rows to REVIEW.
        ALLOWED_TRANSITIONS = map;
    }

    private final KanbanRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final RunRepository runRepository;
    private final ApprovalRepository approvalRepository;
    private final AgentRepository agentRepository;
    private final AgentPickupEligibility eligibility;

    public KanbanService(KanbanRepository repository,
                         ApplicationEventPublisher eventPublisher,
                         RunRepository runRepository,
                         ApprovalRepository approvalRepository,
                         AgentRepository agentRepository,
                         AgentPickupEligibility eligibility) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.runRepository = runRepository;
        this.approvalRepository = approvalRepository;
        this.agentRepository = agentRepository;
        this.eligibility = eligibility;
    }

    @Transactional
    public KanbanItem create(CreateKanbanItemRequest request) {
        MDC.put("operation", "kanban.create");
        long start = System.currentTimeMillis();
        try {
            validateCreate(request);
            KanbanItem item = KanbanItem.builder()
                    .title(request.getTitle())
                    .description(request.getDescription())
                    .status(request.getStatus() != null ? request.getStatus() : KanbanStatus.TODO)
                    .priority(request.getPriority() != null ? request.getPriority() : KanbanPriority.MEDIUM)
                    .assignee(request.getAssignee())
                    .labels(request.getLabels())
                    .linkedRunId(request.getLinkedRunId())
                    .linkedAgentId(request.getLinkedAgentId())
                    .agentTemplateId(request.getAgentTemplateId())
                    .build();

            KanbanItem saved = repository.save(item);
            MDC.put("entityId", saved.getId());
            log.info("Kanban item created successfully, duration={}ms", System.currentTimeMillis() - start);
            eventPublisher.publishEvent(new KanbanItemCreatedEvent(
                    this, saved.getId(), saved.getTitle(),
                    saved.getPriority() != null ? saved.getPriority().name() : "MEDIUM"));
            return saved;
        } finally {
            MDC.remove("operation");
            MDC.remove("entityId");
        }
    }

    @Transactional
    public KanbanItem update(String id, UpdateKanbanItemRequest request) {
        KanbanItem item = findOrThrow(id);

        if (request.getTitle() != null) item.setTitle(request.getTitle());
        if (request.getDescription() != null) item.setDescription(request.getDescription());
        if (request.getPriority() != null) item.setPriority(request.getPriority());
        if (request.getAssignee() != null) item.setAssignee(request.getAssignee());
        if (request.getLabels() != null) item.setLabels(request.getLabels());

        return repository.save(item);
    }

    @Transactional(readOnly = true)
    public KanbanItem get(String id) {
        return findOrThrow(id);
    }

    /**
     * @param status optional status filter; {@code null} returns every item
     */
    @Transactional(readOnly = true)
    public List<KanbanItem> list(KanbanStatus status) {
        List<KanbanItem> items = status == null ? repository.findAll() : repository.findByStatus(status);
        if (!items.isEmpty()) {
            List<Object[]> counts = approvalRepository.countPendingByKanbanItemIds(
                    items.stream().map(KanbanItem::getId).toList());
            Map<String, Long> byItem = new HashMap<>();
            counts.forEach(row -> byItem.put((String) row[0], (Long) row[1]));
            items.forEach(item -> item.setPendingAskCount(byItem.getOrDefault(item.getId(), 0L).intValue()));
        }
        return items;
    }

    @Transactional
    public KanbanItem transition(String id, KanbanStatus toStatus, String comment) {
        MDC.put("operation", "kanban.transition");
        MDC.put("entityId", id);
        long start = System.currentTimeMillis();
        try {
            KanbanItem item = findOrThrow(id);
            KanbanStatus from = item.getStatus();

            if (!isValidTransition(from, toStatus)) {
                throw new IllegalArgumentException(
                        "Invalid kanban transition: " + from + " -> " + toStatus);
            }

            // Guard against premature DONE when a linked run is still active.
            if (toStatus == KanbanStatus.DONE && item.getLinkedRunId() != null) {
                guardLinkedRunNotActive(item.getLinkedRunId());
            }

            item.setStatus(toStatus);
            KanbanItem saved = repository.save(item);
            log.info("Kanban item transitioned {} -> {}, duration={}ms",
                    from, toStatus, System.currentTimeMillis() - start);
            eventPublisher.publishEvent(new KanbanItemTransitionedEvent(
                    this, saved.getId(), from.name(), toStatus.name()));
            return saved;
        } finally {
            MDC.remove("operation");
            MDC.remove("entityId");
        }
    }

    @Transactional
    public void delete(String id) {
        KanbanItem item = findOrThrow(id);
        repository.delete(item);
        log.info("Kanban item deleted: id={}", id);
    }

    /**
     * Create-path validation. A malformed or terminal birth state is a bad
     * request (400); an ineligible dispatch target is a state conflict (409).
     * A rejection stages nothing: no card is saved and no {@code lastError} is
     * written — the 409 body carries the reason for a synchronous caller.
     */
    private void validateCreate(CreateKanbanItemRequest request) {
        KanbanStatus status = request.getStatus();
        if (status == KanbanStatus.DONE || status == KanbanStatus.CANCELLED) {
            throw new IllegalArgumentException("INVALID_BIRTH_STATUS: a card cannot be created in " + status);
        }
        if (isDispatchIntent(request)) {
            validateDispatchTarget(request.getLinkedAgentId());
        } else {
            validateRunLink(request.getLinkedRunId());
        }
    }

    /**
     * A dispatch intent is a create that requests work rather than describing an
     * existing run: only a blank linkedRunId qualifies. It matters because
     * {@code RunKanbanAutoCreator.onRunStarted} creates a card for EVERY run,
     * Aria's own included — applying eligibility there would reject Aria's run
     * cards, since Aria is a RESERVED_OPERATOR_AGENT.
     */
    private static boolean isDispatchIntent(CreateKanbanItemRequest request) {
        String runLink = request.getLinkedRunId();
        return runLink == null || runLink.isBlank();
    }

    private void validateRunLink(String runLink) {
        UUID runId;
        try {
            runId = UUID.fromString(runLink);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("INVALID_RUN_LINK: linkedRunId is not a UUID: " + runLink);
        }
        if (runRepository.findById(runId).isEmpty()) {
            throw new IllegalArgumentException("INVALID_RUN_LINK: no run with id " + runId);
        }
    }

    private void validateDispatchTarget(String agentLink) {
        if (agentLink == null || agentLink.isBlank()) {
            return; // Aria auto-assigns at pickup time.
        }
        Agent agent = agentRepository.findById(UUID.fromString(agentLink)).orElse(null);
        if (agent == null) {
            throw new PickupRejectedException("AGENT_NOT_ELIGIBLE",
                    "Agent not found with id: " + agentLink, Map.of("agentId", agentLink));
        }
        AgentPickupEligibility.Evaluation evaluation = eligibility.evaluate(agent);
        if (!evaluation.eligible()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("agentId", agentLink);
            details.put("reasons", evaluation.reasons().stream().map(Enum::name).toList());
            throw new PickupRejectedException("AGENT_NOT_ELIGIBLE",
                    "Agent " + agent.getName() + " cannot receive a card: " + evaluation.reasons(), details);
        }
    }

    private void guardLinkedRunNotActive(String linkedRunId) {
        UUID runId;
        try {
            runId = UUID.fromString(linkedRunId);
        } catch (IllegalArgumentException e) {
            log.warn("Could not verify linked run status: {}", e.getMessage());
            return; // graceful degradation when linkedRunId is not a UUID
        }
        try {
            runRepository.findById(runId).ifPresent(run -> {
                RunStatus status = run.getStatus();
                if (status == RunStatus.PENDING
                        || status == RunStatus.INITIALIZING
                        || status == RunStatus.RUNNING) {
                    throw new IllegalArgumentException(
                            "Cannot transition to DONE: linked run " + runId
                                    + " is still " + status
                                    + ". Complete or cancel the run first.");
                }
            });
        } catch (IllegalArgumentException e) {
            throw e; // re-throw our own exception
        } catch (Exception e) {
            log.warn("Could not verify linked run status: {}", e.getMessage());
            // Allow transition if we can't verify (graceful degradation).
        }
    }

    boolean isValidTransition(KanbanStatus from, KanbanStatus to) {
        if (from == null || to == null) return false;
        if (from == to) return false;
        Set<KanbanStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    private KanbanItem findOrThrow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanItem", id));
    }
}

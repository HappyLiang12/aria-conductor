package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD + lifecycle service for {@link KanbanItem}.
 *
 * <p>Status transitions are validated via {@link #isValidTransition}; invalid
 * transitions throw {@link IllegalArgumentException} (mapped to HTTP 400 by
 * the global exception handler).
 */
@Slf4j
@Service
public class KanbanService {

    private static final Map<KanbanStatus, Set<KanbanStatus>> ALLOWED_TRANSITIONS;

    static {
        EnumMap<KanbanStatus, Set<KanbanStatus>> map = new EnumMap<>(KanbanStatus.class);
        map.put(KanbanStatus.TODO,
                EnumSet.of(KanbanStatus.IN_PROGRESS, KanbanStatus.BLOCKED, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.IN_PROGRESS,
                EnumSet.of(KanbanStatus.DONE, KanbanStatus.BLOCKED, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.BLOCKED,
                EnumSet.of(KanbanStatus.TODO, KanbanStatus.IN_PROGRESS, KanbanStatus.CANCELLED));
        map.put(KanbanStatus.DONE, EnumSet.noneOf(KanbanStatus.class));
        map.put(KanbanStatus.CANCELLED, EnumSet.noneOf(KanbanStatus.class));
        ALLOWED_TRANSITIONS = map;
    }

    private final KanbanRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final RunRepository runRepository;

    public KanbanService(KanbanRepository repository,
                         ApplicationEventPublisher eventPublisher,
                         RunRepository runRepository) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.runRepository = runRepository;
    }

    @Transactional
    public KanbanItem create(CreateKanbanItemRequest request) {
        MDC.put("operation", "kanban.create");
        long start = System.currentTimeMillis();
        try {
            KanbanItem item = KanbanItem.builder()
                    .title(request.getTitle())
                    .description(request.getDescription())
                    .status(KanbanStatus.TODO)
                    .priority(request.getPriority() != null ? request.getPriority() : KanbanPriority.MEDIUM)
                    .assignee(request.getAssignee())
                    .labels(request.getLabels())
                    .linkedRunId(request.getLinkedRunId())
                    .linkedAgentId(request.getLinkedAgentId())
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
        return status == null ? repository.findAll() : repository.findByStatus(status);
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

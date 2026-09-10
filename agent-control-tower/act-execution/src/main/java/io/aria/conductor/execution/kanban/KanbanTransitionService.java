package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.KanbanItemAssigningEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates kanban transitions with their run side effects (spec section 4):
 * Todo entry is a dispatch intent (two-phase pickup), dragging to TODO or
 * BACKLOG pauses the linked run, request-changes re-dispatches with feedback,
 * cancel denies open asks and cancels the run.
 *
 * <p>Pickup pre-validates agent eligibility so the predictable failure modes
 * (missing/retired/unhealthy agent) stay in-transaction with lastError on the
 * card; anything slipping past pre-validation propagates and rolls the whole
 * transition back.
 */
@Slf4j
@Service
public class KanbanTransitionService {

    /** Prompt-seed section caps so a huge card/feedback cannot blow up the run prompt. */
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_DESCRIPTION_CHARS = 4000;
    private static final int MAX_FEEDBACK_CHARS = 2000;

    private final KanbanRepository kanbanRepository;
    private final KanbanService kanbanService;
    private final RunService runService;
    private final RunRepository runRepository;
    private final AgentRepository agentRepository;
    private final AgentPickerService agentPicker;
    private final ApprovalRepository approvalRepository;
    private final ApplicationEventPublisher eventPublisher;

    public KanbanTransitionService(KanbanRepository kanbanRepository,
                                   KanbanService kanbanService,
                                   RunService runService,
                                   RunRepository runRepository,
                                   AgentRepository agentRepository,
                                   AgentPickerService agentPicker,
                                   ApprovalRepository approvalRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
        this.runService = runService;
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.agentPicker = agentPicker;
        this.approvalRepository = approvalRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public KanbanItem transition(String id, TransitionRequest request) {
        KanbanStatus to = request.getStatus();
        if (to == null) {
            throw new IllegalArgumentException("Target status is required");
        }
        KanbanItem item = kanbanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanItem", id));
        // Idempotent no-op: repeating the current status must not re-dispatch a
        // run, re-fire listeners, or clear lastError (a second CANCELLED click
        // stays side-effect free).
        if (item.getStatus() == to) {
            return item;
        }
        item.setLastError(null);

        return switch (to) {
            case BACKLOG -> {
                pauseIfRunning(item);
                yield kanbanService.transition(id, KanbanStatus.BACKLOG, request.getComment());
            }
            case TODO -> switch (item.getStatus()) {
                case REVIEW -> requestChanges(item, request);
                // Dragging an in-flight card back to TODO pauses the run; no re-dispatch.
                case IN_PROGRESS -> {
                    pauseIfRunning(item);
                    yield kanbanService.transition(id, KanbanStatus.TODO, request.getComment());
                }
                // Normalize BACKLOG/DONE -> TODO first, then the pickup's
                // TODO -> IN_PROGRESS step is a legal transition. DONE means
                // "redo": the completed run stays as history and the pickup
                // creates a fresh run for the new attempt (defect D3).
                case BACKLOG, DONE -> {
                    kanbanService.transition(id, KanbanStatus.TODO, request.getComment());
                    yield pickup(item, request);
                }
                // (TODO -> TODO never reaches here: the same-status no-op guard above returns first.)
                // Terminal states: reject BEFORE any pickup side effect can fire.
                default -> throw new IllegalArgumentException(
                        "Invalid kanban transition: " + item.getStatus() + " -> " + to);
            };
            case IN_PROGRESS -> switch (item.getStatus()) {
                case REVIEW -> resume(item);
                // D3: Backlog never executes — direct dispatch is rejected; the
                // card must be routed through Todo (the only dispatch entry).
                case BACKLOG -> throw new IllegalArgumentException(
                        "Route the card through Todo first — Backlog items do not dispatch directly");
                case TODO -> pickup(item, request);
                // IN_PROGRESS is handled by the no-op guard above.
                default -> throw new IllegalArgumentException(
                        "Invalid kanban transition: " + item.getStatus() + " -> " + to);
            };
            case REVIEW -> kanbanService.transition(id, KanbanStatus.REVIEW, request.getComment());
            case DONE -> kanbanService.transition(id, KanbanStatus.DONE, request.getComment());
            case CANCELLED -> cancel(item, request.getComment());
            // BLOCKED is retired: persisted rows may still carry it; it has no
            // orchestrator side effects and is rejected like any illegal move.
            default -> throw new IllegalArgumentException("Unsupported target status: " + to);
        };
    }

    /**
     * Two-phase pickup: assign (rule-based) then create the run (spec 4.2).
     *
     * <p>Agent eligibility is pre-validated here (against the same
     * {@link AgentRepository} lookup {@link RunService#createRun} performs) so
     * the predictable failure modes stay in this transaction with lastError on
     * the card. Crossing the createRun proxy with a doomed request would mark
     * the shared transaction rollback-only, and catching the failure to
     * "continue" would only defer it to an UnexpectedRollbackException at
     * commit. Unexpected createRun failures are therefore not caught: they
     * propagate and roll the whole transition back atomically.
     */
    private KanbanItem pickup(KanbanItem item, TransitionRequest request) {
        if (isBlank(item.getAssignee()) && isBlank(item.getLinkedAgentId())) {
            eventPublisher.publishEvent(new KanbanItemAssigningEvent(this, item.getId()));
            String templateId = firstNonBlank(request.getAgentTemplateId(), item.getAgentTemplateId());
            // AgentPickerService is a plain bean (no transaction proxy), so an
            // empty healthy pool can be caught here without deferring a
            // rollback-only transaction to commit — unlike createRun below.
            try {
                AgentPickerService.Choice choice = agentPicker.pick(templateId, item.getTitle(), item.getDescription());
                item.setLinkedAgentId(choice.agentId().toString());
                item.setAssignee(choice.agentName());
            } catch (IllegalStateException e) {
                // Predictable failure (no eligible agent): the card stays in its
                // source status with lastError instead of rolling the whole
                // transition back; re-drag retries (spec 4.2/6 refinement).
                log.warn("Kanban pickup failed for {}: {}", item.getId(), e.getMessage());
                item.setLastError(abbreviate(e.getMessage()));
                return kanbanRepository.save(item);
            }
            if (!isBlank(templateId)) {
                item.setAgentTemplateId(templateId);
            }
        }
        String violation = agentEligibilityViolation(item.getLinkedAgentId());
        if (violation != null) {
            log.warn("Kanban pickup failed for {}: {}", item.getId(), violation);
            item.setLastError(abbreviate(violation));
            return kanbanRepository.save(item); // stays in TODO, no auto retry
        }
        // suppressAutoCard: the pickup owns card linkage for orchestrator-created
        // runs — RunKanbanAutoCreator must not double-card the board.
        RunResponse run = runService.createRun(CreateRunRequest.builder()
                .agentId(UUID.fromString(item.getLinkedAgentId()))
                .promptSeed(buildPromptSeed(item, request.getFeedback()))
                .suppressAutoCard(true)
                .build());
        // Link BEFORE the IN_PROGRESS move so every later card face shows the run.
        item.setLinkedRunId(run.getId().toString());
        return kanbanService.transition(item.getId(), KanbanStatus.IN_PROGRESS, request.getComment());
    }

    /** Mirrors {@code RunService.createRun}'s eligibility guards; {@code null} means eligible. */
    private String agentEligibilityViolation(String linkedAgentId) {
        UUID agentId;
        try {
            agentId = UUID.fromString(linkedAgentId);
        } catch (IllegalArgumentException e) {
            return "Agent not found with id: " + linkedAgentId;
        }
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return "Agent not found with id: " + agentId;
        }
        if (agent.getHealthStatus() == HealthStatus.RETIRED) {
            return "Cannot create run for retired agent: " + agent.getId();
        }
        if (agent.getHealthStatus() == HealthStatus.UNHEALTHY) {
            return "Cannot create run for unhealthy agent: " + agent.getId();
        }
        return null;
    }

    private KanbanItem requestChanges(KanbanItem item, TransitionRequest request) {
        approvalRepository.markStaleByKanbanItemId(item.getId(), Instant.now());
        kanbanService.transition(item.getId(), KanbanStatus.TODO, request.getComment());
        return pickup(item, request);
    }

    private KanbanItem resume(KanbanItem item) {
        findRun(item).ifPresent(run -> {
            if (run.getStatus() == RunStatus.PAUSED) {
                runService.resumeRun(UUID.fromString(item.getLinkedRunId()));
            }
        });
        return kanbanService.transition(item.getId(), KanbanStatus.IN_PROGRESS, null);
    }

    private KanbanItem cancel(KanbanItem item, String comment) {
        approvalRepository.denyPendingByKanbanItemId(item.getId(), "task cancelled", Instant.now());
        // Card transition FIRST: the card lands on CANCELLED before any listener
        // can race it. The synchronous RunKanbanAutoCreator.onRunCompleted (fired
        // by cancelRun below) then finds the card already CANCELLED and skips it
        // (terminal cards are never re-transitioned) — harmless by design.
        kanbanService.transition(item.getId(), KanbanStatus.CANCELLED, comment);
        findRun(item).ifPresent(run -> {
            if (run.getStatus() == RunStatus.PENDING || run.getStatus() == RunStatus.INITIALIZING
                    || run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSED) {
                runService.cancelRun(UUID.fromString(item.getLinkedRunId()));
            }
        });
        return item;
    }

    private void pauseIfRunning(KanbanItem item) {
        findRun(item).ifPresent(run -> {
            if (run.getStatus() == RunStatus.RUNNING) {
                runService.pauseRun(UUID.fromString(item.getLinkedRunId()));
            }
        });
    }

    private Optional<Run> findRun(KanbanItem item) {
        if (isBlank(item.getLinkedRunId())) return Optional.empty();
        try {
            return runRepository.findById(UUID.fromString(item.getLinkedRunId()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String buildPromptSeed(KanbanItem item, String feedback) {
        StringBuilder sb = new StringBuilder("Kanban task: ")
                .append(cap(item.getTitle(), MAX_TITLE_CHARS));
        if (!isBlank(item.getDescription())) {
            sb.append("\n\nDescription:\n").append(cap(item.getDescription(), MAX_DESCRIPTION_CHARS));
        }
        if (!isBlank(feedback)) {
            sb.append("\n\nOperator feedback on the previous attempt:\n")
                    .append(cap(feedback, MAX_FEEDBACK_CHARS));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String abbreviate(String msg) {
        if (msg == null) return "pickup failed";
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }
}

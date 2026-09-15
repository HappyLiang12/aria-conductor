package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.KanbanItemAssigningEvent;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates kanban transitions with their run side effects (spec section 4):
 * Todo entry is a dispatch intent (two-phase pickup), dragging to TODO or
 * BACKLOG pauses the linked run, request-changes re-dispatches with feedback,
 * cancel denies open asks and cancels the run.
 *
 * <p>Pickup asks the single eligibility authority before crossing the run
 * creation proxy: an ineligible or unknown agent is a synchronous operator
 * conflict, answered with a 4xx rejection instead of being recorded on the card.
 */
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
    private final AgentPickupEligibility eligibility;
    private final ApprovalRepository approvalRepository;
    private final ApplicationEventPublisher eventPublisher;

    public KanbanTransitionService(KanbanRepository kanbanRepository,
                                   KanbanService kanbanService,
                                   RunService runService,
                                   RunRepository runRepository,
                                   AgentRepository agentRepository,
                                   AgentPickerService agentPicker,
                                   AgentPickupEligibility eligibility,
                                   ApprovalRepository approvalRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
        this.runService = runService;
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.agentPicker = agentPicker;
        this.eligibility = eligibility;
        this.approvalRepository = approvalRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Dispatch a card that is already sitting in Todo (spec D3: Todo entry is a
     * dispatch intent). Used by the create path — a card created directly in
     * Todo must be picked up, not left waiting for a manual drag (defect D1).
     */
    @Transactional
    public KanbanItem dispatch(String id, String agentTemplateId) {
        KanbanItem item = kanbanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanItem", id));
        item.setLastError(null);
        return pickup(item, TransitionRequest.builder().agentTemplateId(agentTemplateId).build());
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
     * <p>Agent eligibility is checked here — against the one authority, before
     * the createRun proxy is crossed: a doomed request would mark the shared
     * transaction rollback-only, and catching the failure to "continue" would
     * only defer it to an UnexpectedRollbackException at commit. Unexpected
     * createRun failures are therefore not caught either: they propagate and roll
     * the whole transition back atomically.
     *
     * <p>A synchronous operator action answers through its response, so a
     * rejection writes no {@code lastError} on the card.
     */
    private KanbanItem pickup(KanbanItem item, TransitionRequest request) {
        // Assign phase runs whenever no agent is linked yet — even when a display
        // assignee is set (Aria/MCP cards can carry one without an agent id);
        // otherwise the eligibility check below would hit UUID.fromString(null).
        if (isBlank(item.getLinkedAgentId())) {
            eventPublisher.publishEvent(new KanbanItemAssigningEvent(this, item.getId()));
            String templateId = firstNonBlank(request.getAgentTemplateId(), item.getAgentTemplateId());
            AgentPickerService.Choice choice = agentPicker.pick(templateId, item.getTitle(), item.getDescription());
            item.setLinkedAgentId(choice.agentId().toString());
            item.setAssignee(choice.agentName());
            if (!isBlank(templateId)) {
                item.setAgentTemplateId(templateId);
            }
        }
        Agent linked = agentRepository.findById(UUID.fromString(item.getLinkedAgentId())).orElse(null);
        if (linked == null) {
            throw new PickupRejectedException("AGENT_NOT_ELIGIBLE",
                    "Agent not found with id: " + item.getLinkedAgentId(),
                    Map.of("agentId", item.getLinkedAgentId()));
        }
        AgentPickupEligibility.Evaluation evaluation = eligibility.evaluate(linked);
        if (!evaluation.eligible()) {
            throw new PickupRejectedException("AGENT_NOT_ELIGIBLE",
                    "Agent " + linked.getName() + " cannot receive a card: " + evaluation.reasons(),
                    Map.of("agentId", item.getLinkedAgentId(),
                            "reasons", evaluation.reasons().stream().map(Enum::name).toList()));
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

    private KanbanItem requestChanges(KanbanItem item, TransitionRequest request) {
        approvalRepository.markStaleByKanbanItemId(item.getId(), Instant.now());
        kanbanService.transition(item.getId(), KanbanStatus.TODO, request.getComment());
        return pickup(item, request);
    }

    private KanbanItem resume(KanbanItem item) {
        String link = item.getLinkedRunId();
        if (link == null || link.isBlank()) {
            throw new PickupRejectedException("RUN_NOT_FOUND",
                    "Card has no linked run to resume. Use request-changes to dispatch a new run.",
                    Map.of("kanbanItemId", item.getId()));
        }
        UUID runId;
        try {
            runId = UUID.fromString(link);
        } catch (IllegalArgumentException e) {
            throw new PickupRejectedException("CORRUPT_RUN_LINK",
                    "Linked run id is not a UUID: " + link, Map.of("linkedRunId", link));
        }
        Run run = runRepository.findById(runId).orElseThrow(() -> new PickupRejectedException("RUN_NOT_FOUND",
                "No run with id " + runId, Map.of("runId", runId.toString())));
        if (run.getStatus() != RunStatus.PAUSED) {
            // A finished run cannot be continued. The honest alternatives are the
            // card's own vocabulary: request-changes re-enters Todo and dispatches
            // a new run; Done closes it out.
            throw new PickupRejectedException("RUN_ALREADY_FINISHED",
                    "Linked run is " + run.getStatus()
                            + " — use request-changes to dispatch a new run, or move the card to Done.",
                    Map.of("runId", runId.toString(), "runStatus", run.getStatus().name()));
        }
        runService.resumeRun(runId);
        return kanbanService.transition(item.getId(), KanbanStatus.IN_PROGRESS, null);
    }

    private KanbanItem cancel(KanbanItem item, String comment) {
        approvalRepository.denyPendingByKanbanItemId(item.getId(), "task cancelled", Instant.now());
        // Card transition FIRST: the card lands on CANCELLED before any listener
        // can race it. RunKanbanAutoCreator.onRunCompleted (fired by cancelRun
        // below, after this transaction commits) then finds the card already
        // CANCELLED and skips it (terminal cards are never re-transitioned) —
        // harmless by design.
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
}

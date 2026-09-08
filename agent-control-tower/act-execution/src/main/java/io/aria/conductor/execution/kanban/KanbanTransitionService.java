package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.KanbanItemAssigningEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
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
 * Todo entry is a dispatch intent (two-phase pickup), dragging back pauses the
 * linked run at a step boundary, request-changes re-dispatches with feedback,
 * cancel denies open asks and cancels the run.
 */
@Slf4j
@Service
public class KanbanTransitionService {

    private final KanbanRepository kanbanRepository;
    private final KanbanService kanbanService;
    private final RunService runService;
    private final RunRepository runRepository;
    private final AgentPickerService agentPicker;
    private final ApprovalRepository approvalRepository;
    private final ApplicationEventPublisher eventPublisher;

    public KanbanTransitionService(KanbanRepository kanbanRepository,
                                   KanbanService kanbanService,
                                   RunService runService,
                                   RunRepository runRepository,
                                   AgentPickerService agentPicker,
                                   ApprovalRepository approvalRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
        this.runService = runService;
        this.runRepository = runRepository;
        this.agentPicker = agentPicker;
        this.approvalRepository = approvalRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public KanbanItem transition(String id, TransitionRequest request) {
        KanbanItem item = kanbanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanItem", id));
        KanbanStatus to = request.getStatus();
        item.setLastError(null);

        return switch (to) {
            case BACKLOG -> {
                pauseIfRunning(item);
                yield kanbanService.transition(id, KanbanStatus.BACKLOG, request.getComment());
            }
            case TODO -> item.getStatus() == KanbanStatus.REVIEW
                    ? requestChanges(item, request)
                    : pickup(item, request);
            case IN_PROGRESS -> item.getStatus() == KanbanStatus.REVIEW
                    ? resume(item)
                    : pickup(item, request);
            case REVIEW -> kanbanService.transition(id, KanbanStatus.REVIEW, request.getComment());
            case DONE -> kanbanService.transition(id, KanbanStatus.DONE, request.getComment());
            case CANCELLED -> cancel(item, request.getComment());
            default -> throw new IllegalArgumentException("Unsupported target status: " + to);
        };
    }

    /** Two-phase pickup: assign (rule-based) then create the run (spec 4.2). */
    private KanbanItem pickup(KanbanItem item, TransitionRequest request) {
        if (isBlank(item.getAssignee()) && isBlank(item.getLinkedAgentId())) {
            eventPublisher.publishEvent(new KanbanItemAssigningEvent(this, item.getId()));
            String templateId = firstNonBlank(request.getAgentTemplateId(), item.getAgentTemplateId());
            AgentPickerService.Choice choice = agentPicker.pick(templateId, item.getTitle(), item.getDescription());
            item.setLinkedAgentId(choice.agentId().toString());
            item.setAssignee(choice.agentName());
            if (!isBlank(templateId)) {
                item.setAgentTemplateId(templateId);
            }
        }
        try {
            runService.createRun(CreateRunRequest.builder()
                    .agentId(UUID.fromString(item.getLinkedAgentId()))
                    .promptSeed(buildPromptSeed(item, request.getFeedback()))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Kanban pickup failed for {}: {}", item.getId(), e.getMessage());
            item.setLastError(abbreviate(e.getMessage()));
            return kanbanRepository.save(item); // stays in TODO, no auto retry
        }
        return kanbanService.transition(item.getId(), KanbanStatus.IN_PROGRESS, request.getComment());
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
        findRun(item).ifPresent(run -> {
            if (run.getStatus() == RunStatus.PENDING || run.getStatus() == RunStatus.INITIALIZING
                    || run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSED) {
                runService.cancelRun(UUID.fromString(item.getLinkedRunId()));
            }
        });
        return kanbanService.transition(item.getId(), KanbanStatus.CANCELLED, comment);
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
        StringBuilder sb = new StringBuilder("Kanban task: ").append(item.getTitle());
        if (!isBlank(item.getDescription())) {
            sb.append("\n\nDescription:\n").append(item.getDescription());
        }
        if (!isBlank(feedback)) {
            sb.append("\n\nOperator feedback on the previous attempt:\n").append(feedback);
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    private static String abbreviate(String msg) {
        if (msg == null) return "pickup failed";
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }
}

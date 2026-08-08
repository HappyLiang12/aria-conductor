package io.aria.conductor.execution.listener;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Automatically manages Kanban items in response to run lifecycle events:
 * <ul>
 *   <li>{@link RunStartedEvent}     → creates a TODO item</li>
 *   <li>{@link RunIterationEvent}   → transitions TODO → IN_PROGRESS</li>
 *   <li>{@link RunCompletedEvent}   → transitions to DONE (or CANCELLED)</li>
 * </ul>
 */
@Slf4j
@Component
public class RunKanbanAutoCreator {

    private final KanbanService kanbanService;
    private final KanbanRepository kanbanRepository;
    private final RunRepository runRepository;

    public RunKanbanAutoCreator(KanbanService kanbanService, KanbanRepository kanbanRepository, RunRepository runRepository) {
        this.kanbanService = kanbanService;
        this.kanbanRepository = kanbanRepository;
        this.runRepository = runRepository;
    }

    @EventListener
    public void onRunStarted(RunStartedEvent event) {
        try {
            // Use the run's promptSeed as a meaningful title (truncated)
            String title = runRepository.findById(event.getRunId())
                    .map(run -> {
                        String seed = run.getPromptSeed();
                        if (seed != null && !seed.isBlank()) {
                            return seed.length() > 60 ? seed.substring(0, 57) + "..." : seed;
                        }
                        return "Run: " + event.getRunId().toString().substring(0, 8);
                    })
                    .orElse("Run: " + event.getRunId().toString().substring(0, 8));

            CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                    .title(title)
                    .priority(KanbanPriority.MEDIUM)
                    .linkedRunId(event.getRunId().toString())
                    .linkedAgentId(event.getAgentId().toString())
                    .build();
            kanbanService.create(request);
            log.info("Auto-created Kanban item for run: runId={}", event.getRunId());
        } catch (Exception e) {
            log.warn("Failed to auto-create Kanban item for run {}: {}",
                    event.getRunId(), e.getMessage());
        }
    }

    @EventListener
    public void onRunIteration(RunIterationEvent event) {
        try {
            List<KanbanItem> items = kanbanRepository.findByLinkedRunId(event.getRunId().toString());
            for (KanbanItem item : items) {
                if (item.getStatus() == KanbanStatus.TODO) {
                    kanbanService.transition(item.getId(), KanbanStatus.IN_PROGRESS, "Run iteration started");
                    log.info("Auto-transitioned Kanban item {} to IN_PROGRESS for run {}",
                            item.getId(), event.getRunId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to auto-transition Kanban item on iteration for run {}: {}",
                    event.getRunId(), e.getMessage());
        }
    }

    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        try {
            List<KanbanItem> items = kanbanRepository.findByLinkedRunId(event.getRunId().toString());
            KanbanStatus targetStatus = switch (event.getStatus()) {
                case COMPLETED -> KanbanStatus.DONE;
                case ABORTED -> KanbanStatus.CANCELLED;
                case CANCELLED -> KanbanStatus.CANCELLED;
                case FAILED -> KanbanStatus.CANCELLED;
                default -> KanbanStatus.DONE;
            };
            for (KanbanItem item : items) {
                if (item.getStatus() != KanbanStatus.DONE
                        && item.getStatus() != KanbanStatus.CANCELLED) {
                    kanbanService.transition(item.getId(), targetStatus, "Run " + event.getStatus());
                    log.info("Auto-transitioned Kanban item {} to {} for run {}",
                            item.getId(), targetStatus, event.getRunId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to auto-transition Kanban item on completion for run {}: {}",
                    event.getRunId(), e.getMessage());
        }
    }
}

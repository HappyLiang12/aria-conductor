package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defect D1 (spec D3): a card created directly in Todo is a dispatch intent —
 * Aria must assign an agent and start a run immediately instead of leaving the
 * card parked until someone drags it.
 *
 * <p>Runs for every creation surface (REST, Aria tools, MCP) because it reacts
 * to the domain event rather than a specific controller. Backlog cards stay
 * queued; pickup failures never break creation (dispatch records lastError on
 * the card).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aria.kanban.auto-dispatch-on-create", havingValue = "true", matchIfMissing = true)
public class KanbanAutoDispatchListener {

    private final KanbanRepository kanbanRepository;
    private final KanbanTransitionService kanbanTransitionService;

    public KanbanAutoDispatchListener(KanbanRepository kanbanRepository,
                                      KanbanTransitionService kanbanTransitionService) {
        this.kanbanRepository = kanbanRepository;
        this.kanbanTransitionService = kanbanTransitionService;
    }

    @EventListener
    @Transactional
    public void onKanbanItemCreated(KanbanItemCreatedEvent event) {
        try {
            kanbanRepository.findById(event.getItemId()).ifPresent(item -> {
                if (item.getStatus() != KanbanStatus.TODO) {
                    return; // Backlog is a queue; other statuses are explicit placements.
                }
                kanbanTransitionService.dispatch(item.getId(), item.getAgentTemplateId());
            });
        } catch (Exception e) {
            // Creation must never fail because the pickup could not start.
            log.warn("Auto-dispatch on create failed for {}: {}", event.getItemId(), e.getMessage());
        }
    }
}

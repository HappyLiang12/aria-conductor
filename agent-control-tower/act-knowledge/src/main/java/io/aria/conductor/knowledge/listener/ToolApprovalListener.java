package io.aria.conductor.knowledge.listener;

import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.knowledge.service.ToolApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ToolApprovalListener {

    private final ToolApprovalService toolApprovalService;

    @EventListener
    public void onKnowledgeApproved(KnowledgeApprovedEvent event) {
        toolApprovalService.onKnowledgeApproved(event.getKnowledgeId());
    }
}

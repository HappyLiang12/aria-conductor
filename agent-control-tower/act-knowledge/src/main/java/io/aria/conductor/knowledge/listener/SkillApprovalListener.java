package io.aria.conductor.knowledge.listener;

import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.knowledge.service.SkillApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Governed skill enable gate: when a {@code type=SKILL} {@link KnowledgeItem} is
 * APPROVED, its linked {@code SkillDefinition}(s) flip to enabled. Non-SKILL approvals
 * never enable skills.
 */
@Component
@RequiredArgsConstructor
public class SkillApprovalListener {

    private final SkillApprovalService skillApprovalService;

    @EventListener
    public void onKnowledgeApproved(KnowledgeApprovedEvent event) {
        if ("SKILL".equals(event.getType())) {
            skillApprovalService.onKnowledgeApproved(event.getKnowledgeId());
        }
    }
}

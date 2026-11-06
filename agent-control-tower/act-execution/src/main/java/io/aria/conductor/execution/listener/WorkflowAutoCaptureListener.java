package io.aria.conductor.execution.listener;

import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.port.KnowledgeCapturePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Listens for {@link WorkflowAdvancedEvent} and automatically captures
 * completed workflow chains as knowledge items (type = WORKFLOW, status = PENDING).
 *
 * <p>When a workflow chain completes:
 * <ul>
 *   <li>Skips chains that have already been captured (non-null knowledgeItemId).</li>
 *   <li>Skips trivial single-step workflows (handled by the port/adapter).</li>
 *   <li>Generates Markdown and YAML representations.</li>
 *   <li>Submits a new PENDING knowledge item of type WORKFLOW.</li>
 *   <li>Links the chain back to the created knowledge item.</li>
 * </ul>
 *
 * <p>Delegates all knowledge operations to {@link KnowledgeCapturePort}
 * to avoid circular module dependencies between act-execution and act-knowledge.
 */
@Slf4j
@Component
public class WorkflowAutoCaptureListener {

    private final KnowledgeCapturePort knowledgeCapturePort;

    public WorkflowAutoCaptureListener(KnowledgeCapturePort knowledgeCapturePort) {
        this.knowledgeCapturePort = knowledgeCapturePort;
    }

    @EventListener
    public void onWorkflowCompleted(WorkflowAdvancedEvent event) {
        // Only act when the chain itself is COMPLETED (not just a step advancing)
        if (event.getChainStatus() != WorkflowChain.Status.COMPLETED) {
            return;
        }

        UUID chainId = event.getWorkflowId();
        log.info("Auto-capture triggered for completed workflow chain: {}", chainId);

        try {
            UUID knowledgeItemId = knowledgeCapturePort.captureWorkflowChain(chainId);
            if (knowledgeItemId != null) {
                log.info("Auto-capture succeeded: chain {} -> knowledge item {}", chainId, knowledgeItemId);
            } else {
                log.debug("Auto-capture skipped for chain {}", chainId);
            }
        } catch (Exception e) {
            log.error("Auto-capture failed for chain {}: {}", chainId, e.getMessage(), e);
        }
    }
}

package io.aria.conductor.execution.listener;

import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.WorkflowChain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link RunCompletedEvent} and automatically advances
 * any workflow chain that the completed run belongs to.
 *
 * <p>When a run completes:
 * <ul>
 *   <li>If the run is part of an active workflow chain, the chain is advanced
 *       to the next step (with the previous step's output injected into the prompt).</li>
 *   <li>If the run failed, the chain is marked as FAILED.</li>
 *   <li>If the run is not part of any chain, this listener is a no-op.</li>
 * </ul>
 */
@Slf4j
@Component
public class WorkflowAutoChainer {

    private final WorkflowService workflowService;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowAutoChainer(WorkflowService workflowService,
                               ApplicationEventPublisher eventPublisher) {
        this.workflowService = workflowService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        try {
            WorkflowChain chain = workflowService.findChainByRunId(event.getRunId());
            if (chain == null) {
                // This run is not part of any workflow chain — no-op
                return;
            }

            int stepIndex = workflowService.findStepIndex(chain, event.getRunId());
            if (stepIndex < 0) {
                log.warn("Run {} found in chain {} but step index not found",
                        event.getRunId(), chain.getId());
                return;
            }

            log.info("Workflow auto-chain triggered: chain={}, step={}, status={}",
                    chain.getId(), stepIndex, event.getStatus());

            if (event.getStatus() == RunStatus.FAILED) {
                workflowService.markStepFailed(chain.getId(), stepIndex,
                        "Run failed" + (event.getFinalOutput() != null ? ": " + event.getFinalOutput() : ""));
                return;
            }

            if (event.getStatus() == RunStatus.CANCELLED) {
                workflowService.markStepFailed(chain.getId(), stepIndex, "Run was cancelled");
                return;
            }

            if (event.getStatus() == RunStatus.ABORTED) {
                workflowService.markStepFailed(chain.getId(), stepIndex, "Run was aborted");
                return;
            }

            // Advance to next step (or complete the chain)
            boolean started = workflowService.advanceWorkflow(
                    chain.getId(), stepIndex, event.getFinalOutput());

            if (started) {
                log.info("Workflow chain advanced: chain={}, next step started", chain.getId());
                eventPublisher.publishEvent(new WorkflowAdvancedEvent(
                        this, chain.getId(), chain.getName(), stepIndex, stepIndex + 1, WorkflowChain.Status.RUNNING));
            } else {
                log.info("Workflow chain completed: chain={}, all {} steps done",
                        chain.getId(), stepIndex + 1);
                eventPublisher.publishEvent(new WorkflowAdvancedEvent(
                        this, chain.getId(), chain.getName(), stepIndex, -1, WorkflowChain.Status.COMPLETED));
            }
        } catch (Exception e) {
            log.error("Workflow auto-chain failed for run {}: {}",
                    event.getRunId(), e.getMessage(), e);
        }
    }
}

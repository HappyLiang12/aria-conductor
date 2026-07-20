package io.aria.conductor.execution.listener;

import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class RunExecutionListener {

    private final AgentLoopEngine agentLoopEngine;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunStarted(RunStartedEvent event) {
        log.info("Run execution triggered for runId={}", event.getRunId());
        try {
            agentLoopEngine.startRun(event.getRunId());
        } catch (Exception e) {
            log.error("Failed to start execution for runId={}: {}", event.getRunId(), e.getMessage(), e);
        }
    }

    /**
     * Bridges the cancel signal from RunService (act-agent) to AgentLoopEngine (act-execution).
     * RunService.cancelRun() publishes RunCompletedEvent(CANCELLED) but never signals the
     * in-memory RunContext. This listener sets the volatile cancelled flag so the loop exits.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunCompleted(RunCompletedEvent event) {
        if (event.getStatus() == RunStatus.CANCELLED) {
            log.info("Cancel signal received via event for runId={}", event.getRunId());
            try {
                agentLoopEngine.cancelRun(event.getRunId());
            } catch (Exception e) {
                log.warn("Failed to propagate cancel for runId={}: {}", event.getRunId(), e.getMessage());
            }
        }
    }
}

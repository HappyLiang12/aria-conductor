package io.aria.conductor.execution.listener;

import io.aria.conductor.common.event.RunStartedEvent;
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
}

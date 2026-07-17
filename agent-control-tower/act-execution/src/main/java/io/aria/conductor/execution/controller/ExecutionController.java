package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.engine.SessionStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/execution")
public class ExecutionController {

    private final AgentLoopEngine loopEngine;
    private final SessionStateManager sessionStateManager;

    public ExecutionController(AgentLoopEngine loopEngine, SessionStateManager sessionStateManager) {
        this.loopEngine = loopEngine;
        this.sessionStateManager = sessionStateManager;
    }

    @PostMapping("/start/{runId}")
    public ResponseEntity<Map<String, Object>> startRun(@PathVariable UUID runId) {
        log.info("Start run request: runId={}", runId);
        loopEngine.startRun(runId);
        return ResponseEntity.accepted().body(Map.of(
                "runId", runId,
                "status", "STARTED"
        ));
    }

    @GetMapping("/status/{runId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable UUID runId) {
        AgentSession session = sessionStateManager.getSession(runId);

        if (session == null) {
            return ResponseEntity.ok(Map.of(
                    "runId", runId,
                    "status", "NOT_FOUND"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "runId", runId,
                "agentId", session.getAgentId(),
                "status", session.getStatus(),
                "turnCount", session.getTurnCount(),
                "totalInputTokens", session.getTotalInputTokens(),
                "totalOutputTokens", session.getTotalOutputTokens()
        ));
    }

    @PostMapping("/pause/{runId}")
    public ResponseEntity<Map<String, Object>> pauseRun(@PathVariable UUID runId) {
        log.info("Pause run request: runId={}", runId);
        loopEngine.pauseRun(runId);
        return ResponseEntity.ok(Map.of(
                "runId", runId,
                "status", "PAUSING"
        ));
    }

    @PostMapping("/resume/{runId}")
    public ResponseEntity<Map<String, Object>> resumeRun(@PathVariable UUID runId) {
        log.info("Resume run request: runId={}", runId);
        loopEngine.resumeRun(runId);
        return ResponseEntity.ok(Map.of(
                "runId", runId,
                "status", "RESUMING"
        ));
    }
}
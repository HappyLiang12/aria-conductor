package io.aria.conductor.execution.controller;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallResponse;
import io.aria.conductor.common.model.TrajectoryResponse;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.RunInputCoordinator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/runs")
public class RunToolCallController {

    /** How a persisted injection reached (or did not reach) the run (F16). */
    static final String DELIVERY_LIVE = "LIVE";
    static final String DELIVERY_TRAJECTORY_ONLY = "TRAJECTORY_ONLY";

    private final ToolCallRepository toolCallRepository;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final RunRepository runRepository;
    private final RunInputCoordinator runInputs;

    public RunToolCallController(ToolCallRepository toolCallRepository,
                                 SessionTrajectoryRepository trajectoryRepository,
                                 RunRepository runRepository,
                                 RunInputCoordinator runInputs) {
        this.toolCallRepository = toolCallRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.runRepository = runRepository;
        this.runInputs = runInputs;
    }

    @GetMapping("/{id}/tool-calls")
    public ResponseEntity<List<ToolCallResponse>> getToolCalls(@PathVariable UUID id) {
        List<ToolCall> calls = toolCallRepository.findByRunId(id);
        return ResponseEntity.ok(calls.stream().map(ToolCallResponse::from).toList());
    }

    @GetMapping("/{id}/trajectory")
    public ResponseEntity<List<TrajectoryResponse>> getTrajectory(@PathVariable UUID id) {
        List<SessionTrajectory> entries = trajectoryRepository.findByRunIdOrderByTurnNumberAsc(id);
        return ResponseEntity.ok(entries.stream().map(TrajectoryResponse::from).toList());
    }

    /**
     * Injects an operator message into a run (F16).
     *
     * <p>The honest contract:
     * <ul>
     *   <li>A terminal run is frozen: the request is persisted as a
     *       trajectory-only annotation and the response says so
     *       ({@code delivery = TRAJECTORY_ONLY}). Nothing can consume it.</li>
     *   <li>A live run is delivered only when it is actually parked on an
     *       operator-input ask; that is the one live seam that exists
     *       ({@link RunInputCoordinator#submitAnswer}). The response says
     *       {@code delivery = LIVE}.</li>
     *   <li>Any other live run cannot receive the message. Returning 200 would
     *       reproduce the live-QA bug where an operator believed they had
     *       steered the core; the route instead refuses with 409 and an
     *       actionable message, and persists nothing.</li>
     * </ul>
     */
    @PostMapping("/{id}/inject")
    public ResponseEntity<?> injectMessage(@PathVariable UUID id,
                                           @Valid @RequestBody InjectMessageRequest request) {
        Run run = runRepository.findById(id).orElse(null);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }

        boolean terminal = isTerminal(run.getStatus());
        String delivery;
        if (terminal) {
            delivery = DELIVERY_TRAJECTORY_ONLY;
        } else {
            try {
                runInputs.submitAnswer(id, request.content());
                delivery = DELIVERY_LIVE;
            } catch (IllegalStateException e) {
                log.info("Injected message refused for live run {}: {}", id, e.getMessage());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(liveInjectRefusal(id, run.getStatus()));
            }
        }

        int nextTurn = trajectoryRepository.findMaxTurnNumberByRunId(id) + 1;
        String role = (request.role() != null && !request.role().isBlank()) ? request.role() : "user";

        SessionTrajectory entry = SessionTrajectory.builder()
                .runId(id)
                .turnNumber(nextTurn)
                .role(role)
                .content(request.content())
                .build();
        entry = trajectoryRepository.save(entry);
        log.info("Injected message into run {}: turn={}, role={}, delivery={}",
                id, nextTurn, role, delivery);
        TrajectoryResponse response = TrajectoryResponse.from(entry);
        response.setDelivery(delivery);
        return ResponseEntity.ok(response);
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED
                || status == RunStatus.ABORTED;
    }

    private static Map<String, Object> liveInjectRefusal(UUID runId, RunStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", HttpStatus.CONFLICT.getReasonPhrase());
        body.put("code", "LIVE_INJECT_UNAVAILABLE");
        body.put("runId", runId.toString());
        body.put("runStatus", status != null ? status.name() : null);
        body.put("message", "Run " + runId + " is " + status
                + " and is not waiting for operator input, so live injection is unavailable."
                + " Use the approval/answer surface when the run asks for input, or wait for a"
                + " terminal run to record a trajectory-only annotation.");
        return body;
    }
}

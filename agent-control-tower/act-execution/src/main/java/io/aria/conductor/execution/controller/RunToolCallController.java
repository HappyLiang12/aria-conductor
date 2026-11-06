package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallResponse;
import io.aria.conductor.common.model.TrajectoryResponse;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/runs")
public class RunToolCallController {

    private final ToolCallRepository toolCallRepository;
    private final SessionTrajectoryRepository trajectoryRepository;

    public RunToolCallController(ToolCallRepository toolCallRepository,
                                 SessionTrajectoryRepository trajectoryRepository) {
        this.toolCallRepository = toolCallRepository;
        this.trajectoryRepository = trajectoryRepository;
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

    @PostMapping("/{id}/inject")
    public ResponseEntity<TrajectoryResponse> injectMessage(@PathVariable UUID id,
                                                             @Valid @RequestBody InjectMessageRequest request) {
        int nextTurn = trajectoryRepository.findMaxTurnNumberByRunId(id) + 1;
        String role = (request.role() != null && !request.role().isBlank()) ? request.role() : "user";

        SessionTrajectory entry = SessionTrajectory.builder()
                .runId(id)
                .turnNumber(nextTurn)
                .role(role)
                .content(request.content())
                .build();
        entry = trajectoryRepository.save(entry);
        log.info("Injected message into run {}: turn={}, role={}", id, nextTurn, role);
        return ResponseEntity.ok(TrajectoryResponse.from(entry));
    }
}

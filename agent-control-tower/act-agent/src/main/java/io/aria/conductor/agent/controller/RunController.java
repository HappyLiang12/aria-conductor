package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.RunStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RunResponse> createRun(@Valid @RequestBody CreateRunRequest request) {
        RunResponse response = runService.createRun(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<RunResponse>> listRuns(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) RunStatus status) {

        List<RunResponse> runs;
        if (agentId != null && status != null) {
            runs = runService.listRunsByAgentAndStatus(agentId, status);
        } else if (agentId != null) {
            runs = runService.listRunsByAgent(agentId);
        } else if (status != null) {
            runs = runService.listRunsByStatus(status);
        } else {
            runs = runService.listRuns();
        }
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> getRun(@PathVariable UUID id) {
        return ResponseEntity.ok(runService.getRun(id));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<RunResponse> pauseRun(@PathVariable UUID id) {
        return ResponseEntity.ok(runService.pauseRun(id));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<RunResponse> resumeRun(@PathVariable UUID id) {
        return ResponseEntity.ok(runService.resumeRun(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<RunResponse> cancelRun(@PathVariable UUID id) {
        return ResponseEntity.ok(runService.cancelRun(id));
    }
}

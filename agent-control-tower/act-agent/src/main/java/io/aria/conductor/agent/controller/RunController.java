package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunProgressEventDto;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.port.RunInputPort;
import io.aria.conductor.common.repository.RunProgressEventRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runService;
    private final RunProgressEventRepository progressRepository;
    /**
     * The run-input port (2026-10-05 spec §5), optional and resolved lazily:
     * when the execution runtime owns the parked run, finalize is delivered
     * through this port and 202 is answered only for a run actually parked in
     * this process; a run no deployment owns (the legacy paths) keeps the
     * honest 409 refusal.
     */
    private final ObjectProvider<RunInputPort> runInputProvider;

    /** Direct-instantiation (test) constructor: no run-input port, finalize honestly refuses. */
    public RunController(RunService runService, RunProgressEventRepository progressRepository) {
        this(runService, progressRepository, null);
    }

    @Autowired
    public RunController(RunService runService, RunProgressEventRepository progressRepository,
                         ObjectProvider<RunInputPort> runInputProvider) {
        this.runService = runService;
        this.progressRepository = progressRepository;
        this.runInputProvider = runInputProvider;
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

    @GetMapping("/{id}/progress")
    public ResponseEntity<List<RunProgressEventDto>> getRunProgress(
            @PathVariable UUID id,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq) {
        List<RunProgressEventDto> events = progressRepository
                .findByRunIdAndSeqAfterOrderBySeqAsc(id, afterSeq)
                .stream()
                .map(p -> new RunProgressEventDto(p.getId(), p.getRunId(), p.getAgentId(),
                        p.getIteration(), p.getKind(), p.getSeq(), p.getContent(),
                        p.getToolName(), p.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(events);
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

    /**
     * Finalizes a run parked in WAITING_INPUT (2026-10-05 spec §5): the parked
     * thread is woken with the finalize signal and the run's own finalize path
     * settles it honestly. 202 means the signal was delivered to a run parked
     * in this process; 409 means it was not (already finalized, no such park,
     * or no deployment owns the run) — nothing is claimed for a run the port
     * refused. Deliberately NOT operator-gated (spec D6): the local operator's
     * loopback authority is automatic and remote callers use the system's
     * existing auth posture.
     */
    @PostMapping("/{id}/finalize")
    public ResponseEntity<Object> finalizeRun(@PathVariable UUID id) {
        RunInputPort inputPort = runInputProvider == null ? null : runInputProvider.getIfAvailable();
        if (inputPort == null || !inputPort.requestFinalize(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Run " + id + " is not waiting for operator input"));
        }
        return ResponseEntity.accepted()
                .body(Map.of("runId", id.toString(), "status", "finalizing"));
    }
}

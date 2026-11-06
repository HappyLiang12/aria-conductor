package io.aria.conductor.execution.dod;

import io.aria.conductor.execution.dod.dto.CreateEvidenceRequest;
import io.aria.conductor.execution.dod.dto.DoDStatusResponse;
import io.aria.conductor.execution.dod.dto.InitDoDRequest;
import io.aria.conductor.execution.dod.dto.SubmitReviewRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/dod")
public class DoDController {

    private final DoDService dodService;

    public DoDController(DoDService dodService) {
        this.dodService = dodService;
    }

    @PostMapping("/init")
    public ResponseEntity<DoDRecord> init(@Valid @RequestBody InitDoDRequest request) {
        log.info("DoD init: taskId={} taskType={} stages={}",
                request.taskId(), request.taskType(), request.stages());
        DoDRecord record = dodService.init(request.taskId(), request.taskType(), request.stages());
        return ResponseEntity.ok(record);
    }

    @PostMapping("/review")
    public ResponseEntity<?> review(@Valid @RequestBody SubmitReviewRequest request) {
        log.info("DoD review: taskId={} reviewer={} passed={}",
                request.taskId(), request.reviewerId(), request.passed());
        try {
            DoDRecord record = dodService.review(
                    request.taskId(),
                    request.reviewerId(),
                    request.reviewerName(),
                    request.passed(),
                    request.evidence(),
                    request.comment(),
                    request.verdict());
            return ResponseEntity.ok(record);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> getStatus(@PathVariable String taskId) {
        try {
            DoDStatusResponse response = dodService.buildStatusResponse(taskId);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{taskId}/evidence")
    public ResponseEntity<List<EvidenceItem>> getEvidence(@PathVariable String taskId) {
        return ResponseEntity.ok(dodService.getEvidence(taskId));
    }

    @PostMapping("/{taskId}/evidence")
    public ResponseEntity<?> addEvidence(@PathVariable String taskId,
                                         @Valid @RequestBody CreateEvidenceRequest request) {
        try {
            EvidenceItem item = dodService.addEvidence(taskId, request);
            return ResponseEntity.ok(item);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}

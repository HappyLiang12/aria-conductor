package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.MergeWorkflowRequest;
import io.aria.conductor.agent.dto.RetryWorkflowRequest;
import io.aria.conductor.agent.dto.ReuseWorkflowRequest;
import io.aria.conductor.agent.dto.UpdateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.service.WorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowResponse response = workflowService.createAndStart(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<WorkflowResponse>> listWorkflows() {
        return ResponseEntity.ok(workflowService.listWorkflows());
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowResponse> getWorkflow(@PathVariable UUID id) {
        return ResponseEntity.ok(workflowService.getWorkflow(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<WorkflowResponse> cancelWorkflow(@PathVariable UUID id) {
        return ResponseEntity.ok(workflowService.cancelWorkflow(id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<WorkflowResponse> retryWorkflow(@PathVariable UUID id, @RequestBody RetryWorkflowRequest request) {
        return ResponseEntity.ok(workflowService.retryStep(id, request.getStepIndex()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkflowResponse> updateWorkflow(@PathVariable UUID id, @RequestBody UpdateWorkflowRequest request) {
        return ResponseEntity.ok(workflowService.updateWorkflow(id, request.getName(), request.getDescription(), request.getSteps()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable UUID id) {
        workflowService.deleteWorkflow(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/merge")
    public ResponseEntity<WorkflowResponse> mergeWorkflows(@Valid @RequestBody MergeWorkflowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.mergeWorkflows(request.getSourceIds(), request.getName()));
    }

    @PostMapping("/templates/{id}/reuse")
    public ResponseEntity<WorkflowResponse> reuseWorkflow(@PathVariable UUID id, @RequestBody ReuseWorkflowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.createFromTemplate(id, request.getParameters()));
    }
}

package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint for executing workflows directly from YAML templates,
 * bypassing Aria LLM orchestration.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowYamlController {

    private final AgentLoopEngine agentLoopEngine;

    public WorkflowYamlController(AgentLoopEngine agentLoopEngine) {
        this.agentLoopEngine = agentLoopEngine;
    }

    /**
     * Execute a workflow from a YAML template.
     * Each step creates a Run that executes sequentially via the existing engine infrastructure.
     *
     * @param request contains the YAML content and optional parameter substitutions
     * @return the created WorkflowChain with its generated ID and status
     */
    @PostMapping("/execute-yaml")
    public ResponseEntity<Map<String, Object>> executeFromYaml(@RequestBody YamlWorkflowRequest request) {
        log.info("Execute YAML workflow request received");

        if (request.yamlContent() == null || request.yamlContent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "yamlContent is required"
            ));
        }

        WorkflowChain chain = agentLoopEngine.executeWorkflowFromYaml(
                request.yamlContent(), request.parameters());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", chain.getId(),
                "name", chain.getName() != null ? chain.getName() : "",
                "status", chain.getStatus().name(),
                "currentStepIndex", chain.getCurrentStepIndex()
        ));
    }

    /**
     * Request body for the execute-yaml endpoint.
     *
     * @param yamlContent the YAML workflow template content
     * @param parameters  optional parameter map for {key} substitution in prompt templates
     */
    public record YamlWorkflowRequest(String yamlContent, Map<String, String> parameters) {}
}

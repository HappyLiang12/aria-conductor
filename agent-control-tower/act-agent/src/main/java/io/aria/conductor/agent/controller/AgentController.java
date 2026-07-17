package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.AgentTemplateDTO;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.AssignToolRequest;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.AgentTemplateService;
import io.aria.conductor.common.model.ToolDefinition;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentService agentService;
    private final AgentTemplateService templateService;

    public AgentController(AgentService agentService, AgentTemplateService templateService) {
        this.agentService = agentService;
        this.templateService = templateService;
    }

    @PostMapping
    public ResponseEntity<AgentResponse> createAgent(@Valid @RequestBody CreateAgentRequest request) {
        AgentResponse response = agentService.createAgent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> listAgents() {
        return ResponseEntity.ok(agentService.listAgents());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> getAgent(@PathVariable UUID id) {
        return ResponseEntity.ok(agentService.getAgent(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentResponse> updateAgent(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateAgentRequest request) {
        return ResponseEntity.ok(agentService.updateAgent(id, request));
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<AgentResponse> retireAgent(@PathVariable UUID id) {
        return ResponseEntity.ok(agentService.retireAgent(id));
    }

    @GetMapping("/templates")
    public ResponseEntity<List<AgentTemplateDTO>> listTemplates() {
        return ResponseEntity.ok(templateService.listTemplates());
    }

    @PostMapping("/from-template/{templateName}")
    public ResponseEntity<AgentResponse> createFromTemplate(@PathVariable String templateName) {
        AgentResponse response = templateService.createFromTemplate(templateName);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<List<ToolDefinition>> listAgentTools(@PathVariable UUID id) {
        return ResponseEntity.ok(agentService.getAgentTools(id));
    }

    @PostMapping("/{id}/tools")
    public ResponseEntity<List<ToolDefinition>> assignTool(@PathVariable UUID id,
                                                           @Valid @RequestBody AssignToolRequest request) {
        agentService.assignTool(id, request.getToolId());
        return ResponseEntity.status(HttpStatus.CREATED).body(agentService.getAgentTools(id));
    }

    @DeleteMapping("/{id}/tools/{toolId}")
    public ResponseEntity<List<ToolDefinition>> unassignTool(@PathVariable UUID id,
                                                             @PathVariable String toolId) {
        agentService.unassignTool(id, toolId);
        return ResponseEntity.ok(agentService.getAgentTools(id));
    }
}

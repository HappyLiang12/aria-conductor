package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.AgentTemplateDTO;
import io.aria.conductor.agent.dto.ApplyRuntimeRequest;
import io.aria.conductor.agent.dto.ApplyRuntimeResult;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.AssignToolRequest;
import io.aria.conductor.agent.dto.AssignSkillRequest;
import io.aria.conductor.agent.dto.IdListRequest;
import io.aria.conductor.agent.dto.RoleDefaultsResponse;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.AgentTemplateService;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.model.SkillContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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

    /**
     * Fleet runtime governance (2026-10-11): the ONE converging action for the
     * fleet's single (core, mode) pair (spec section 3). Deliberately bypasses the
     * per-agent uniformity guard ({@code AgentService.assertNoRuntimeMix}): the
     * guard refuses any per-agent write that would introduce a second pair and
     * directs the operator to THIS endpoint -- so running the guard here would
     * refuse the very action that cures a mixed fleet. Do NOT "unify" the two;
     * they are intentionally different (per-agent guard vs the batch convergence
     * write).
     */
    @PutMapping("/runtime")
    public Map<String, Object> applyRuntime(@RequestBody ApplyRuntimeRequest request) {
        ApplyRuntimeResult result =
                agentService.applyRuntimeToAll(request.adkProvider(), request.executionMode());
        return Map.of(
                "runtime", Map.of(
                        "adkProvider", result.adkProvider(),
                        "executionMode", result.executionMode().name()),
                "updated", result.updated());
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

    @GetMapping("/{id}/skills")
    public ResponseEntity<List<SkillContext>> listAgentSkills(@PathVariable UUID id) {
        return ResponseEntity.ok(agentService.getAgentSkills(id));
    }

    @PostMapping("/{id}/skills")
    public ResponseEntity<List<SkillContext>> assignSkill(@PathVariable UUID id,
                                                          @Valid @RequestBody AssignSkillRequest request) {
        agentService.assignSkill(id, request.getSkillId());
        return ResponseEntity.status(HttpStatus.CREATED).body(agentService.getAgentSkills(id));
    }

    @DeleteMapping("/{id}/skills/{skillId}")
    public ResponseEntity<List<SkillContext>> unassignSkill(@PathVariable UUID id,
                                                            @PathVariable String skillId) {
        agentService.unassignSkill(id, skillId);
        return ResponseEntity.ok(agentService.getAgentSkills(id));
    }

    @PutMapping("/{id}/tools")
    public ResponseEntity<List<ToolDefinition>> setTools(@PathVariable UUID id,
                                                         @RequestBody IdListRequest request) {
        return ResponseEntity.ok(agentService.setTools(id, request.getIds()));
    }

    @PutMapping("/{id}/skills")
    public ResponseEntity<List<SkillContext>> setSkills(@PathVariable UUID id,
                                                        @RequestBody IdListRequest request) {
        return ResponseEntity.ok(agentService.setSkills(id, request.getIds()));
    }

    @GetMapping("/role-defaults/{role}")
    public ResponseEntity<RoleDefaultsResponse> getRoleDefaults(@PathVariable String role) {
        return ResponseEntity.ok(agentService.getRoleDefaults(role));
    }
}

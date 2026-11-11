package io.aria.conductor.dashboard.agent;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.AgentTemplateDTO;
import io.aria.conductor.agent.dto.ApplyRuntimeRequest;
import io.aria.conductor.agent.dto.ApplyRuntimeResult;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.AssignSkillRequest;
import io.aria.conductor.agent.dto.IdListRequest;
import io.aria.conductor.agent.dto.RoleDefaultsResponse;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.AgentTemplateService;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent REST surface (moved out of act-agent: only act-dashboard-api may depend
 * on both act-agent's services and act-execution's
 * {@link OperatorAuthorityResolver}).
 *
 * <p>Two actions here are operator-only: {@code POST /{id}/retire} (matching the MCP
 * {@code retire_agent} guard) and {@code PUT /runtime}, which rewrites the whole
 * fleet's runtime pair and has no MCP twin. Reads and the remaining mutations
 * (create/update/templates/skills) keep their existing contract.
 */
@Validated
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentService agentService;
    private final AgentTemplateService templateService;
    private final OperatorAuthorityResolver operatorAuthority;

    public AgentController(AgentService agentService, AgentTemplateService templateService,
                           OperatorAuthorityResolver operatorAuthority) {
        this.agentService = agentService;
        this.templateService = templateService;
        this.operatorAuthority = operatorAuthority;
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
     * <p>
     * The bypass is of the uniformity guard ONLY: operator authority IS resolved
     * here, before the service call (Wave C "authority honesty"). This route has no
     * MCP twin, so the parity argument that leaves {@code create_agent} /
     * {@code update_agent} worker-permitted does not apply: a sandbox worker reaching
     * a non-loopback address cannot move the whole fleet onto the host. The refusal
     * names the action and the surface the fleet error messages already point at
     * ("Configure &gt; Runtime").
     */
    @PutMapping("/runtime")
    public ResponseEntity<?> applyRuntime(@RequestBody ApplyRuntimeRequest request,
                                          HttpServletRequest httpRequest) {
        ResponseEntity<Object> refusal = rejectNonOperator(httpRequest,
                "changing the fleet runtime",
                "Change it fleet-wide in Configure > Runtime.");
        if (refusal != null) {
            return refusal;
        }
        ApplyRuntimeResult result =
                agentService.applyRuntimeToAll(request.adkProvider(), request.executionMode());
        return ResponseEntity.ok(Map.of(
                "runtime", Map.of(
                        "adkProvider", result.adkProvider(),
                        "executionMode", result.executionMode().name()),
                "updated", result.updated()));
    }

    /**
     * Operator-only, matching MCP {@code retire_agent}: the authority decision is
     * made before the service call, so a worker or anonymous caller cannot retire.
     */
    @PostMapping("/{id}/retire")
    public ResponseEntity<?> retireAgent(@PathVariable UUID id, HttpServletRequest request) {
        ResponseEntity<Object> refusal = rejectNonOperator(request,
                "retiring an agent", "Retire it on the Crew page.");
        if (refusal != null) {
            return refusal;
        }
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

    @PutMapping("/{id}/skills")
    public ResponseEntity<List<SkillContext>> setSkills(@PathVariable UUID id,
                                                        @RequestBody IdListRequest request) {
        return ResponseEntity.ok(agentService.setSkills(id, request.getIds()));
    }

    @GetMapping("/role-defaults/{role}")
    public ResponseEntity<RoleDefaultsResponse> getRoleDefaults(@PathVariable String role) {
        return ResponseEntity.ok(agentService.getRoleDefaults(role));
    }

    /**
     * The same authority decision the other operator-gated controllers make: 403 for
     * a worker credential (or a forged cookie mutation), 401 with no verifiable
     * identity. The refusal names the action and the surface where the operator can
     * act, in the MCP {@code retire_agent} voice. Null means the caller is the
     * operator; call this before any side effect.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, String action,
                                                     String surface) {
        try {
            operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", refusal(action, surface)));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", refusal(action, surface)));
        }
        return null;
    }

    private static String refusal(String action, String surface) {
        return "Operator authority required: " + action + " is an operator decision. " + surface;
    }
}

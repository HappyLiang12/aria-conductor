package io.aria.conductor.dashboard.agent;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.service.LlmProviderService;
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
 * LLM provider REST surface (moved out of act-agent: only act-dashboard-api may
 * depend on both act-agent's service and act-execution's
 * {@link OperatorAuthorityResolver}).
 *
 * <p>Every mutation is operator-only, matching the MCP provider tools: a provider
 * holds the installation's credential and activates a model for every run. The
 * two GETs stay readable for the Configure surface.
 */
@Validated
@RestController
@RequestMapping("/api/v1/llm-providers")
public class LlmProviderController {

    private final LlmProviderService providerService;
    private final OperatorAuthorityResolver operatorAuthority;

    public LlmProviderController(LlmProviderService providerService,
                                 OperatorAuthorityResolver operatorAuthority) {
        this.providerService = providerService;
        this.operatorAuthority = operatorAuthority;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody LlmProviderRequest request,
                                    HttpServletRequest httpRequest) {
        ResponseEntity<Object> refusal = rejectNonOperator(httpRequest,
                "adding an LLM provider", "Add it on the LLM Providers tab in Configure.");
        if (refusal != null) {
            return refusal;
        }
        LlmProviderResponse response = providerService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<LlmProviderResponse>> listAll() {
        return ResponseEntity.ok(providerService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<LlmProviderResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(providerService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @Valid @RequestBody LlmProviderRequest request,
                                    HttpServletRequest httpRequest) {
        ResponseEntity<Object> refusal = rejectNonOperator(httpRequest,
                "changing an LLM provider", "Edit it on the LLM Providers tab in Configure.");
        if (refusal != null) {
            return refusal;
        }
        return ResponseEntity.ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, HttpServletRequest httpRequest) {
        ResponseEntity<Object> refusal = rejectNonOperator(httpRequest,
                "deleting an LLM provider", "Delete it on the LLM Providers tab in Configure.");
        if (refusal != null) {
            return refusal;
        }
        providerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testConnection(@PathVariable UUID id, HttpServletRequest httpRequest) {
        ResponseEntity<Object> refusal = rejectNonOperator(httpRequest,
                "testing an LLM provider", "Test it on the LLM Providers tab in Configure.");
        if (refusal != null) {
            return refusal;
        }
        boolean success = providerService.testConnection(id);
        return ResponseEntity.ok(Map.of(
                "success", success,
                "message", success ? "Connection successful"
                        : "Connection failed. Check the provider's base URL and API key"
                                + " in Configure > LLM Providers."
        ));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable UUID id, HttpServletRequest httpRequest) {
        ResponseEntity<Object> refusal = rejectNonOperator(httpRequest,
                "activating an LLM provider",
                "Activate it on the LLM Providers tab in Configure.");
        if (refusal != null) {
            return refusal;
        }
        return ResponseEntity.ok(providerService.activate(id));
    }

    /**
     * The shared operator boundary, copied from the existing operator-gated
     * controllers: 403 for a worker credential or a forged cookie mutation, 401
     * with no verifiable identity. Null means the caller is the operator; call it
     * before any side effect. The body names the action and the surface where the
     * operator can act, in the MCP provider-tool voice.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, String action,
                                                     String surface) {
        try {
            operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", refusal(action, surface)));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", refusal(action, surface)));
        }
        return null;
    }

    private static String refusal(String action, String surface) {
        return "Operator authority required: " + action + " is an operator decision. " + surface;
    }
}

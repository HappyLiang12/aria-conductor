package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.service.LlmProviderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/llm-providers")
public class LlmProviderController {

    private final LlmProviderService providerService;

    public LlmProviderController(LlmProviderService providerService) {
        this.providerService = providerService;
    }

    @PostMapping
    public ResponseEntity<LlmProviderResponse> create(@Valid @RequestBody LlmProviderRequest request) {
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
    public ResponseEntity<LlmProviderResponse> update(@PathVariable UUID id,
                                                       @Valid @RequestBody LlmProviderRequest request) {
        return ResponseEntity.ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        providerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<java.util.Map<String, Object>> testConnection(@PathVariable UUID id) {
        boolean success = providerService.testConnection(id);
        return ResponseEntity.ok(java.util.Map.of(
                "success", success,
                "message", success ? "Connection successful" : "Connection failed"
        ));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<LlmProviderResponse> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(providerService.activate(id));
    }
}

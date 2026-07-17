package io.aria.conductor.agent.controller;

import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.common.service.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolDefinitionRepository toolRepo;
    private final ToolRegistry toolRegistry;

    @GetMapping
    public ResponseEntity<List<ToolDefinition>> listTools(
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) String category) {
        if (tier != null) return ResponseEntity.ok(toolRepo.findByTierAndEnabledTrue(tier));
        if (category != null) return ResponseEntity.ok(toolRepo.findByCategoryAndEnabledTrue(category));
        return ResponseEntity.ok(toolRepo.findByEnabledTrue());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ToolDefinition> getTool(@PathVariable String id) {
        return toolRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<ToolDefinition> toggleTool(@PathVariable String id) {
        return toolRepo.findById(id).map(tool -> {
            tool.setEnabled(!tool.isEnabled());
            return ResponseEntity.ok(toolRepo.save(tool));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/payload")
    public ResponseEntity<List<Map<String, Object>>> getToolsPayload() {
        return ResponseEntity.ok(toolRegistry.buildToolsPayloadForOpenAi());
    }
}

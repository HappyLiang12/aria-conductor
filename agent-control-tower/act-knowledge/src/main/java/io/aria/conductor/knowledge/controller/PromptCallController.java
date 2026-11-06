package io.aria.conductor.knowledge.controller;

import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.knowledge.dto.PromptCallStatsResponse;
import io.aria.conductor.knowledge.service.SelfImprovementService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/prompt-calls")
@Validated
public class PromptCallController {

    private final SelfImprovementService selfImprovementService;

    public PromptCallController(SelfImprovementService selfImprovementService) {
        this.selfImprovementService = selfImprovementService;
    }

    @GetMapping
    public ResponseEntity<List<PromptCall>> listPromptCalls(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID runId) {
        List<PromptCall> calls = selfImprovementService.listPromptCalls(agentId, runId);
        return ResponseEntity.ok(calls);
    }

    @GetMapping("/stats")
    public ResponseEntity<PromptCallStatsResponse> getStats(@RequestParam UUID agentId) {
        PromptCallStatsResponse response = selfImprovementService.getPromptCallStats(agentId);
        return ResponseEntity.ok(response);
    }
}

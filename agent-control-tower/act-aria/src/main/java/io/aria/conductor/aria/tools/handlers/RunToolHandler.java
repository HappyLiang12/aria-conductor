package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("runToolHandler")
public class RunToolHandler implements ToolHandler {

    private final RunService runService;
    private final RunRepository runRepository;
    private final ApprovalRepository approvalRepository;

    public RunToolHandler(RunService runService, RunRepository runRepository,
                          ApprovalRepository approvalRepository) {
        this.runService = runService;
        this.runRepository = runRepository;
        this.approvalRepository = approvalRepository;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "run_agent" -> startRun(arguments);
                case "list_runs" -> listRuns();
                case "get_run" -> getRun(arguments);
                case "get_run_status" -> getRun(arguments);
                case "pause_run" -> pauseRun(arguments);
                case "resume_run" -> resumeRun(arguments);
                case "cancel_run" -> cancelRun(arguments);
                case "list_running_runs" -> listRunningRuns();
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("RunToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String startRun(Map<String, Object> args) {
        String agentId = Objects.toString(args.get("agentId"), "");
        String prompt = Objects.toString(args.get("prompt"), "");
        if (agentId.isEmpty()) return error("Missing required parameter: agentId");
        if (prompt.isEmpty()) return error("Missing required parameter: prompt");
        CreateRunRequest req = CreateRunRequest.builder()
                .agentId(UUID.fromString(agentId))
                .promptSeed(prompt)
                .build();
        RunResponse resp = runService.createRun(req);
        return "Run started: " + resp.getId().toString() + " (agent: " + agentId + ")";
    }

    private String listRuns() {
        List<Run> runs = runRepository.findAll();
        StringBuilder sb = new StringBuilder("Runs (" + runs.size() + " total):\n");
        for (Run r : runs) {
            sb.append("  - ").append(r.getId())
                    .append(" | Agent: ").append(r.getAgentId() != null ? r.getAgentId() : "N/A")
                    .append(" | Status: ").append(r.getStatus() != null ? r.getStatus().name() : "UNKNOWN")
                    .append(" | Iterations: ").append(r.getIterationCount())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String getRun(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        UUID uuid = UUID.fromString(id);
        Run r = runRepository.findById(uuid).orElse(null);
        if (r == null) return error("Run not found: " + id);
        StringBuilder sb = new StringBuilder();
        sb.append("Run: ").append(r.getId()).append("\n");
        sb.append("  Agent ID: ").append(r.getAgentId() != null ? r.getAgentId() : "N/A").append("\n");
        sb.append("  Status: ").append(r.getStatus() != null ? r.getStatus().name() : "UNKNOWN").append("\n");
        sb.append("  Iterations: ").append(r.getIterationCount()).append("\n");
        sb.append("  Tokens Used: ").append(r.getTotalTokensUsed()).append("\n");
        sb.append("  Error: ").append(r.getErrorMessage() != null ? r.getErrorMessage() : "None");
        return sb.toString();
    }

    private String pauseRun(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        runService.pauseRun(UUID.fromString(id));
        return "Run " + id + " paused.";
    }

    private String resumeRun(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        UUID runId = UUID.fromString(id);
        // #28: the orchestrator must not resume past a pending human approval gate. Direct it to
        // decide_approval instead of bypassing HITL.
        boolean pending = approvalRepository.findByRunId(runId).stream()
                .anyMatch(a -> a.getStatus() == ApprovalStatus.PENDING);
        if (pending) {
            return error("Run " + id + " is waiting for human approval. Use decide_approval to "
                    + "approve or reject the pending action; do not resume past the gate.");
        }
        String instruction = Objects.toString(args.get("instruction"), null);
        runService.resumeRun(runId, instruction);
        return "Run " + id + " resumed.";
    }

    private String cancelRun(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        runService.cancelRun(UUID.fromString(id));
        return "Run " + id + " cancelled.";
    }

    private String listRunningRuns() {
        List<Run> runs = runRepository.findAll().stream()
            .filter(r -> r.getStatus() == RunStatus.RUNNING || r.getStatus() == RunStatus.PENDING
                || r.getStatus() == RunStatus.INITIALIZING || r.getStatus() == RunStatus.PAUSED)
            .toList();
        if (runs.isEmpty()) return "No running runs.";
        StringBuilder sb = new StringBuilder("Running runs (" + runs.size() + " total):\n");
        for (Run r : runs) {
            sb.append("  - ").append(r.getId()).append(" | Status: ").append(r.getStatus()).append("\n");
        }
        return sb.toString().trim();
    }

    private String error(String msg) { return "Error: " + msg; }
}

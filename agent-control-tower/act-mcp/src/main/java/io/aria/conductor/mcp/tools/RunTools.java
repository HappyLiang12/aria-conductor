package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Run lifecycle tools. Thin wrappers over the same RunService the REST
 * controllers call — REST/dashboard and MCP stay at parity. Error types mirror
 * GlobalExceptionHandler's REST status mapping (409 CONFLICT for
 * InvalidStateTransitionException/IllegalStateException).
 */
@Component
@RequiredArgsConstructor
public class RunTools implements McpTool {

    private static final Set<RunStatus> ACTIVE_STATUSES =
            Set.of(RunStatus.RUNNING, RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.PAUSED);

    private final RunService runService;
    private final ApprovalRepository approvalRepository;
    private final McpProperties mcpProperties;

    @Tool(name = "run_agent",
            description = "Start a run for an agent with the given prompt. Returns the created run including its id. maxIterations is optional; omit it to use the service default.")
    public String runAgent(
            @ToolParam(description = "Agent id") UUID agentId,
            @ToolParam(description = "Prompt seed: the agent's initial instruction") String prompt,
            @ToolParam(description = "Maximum reasoning iterations; omit for the service default", required = false)
            Integer maxIterations) {
        try {
            CreateRunRequest.CreateRunRequestBuilder builder = CreateRunRequest.builder()
                    .agentId(agentId)
                    .promptSeed(prompt);
            if (maxIterations != null) {
                builder.maxIterations(maxIterations);
            }
            return ToolResponses.ok(runService.createRun(builder.build()));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RUN_CREATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_runs",
            description = "List runs. Optional agentId (UUID) filters by agent and optional status (PENDING/INITIALIZING/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED/ABORTED) filters by status; blank means no filter.")
    public String listRuns(
            @ToolParam(description = "Agent id (UUID) or blank for all agents", required = false) String agentId,
            @ToolParam(description = "RunStatus name or blank for all statuses", required = false) String status) {
        try {
            UUID agentUuid = agentId == null || agentId.isBlank() ? null : parseUuid(agentId);
            RunStatus runStatus = status == null || status.isBlank() ? null : parseStatus(status);
            List<RunResponse> runs;
            if (agentUuid != null && runStatus != null) {
                runs = runService.listRunsByAgentAndStatus(agentUuid, runStatus);
            } else if (agentUuid != null) {
                runs = runService.listRunsByAgent(agentUuid);
            } else if (runStatus != null) {
                runs = runService.listRunsByStatus(runStatus);
            } else {
                runs = runService.listRuns();
            }
            return ToolResponses.ok(runs);
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RUN_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_running_runs",
            description = "List runs that are still active: RUNNING, PENDING, INITIALIZING or PAUSED.")
    public String listRunningRuns() {
        try {
            List<RunResponse> runs = runService.listRuns().stream()
                    .filter(r -> ACTIVE_STATUSES.contains(r.getStatus()))
                    .toList();
            return ToolResponses.ok(runs);
        } catch (Exception e) {
            return ToolResponses.error("RUN_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_run",
            description = "Get a run by id: status, agentId, iteration count, tokens used, error message and final output.")
    public String getRun(@ToolParam(description = "Run id") UUID id) {
        try {
            return ToolResponses.ok(runService.getRun(id));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RUN_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "pause_run",
            description = "Pause a running run. Only RUNNING runs can be paused.")
    public String pauseRun(@ToolParam(description = "Run id") UUID id) {
        try {
            return ToolResponses.ok(runService.pauseRun(id));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RUN_PAUSE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "resume_run",
            description = "Resume a paused run, optionally replacing its prompt seed with instruction. Refused while a pending human approval gate exists — use decide_approval first.")
    public String resumeRun(
            @ToolParam(description = "Run id") UUID id,
            @ToolParam(description = "Replacement prompt seed / instruction", required = false) String instruction) {
        try {
            // #28: never resume past a pending human approval gate; the kanban REVIEW_REQUEST ask
            // is a display-only prompt, not a gate, so it must not block resuming.
            boolean pendingApproval = approvalRepository.findByRunId(id).stream()
                    .anyMatch(a -> a.getStatus() == ApprovalStatus.PENDING
                            && a.getAskType() != Approval.AskType.REVIEW_REQUEST);
            if (pendingApproval) {
                throw new IllegalStateException("Run " + id + " is waiting for human approval. "
                        + "Use decide_approval to approve or reject the pending action; do not resume past the gate.");
            }
            return ToolResponses.ok(runService.resumeRun(id, instruction));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RUN_RESUME_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "cancel_run",
            description = "Cancel a run. Terminal runs (COMPLETED/FAILED/CANCELLED) cannot be cancelled.")
    public String cancelRun(@ToolParam(description = "Run id") UUID id) {
        try {
            return ToolResponses.ok(runService.cancelRun(id));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("RUN_CANCEL_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid agentId '" + raw + "'. Expected a UUID");
        }
    }

    private static RunStatus parseStatus(String raw) {
        try {
            return RunStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status '" + raw
                    + "'. Valid: PENDING, INITIALIZING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, ABORTED");
        }
    }
}

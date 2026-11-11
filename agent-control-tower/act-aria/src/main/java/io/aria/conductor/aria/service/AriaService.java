package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaAction;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.repository.AriaRunStateRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.model.*;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import static io.aria.conductor.execution.engine.AgentLoopEngine.parseMaxIterationsFromConfig;
import io.aria.conductor.execution.llm.*;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.common.model.PromptCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Sync Aria chat adapter. It owns no prompt and no history: the managed policy
 * comes from {@code AriaPromptContributor} and the bounded conversation history
 * comes from the engine's server-side history loader, both keyed by this run's
 * conversation id (P1/P5).
 */
@Slf4j
@Service
public class AriaService {

    /** Marker prefix on the synthesized summary message so it is easy to identify in logs/UI. */
    private static final String COMPACTION_MARKER = "[Earlier context] ";

    private final AgentLoopEngine agentLoopEngine;
    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final IntentClassifier intentClassifier;
    private final ToolCallRepository toolCallRepository;
    private final AriaSyncRunWaiter syncRunWaiter;

    public AriaService(AgentLoopEngine agentLoopEngine,
                       AgentRepository agentRepository,
                       RunRepository runRepository,
                       IntentClassifier intentClassifier,
                       ToolCallRepository toolCallRepository,
                       AriaSyncRunWaiter syncRunWaiter) {
        this.agentLoopEngine = agentLoopEngine;
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.intentClassifier = intentClassifier;
        this.toolCallRepository = toolCallRepository;
        this.syncRunWaiter = syncRunWaiter;
    }

    public AriaChatResponse chat(AriaChatRequest request) {
        String conversationId = request.getConversationId() != null ? request.getConversationId()
                : (request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
        String intent = intentClassifier.classify(request.getMessage());
        log.info("Aria chat (via AgentLoopEngine): conversationId={}, intent={}", conversationId, intent);

        // Look up Aria agent (created by AriaDefaultAgentInitializer at startup)
        Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID)
                .orElseThrow(() -> new IllegalStateException("Aria agent not found — ensure AriaDefaultAgentInitializer ran"));

        // Build a Run — AgentLoopEngine will handle LLM calls and tool execution
        Run run = Run.builder()
                .agentId(AriaConstants.ARIA_AGENT_ID)
                .promptSeed(request.getMessage())
                .maxIterations(parseMaxIterationsFromConfig(aria, 0))
                .status(RunStatus.PENDING)
                .conversationId(conversationId)
                .build();
        run = runRepository.save(run);

        // History has one owner: the engine loads it server-side from the run's
        // conversation id (P5). The sync adapter delegates with no client messages.
        agentLoopEngine.startRun(run.getId(), List.of());

        // Wait for the run without holding a request transaction or DB connection
        // (F6): every read is its own short transaction, the sleep is outside one.
        AriaSyncRunWaiter.Outcome outcome = syncRunWaiter.await(run.getId());
        return buildResponse(run.getId(), conversationId, intent, outcome);
    }

    /**
     * Maps the observed committed state to the wire contract (F2): the reply
     * always carries the run id and the status that was actually read, and only
     * a COMPLETED run may claim completion. A FAILED/ABORTED run carries its
     * stable error identity and reason.
     */
    private AriaChatResponse buildResponse(UUID runId, String conversationId, String intent,
                                           AriaSyncRunWaiter.Outcome outcome) {
        AriaRunStateRepository.RunSnapshot snapshot = outcome.snapshot();
        RunStatus status = snapshot != null && snapshot.getStatus() != null
                ? snapshot.getStatus() : RunStatus.PENDING;
        List<AriaAction> actions = buildActionsTaken(runId);
        return AriaChatResponse.builder()
                .runId(runId.toString())
                .conversationId(conversationId)
                .message(messageFor(runId, status, snapshot, actions))
                .intent(intent)
                .actionsTaken(actions)
                .status(status.name())
                .errorCode(snapshot != null ? snapshot.getErrorCode() : null)
                .errorMessage(snapshot != null ? snapshot.getErrorMessage() : null)
                .timestamp(Instant.now())
                .build();
    }

    private static String messageFor(UUID runId, RunStatus status,
                                     AriaRunStateRepository.RunSnapshot snapshot,
                                     List<AriaAction> actions) {
        String output = snapshot != null ? snapshot.getFinalOutput() : null;
        if (status == RunStatus.COMPLETED) {
            if (output != null && !output.isBlank()) {
                return output;
            }
            if (!actions.isEmpty()) {
                return "Executed: " + actions.stream()
                        .map(AriaAction::description)
                        .collect(Collectors.joining(", "));
            }
            return "I've completed your request. Check the Run dashboard for details.";
        }
        if (status == RunStatus.FAILED || status == RunStatus.ABORTED) {
            if (output != null && !output.isBlank()) {
                return output;
            }
            String code = snapshot != null ? snapshot.getErrorCode() : null;
            String reason = snapshot != null ? snapshot.getErrorMessage() : null;
            StringBuilder message = new StringBuilder(
                    status == RunStatus.ABORTED ? "Run was aborted" : "Run failed");
            if (code != null && !code.isBlank()) {
                message.append(" (").append(code).append(')');
            }
            if (reason != null && !reason.isBlank()) {
                message.append(": ").append(reason);
            } else {
                message.append(" before producing a result.");
            }
            return message.append(" Check the Runs dashboard for details.").toString();
        }
        if (status == RunStatus.CANCELLED) {
            if (output != null && !output.isBlank()) {
                return output;
            }
            return "Run was cancelled before producing a result. Check the Runs dashboard for details.";
        }
        // Still live after the bounded sync window: report exactly what was seen.
        return "Run " + runId + " is still " + status
                + ". It has not completed; follow it in the Runs dashboard or use streaming chat for live updates.";
    }

    /** One-click synthesis template (Feature B4): fixed header, one line per child, fixed tail. */
    private static final String SYNTHESIS_PROMPT_HEADER =
            "以下子任務已完成，請彙整結果並給我建議報告與下一步：";
    private static final String SYNTHESIS_PROMPT_TAIL =
            "請先讀取需要的子任務結果（用你的 run 工具），以繁體中文輸出：完成/失敗統計、各子任務重點、整體建議、仍無法核實之事項。";

    /**
     * Compose (but never run) the one-click synthesis prompt for a dispatch batch
     * (Feature B4). With a {@code dispatchedByRunId} the group is loaded directly;
     * otherwise the conversation's latest group — the newest run that dispatched
     * at least one child — is used. Empty when nothing resolves; the controller
     * maps that to the module's usual 404.
     */
    public Optional<String> composeSynthesisPrompt(String conversationId, UUID dispatchedByRunId) {
        List<Run> children = dispatchedByRunId != null
                ? runRepository.findByDispatchedByRunId(dispatchedByRunId)
                : findLatestDispatchGroup(conversationId);
        if (children.isEmpty()) {
            return Optional.empty();
        }
        List<Run> ordered = children.stream()
                .sorted(Comparator.comparing(Run::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        StringBuilder prompt = new StringBuilder(SYNTHESIS_PROMPT_HEADER).append('\n');
        for (Run child : ordered) {
            prompt.append("- run ").append(child.getId()).append('：')
                    .append(child.getStatus()).append('\n');
        }
        prompt.append(SYNTHESIS_PROMPT_TAIL);
        return Optional.of(prompt.toString());
    }

    /**
     * The children of the conversation's latest dispatch group: walk the runs
     * newest-first and take the first that dispatched any child.
     */
    private List<Run> findLatestDispatchGroup(String conversationId) {
        List<Run> runs = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        for (int i = runs.size() - 1; i >= 0; i--) {
            List<Run> children = runRepository.findByDispatchedByRunId(runs.get(i).getId());
            if (!children.isEmpty()) {
                return children;
            }
        }
        return List.of();
    }

    /**
     * Build actionsTaken list from completed tool calls for this run.
     */
    private List<AriaAction> buildActionsTaken(UUID runId) {
        try {
            return toolCallRepository.findByRunId(runId).stream()
                    .filter(tc -> tc.getStatus() == ToolCallStatus.COMPLETED)
                    .map(tc -> new AriaAction(
                            tc.getToolName(),
                            tc.getToolName(),
                            tc.getResult() != null ? tc.getResult() : ""))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to build actionsTaken: {}", e.getMessage());
            return List.of();
        }
    }

}

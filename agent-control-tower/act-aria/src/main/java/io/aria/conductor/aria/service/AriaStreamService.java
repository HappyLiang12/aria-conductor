package io.aria.conductor.aria.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.service.SkillContextProvider;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import static io.aria.conductor.execution.engine.AgentLoopEngine.parseMaxIterationsFromConfig;
import io.aria.conductor.execution.llm.LlmMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Streaming variant of {@link AriaService#chat(AriaChatRequest)}.
 *
 * <p>Acts as a thin bridge: creates a Run, then delegates execution to
 * {@link AgentLoopEngine#startRunStream(UUID, SseEmitter, List)}.
 * The engine emits SSE events directly into the provided emitter.
 */
@Slf4j
@Service
public class AriaStreamService {

    private static final UUID ARIA_AGENT_ID = AriaConstants.ARIA_AGENT_ID;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentLoopEngine agentLoopEngine;
    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final IntentClassifier intentClassifier;
    private final AriaService ariaService;
    private final SkillContextProvider skillContextProvider;

    public AriaStreamService(AgentLoopEngine agentLoopEngine,
                             AgentRepository agentRepository,
                             RunRepository runRepository,
                             IntentClassifier intentClassifier,
                             AriaService ariaService,
                             SkillContextProvider skillContextProvider) {
        this.agentLoopEngine = agentLoopEngine;
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.intentClassifier = intentClassifier;
        this.ariaService = ariaService;
        this.skillContextProvider = skillContextProvider;
    }

    public void streamChat(AriaChatRequest request, SseEmitter emitter) {
        emitter.onTimeout(() -> {
            log.warn("Aria SSE stream timed out");
            sendErrorSilent(emitter, errorPayloadJson("stream timed out", null, null));
            emitter.complete();
        });
        emitter.onError(t -> log.warn("Aria SSE stream error: {}", t.getMessage()));

        Run run = null;
        try {
            String conversationId = request.getConversationId() != null
                    ? request.getConversationId()
                    : (request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
            String intent = intentClassifier.classify(request.getMessage());
            log.info("Aria stream chat: conversationId={}, intent={}, messageLength={}",
                    conversationId, intent, request.getMessage().length());

            Agent aria = agentRepository.findById(ARIA_AGENT_ID)
                    .orElseThrow(() -> new IllegalStateException(
                            "Aria agent not found"));

            int maxIterations = parseMaxIterationsFromConfig(aria, 0);

            run = Run.builder()
                    .agentId(ARIA_AGENT_ID)
                    .promptSeed(request.getMessage())
                    .maxIterations(maxIterations)
                    .status(RunStatus.PENDING)
                    .conversationId(conversationId)
                    .build();
            run = runRepository.save(run);

            List<LlmMessage> contextMessages = buildInitialContext(request);

            agentLoopEngine.startRunStream(run.getId(), emitter, contextMessages, intent);

        } catch (Exception ex) {
            log.warn("Aria stream chat failed to start: {}", ex.getMessage(), ex);
            // Mark the pre-saved Run as FAILED so it doesn't stay orphaned
            if (run != null) {
                try {
                    run.setStatus(RunStatus.FAILED);
                    run.setErrorMessage("Stream startup failed: " + ex.getMessage());
                    runRepository.save(run);
                } catch (Exception ignored) { /* best-effort */ }
            }
            // A persisted run makes this a failed TURN: the error event carries the run
            // id and reason so the client can tell it apart from a transport error.
            sendErrorSilent(emitter, errorPayloadJson(
                    "Aria streaming failed: " + ex.getMessage(), run, ex.getMessage()));
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    /**
     * The turns that precede the current request, in order: the system prompt and the
     * client's history. The current request itself is the run's prompt seed -- the
     * engine persists it as the run's first timeline row and passes it to the model,
     * so including it here as well would duplicate it in both the messages and the
     * conversation timeline.
     */
    private List<LlmMessage> buildInitialContext(AriaChatRequest request) {
        List<LlmMessage> messages = new ArrayList<>();

        String systemPrompt = ariaService.buildSystemPrompt();
        String directive = resolveSkillDirective(request.getSkillId());
        String combined = (systemPrompt == null ? "" : systemPrompt) + directive;
        if (!combined.isBlank()) {
            messages.add(LlmMessage.system(combined));
        }

        List<AriaChatRequest.ChatMessage> clientHistory = request.getHistory();
        if (clientHistory != null && !clientHistory.isEmpty()) {
            for (AriaChatRequest.ChatMessage m : clientHistory) {
                if (m == null || m.getContent() == null || m.getContent().isBlank()) continue;
                String role = m.getRole() == null ? "" : m.getRole();
                if ("user".equalsIgnoreCase(role)) {
                    messages.add(LlmMessage.user(m.getContent()));
                } else if ("assistant".equalsIgnoreCase(role)) {
                    messages.add(LlmMessage.assistant(m.getContent()));
                }
            }
        }

        return messages;
    }

    /**
     * Resolve a slash-command skillId into a system-prompt suffix.
     * Returns "" when absent, unknown, disabled, non-SKILL stage, or template-less.
     * Governance is delegated to SkillContextProvider — never throws into the stream.
     */
    private String resolveSkillDirective(String skillId) {
        if (skillId == null || skillId.isBlank()) return "";
        try {
            return skillContextProvider.getEnabledSkillsByIds(List.of(skillId)).stream()
                    .findFirst()
                    .map(s -> "\n\n## Active Skill: " + s.name() + "\n" + s.template())
                    .orElseGet(() -> {
                        log.warn("Skill {} not injectable (unknown/disabled/non-SKILL/no template)", skillId);
                        return "";
                    });
        } catch (Exception e) {
            log.warn("Skill lookup failed for {}: {}", skillId, e.getMessage());
            return "";
        }
    }

    /**
     * JSON payload of the terminal SSE {@code error} event.
     *
     * <p>When {@code failedRun} is a persisted run the error reports a failed TURN: the
     * legacy {@code message} key is kept and {@code turnFailed}/{@code runId}/{@code reason}
     * are added so the client can distinguish a turn failure from a transport error.
     * Otherwise the payload keeps the legacy {@code {"message": ...}} shape.
     *
     * <p>{@code reason} is the run's error message, falling back to {@code causeMessage}
     * when the run recorded none, clipped to {@link #MAX_ERROR_REASON_CHARS}.
     */
    static String errorPayloadJson(String message, Run failedRun, String causeMessage) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message == null ? "unknown error" : message);
        if (failedRun != null && failedRun.getId() != null) {
            String source = failedRun.getErrorMessage();
            if (source == null || source.isBlank()) {
                source = causeMessage;
            }
            payload.put("turnFailed", true);
            payload.put("runId", failedRun.getId().toString());
            payload.put("reason", clipReason(source));
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Serializing plain strings cannot realistically fail; fall back to the legacy
            // literal rather than dropping the terminal error event.
            return "{\"message\":\"unknown error\"}";
        }
    }

    /** Max characters of a failed run's error carried in the terminal SSE error payload. */
    private static final int MAX_ERROR_REASON_CHARS = 300;

    private static String clipReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown error";
        }
        return reason.length() > MAX_ERROR_REASON_CHARS
                ? reason.substring(0, MAX_ERROR_REASON_CHARS)
                : reason;
    }

    private void sendErrorSilent(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().name("error").data(json));
        } catch (Exception ignored) {
            // best-effort
        }
    }
}

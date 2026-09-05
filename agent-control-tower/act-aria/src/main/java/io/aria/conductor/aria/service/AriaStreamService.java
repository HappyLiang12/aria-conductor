package io.aria.conductor.aria.service;

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
import java.util.List;
import java.util.Map;
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

    private final AgentLoopEngine agentLoopEngine;
    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final IntentClassifier intentClassifier;
    private final AriaService ariaService;
    private final SkillContextProvider skillContextProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            sendErrorSilent(emitter, "stream timed out");
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
            sendErrorSilent(emitter, "Aria streaming failed: " + ex.getMessage());
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

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

        messages.add(LlmMessage.user(request.getMessage()));
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

    private void sendErrorSilent(SseEmitter emitter, String message) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "message", message == null ? "unknown error" : message));
            emitter.send(SseEmitter.event().name("error").data(json));
        } catch (Exception ignored) {
            // best-effort
        }
    }
}

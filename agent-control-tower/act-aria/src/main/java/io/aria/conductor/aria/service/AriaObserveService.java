package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.AriaObserveRequest;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunErrorText;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.runtime.CoreAttachment;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.aria.conductor.execution.engine.AgentLoopEngine.parseMaxIterationsFromConfig;

/**
 * Turns one glasses observation into a normal Aria run. The image travels as a
 * turn-only attachment; the persisted prompt seed is text built by
 * {@link AriaObservationSeed}. It owns no prompt of its own.
 */
@Slf4j
@Service
public class AriaObserveService {

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final AgentLoopEngine agentLoopEngine;

    public AriaObserveService(AgentRepository agentRepository,
                              RunRepository runRepository,
                              AgentLoopEngine agentLoopEngine) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.agentLoopEngine = agentLoopEngine;
    }

    /**
     * Refusals that must answer JSON before a stream exists. A null or blank
     * core is the documented default (opencode) and is accepted.
     */
    public Optional<AriaObservationValidator.Rejection> preflight(AriaObserveRequest request) {
        Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID).orElse(null);
        if (aria == null) {
            return Optional.of(new AriaObservationValidator.Rejection(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Aria agent not found"));
        }
        String core = aria.getAdkProvider();
        if (core != null && !core.isBlank() && !OpenCodeCoreAdapter.CORE_ID.equalsIgnoreCase(core)) {
            return Optional.of(new AriaObservationValidator.Rejection(HttpStatus.CONFLICT,
                    "Observations require the opencode core; Aria is on '" + core + "'"));
        }
        return Optional.empty();
    }

    public void stream(AriaObserveRequest request,
                       AriaObservationValidator.ObservedImage image,
                       SseEmitter emitter) {
        emitter.onTimeout(() -> {
            log.warn("Aria observation stream timed out");
            sendErrorSilent(emitter, AriaStreamService.errorPayloadJson("stream timed out", null, null));
            emitter.complete();
        });
        emitter.onError(t -> log.warn("Aria observation stream error: {}", t.getMessage()));

        Run run = null;
        try {
            String conversationId = resolveConversationId(request.getConversationId());
            Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID)
                    .orElseThrow(() -> new IllegalStateException("Aria agent not found"));
            String seed = AriaObservationSeed.build(request);
            log.info("Aria observe: conversationId={}, objects={}", conversationId,
                    request.getObservation().getObjects() == null
                            ? 0 : request.getObservation().getObjects().size());

            run = Run.builder()
                    .agentId(AriaConstants.ARIA_AGENT_ID)
                    .promptSeed(seed)
                    .maxIterations(parseMaxIterationsFromConfig(aria, 0))
                    .status(RunStatus.PENDING)
                    .conversationId(conversationId)
                    .build();
            run = runRepository.save(run);

            String filename = "image/png".equals(image.mimeType())
                    ? "observation.png" : "observation.jpg";
            agentLoopEngine.startRunStream(run.getId(), emitter, List.of(), "observe",
                    List.of(new CoreAttachment(image.mimeType(), image.bytes(), filename)));
        } catch (Exception ex) {
            log.warn("Aria observation failed to start: {}", ex.getMessage(), ex);
            if (run != null) {
                try {
                    run.setStatus(RunStatus.FAILED);
                    run.setErrorMessage(RunErrorText.bound("Observation startup failed: " + ex.getMessage()));
                    runRepository.save(run);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
            sendErrorSilent(emitter, AriaStreamService.errorPayloadJson(
                    "Aria observation failed: " + ex.getMessage(), run, ex.getMessage()));
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /**
     * The conversation this observation joins: the caller's when it names one, else
     * Aria's newest existing conversation, else a fresh id. The lookup reads only
     * {@code conversation_id} rows and is scoped to Aria, so it neither loads every
     * run's text nor adopts a worker run's conversation.
     */
    private String resolveConversationId(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return runRepository.findLatestConversationIds(
                        AriaConstants.ARIA_AGENT_ID, PageRequest.of(0, 1)).stream()
                .findFirst()
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    private void sendErrorSilent(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().name("error").data(json));
        } catch (Exception ignored) {
            // best-effort
        }
    }
}

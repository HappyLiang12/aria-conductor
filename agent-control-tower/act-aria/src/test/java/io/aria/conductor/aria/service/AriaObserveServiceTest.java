package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.runtime.CoreAttachment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AriaObserveServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RunRepository runRepository = mock(RunRepository.class);
    private final AgentLoopEngine engine = mock(AgentLoopEngine.class);
    private final AriaObserveService service =
            new AriaObserveService(agentRepository, runRepository, engine);

    @Test
    void theRunCarriesTheSeedAndTheImageAsAnAttachment() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(agent("opencode")));
        when(runRepository.findAll()).thenReturn(List.of());
        when(runRepository.save(any(Run.class))).thenAnswer(invocation -> {
            Run run = invocation.getArgument(0);
            run.setId(RUN_ID);
            return run;
        });

        SseEmitter emitter = new SseEmitter(1_000L);
        service.stream(AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1)),
                new AriaObservationValidator.ObservedImage(new byte[]{1, 2, 3}, "image/jpeg"),
                emitter);

        ArgumentCaptor<Run> runCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getPromptSeed())
                .startsWith("[glasses observation]")
                .doesNotContain("/9j/2Q==");
        assertThat(runCaptor.getValue().getConversationId()).isNotBlank();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoreAttachment>> attachmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(engine).startRunStream(eq(RUN_ID), eq(emitter), eq(List.of()), eq("observe"),
                attachmentCaptor.capture());
        assertThat(attachmentCaptor.getValue()).singleElement().satisfies(attachment -> {
            assertThat(attachment.mimeType()).isEqualTo("image/jpeg");
            assertThat(attachment.filename()).isEqualTo("observation.jpg");
            assertThat(attachment.bytes()).containsExactly(1, 2, 3);
        });
    }

    @Test
    void anOmittedConversationReusesTheLatestOne() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(agent("opencode")));
        when(runRepository.findAll()).thenReturn(List.of(
                run("older", Instant.parse("2026-11-20T10:00:00Z")),
                run("newest", Instant.parse("2026-11-21T10:00:00Z"))));
        when(runRepository.save(any(Run.class))).thenAnswer(invocation -> {
            Run saved = invocation.getArgument(0);
            saved.setId(RUN_ID);
            return saved;
        });
        var request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        request.setConversationId(null);

        service.stream(request,
                new AriaObservationValidator.ObservedImage(new byte[]{1}, "image/jpeg"),
                new SseEmitter(1_000L));

        ArgumentCaptor<Run> runCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getConversationId()).isEqualTo("newest");
    }

    @Test
    void aNonOpencodeCoreIsRefusedBeforeAnyRunIsCreated() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(agent("qoder")));

        Optional<AriaObservationValidator.Rejection> rejection =
                service.preflight(AriaObservationValidatorTest.request(
                        AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1)));

        assertThat(rejection).isPresent();
        assertThat(rejection.get().status().value()).isEqualTo(409);
        verify(runRepository, never()).save(any());
    }

    private static Agent agent(String core) {
        Agent agent = new Agent();
        agent.setId(AriaConstants.ARIA_AGENT_ID);
        agent.setAdkProvider(core);
        return agent;
    }

    private static Run run(String conversationId, Instant createdAt) {
        Run run = new Run();
        run.setConversationId(conversationId);
        run.setCreatedAt(createdAt);
        return run;
    }
}

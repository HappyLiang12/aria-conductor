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
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
        when(runRepository.findLatestConversationIds(eq(AriaConstants.ARIA_AGENT_ID), any(Pageable.class)))
                .thenReturn(List.of());
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
        // The query orders by createdAt DESC and reads one row, so the newest id comes first.
        when(runRepository.findLatestConversationIds(eq(AriaConstants.ARIA_AGENT_ID), any(Pageable.class)))
                .thenReturn(List.of("newest"));
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

        // Page 0 of size 1 is the newest row: that is the whole of the "latest" claim.
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findLatestConversationIds(
                eq(AriaConstants.ARIA_AGENT_ID), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(1);
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
}

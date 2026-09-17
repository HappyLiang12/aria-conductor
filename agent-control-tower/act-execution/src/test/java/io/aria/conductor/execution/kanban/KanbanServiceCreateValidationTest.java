package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Create-path validation (spec 5.1): a phantom card must not be reachable —
 * neither a terminal birth status nor a run link that resolves to nothing.
 *
 * <p>Eligibility is part of the create path too, but ONLY for a dispatch intent
 * (a create with no run link). A card that observes an existing run is not a
 * dispatch intent, so eligibility must not be applied to it.
 */
class KanbanServiceCreateValidationTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private KanbanRepository repository;
    private RunRepository runRepository;
    private AgentRepository agentRepository;
    private KanbanService service;

    @BeforeEach
    void setUp() {
        repository = mock(KanbanRepository.class);
        runRepository = mock(RunRepository.class);
        agentRepository = mock(AgentRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new KanbanService(repository, mock(ApplicationEventPublisher.class), runRepository,
                mock(ApprovalRepository.class), agentRepository, new AgentPickupEligibility());
    }

    @ParameterizedTest
    @EnumSource(value = KanbanStatus.class, names = {"DONE", "CANCELLED", "BLOCKED"})
    void rejectsNonCreatableBirthStatus(KanbanStatus status) {
        assertThatThrownBy(() -> service.create(CreateKanbanItemRequest.builder()
                .title("born done").status(status).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_BIRTH_STATUS");

        verify(repository, never()).save(any());
    }

    /**
     * Positive control for the birth allow-list: every status the board has a
     * column for and the state machine can leave is still creatable.
     */
    @ParameterizedTest
    @EnumSource(value = KanbanStatus.class, names = {"BACKLOG", "TODO", "IN_PROGRESS", "REVIEW"})
    void acceptsCreatableBirthStatus(KanbanStatus status) {
        KanbanItem item = service.create(CreateKanbanItemRequest.builder()
                .title("born " + status).status(status).build());

        assertThat(item.getStatus()).isEqualTo(status);
        verify(repository).save(any());
    }

    @Test
    void rejectsMalformedRunLink() {
        assertThatThrownBy(() -> service.create(CreateKanbanItemRequest.builder()
                .title("bad link").linkedRunId("not-a-uuid").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_RUN_LINK");

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsRunLinkThatResolvesToNothing() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(CreateKanbanItemRequest.builder()
                .title("ghost link").linkedRunId(runId.toString()).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_RUN_LINK");

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsIneligibleDispatchTarget() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(Agent.builder()
                .id(AGENT_ID).name("Disabled").agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY).pickupEnabled(Boolean.FALSE).build()));

        assertThatThrownBy(() -> service.create(CreateKanbanItemRequest.builder()
                .title("dispatch intent").linkedAgentId(AGENT_ID.toString()).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> {
                    PickupRejectedException rejected = (PickupRejectedException) e;
                    assertThat(rejected.code()).isEqualTo("AGENT_NOT_ELIGIBLE");
                    assertThat(rejected.details())
                            .containsEntry("agentId", AGENT_ID.toString())
                            .containsEntry("reasons", List.of("PICKUP_DISABLED"));
                });

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsUnknownDispatchTarget() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(CreateKanbanItemRequest.builder()
                .title("dispatch intent").linkedAgentId(AGENT_ID.toString()).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code()).isEqualTo("AGENT_NOT_ELIGIBLE"));

        verify(repository, never()).save(any());
    }

    @Test
    void acceptsEligibleDispatchTarget() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(Agent.builder()
                .id(AGENT_ID).name("Worker").agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY).pickupEnabled(Boolean.TRUE).build()));

        KanbanItem item = service.create(CreateKanbanItemRequest.builder()
                .title("dispatch intent").linkedAgentId(AGENT_ID.toString()).build());

        assertThat(item.getStatus()).isEqualTo(KanbanStatus.TODO);
        assertThat(item.getLinkedAgentId()).isEqualTo(AGENT_ID.toString());
    }

    @Test
    void acceptsRunObservationCardForAnIneligibleAgent() {
        // RunKanbanAutoCreator creates a card for EVERY run, including Aria's own.
        // Observing an existing run is not a dispatch intent, so eligibility is
        // never consulted — otherwise Aria's run cards (RESERVED_OPERATOR_AGENT)
        // would be rejected.
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(Run.builder().id(runId).build()));

        KanbanItem item = service.create(CreateKanbanItemRequest.builder()
                .title("run card")
                .linkedRunId(runId.toString())
                .linkedAgentId(AriaConstants.ARIA_AGENT_ID.toString())
                .build());

        assertThat(item.getStatus()).isEqualTo(KanbanStatus.TODO);
        assertThat(item.getLinkedRunId()).isEqualTo(runId.toString());
        verify(agentRepository, never()).findById(any());
    }
}

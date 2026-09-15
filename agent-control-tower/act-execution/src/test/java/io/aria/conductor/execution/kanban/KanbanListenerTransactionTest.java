package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.listener.RunKanbanAutoCreator;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Transaction-boundary coverage for the kanban-mutating listeners.
 *
 * <p>A synchronous operator move is answered by its own response (Task 3): a
 * rejected pickup is a 4xx, never a card-level {@code lastError}. An
 * asynchronous listener has no request to answer with — it must instead leave
 * the created card intact and record the reason on the card face. These tests
 * pin the second half by driving the real services through a real transaction,
 * which is where a synchronous listener used to poison the creator's
 * transaction and resurface as {@code UnexpectedRollbackException} (a 500 on
 * card creation).
 */
@SpringBootTest
@ActiveProfiles("test")
class KanbanListenerTransactionTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfig {

        @Bean
        AgentPickerService agentPickerService() {
            return mock(AgentPickerService.class);
        }

        @Bean
        RunService runService() {
            return mock(RunService.class);
        }

        @Bean
        KanbanTransitionService kanbanTransitionService(KanbanRepository kanbanRepository,
                                                       KanbanService kanbanService,
                                                       RunService runService,
                                                       RunRepository runRepository,
                                                       AgentRepository agentRepository,
                                                       AgentPickerService agentPickerService,
                                                       AgentPickupEligibility agentPickupEligibility,
                                                       ApprovalRepository approvalRepository,
                                                       ApplicationEventPublisher eventPublisher) {
            return new KanbanTransitionService(kanbanRepository, kanbanService, runService, runRepository,
                    agentRepository, agentPickerService, agentPickupEligibility, approvalRepository,
                    eventPublisher);
        }

        @Bean
        KanbanAutoDispatchListener kanbanAutoDispatchListener(KanbanRepository kanbanRepository,
                                                             KanbanTransitionService kanbanTransitionService,
                                                             PlatformTransactionManager transactionManager) {
            return new KanbanAutoDispatchListener(kanbanRepository, kanbanTransitionService, transactionManager);
        }

        @Bean
        RunKanbanAutoCreator runKanbanAutoCreator(KanbanService kanbanService,
                                                  KanbanRepository kanbanRepository,
                                                  RunRepository runRepository) {
            return new RunKanbanAutoCreator(kanbanService, kanbanRepository, runRepository);
        }
    }

    @Autowired private KanbanService kanbanService;
    @Autowired private RunService runService;
    @Autowired private AgentPickerService agentPicker;
    @Autowired private KanbanRepository kanbanRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private ApprovalRepository approvalRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void rejectedPickupStillCreatesTheCardAndRecordsTheReason() {
        // An empty eligible pool is the asynchronous rejection the create path
        // cannot foresee: creation is the operator action that must succeed, and
        // the card face — not a 4xx — carries the reason.
        when(agentPicker.pick(any(), any(), any())).thenThrow(new PickupRejectedException(
                "NO_ELIGIBLE_AGENT", "No pickup-eligible agent: every candidate is excluded",
                Map.of("evaluated", 1)));

        KanbanItem card = createTodoCard();

        KanbanItem reloaded = kanbanRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(KanbanStatus.TODO);
        assertThat(reloaded.getLastError()).contains("NO_ELIGIBLE_AGENT");
    }

    @Test
    void aCorruptRunLinkLeavesTheCardOutOfReviewAndCreatesNoAsk() {
        // Real transaction, real review-ask listener: the parse precedes the move,
        // so a blemished card neither lands in Review nor gains an ask there.
        KanbanItem card = kanbanRepository.save(KanbanItem.builder()
                .title("blemished card")
                .status(KanbanStatus.IN_PROGRESS)
                .priority(KanbanPriority.MEDIUM)
                .linkedRunId("not-a-uuid")
                .build());

        assertThatThrownBy(() -> kanbanService.transition(card.getId(), KanbanStatus.REVIEW, null))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                        .isEqualTo("CORRUPT_RUN_LINK"));

        KanbanItem untouched = kanbanRepository.findById(card.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
        // The rejection is answered by the response, never recorded on the card.
        assertThat(untouched.getLastError()).isNull();
        assertThat(approvalRepository.findByStatusAndKanbanItemId(
                ApprovalStatus.PENDING, card.getId())).isEmpty();
    }

    @Test
    void pickupFromTheAfterCommitListenerCommitsItsOwnWork() {
        Agent eligible = agentRepository.save(agent(true));
        // The stubbed run service writes through the real repository, inside
        // dispatch()'s transaction: the row is only readable if that transaction
        // really began and committed after the card's own transaction ended.
        when(runService.createRun(any())).thenAnswer(invocation -> {
            CreateRunRequest request = invocation.getArgument(0);
            Run run = runRepository.save(Run.builder()
                    .agentId(request.getAgentId())
                    .promptSeed(request.getPromptSeed())
                    .status(RunStatus.PENDING)
                    .build());
            return RunResponse.builder()
                    .id(run.getId()).agentId(request.getAgentId())
                    .status(RunStatus.PENDING).promptSeed(request.getPromptSeed())
                    .build();
        });

        KanbanItem card = createTodoCardFor(eligible);

        assertThat(runRepository.findByAgentId(eligible.getId())).hasSize(1);
        assertThat(kanbanRepository.findById(card.getId()).orElseThrow().getStatus())
                .isEqualTo(KanbanStatus.IN_PROGRESS);
    }

    @Test
    void runStartedAutoCardIsCreatedOnlyAfterTheRunTransactionCommits() {
        UUID agentId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        UUID runId = tx.execute(status -> {
            Run run = runRepository.save(Run.builder()
                    .agentId(agentId).promptSeed("order check").status(RunStatus.PENDING).build());
            eventPublisher.publishEvent(new RunStartedEvent(this, run.getId(), agentId));

            // After-commit: the card must not exist while the run's transaction is
            // still open — the run row is the durable anchor the card points at.
            assertThat(kanbanRepository.findByLinkedRunId(run.getId().toString())).isEmpty();
            return run.getId();
        });

        assertThat(kanbanRepository.findByLinkedRunId(runId.toString())).hasSize(1);
    }

    private KanbanItem createTodoCard() {
        return new TransactionTemplate(transactionManager).execute(status ->
                kanbanService.create(CreateKanbanItemRequest.builder()
                        .title("unassigned card")
                        .status(KanbanStatus.TODO)
                        .build()));
    }

    private KanbanItem createTodoCardFor(Agent agent) {
        return new TransactionTemplate(transactionManager).execute(status ->
                kanbanService.create(CreateKanbanItemRequest.builder()
                        .title("card for " + agent.getName())
                        .status(KanbanStatus.TODO)
                        .linkedAgentId(agent.getId().toString())
                        .build()));
    }

    private static Agent agent(boolean pickupEnabled) {
        return Agent.builder()
                .id(UUID.randomUUID()).name("agent-" + UUID.randomUUID())
                .description("listener transaction test").agentType(AgentType.NATIVE).role("tester")
                .model("gpt-4o-mini").provider("openai").config("{}")
                .pickupEnabled(pickupEnabled).healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build();
    }
}

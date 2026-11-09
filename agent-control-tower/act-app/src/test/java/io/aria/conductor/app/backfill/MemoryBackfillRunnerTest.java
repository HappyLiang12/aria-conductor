package io.aria.conductor.app.backfill;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.SystemConfig;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one-time memory backfill must be deterministic, idempotent, and conservative:
 * it derives entities only from rows that already exist (agents and repository URLs),
 * never guesses a fact or a relation, and proposes a knowledge link only on an exact
 * display-name match.
 */
@ExtendWith(MockitoExtension.class)
class MemoryBackfillRunnerTest {

    @Mock
    private AgentRepository agents;

    @Mock
    private WorkflowChainRepository chains;

    @Mock
    private KnowledgeItemRepository knowledge;

    @Mock
    private MemoryEntityRepository entities;

    @Mock
    private MemoryKnowledgeLinkRepository links;

    @Mock
    private SystemConfigRepository systemConfig;

    private MemoryBackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new MemoryBackfillRunner(agents, chains, knowledge, entities, links, systemConfig, true);
    }

    @Test
    void createsOneActiveAgentEntityPerNonRetiredAgent() {
        Agent alpha = agent("Alpha", null);
        Agent beta = agent("Beta", null);
        Agent retired = agent("Retired", Instant.now());
        when(agents.findAll()).thenReturn(List.of(alpha, beta, retired));

        BackfillReport report = runner.run();

        assertThat(report.agentsCreated()).isEqualTo(2);
        assertThat(report.reposCreated()).isZero();
        assertThat(report.linksProposed()).isZero();

        List<MemoryEntity> saved = savedEntities();
        assertThat(saved).extracting(MemoryEntity::getCanonicalKey)
                .containsExactlyInAnyOrder("agent:" + alpha.getId(), "agent:" + beta.getId());
        assertThat(saved).extracting(MemoryEntity::getDisplayName)
                .containsExactlyInAnyOrder("Alpha", "Beta");
        assertThat(saved).allSatisfy(entity -> {
            assertThat(entity.getKind()).isEqualTo("AGENT");
            assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
            assertThat(entity.getOrigin()).isEqualTo(MemoryOrigin.OPERATOR);
            assertThat(entity.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);
            assertThat(entity.getId()).isNotNull();
        });
    }

    @Test
    void createsOneRepoEntityPerDistinctNormalizedUrlAcrossBothColumns() {
        when(chains.findAll()).thenReturn(List.of(
                chain("{\"repoUrl\":\"https://github.com/x/y\"}", null),
                chain("{\"issueRepo\":\"https://github.com/x/y/\"}", null),
                chain(null, "[{\"kind\":\"DEV\",\"repoUrl\":\"https://github.com/x/z\"}]"),
                chain("{\"issueRepo\":\"acme/widgets\"}", null)));

        BackfillReport report = runner.run();

        assertThat(report.reposCreated()).isEqualTo(3);
        List<MemoryEntity> saved = savedEntities();
        assertThat(saved).extracting(MemoryEntity::getCanonicalKey)
                .containsExactlyInAnyOrder(
                        "https://github.com/x/y",
                        "https://github.com/x/z",
                        "acme/widgets");
        assertThat(saved).extracting(MemoryEntity::getDisplayName)
                .containsExactlyInAnyOrder(
                        "https://github.com/x/y",
                        "https://github.com/x/z",
                        "acme/widgets");
        assertThat(saved).allSatisfy(entity -> {
            assertThat(entity.getKind()).isEqualTo("REPO");
            assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        });
    }

    @Test
    void createsNoRepoEntityWhenNoUrlIsPresent() {
        when(chains.findAll()).thenReturn(List.of(
                chain("{}", "[]"),
                chain(null, "not-json"),
                chain(null, null)));

        BackfillReport report = runner.run();

        assertThat(report.reposCreated()).isZero();
        verify(entities, never()).save(any());
    }

    @Test
    void linksKnowledgeOnlyOnAnExactDisplayNameMatch() {
        MemoryEntity operator = existingEntity("PERSON", "operator", "Operator");
        when(entities.findAll()).thenReturn(List.of(operator));
        KnowledgeItem exact = knowledge("Operator");
        KnowledgeItem longerName = knowledge("Operator Handbook");
        when(knowledge.findAll()).thenReturn(List.of(exact, longerName));

        BackfillReport report = runner.run();

        assertThat(report.linksProposed()).isEqualTo(1);
        ArgumentCaptor<MemoryKnowledgeLink> captor = ArgumentCaptor.forClass(MemoryKnowledgeLink.class);
        verify(links).save(captor.capture());
        MemoryKnowledgeLink link = captor.getValue();
        assertThat(link.getEntityId()).isEqualTo(operator.getId());
        assertThat(link.getKnowledgeItemId()).isEqualTo(exact.getId());
        assertThat(link.getStatus()).isEqualTo(MemoryLinkStatus.PROPOSED);
        assertThat(link.getCreatedAt()).isNotNull();
    }

    @Test
    void theSecondRunChangesNothing() {
        when(agents.findAll()).thenReturn(List.of(agent("A", null), agent("B", null)));
        when(chains.findAll()).thenReturn(List.of(chain("{\"repoUrl\":\"https://github.com/x/y\"}", null)));

        BackfillReport first = runner.run();
        BackfillReport second = runner.run();

        assertThat(first.agentsCreated()).isEqualTo(2);
        assertThat(first.reposCreated()).isEqualTo(1);
        assertThat(second.agentsCreated()).isZero();
        assertThat(second.reposCreated()).isZero();
        assertThat(second.linksProposed()).isZero();
        verify(entities, times(3)).save(any());
        verify(systemConfig, times(1)).save(any());
    }

    @Test
    void anAlreadyCompletedBackfillIsANoOpEvenForAFreshRunnerInstance() {
        SystemConfig completion = SystemConfig.builder()
                .configKey(MemoryBackfillRunner.COMPLETION_KEY)
                .configValue("true")
                .build();
        when(systemConfig.findByConfigKey(MemoryBackfillRunner.COMPLETION_KEY))
                .thenReturn(Optional.of(completion));

        BackfillReport report = new MemoryBackfillRunner(
                agents, chains, knowledge, entities, links, systemConfig, true).run();

        assertThat(report).isEqualTo(BackfillReport.noop());
        verify(agents, never()).findAll();
        verify(chains, never()).findAll();
        verify(entities, never()).save(any());
        verify(systemConfig, never()).save(any());
    }

    @Test
    void aRerunOnAPopulatedDatabaseWithoutTheMarkerStillCreatesNoDuplicates() {
        Agent alpha = agent("Alpha", null);
        Agent beta = agent("Beta", null);
        when(agents.findAll()).thenReturn(List.of(alpha, beta));
        when(chains.findAll()).thenReturn(List.of(chain("{\"repoUrl\":\"https://github.com/x/y\"}", null)));
        MemoryEntity existingAgent = existingEntity("AGENT", "agent:" + alpha.getId(), "Alpha");
        MemoryEntity existingRepo = existingEntity("REPO", "https://github.com/x/y", "https://github.com/x/y");
        when(entities.findAll()).thenReturn(List.of(existingAgent, existingRepo));
        KnowledgeItem alphaKnowledge = knowledge("Alpha");
        when(knowledge.findAll()).thenReturn(List.of(alphaKnowledge));
        UUID alreadyLinkedId = UUID.randomUUID();
        when(links.findAll()).thenReturn(List.of(MemoryKnowledgeLink.builder()
                .id(alreadyLinkedId)
                .entityId(existingAgent.getId())
                .knowledgeItemId(alphaKnowledge.getId())
                .status(MemoryLinkStatus.PROPOSED)
                .createdAt(Instant.now())
                .build()));

        BackfillReport report = new MemoryBackfillRunner(
                agents, chains, knowledge, entities, links, systemConfig, true).run();

        assertThat(report.agentsCreated()).as("only the agent missing from the previous pass").isEqualTo(1);
        assertThat(report.reposCreated()).isZero();
        assertThat(report.linksProposed()).as("the existing link is not inserted twice").isZero();
        verify(links, never()).save(any());
        List<MemoryEntity> saved = savedEntities();
        assertThat(saved).extracting(MemoryEntity::getCanonicalKey)
                .containsExactly("agent:" + beta.getId());
    }

    @Test
    void aDisabledRunnerWritesNothing() {
        runner = new MemoryBackfillRunner(agents, chains, knowledge, entities, links, systemConfig, false);

        BackfillReport report = runner.run();

        assertThat(report).isEqualTo(BackfillReport.noop());
        verifyNoInteractions(agents, chains, knowledge, entities, links, systemConfig);
    }

    @Test
    void aFailedPassDoesNotRecordCompletionSoTheNextBootRetries() {
        when(agents.findAll())
                .thenThrow(new DataAccessResourceFailureException("database unavailable"))
                .thenReturn(List.of(agent("A", null)));

        runner.run(new DefaultApplicationArguments(new String[0]));

        // Completion is recorded last, so a failed pass leaves no marker and is retried.
        verify(systemConfig, never()).save(any());

        BackfillReport retry = runner.run();

        assertThat(retry.agentsCreated()).as("the unmarked pass still has work to do").isEqualTo(1);
        verify(systemConfig, times(1)).save(any());
    }

    @Test
    void aDuplicateKeyFromAConcurrentBootDoesNotAbortThePass() {
        when(agents.findAll()).thenReturn(List.of(agent("Alpha", null), agent("Beta", null)));
        when(entities.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_memory_entity"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BackfillReport report = runner.run();

        assertThat(report.agentsCreated())
                .as("the row that lost the race is skipped, not written twice")
                .isEqualTo(1);
        verify(systemConfig, times(1)).save(any());
    }

    @Test
    void aDuplicateLinkFromAConcurrentBootDoesNotAbortThePass() {
        when(entities.findAll()).thenReturn(List.of(existingEntity("PERSON", "operator", "Operator")));
        when(knowledge.findAll()).thenReturn(List.of(knowledge("Operator")));
        when(links.save(any())).thenThrow(new DataIntegrityViolationException("uq_memory_knowledge_link"));

        BackfillReport report = runner.run();

        assertThat(report.linksProposed()).isZero();
        verify(systemConfig, times(1)).save(any());
    }

    @Test
    void keepsARepositoryKeyAtTheColumnLimitAndSkipsALongerOne() {
        String prefix = "https://github.com/acme/";
        String atLimit = prefix + "a".repeat(255 - prefix.length());
        String overLimit = prefix + "b".repeat(256 - prefix.length());
        assertThat(atLimit).hasSize(255);
        assertThat(overLimit).hasSize(256);
        when(chains.findAll()).thenReturn(List.of(
                chain("{\"repoUrl\":\"" + atLimit + "\"}", null),
                chain("{\"issueRepo\":\"" + overLimit + "\"}", null)));

        try (LogCapture logs = captureLog()) {
            BackfillReport report = runner.run();

            assertThat(report.reposCreated())
                    .as("canonical_key and display_name are VARCHAR(255), so only the shorter key fits")
                    .isEqualTo(1);
            List<MemoryEntity> saved = savedEntities();
            assertThat(saved).extracting(MemoryEntity::getCanonicalKey).containsExactly(atLimit);
            assertThat(saved).extracting(MemoryEntity::getDisplayName).containsExactly(atLimit);
            assertThat(logs.events()).as("the skipped key is reported, so the data loss is visible")
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("Skipping a repository key"));
        }
    }

    @Test
    void theFirstPassOverAnEmptyDatabaseDoesNotReportItselfAsAlreadyCompleted() {
        try (LogCapture logs = captureLog()) {
            runner.run(new DefaultApplicationArguments(new String[0]));

            verify(systemConfig, times(1)).save(any());
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .as("the marker was just written, so this pass did not find a completed backfill")
                    .anyMatch(message -> message.startsWith("Memory backfill complete"))
                    .noneMatch(message -> message.contains("already completed"));
        }
    }

    @Test
    void theApplicationRunnerEntryPointRunsTheBackfill() {
        when(agents.findAll()).thenReturn(List.of(agent("A", null)));

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(entities).save(any());
        verify(systemConfig).save(any());
    }

    private List<MemoryEntity> savedEntities() {
        ArgumentCaptor<MemoryEntity> captor = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(entities, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    /** Captures the runner's own log events for the duration of a try-with-resources block. */
    private static LogCapture captureLog() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MemoryBackfillRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LogCapture(logger, appender);
    }

    private record LogCapture(ch.qos.logback.classic.Logger logger, ListAppender<ILoggingEvent> appender)
            implements AutoCloseable {

        List<ILoggingEvent> events() {
            return List.copyOf(appender.list);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
        }
    }

    private static Agent agent(String name, Instant retiredAt) {
        return Agent.builder()
                .id(UUID.randomUUID())
                .name(name)
                .agentType(AgentType.ADK)
                .healthStatus(HealthStatus.HEALTHY)
                .pickupEnabled(Boolean.TRUE)
                .createdAt(Instant.now())
                .retiredAt(retiredAt)
                .build();
    }

    private static WorkflowChain chain(String templateParams, String stepsJson) {
        return WorkflowChain.builder()
                .id(UUID.randomUUID())
                .name("chain")
                .status(WorkflowChain.Status.PENDING)
                .currentStepIndex(0)
                .templateParams(templateParams)
                .stepsJson(stepsJson)
                .createdAt(Instant.now())
                .build();
    }

    private static KnowledgeItem knowledge(String name) {
        return KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(KnowledgeType.GUIDELINE)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
    }

    private static MemoryEntity existingEntity(String kind, String canonicalKey, String displayName) {
        return MemoryEntity.builder()
                .id(UUID.randomUUID())
                .kind(kind)
                .canonicalKey(canonicalKey)
                .displayName(displayName)
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .createdAt(Instant.now())
                .build();
    }
}

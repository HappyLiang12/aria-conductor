package io.aria.conductor.app.backfill;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.app.BaseH2IntegrationTest;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill against the real repositories and the real schema, not mocks: a second
 * pass over a database the first pass populated must add no entity and no link. The
 * completion marker is removed between the passes, so the durable marker is not what
 * makes the second pass a no-op -- the per-row existence checks are.
 */
class MemoryBackfillRunnerIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    private AgentRepository agents;

    @Autowired
    private WorkflowChainRepository chains;

    @Autowired
    private KnowledgeItemRepository knowledge;

    @Autowired
    private MemoryEntityRepository entities;

    @Autowired
    private MemoryKnowledgeLinkRepository links;

    @Autowired
    private SystemConfigRepository systemConfig;

    @Test
    @Transactional
    void aSecondPassOverAPopulatedDatabaseWritesNothingAndKeepsOneExactLink() {
        Agent probe = agents.save(Agent.builder()
                .id(UUID.randomUUID())
                .name("Lane D Backfill Probe Agent")
                .agentType(AgentType.ADK)
                .build());
        chains.save(WorkflowChain.builder()
                .id(UUID.randomUUID())
                .name("lane-d probe chain")
                .status(WorkflowChain.Status.PENDING)
                .currentStepIndex(0)
                .templateParams("{\"repoUrl\":\"https://github.com/probe/lane-d/\"}")
                .build());
        KnowledgeItem exact = knowledge.save(KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("Lane D Backfill Probe Agent")
                .type(KnowledgeType.GUIDELINE)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .build());
        KnowledgeItem longerName = knowledge.save(KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("Lane D Backfill Probe Agent Handbook")
                .type(KnowledgeType.GUIDELINE)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .build());

        long entitiesBefore = entities.count();
        long linksBefore = links.count();

        // The boot already ran the backfill and recorded completion; clear the marker so
        // both passes below start from an unmarked database and only the per-row checks can
        // make the second one a no-op.
        systemConfig.deleteById(MemoryBackfillRunner.COMPLETION_KEY);
        BackfillReport first = freshRunner().run();

        assertThat(knowledge.findAll()).extracting(KnowledgeItem::getName)
                .as("the probe knowledge rows are visible to the repository the runner reads")
                .contains("Lane D Backfill Probe Agent", "Lane D Backfill Probe Agent Handbook");
        assertThat(first.agentsCreated()).isEqualTo(1);
        assertThat(first.reposCreated()).isEqualTo(1);
        assertThat(first.linksProposed()).isEqualTo(1);
        assertThat(entities.count()).isEqualTo(entitiesBefore + 2);

        MemoryEntity agentEntity = activeEntity("AGENT", "agent:" + probe.getId());
        assertThat(agentEntity.getDisplayName()).isEqualTo("Lane D Backfill Probe Agent");
        assertThat(agentEntity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        assertThat(activeEntity("REPO", "https://github.com/probe/lane-d").getDisplayName())
                .isEqualTo("https://github.com/probe/lane-d");

        List<MemoryKnowledgeLink> agentLinks = links.findByEntityId(agentEntity.getId());
        assertThat(agentLinks).hasSize(1);
        MemoryKnowledgeLink link = agentLinks.get(0);
        assertThat(link.getKnowledgeItemId()).isEqualTo(exact.getId());
        assertThat(link.getStatus()).isEqualTo(MemoryLinkStatus.PROPOSED);
        assertThat(agentLinks).noneMatch(candidate -> longerName.getId().equals(candidate.getKnowledgeItemId()));

        systemConfig.deleteById(MemoryBackfillRunner.COMPLETION_KEY);
        BackfillReport second = freshRunner().run();

        assertThat(second).as("no row is created and no link is proposed again").isEqualTo(BackfillReport.noop());
        assertThat(entities.count()).isEqualTo(entitiesBefore + 2);
        assertThat(links.count()).isEqualTo(linksBefore + 1);
    }

    private MemoryBackfillRunner freshRunner() {
        return new MemoryBackfillRunner(agents, chains, knowledge, entities, links, systemConfig, true);
    }

    private MemoryEntity activeEntity(String kind, String canonicalKey) {
        return entities.findByKindAndStatus(kind, EntityStatus.ACTIVE).stream()
                .filter(entity -> canonicalKey.equals(entity.getCanonicalKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + kind + " entity " + canonicalKey));
    }
}

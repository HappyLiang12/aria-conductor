package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice for {@link WorkflowChainRepository} declared finders against real H2:
 * status filtering, template lookup, knowledge-item linkage, and case-insensitive
 * name search. First subclass of the shared {@link DataJpaTestBase} slice context.
 */
class WorkflowChainRepositorySliceIntegrationTest extends DataJpaTestBase {

    @Autowired
    WorkflowChainRepository repository;

    private WorkflowChain persist(WorkflowChain chain) {
        entityManager.persist(chain);
        return chain;
    }

    @Test
    void findByStatus_returnsOnlyChainsInThatStatus() {
        WorkflowChain running1 = persist(TestDataBuilder.aWorkflowChain()
                .withStatus(WorkflowChain.Status.RUNNING).build());
        WorkflowChain running2 = persist(TestDataBuilder.aWorkflowChain()
                .withStatus(WorkflowChain.Status.RUNNING).build());
        persist(TestDataBuilder.aWorkflowChain()
                .withStatus(WorkflowChain.Status.FAILED).build());
        persist(TestDataBuilder.aWorkflowChain()
                .withStatus(WorkflowChain.Status.CANCELLED).build());
        flushAndClear();

        assertThat(repository.findByStatus(WorkflowChain.Status.RUNNING))
                .extracting(WorkflowChain::getId)
                .containsExactlyInAnyOrder(running1.getId(), running2.getId());
        assertThat(repository.findByStatus(WorkflowChain.Status.COMPLETED)).isEmpty();
    }

    @Test
    void findByIsTemplateTrue_excludesConcreteChains() {
        WorkflowChain template = persist(TestDataBuilder.aWorkflowChain()
                .asTemplate(true)
                .withTemplateParams("{\"env\":\"prod\"}")
                .build());
        persist(TestDataBuilder.aWorkflowChain().asTemplate(false).build());
        flushAndClear();

        assertThat(repository.findByIsTemplateTrue())
                .extracting(WorkflowChain::getId)
                .containsExactly(template.getId());
    }

    @Test
    void findByKnowledgeItemId_matchesOnlyLinkedChains() {
        UUID knowledgeItemId = UUID.randomUUID();
        WorkflowChain linked1 = persist(TestDataBuilder.aWorkflowChain()
                .withKnowledgeItemId(knowledgeItemId).build());
        WorkflowChain linked2 = persist(TestDataBuilder.aWorkflowChain()
                .withKnowledgeItemId(knowledgeItemId).build());
        persist(TestDataBuilder.aWorkflowChain()
                .withKnowledgeItemId(UUID.randomUUID()).build());
        persist(TestDataBuilder.aWorkflowChain().build()); // no linkage at all
        flushAndClear();

        assertThat(repository.findByKnowledgeItemId(knowledgeItemId))
                .extracting(WorkflowChain::getId)
                .containsExactlyInAnyOrder(linked1.getId(), linked2.getId());
    }

    @Test
    void findByNameContainingIgnoreCase_matchesSubstringInAnyCase() {
        WorkflowChain upper = persist(TestDataBuilder.aWorkflowChain()
                .withName("Deploy Pipeline").build());
        WorkflowChain lower = persist(TestDataBuilder.aWorkflowChain()
                .withName("nightly deploy hotfix").build());
        persist(TestDataBuilder.aWorkflowChain().withName("Cleanup Chain").build());
        flushAndClear();

        assertThat(repository.findByNameContainingIgnoreCase("DEPLOY"))
                .extracting(WorkflowChain::getId)
                .containsExactlyInAnyOrder(upper.getId(), lower.getId());
        assertThat(repository.findByNameContainingIgnoreCase("no-such-chain")).isEmpty();
    }
}

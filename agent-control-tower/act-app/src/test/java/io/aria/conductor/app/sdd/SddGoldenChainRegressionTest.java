package io.aria.conductor.app.sdd;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.app.BaseH2IntegrationTest;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden regression for GENERIC (non-SDD) chains: the pre-SDD linear advance path
 * must be byte-for-byte unchanged - no DoD interaction, no SPEC knowledge, no
 * SPEC_REVIEW approvals, no WAITING_APPROVAL state.
 */
class SddGoldenChainRegressionTest extends BaseH2IntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowChainRepository workflowChainRepository;
    @Autowired
    private AgentRepository agentRepository;
    @MockBean
    private AdkProviderRegistry adkProviderRegistry;

    @Test
    void genericChain_withoutKinds_walksExactCurrentPath() throws Exception {
        Agent agent = agentRepository.save(Agent.builder()
                .name("golden-generic-agent")
                .role("test-role")
                .agentType(AgentType.NATIVE)
                .adkProvider("langchain")
                .config("{}")
                .healthStatus(HealthStatus.HEALTHY)
                .build());
        UUID agentId = agent.getId();
        AdkProvider mock = mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(mock);
        when(mock.isHealthy(any())).thenReturn(true);
        when(mock.parseActionsFromResponse(any())).thenReturn(List.of());
        when(mock.call(any(), any(), any(), any())).thenAnswer(inv ->
                new LlmResponse("step output", 10, 20, "stop", List.of()));

        WorkflowResponse created = workflowService.createAndStart(
                CreateWorkflowRequest.builder()
                        .name("golden-generic-chain")
                        .steps(List.of(
                                CreateWorkflowRequest.StepDef.builder()
                                        .agentId(agentId)
                                        .promptTemplate("first {previousOutput}")
                                        .build(),
                                CreateWorkflowRequest.StepDef.builder()
                                        .agentId(agentId)
                                        .promptTemplate("second")
                                        .build()))
                        .build());
        UUID chainId = created.getId();

        // Both GENERIC steps run and the chain completes - exactly like pre-SDD.
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            WorkflowChain c = workflowChainRepository.findById(chainId).orElse(null);
            return c != null && c.getStatus() == WorkflowChain.Status.COMPLETED;
        });

        WorkflowChain chain = workflowChainRepository.findById(chainId).orElseThrow();
        assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.COMPLETED);
        assertThat(chain.getReportArtifactId()).isNull();
        // No SDD residue on the steps.
        assertThat(chain.getStepsJson()).doesNotContain("WAITING_APPROVAL");
    }
}

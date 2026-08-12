package io.aria.conductor.aria.tools.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.WorkflowChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowToolHandlerTest {

    @Mock private WorkflowService workflowService;
    @Mock private AgentRepository agentRepository;
    @Mock private io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowToolHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WorkflowToolHandler(workflowService, workflowTemplateService, agentRepository, objectMapper);
    }

    @Test
    void createWorkflowShouldBuildChainFromSteps() {
        UUID devId = UUID.randomUUID();
        UUID qaId = UUID.randomUUID();
        when(agentRepository.findByName("dev-agent")).thenReturn(Optional.of(
                Agent.builder().id(devId).name("dev-agent").healthStatus(HealthStatus.HEALTHY).build()));
        when(agentRepository.findByName("qa-agent")).thenReturn(Optional.of(
                Agent.builder().id(qaId).name("qa-agent").healthStatus(HealthStatus.HEALTHY).build()));
        UUID chainId = UUID.randomUUID();
        when(workflowService.createAndStart(any())).thenReturn(WorkflowResponse.builder()
                .id(chainId).name("sdd").status(WorkflowChain.Status.RUNNING).totalSteps(2).build());

        Map<String, Object> args = new HashMap<>();
        args.put("toolName", "create_workflow");
        args.put("name", "sdd");
        args.put("steps", List.of(
                Map.of("agent", "dev-agent", "promptTemplate", "implement the feature"),
                Map.of("agent", "qa-agent", "promptTemplate", "verify {previousOutput}")));

        String result = handler.execute(args);

        assertTrue(result.contains("created and started"));
        assertTrue(result.contains(chainId.toString()));
        ArgumentCaptor<CreateWorkflowRequest> captor = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(captor.capture());
        assertEquals(2, captor.getValue().getSteps().size());
        assertEquals(devId, captor.getValue().getSteps().get(0).getAgentId());
        assertEquals(qaId, captor.getValue().getSteps().get(1).getAgentId());
    }

    @Test
    void createWorkflowMissingNameShouldError() {
        String result = handler.execute(Map.of("toolName", "create_workflow"));
        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(workflowService);
    }

    @Test
    void createWorkflowUnknownAgentShouldError() {
        when(agentRepository.findByName("ghost")).thenReturn(Optional.empty());
        when(agentRepository.findByRole("ghost")).thenReturn(List.of());
        Map<String, Object> args = new HashMap<>();
        args.put("toolName", "create_workflow");
        args.put("name", "wf");
        args.put("steps", List.of(Map.of("agent", "ghost", "promptTemplate", "do it")));

        String result = handler.execute(args);

        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("agent not found"));
        verifyNoInteractions(workflowService);
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nope"));
        assertTrue(result.startsWith("Error"));
    }
}

package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static io.aria.conductor.test.TestDataBuilder.aWorkflowChain;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowYamlControllerTest extends WebMvcTestBase {

    private static final String VALID_YAML = """
            name: deploy-flow
            steps:
              - agentId: 00000000-0000-0000-0000-000000000001
                promptTemplate: "Deploy {env}"
            """;

    private final AgentLoopEngine agentLoopEngine = mock(AgentLoopEngine.class);
    private final MockMvc mvc = mockMvcFor(new WorkflowYamlController(agentLoopEngine));

    @Test
    void executeFromYaml_validYaml_returns201WithChainSummary() throws Exception {
        UUID chainId = UUID.randomUUID();
        WorkflowChain chain = aWorkflowChain().withId(chainId).withName("deploy-flow")
                .withStatus(WorkflowChain.Status.RUNNING).withCurrentStepIndex(0).build();
        when(agentLoopEngine.executeWorkflowFromYaml(anyString(), any())).thenReturn(chain);

        mvc.perform(post("/api/v1/workflows/execute-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "yamlContent", VALID_YAML,
                                "parameters", Map.of("env", "prod")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(chainId.toString()))
                .andExpect(jsonPath("$.name").value("deploy-flow"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentStepIndex").value(0));

        // The raw YAML and the substitution map must reach the engine unmodified.
        ArgumentCaptor<String> yamlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(agentLoopEngine).executeWorkflowFromYaml(yamlCaptor.capture(), paramsCaptor.capture());
        assertThat(yamlCaptor.getValue()).isEqualTo(VALID_YAML);
        assertThat(paramsCaptor.getValue()).containsEntry("env", "prod");
    }

    @Test
    void executeFromYaml_withoutParameters_passesNullParametersThrough() throws Exception {
        WorkflowChain chain = aWorkflowChain().withStatus(WorkflowChain.Status.PENDING).build();
        when(agentLoopEngine.executeWorkflowFromYaml(anyString(), any())).thenReturn(chain);

        mvc.perform(post("/api/v1/workflows/execute-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("yamlContent", VALID_YAML))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(agentLoopEngine).executeWorkflowFromYaml(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).isNull();
    }

    @Test
    void executeFromYaml_chainWithNullName_serializesNameAsEmptyString() throws Exception {
        WorkflowChain chain = aWorkflowChain().withName(null)
                .withStatus(WorkflowChain.Status.PENDING).build();
        when(agentLoopEngine.executeWorkflowFromYaml(anyString(), any())).thenReturn(chain);

        mvc.perform(post("/api/v1/workflows/execute-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("yamlContent", VALID_YAML))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",                          // yamlContent absent
            "{\"yamlContent\":\"\"}",      // empty
            "{\"yamlContent\":\"   \"}"    // blank
    })
    void executeFromYaml_missingOrBlankYaml_returns400WithoutCallingEngine(String body)
            throws Exception {
        mvc.perform(post("/api/v1/workflows/execute-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("yamlContent is required"));
        verifyNoInteractions(agentLoopEngine);
    }

    @Test
    void executeFromYaml_yamlWithoutSteps_returns400WithEngineMessage() throws Exception {
        when(agentLoopEngine.executeWorkflowFromYaml(anyString(), any()))
                .thenThrow(new IllegalArgumentException("YAML template contains no steps"));

        mvc.perform(post("/api/v1/workflows/execute-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("yamlContent", "name: empty-flow"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("YAML template contains no steps"));
    }
}

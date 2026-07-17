package io.aria.conductor.knowledge.converter;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateConverterTest {

    @Mock
    AgentRepository agentRepository;

    private WorkflowTemplateConverter converter;

    @BeforeEach
    void setUp() {
        converter = new WorkflowTemplateConverter(agentRepository);
    }

    // ---- helpers ----

    private WorkflowChain sampleChain(String name) {
        return WorkflowChain.builder()
                .id(UUID.randomUUID())
                .name(name)
                .description("Test workflow description")
                .status(WorkflowChain.Status.COMPLETED)
                .currentStepIndex(0)
                .createdAt(Instant.now())
                .build();
    }

    private WorkflowStep sampleStep(UUID agentId, String prompt, int maxIter) {
        return WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate(prompt)
                .maxIterations(maxIter)
                .status(WorkflowStep.Status.PENDING)
                .build();
    }

    // ==================== toMarkdown ====================

    @Test
    void toMarkdown_shouldContainTitleAndSteps() {
        UUID agentId = UUID.randomUUID();
        WorkflowChain chain = sampleChain("My Workflow");
        List<WorkflowStep> steps = List.of(
                sampleStep(agentId, "Do task A", 3),
                sampleStep(agentId, "Do task B", 5)
        );

        String md = converter.workflowChainToMarkdown(chain, steps);

        assertThat(md).contains("# My Workflow");
        assertThat(md).contains("Test workflow description");
        assertThat(md).contains("## Steps");
        assertThat(md).contains("### Step 1");
        assertThat(md).contains("### Step 2");
        assertThat(md).contains("Do task A");
        assertThat(md).contains("Do task B");
        assertThat(md).contains("**Max Iterations:** 3");
        assertThat(md).contains("**Max Iterations:** 5");
    }

    @Test
    void toMarkdown_nullChain_returnsEmpty() {
        String md = converter.workflowChainToMarkdown(null, List.of());
        assertThat(md).isEmpty();
    }

    // ==================== toYaml ====================

    @Test
    void toYaml_shouldContainSchemaVersionAndSteps() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId))
                .thenReturn(Optional.of(Agent.builder().id(agentId).role("coder").build()));

        WorkflowChain chain = sampleChain("YAML Workflow");
        List<WorkflowStep> steps = List.of(
                sampleStep(agentId, "Generate code for {feature}", 3)
        );

        String yaml = converter.workflowChainToYaml(chain, steps, null);

        assertThat(yaml).contains("schema_version");
        assertThat(yaml).contains("YAML Workflow");
        assertThat(yaml).contains("steps");
        assertThat(yaml).contains("Generate code for {feature}");
        assertThat(yaml).contains("parameters");
        assertThat(yaml).contains("feature");
    }

    // ==================== yamlToSteps ====================

    @Test
    void yamlToSteps_shouldParseCorrectly() {
        UUID agentId = UUID.randomUUID();
        String yaml = """
                schema_version: "1.0"
                name: test
                steps:
                  - agent_id: "%s"
                    prompt_template: "Write tests for {module}"
                    max_iterations: 5
                  - agent_id: "%s"
                    prompt_template: "Review {module} code"
                    max_iterations: 3
                """.formatted(agentId, agentId);

        List<WorkflowStep> steps = converter.yamlToWorkflowSteps(yaml);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getAgentId()).isEqualTo(agentId);
        assertThat(steps.get(0).getPromptTemplate()).isEqualTo("Write tests for {module}");
        assertThat(steps.get(0).getMaxIterations()).isEqualTo(5);
        assertThat(steps.get(1).getPromptTemplate()).isEqualTo("Review {module} code");
    }

    @Test
    void yamlToSteps_nullYaml_returnsEmpty() {
        assertThat(converter.yamlToWorkflowSteps(null)).isEmpty();
        assertThat(converter.yamlToWorkflowSteps("")).isEmpty();
    }

    // ==================== roundTrip ====================

    @Test
    void roundTrip_mdToYamlAndBack() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId))
                .thenReturn(Optional.of(Agent.builder().id(agentId).role("reviewer").build()));

        WorkflowChain chain = sampleChain("Round Trip WF");
        List<WorkflowStep> originalSteps = List.of(
                sampleStep(agentId, "Analyze {input}", 4)
        );

        // Chain -> YAML
        String yaml = converter.workflowChainToYaml(chain, originalSteps, null);
        assertThat(yaml).isNotBlank();

        // YAML -> Steps
        List<WorkflowStep> parsedSteps = converter.yamlToWorkflowSteps(yaml);
        assertThat(parsedSteps).hasSize(1);
        assertThat(parsedSteps.get(0).getPromptTemplate()).isEqualTo("Analyze {input}");
        assertThat(parsedSteps.get(0).getMaxIterations()).isEqualTo(4);
    }

    // ==================== substituteParameters ====================

    @Test
    void substituteParameters_shouldReplacePlaceholders() {
        String template = "Deploy {service} to {environment} using {tool}";
        Map<String, String> params = Map.of(
                "service", "user-api",
                "environment", "production",
                "tool", "helm"
        );

        String result = converter.substituteParameters(template, params);

        assertThat(result).isEqualTo("Deploy user-api to production using helm");
    }

    @Test
    void substituteParameters_shouldPreservePreviousOutput() {
        String template = "Process {previousOutput} with {strategy}";
        Map<String, String> params = Map.of("strategy", "batch");

        String result = converter.substituteParameters(template, params);

        assertThat(result).contains("{previousOutput}");
        assertThat(result).contains("batch");
    }

    @Test
    void substituteParameters_nullTemplate_returnsNull() {
        assertThat(converter.substituteParameters(null, Map.of("k", "v"))).isNull();
    }

    @Test
    void substituteParameters_emptyParams_returnsOriginal() {
        String template = "Hello {name}";
        assertThat(converter.substituteParameters(template, Map.of())).isEqualTo("Hello {name}");
        assertThat(converter.substituteParameters(template, null)).isEqualTo("Hello {name}");
    }

    // ==================== mergeYamlTemplates ====================

    @Test
    void mergeYamlTemplates_shouldConcatenateSteps() {
        String yaml1 = """
                schema_version: "1.0"
                name: wf1
                steps:
                  - agent_id: "id1"
                    prompt_template: "Step A"
                    max_iterations: 3
                """;
        String yaml2 = """
                schema_version: "1.0"
                name: wf2
                steps:
                  - agent_id: "id2"
                    prompt_template: "Step B"
                    max_iterations: 5
                """;

        String merged = converter.mergeYamlTemplates(List.of(yaml1, yaml2));

        assertThat(merged).contains("merged-workflow");
        assertThat(merged).contains("Step A");
        assertThat(merged).contains("Step B");
    }

    @Test
    void mergeYamlTemplates_emptyList_returnsEmpty() {
        assertThat(converter.mergeYamlTemplates(List.of())).isEmpty();
    }

    // ==================== extractParameterNames ====================

    @Test
    void extractParameterNames_shouldFindAllPlaceholders() {
        UUID agentId = UUID.randomUUID();
        List<WorkflowStep> steps = List.of(
                sampleStep(agentId, "Deploy {service} to {environment}", 3),
                sampleStep(agentId, "Run tests on {environment} with {previousOutput}", 3)
        );

        Set<String> params = converter.extractParameterNames(steps);

        assertThat(params).contains("service", "environment");
        assertThat(params).doesNotContain("previousOutput");
    }

    @Test
    void extractParameterNames_emptySteps_returnsEmpty() {
        assertThat(converter.extractParameterNames(List.of())).isEmpty();
        assertThat(converter.extractParameterNames(null)).isEmpty();
    }
}

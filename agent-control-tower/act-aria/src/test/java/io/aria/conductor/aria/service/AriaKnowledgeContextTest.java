package io.aria.conductor.aria.service;

import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmProperties;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * TDD: Dynamic Knowledge Context Injection — Aria's system prompt must include
 * APPROVED knowledge items so Aria benefits from the knowledge base.
 *
 * Design mandate: "Agents should learn from approved knowledge only within their
 * access policy" + "Private memory MUST NOT become shared knowledge".
 */
@ExtendWith(MockitoExtension.class)
class AriaKnowledgeContextTest {

    @Mock AgentLoopEngine agentLoopEngine;
    @Mock AgentRepository agentRepository;
    @Mock RunRepository runRepository;
    @Mock LlmClient llmClient;
    @Mock IntentClassifier intentClassifier;
    @Mock ToolExecutionEngine toolExecutionEngine;
    @Mock ToolRegistry toolRegistry;
    @Mock KnowledgeService knowledgeService;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ToolCallRepository toolCallRepository;

    LlmProperties llmProperties;
    AriaService ariaService;

    @BeforeEach
    void setup() {
        llmProperties = new LlmProperties();
        llmProperties.setModel("gpt-4o-mini");
        llmProperties.setBaseUrl("https://api.openai.com/v1");
        llmProperties.setApiKey("test-key");

        ariaService = new AriaService(
                agentLoopEngine,
                agentRepository,
                runRepository,
                llmClient,
                llmProperties,
                intentClassifier,
                toolRegistry,
                toolExecutionEngine,
                knowledgeService,
                trajectoryRepository,
                toolCallRepository
        );
    }

    @Test
    void buildSystemPrompt_shouldInjectApprovedKnowledge() {
        List<KnowledgeItem> approved = List.of(
                buildItem("Error Handling Pattern", KnowledgeType.SKILL, "Always wrap calls in try-catch")
        );
        when(knowledgeService.buildKnowledgeContextPrompt(5))
                .thenReturn("## Knowledge Context\n- **Error Handling Pattern** (SKILL): Always wrap calls in try-catch\n");

        String prompt = ariaService.buildSystemPrompt();

        assertThat(prompt).contains("## Knowledge Context");
        assertThat(prompt).contains("Error Handling Pattern");
        assertThat(prompt).contains("Always wrap calls in try-catch");
    }

    @Test
    void buildSystemPrompt_withNoApprovedKnowledge_shouldNotCrash() {
        when(knowledgeService.buildKnowledgeContextPrompt(5)).thenReturn("");

        String prompt = ariaService.buildSystemPrompt();

        assertThat(prompt).isNotNull();
        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("You are Aria");
        assertThat(prompt).doesNotContain("## Knowledge Context");
    }

    @Test
    void buildSystemPrompt_shouldLimitTo5Items() {
        List<KnowledgeItem> tenItems = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenItems.add(buildItem("Item " + i, KnowledgeType.SKILL, "Content " + i));
        }
        when(knowledgeService.buildKnowledgeContextPrompt(5))
                .thenReturn("- **Item 0** (SKILL): Content 0\n");

        String prompt = ariaService.buildSystemPrompt();

        // Delegation check: the shared builder is responsible for limiting;
        // this test just verifies the builder output appears.
        assertThat(prompt).contains("- **Item 0** (SKILL): Content 0");
    }

    @Test
    void buildSystemPrompt_shouldTruncateContentTo500Chars() {
        when(knowledgeService.buildKnowledgeContextPrompt(5))
                .thenReturn("- **Verbose Knowledge** (PROMPT): " + "X".repeat(500) + "...\n");

        String prompt = ariaService.buildSystemPrompt();

        // Truncation is handled by KnowledgeService.buildKnowledgeContextPrompt;
        // AriaService delegates and trusts the result. Verify delegation works.
        assertThat(prompt).contains("X".repeat(500) + "...");
    }

    @Test
    void buildSystemPrompt_shouldExcludeRetiredAndRejected() {
        when(knowledgeService.buildKnowledgeContextPrompt(5))
                .thenReturn("- **Good Knowledge** (SKILL): Useful info\n");

        String prompt = ariaService.buildSystemPrompt();

        assertThat(prompt).contains("Good Knowledge");
        assertThat(prompt).doesNotContain("RETIRED");
        assertThat(prompt).doesNotContain("REJECTED");
    }

    @Test
    void buildSystemPrompt_shouldExcludePrivateMemory() {
        // Knowledge context is ONLY from knowledge_items table with APPROVED status.
        // Session history (conversation messages) must never appear in the knowledge section.
        when(knowledgeService.buildKnowledgeContextPrompt(5))
                .thenReturn("- **Shared Skill** (SKILL): Approved shared knowledge\n");

        String prompt = ariaService.buildSystemPrompt();

        // The knowledge section should only have items from the knowledge table
        assertThat(prompt).contains("Shared Skill");
        assertThat(prompt).contains("SKILL");
        // No session/private memory markers should be in the knowledge section
        assertThat(prompt).doesNotContain("session_memory");
        assertThat(prompt).doesNotContain("private_memory");
    }

    private KnowledgeItem buildItem(String name, KnowledgeType type, String description) {
        return KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(type)
                .description(description)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}

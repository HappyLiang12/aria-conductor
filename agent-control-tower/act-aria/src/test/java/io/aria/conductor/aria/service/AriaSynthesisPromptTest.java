package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmProperties;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One-click synthesis prompt composition (Feature B4):
 * {@link AriaService#composeSynthesisPrompt(String, UUID)} resolves the dispatch
 * group (explicit id or the conversation's latest group) and renders the
 * server-side template verbatim.
 */
@ExtendWith(MockitoExtension.class)
class AriaSynthesisPromptTest {

    @Mock AgentLoopEngine agentLoopEngine;
    @Mock AgentRepository agentRepository;
    @Mock RunRepository runRepository;
    @Mock LlmClient llmClient;
    @Mock IntentClassifier intentClassifier;
    @Mock ToolRegistry toolRegistry;
    @Mock ToolExecutionEngine toolExecutionEngine;
    @Mock KnowledgeService knowledgeService;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ToolCallRepository toolCallRepository;

    private AriaService ariaService;

    @BeforeEach
    void setUp() {
        ariaService = new AriaService(agentLoopEngine, agentRepository, runRepository,
                llmClient, new LlmProperties(), intentClassifier, toolRegistry, toolExecutionEngine,
                knowledgeService, trajectoryRepository, toolCallRepository);
    }

    /** The template's fixed header and tail, verbatim from the plan. */
    private static final String HEADER =
            "以下子任務已完成，請彙整結果並給我建議報告與下一步：";
    private static final String TAIL =
            "請先讀取需要的子任務結果（用你的 run 工具），以繁體中文輸出：完成/失敗統計、各子任務重點、整體建議、仍無法核實之事項。";

    @Test
    void composeSynthesisPrompt_listsTheLatestGroupsChildrenInCreatedAtOrder() {
        Instant base = Instant.parse("2026-10-03T10:00:00Z");
        UUID parentId = UUID.randomUUID();
        Run parent = Run.builder().id(parentId).conversationId("conv-1")
                .status(RunStatus.COMPLETED).createdAt(base).build();
        UUID firstId = UUID.randomUUID();
        Run first = child(firstId, RunStatus.COMPLETED, base.plusSeconds(1));
        UUID failedId = UUID.randomUUID();
        Run failed = child(failedId, RunStatus.FAILED, base.plusSeconds(2));
        UUID lastId = UUID.randomUUID();
        Run last = child(lastId, RunStatus.COMPLETED, base.plusSeconds(3));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(parent));
        // The finder is unordered; the composer must sort by createdAt itself.
        when(runRepository.findByDispatchedByRunId(parentId))
                .thenReturn(List.of(failed, last, first));

        Optional<String> prompt = ariaService.composeSynthesisPrompt("conv-1", null);

        assertThat(prompt).contains(HEADER + "\n"
                + "- run " + firstId + "：COMPLETED\n"
                + "- run " + failedId + "：FAILED\n"
                + "- run " + lastId + "：COMPLETED\n"
                + TAIL);
    }

    @Test
    void composeSynthesisPrompt_picksTheNewestRunWithChildrenWhenNoIdIsGiven() {
        Instant base = Instant.parse("2026-10-03T10:00:00Z");
        UUID olderParentId = UUID.randomUUID();
        Run olderParent = Run.builder().id(olderParentId).conversationId("conv-1")
                .status(RunStatus.COMPLETED).createdAt(base).build();
        UUID plainRunId = UUID.randomUUID();
        Run plainRun = Run.builder().id(plainRunId).conversationId("conv-1")
                .status(RunStatus.COMPLETED).createdAt(base.plusSeconds(1)).build();
        UUID newerParentId = UUID.randomUUID();
        Run newerParent = Run.builder().id(newerParentId).conversationId("conv-1")
                .status(RunStatus.COMPLETED).createdAt(base.plusSeconds(2)).build();
        UUID childId = UUID.randomUUID();
        Run child = child(childId, RunStatus.COMPLETED, base.plusSeconds(3));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(olderParent, plainRun, newerParent));
        when(runRepository.findByDispatchedByRunId(newerParentId)).thenReturn(List.of(child));

        Optional<String> prompt = ariaService.composeSynthesisPrompt("conv-1", null);

        assertThat(prompt).contains(HEADER + "\n"
                + "- run " + childId + "：COMPLETED\n"
                + TAIL);
        // Newest-first walk: older runs are never probed once the group is found.
        verify(runRepository, never()).findByDispatchedByRunId(olderParentId);
        verify(runRepository, never()).findByDispatchedByRunId(plainRunId);
    }

    @Test
    void composeSynthesisPrompt_usesTheExplicitDispatchedByRunIdWhenGiven() {
        Instant base = Instant.parse("2026-10-03T10:00:00Z");
        UUID explicitId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Run child = child(childId, RunStatus.FAILED, base);
        when(runRepository.findByDispatchedByRunId(explicitId)).thenReturn(List.of(child));

        Optional<String> prompt = ariaService.composeSynthesisPrompt("conv-1", explicitId);

        assertThat(prompt).contains(HEADER + "\n"
                + "- run " + childId + "：FAILED\n"
                + TAIL);
        // An explicit group id needs no conversation scan.
        verify(runRepository, never()).findByConversationIdOrderByCreatedAtAsc(anyString());
    }

    @Test
    void composeSynthesisPrompt_isEmptyWhenTheConversationIsUnknown() {
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("ghost")).thenReturn(List.of());

        assertThat(ariaService.composeSynthesisPrompt("ghost", null)).isEmpty();
        verify(runRepository, never()).findByDispatchedByRunId(any());
    }

    @Test
    void composeSynthesisPrompt_isEmptyWhenNoRunDispatchedChildren() {
        UUID runId = UUID.randomUUID();
        Run run = Run.builder().id(runId).conversationId("conv-1")
                .status(RunStatus.COMPLETED).createdAt(Instant.now()).build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1")).thenReturn(List.of(run));
        when(runRepository.findByDispatchedByRunId(runId)).thenReturn(List.of());

        assertThat(ariaService.composeSynthesisPrompt("conv-1", null)).isEmpty();
    }

    @Test
    void composeSynthesisPrompt_isEmptyWhenTheExplicitIdHasNoChildren() {
        UUID explicitId = UUID.randomUUID();
        when(runRepository.findByDispatchedByRunId(explicitId)).thenReturn(List.of());

        assertThat(ariaService.composeSynthesisPrompt("conv-1", explicitId)).isEmpty();
    }

    /** Dispatched children carry no conversationId by design — only the parent resolves the conversation. */
    private static Run child(UUID id, RunStatus status, Instant createdAt) {
        return Run.builder()
                .id(id)
                .agentId(AriaConstants.ARIA_AGENT_ID)
                .status(status)
                .createdAt(createdAt)
                .build();
    }
}

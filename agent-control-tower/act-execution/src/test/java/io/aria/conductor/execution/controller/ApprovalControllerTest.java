package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static io.aria.conductor.test.TestDataBuilder.anApproval;
import static io.aria.conductor.test.TestDataBuilder.aToolCall;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalControllerTest extends WebMvcTestBase {

    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final ApprovalGate approvalGate = mock(ApprovalGate.class);
    private final ToolCallRepository toolCallRepository = mock(ToolCallRepository.class);
    private final ToolRiskResolver toolRiskResolver = mock(ToolRiskResolver.class);
    private final MockMvc mvc = mockMvcFor(new ApprovalController(
            approvalRepository, approvalGate, toolCallRepository, toolRiskResolver));

    @Test
    void listApprovals_enrichesApprovalsWithToolNameAndRiskTier() throws Exception {
        UUID toolCallId = UUID.randomUUID();
        Approval withTool = anApproval().withToolCallId(toolCallId).withReason("push gate").build();
        Approval withoutTool = anApproval().build(); // toolCallId null → no enrichment
        ToolCall toolCall = aToolCall().withId(toolCallId)
                .withToolName("git_push").withArguments("{\"remote\":\"origin\"}").build();

        when(approvalRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(withTool, withoutTool)));
        when(toolCallRepository.findAllById(List.of(toolCallId))).thenReturn(List.of(toolCall));
        when(toolRiskResolver.resolve("git_push")).thenReturn(RiskTier.PUSH);

        mvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(withTool.getId().toString()))
                .andExpect(jsonPath("$[0].toolName").value("git_push"))
                .andExpect(jsonPath("$[0].arguments").value("{\"remote\":\"origin\"}"))
                .andExpect(jsonPath("$[0].riskTier").value("PUSH"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].id").value(withoutTool.getId().toString()))
                .andExpect(jsonPath("$[1].toolName").isEmpty())
                .andExpect(jsonPath("$[1].riskTier").isEmpty());
    }

    @Test
    void listApprovals_batchLoadsDistinctToolCallIds_avoidingNPlusOne() throws Exception {
        UUID sharedToolCallId = UUID.randomUUID();
        Approval first = anApproval().withToolCallId(sharedToolCallId).build();
        Approval second = anApproval().withToolCallId(sharedToolCallId).build();
        when(approvalRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second)));
        when(toolCallRepository.findAllById(List.of(sharedToolCallId)))
                .thenReturn(List.of(aToolCall().withId(sharedToolCallId).withToolName("read_file").build()));
        when(toolRiskResolver.resolve("read_file")).thenReturn(RiskTier.READ);

        mvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolName").value("read_file"))
                .andExpect(jsonPath("$[1].toolName").value("read_file"));

        // Duplicate toolCallIds must be collapsed into a single batch lookup.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> idsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(toolCallRepository).findAllById(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(sharedToolCallId);
    }

    @Test
    void listApprovals_noApprovals_returnsEmptyArray() throws Exception {
        when(approvalRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listApprovals_noStatus_returnsAllIncludingDecided() throws Exception {
        Approval pending = anApproval().build();
        Approval approved = anApproval().withStatus(ApprovalStatus.APPROVED).build();
        when(approvalRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pending, approved)));

        mvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("APPROVED"));
        verify(approvalRepository, never()).findByStatus(any());
    }

    @Test
    void listApprovals_pendingStatus_filtersOnly() throws Exception {
        Approval pending = anApproval().build();
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(pending));

        mvc.perform(get("/api/v1/approvals").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        verify(approvalRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listApprovals_kanbanItemIdParam_routesToPerCardFinder() throws Exception {
        String cardId = "card-1";
        Approval ask = anApproval().withReason("what format do you want?").build();
        when(approvalRepository.findByKanbanItemId(cardId)).thenReturn(List.of(ask));

        mvc.perform(get("/api/v1/approvals").param("kanbanItemId", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("what format do you want?"));

        verify(approvalRepository).findByKanbanItemId(cardId);
        verify(approvalRepository, never()).findByStatus(any());
        verify(approvalRepository, never()).findAll(any(Pageable.class));
    }

    /**
     * HITL asks round-trip their ask fields through {@code toDetail}: the Review
     * panel renders the QUESTION prompt, options and recorded answer from the
     * {@code GET /api/v1/approvals?kanbanItemId=} payload.
     */
    @Test
    void listApprovals_kanbanItemIdParam_returnsQuestionAskFields() throws Exception {
        String cardId = "card-ask";
        Approval ask = anApproval().withReason("which export format?").build();
        ask.setAskType(Approval.AskType.QUESTION);
        ask.setKanbanItemId(cardId);
        ask.setContextMd("### Context\nPick the format for the weekly report.");
        ask.setOptionsJson("[{\"label\":\"CSV\"},{\"label\":\"XLSX\"}]");
        ask.setAnswer("CSV");
        when(approvalRepository.findByKanbanItemId(cardId)).thenReturn(List.of(ask));

        mvc.perform(get("/api/v1/approvals").param("kanbanItemId", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kanbanItemId").value(cardId))
                .andExpect(jsonPath("$[0].askType").value("QUESTION"))
                .andExpect(jsonPath("$[0].contextMd")
                        .value("### Context\nPick the format for the weekly report."))
                .andExpect(jsonPath("$[0].optionsJson")
                        .value("[{\"label\":\"CSV\"},{\"label\":\"XLSX\"}]"))
                .andExpect(jsonPath("$[0].answer").value("CSV"));
    }

    /** QUESTION ask: the only ask type whose approved/denied flag may be set via /answer. */
    private Approval questionAsk(UUID id) {
        Approval approval = anApproval().withId(id).build();
        approval.setAskType(Approval.AskType.QUESTION);
        return approval;
    }

    @Test
    void answer_recordsAnswerAndApprovalDecision() throws Exception {
        UUID id = UUID.randomUUID();
        Approval approval = questionAsk(id);
        when(approvalRepository.findById(id)).thenReturn(Optional.of(approval));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "CSV is fine", "approved", true, "reason", "ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.answer").value("CSV is fine"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedAt").exists())
                .andExpect(jsonPath("$.reason").value("ok"));
    }

    @Test
    void answer_deny_marksDenied() throws Exception {
        UUID id = UUID.randomUUID();
        Approval approval = questionAsk(id);
        when(approvalRepository.findById(id)).thenReturn(Optional.of(approval));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "not this one", "approved", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.decidedAt").exists());
    }

    @Test
    void answer_freeTextWithoutDecision_keepsStatusPending() throws Exception {
        UUID id = UUID.randomUUID();
        Approval approval = anApproval().withId(id).build();
        when(approvalRepository.findById(id)).thenReturn(Optional.of(approval));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "use semicolons"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("use semicolons"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.decidedAt").isEmpty());
    }

    @Test
    void answer_answerOnlyOnQuestionAsk_passes() throws Exception {
        UUID id = UUID.randomUUID();
        Approval approval = questionAsk(id);
        when(approvalRepository.findById(id)).thenReturn(Optional.of(approval));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "option B please"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("option B please"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void answer_alreadyDecided_returns400() throws Exception {
        UUID id = UUID.randomUUID();
        Approval decided = anApproval().withId(id).withStatus(ApprovalStatus.DENIED).build();
        when(approvalRepository.findById(id)).thenReturn(Optional.of(decided));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "late answer"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Approval already decided: DENIED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL", "REVIEW_REQUEST"})
    void answer_approvedFlagOnGateAsk_returns400(String askType) throws Exception {
        UUID id = UUID.randomUUID();
        Approval approval = anApproval().withId(id).build();
        approval.setAskType(Approval.AskType.valueOf(askType));
        when(approvalRepository.findById(id)).thenReturn(Optional.of(approval));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Only QUESTION asks are answerable here; gate approvals must use /decide"));
    }

    @Test
    void answer_unknownId_returns400() throws Exception {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Approval not found: " + id));
    }

    @Test
    void getApproval_returns200WithEnrichedDetail() throws Exception {
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID toolCallId = UUID.randomUUID();
        Approval approval = anApproval().withId(id).withRunId(runId)
                .withToolCallId(toolCallId).withReason("destructive op").build();
        when(approvalRepository.findById(id)).thenReturn(Optional.of(approval));
        when(toolCallRepository.findById(toolCallId)).thenReturn(Optional.of(
                aToolCall().withId(toolCallId).withToolName("delete_branch").withArguments("{}").build()));
        when(toolRiskResolver.resolve("delete_branch")).thenReturn(RiskTier.DESTRUCTIVE);

        mvc.perform(get("/api/v1/approvals/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.reason").value("destructive op"))
                .andExpect(jsonPath("$.toolName").value("delete_branch"))
                .andExpect(jsonPath("$.riskTier").value("DESTRUCTIVE"));
    }

    @Test
    void getApproval_withoutToolCall_returnsDetailWithNullEnrichment() throws Exception {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findById(id))
                .thenReturn(Optional.of(anApproval().withId(id).build()));

        mvc.perform(get("/api/v1/approvals/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.toolName").isEmpty())
                .andExpect(jsonPath("$.riskTier").isEmpty());
        verifyNoInteractions(toolCallRepository, toolRiskResolver);
    }

    @Test
    void getApproval_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/approvals/" + id))
                .andExpect(status().isNotFound());
        verify(approvalRepository).findById(id);
    }

    @Test
    void decideApproval_approve_passesParsedArgumentsToGate() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "looks safe"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(id.toString()))
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.status").value("processed"));

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalGate).decideApproval(eq(id), approvedCaptor.capture(), reasonCaptor.capture());
        assertThat(approvedCaptor.getValue()).isTrue();
        assertThat(reasonCaptor.getValue()).isEqualTo("looks safe");
    }

    @Test
    void decideApproval_deny_propagatesFalseDecision() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", false, "reason", "too risky"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.status").value("processed"));

        verify(approvalGate).decideApproval(id, false, "too risky");
    }

    @Test
    void decideApproval_unknownId_returns400WithGateError() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalArgumentException("Approval not found: " + id))
                .when(approvalGate).decideApproval(id, true, "ok");

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "ok"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Approval not found: " + id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{\"approved\":"})
    void decideApproval_malformedBody_returns400WithoutTouchingGate(String body) throws Exception {
        mvc.perform(post("/api/v1/approvals/" + UUID.randomUUID() + "/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(approvalGate);
    }
}

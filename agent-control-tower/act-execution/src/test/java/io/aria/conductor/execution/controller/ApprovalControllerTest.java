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
import org.springframework.http.HttpHeaders;
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
    private final io.aria.conductor.execution.approval.PermissionCoordinator permissionCoordinator =
            mock(io.aria.conductor.execution.approval.PermissionCoordinator.class);
    private final io.aria.conductor.execution.security.OperatorSessionService operatorSessions =
            mock(io.aria.conductor.execution.security.OperatorSessionService.class);
    private final io.aria.conductor.execution.security.ActorTokenService actorTokens =
            mock(io.aria.conductor.execution.security.ActorTokenService.class);
    private final io.aria.conductor.execution.repository.SessionTrajectoryRepository trajectoryRepository =
            mock(io.aria.conductor.execution.repository.SessionTrajectoryRepository.class);
    /**
     * The real coordinator behind /answer's wake: a test that parks the run first
     * (via {@code requestInput}) gets the true answered-wake behavior; one that
     * does not gets the true not-waiting refusal.
     */
    private final io.aria.conductor.execution.runtime.RunInputCoordinator runInputs =
            new io.aria.conductor.execution.runtime.RunInputCoordinator(event -> { });
    /** The shared authority resolver wired around the same mocked services. */
    private final io.aria.conductor.execution.security.OperatorAuthorityResolver operatorAuthority =
            new io.aria.conductor.execution.security.OperatorAuthorityResolver(
                    operatorSessions, actorTokens, "");
    private final MockMvc mvc = mockMvcFor(new ApprovalController(
            approvalRepository, approvalGate, toolCallRepository, toolRiskResolver,
            permissionCoordinator, operatorSessions, operatorAuthority,
            trajectoryRepository, runInputs, event -> { }));

    /** The configured operator bearer credential the boundary verifies. */
    private static final String OPERATOR_AUTHORIZATION = "Bearer operator-credential-1";
    private static final String WORKER_AUTHORIZATION = "Bearer run-scoped-worker-token";

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

    // ---------------------------------------------------------------------
    // CLARIFICATION asks (2026-10-05 spec §5): the answer settles the ask and
    // wakes the parked run; a run not parked in this process answers 409.
    // These routes are deliberately NOT operator-gated (spec D6).
    // ---------------------------------------------------------------------

    /** A CLARIFICATION ask exactly as the engine's waiting-input bookkeeping creates it. */
    private Approval clarificationAsk(UUID id, UUID runId) {
        return Approval.builder()
                .id(id).runId(runId)
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .askType(Approval.AskType.QUESTION)
                .source(io.aria.conductor.common.model.ApprovalSource.CLARIFICATION)
                .content("Which database should the migration target?")
                .build();
    }

    @Test
    void answer_clarificationSettlesTheAskAndWakesTheParkedRun() throws Exception {
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Approval ask = clarificationAsk(id, runId);
        when(approvalRepository.findById(id)).thenReturn(Optional.of(ask));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(2);
        // Park the run first: the coordinator now truly holds it in WAITING_INPUT.
        java.util.concurrent.CompletableFuture<io.aria.conductor.execution.runtime.RunInputCoordinator.OperatorInput>
                parked = runInputs.requestInput(runId, "Which database should the migration target?");

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "postgres", "approved", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.answer").value("postgres"))
                .andExpect(jsonPath("$.reason").value("postgres"))
                .andExpect(jsonPath("$.decidedAt").exists());

        // The parked run thread actually received the operator's answer.
        io.aria.conductor.execution.runtime.RunInputCoordinator.OperatorInput input = parked.join();
        org.assertj.core.api.Assertions.assertThat(input.answer()).isEqualTo("postgres");
        org.assertj.core.api.Assertions.assertThat(input.finalizeRequested()).isFalse();
    }

    @Test
    void answer_clarificationOnAnUnparkedRun_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        Approval ask = clarificationAsk(id, UUID.randomUUID());
        when(approvalRepository.findById(id)).thenReturn(Optional.of(ask));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "postgres", "approved", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Run " + ask.getRunId()
                        + " is not waiting for operator input"));
    }

    @Test
    void answer_clarificationDenyIsRefused() throws Exception {
        UUID id = UUID.randomUUID();
        Approval ask = clarificationAsk(id, UUID.randomUUID());
        when(approvalRepository.findById(id)).thenReturn(Optional.of(ask));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "no", "approved", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Deny a waiting run via POST /runs/{id}/finalize, not /answer"));
    }

    @Test
    void answer_clarificationBlankAnswerIsRefused() throws Exception {
        UUID id = UUID.randomUUID();
        Approval ask = clarificationAsk(id, UUID.randomUUID());
        when(approvalRepository.findById(id)).thenReturn(Optional.of(ask));

        mvc.perform(post("/api/v1/approvals/" + id + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "   ", "approved", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("An answer is required to continue a run waiting for input"));
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

    /**
     * The decision route is operator-only. A legacy (gate) approval keeps its
     * existing decision semantics once the operator credential is presented.
     */
    @Test
    void decideApproval_operatorCredential_approvesThroughTheGate() throws Exception {
        UUID id = UUID.randomUUID();
        when(operatorSessions.verifyOperatorCredential(OPERATOR_AUTHORIZATION)).thenReturn(true);
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(false);

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_AUTHORIZATION)
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
        verify(permissionCoordinator).isNativePermissionRequest(id);
        verify(permissionCoordinator, never()).decide(any(), any(), any());
    }

    @Test
    void decideApproval_operatorCredential_deny_propagatesFalseDecision() throws Exception {
        UUID id = UUID.randomUUID();
        when(operatorSessions.verifyOperatorCredential(OPERATOR_AUTHORIZATION)).thenReturn(true);
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(false);

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", false, "reason", "too risky"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.status").value("processed"));

        verify(approvalGate).decideApproval(id, false, "too risky");
    }

    /** A native permission ask is dispatched to the coordinator, never the gate. */
    @Test
    void decideApproval_nativePermissionRequest_routesToTheCoordinator() throws Exception {
        UUID id = UUID.randomUUID();
        when(operatorSessions.verifyOperatorCredential(OPERATOR_AUTHORIZATION)).thenReturn(true);
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(true);

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "allow once"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        verify(permissionCoordinator).decide(id,
                io.aria.conductor.execution.approval.PermissionChoice.ALLOW_ONCE,
                io.aria.conductor.common.security.ActorPrincipal.operator(null));
        verifyNoInteractions(approvalGate);
    }

    @Test
    void decideApproval_nativePermissionRequest_deny_routesTheDenyChoice() throws Exception {
        UUID id = UUID.randomUUID();
        when(operatorSessions.verifyOperatorCredential(OPERATOR_AUTHORIZATION)).thenReturn(true);
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(true);

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", false, "reason", "no"))))
                .andExpect(status().isOk());

        verify(permissionCoordinator).decide(id,
                io.aria.conductor.execution.approval.PermissionChoice.DENY,
                io.aria.conductor.common.security.ActorPrincipal.operator(null));
        verifyNoInteractions(approvalGate);
    }

    /**
     * An anonymous caller from a NON-loopback client is still 401. (From
     * loopback an anonymous request is the local operator — see
     * {@link #loopbackAnonymousDecisionIsAuthorized}.) MockMvc's default peer
     * is loopback, so the address is pinned explicitly here.
     */
    @Test
    void decideApproval_withoutAnyOperatorIdentity_returns401() throws Exception {
        mvc.perform(post("/api/v1/approvals/" + UUID.randomUUID() + "/decide")
                        .with(request -> { request.setRemoteAddr("203.0.113.7"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "ok"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Operator session required"));

        verifyNoInteractions(approvalGate, permissionCoordinator);
    }

    /** A verified worker credential is authenticated but never operator-authorized. */
    @Test
    void decideApproval_workerCredential_returns403() throws Exception {
        UUID runId = UUID.randomUUID();
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION))
                .thenReturn(Optional.of(io.aria.conductor.common.security.ActorPrincipal.worker(
                        runId, java.time.Instant.parse("2026-09-22T12:10:00Z"))));

        mvc.perform(post("/api/v1/approvals/" + UUID.randomUUID() + "/decide")
                        .header(HttpHeaders.AUTHORIZATION, WORKER_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "self-approval"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Operator authority required"));

        verifyNoInteractions(approvalGate, permissionCoordinator);
    }

    /** A fresh PENDING (non-native) gate approval id: the decide routes through the gate. */
    private UUID pendingApprovalId() {
        UUID id = UUID.randomUUID();
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(false);
        return id;
    }

    /**
     * The local operator needs no credential at all: an anonymous request from
     * loopback IS the single local operator (2026-10-05 local authority
     * simplification), so the decision succeeds with no Authorization header.
     */
    @Test
    void loopbackAnonymousDecisionIsAuthorized() throws Exception {
        mvc.perform(post("/api/v1/approvals/{id}/decide", pendingApprovalId())
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                        .contentType("application/json")
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk());
    }

    /**
     * A resolvable worker bearer is never promoted to operator -- not even from
     * loopback (an unresolvable token is "no identity" and falls through to the
     * loopback rule instead, so the worker token must be one the resolver can
     * verify for this test to pin the 403 rule).
     */
    @Test
    void workerBearerDecisionRemainsForbidden() throws Exception {
        when(actorTokens.resolveBearer("Bearer worker-token"))
                .thenReturn(Optional.of(io.aria.conductor.common.security.ActorPrincipal.worker(
                        UUID.randomUUID(), java.time.Instant.parse("2026-09-22T12:10:00Z"))));
        mvc.perform(post("/api/v1/approvals/{id}/decide", pendingApprovalId())
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                        .header("Authorization", "Bearer worker-token")
                        .contentType("application/json")
                        .content("{\"approved\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void decideApproval_operatorSessionWithoutCsrf_returns403() throws Exception {
        UUID id = UUID.randomUUID();
        io.aria.conductor.execution.security.OperatorSessionService.OperatorSession session =
                new io.aria.conductor.execution.security.OperatorSessionService.OperatorSession(
                        "session-1", "csrf-1", java.time.Instant.parse("2026-09-22T20:00:00Z"));
        when(operatorSessions.findSession("session-1")).thenReturn(Optional.of(session));
        doThrow(new io.aria.conductor.execution.security.OperatorSessionService.ForbiddenMutationException(
                "CSRF validation failed"))
                .when(operatorSessions).validateMutation(session, null, null);

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .cookie(new jakarta.servlet.http.Cookie(
                                io.aria.conductor.execution.security.OperatorSessionService.COOKIE_NAME, "session-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "ok"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF validation failed"));

        verifyNoInteractions(approvalGate, permissionCoordinator);
    }

    @Test
    void decideApproval_operatorSessionWithValidMutation_decidesAsTheSessionOperator() throws Exception {
        UUID id = UUID.randomUUID();
        io.aria.conductor.execution.security.OperatorSessionService.OperatorSession session =
                new io.aria.conductor.execution.security.OperatorSessionService.OperatorSession(
                        "session-2", "csrf-2", java.time.Instant.parse("2026-09-22T20:00:00Z"));
        when(operatorSessions.findSession("session-2")).thenReturn(Optional.of(session));
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(false);

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .cookie(new jakarta.servlet.http.Cookie(
                                io.aria.conductor.execution.security.OperatorSessionService.COOKIE_NAME, "session-2"))
                        .header(io.aria.conductor.execution.security.OperatorSessionService.CSRF_HEADER, "csrf-2")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "ok"))))
                .andExpect(status().isOk());

        verify(approvalGate).decideApproval(id, true, "ok");
        verify(permissionCoordinator).isNativePermissionRequest(id);
        verify(permissionCoordinator, never()).decide(any(), any(), any());
    }

    @Test
    void decideApproval_unknownId_returns400WithGateError() throws Exception {
        UUID id = UUID.randomUUID();
        when(operatorSessions.verifyOperatorCredential(OPERATOR_AUTHORIZATION)).thenReturn(true);
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(false);
        doThrow(new IllegalArgumentException("Approval not found: " + id))
                .when(approvalGate).decideApproval(id, true, "ok");

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "ok"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Approval not found: " + id));
    }

    /** A settled or expired native ask is refused: nothing is delivered. */
    @Test
    void decideApproval_settledNativeRequest_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(operatorSessions.verifyOperatorCredential(OPERATOR_AUTHORIZATION)).thenReturn(true);
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(true);
        doThrow(new IllegalStateException("Approval " + id
                + " is already APPROVED; a decision on a settled request is refused"))
                .when(permissionCoordinator).decide(eq(id),
                        eq(io.aria.conductor.execution.approval.PermissionChoice.ALLOW_ONCE), any());

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "again"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Approval " + id
                        + " is already APPROVED; a decision on a settled request is refused"));

        verifyNoInteractions(approvalGate);
    }

    /**
     * The operator session can cross its TTL between the identity resolution
     * ({@code findSession}) and the decision's own freshness check
     * ({@code ActorPrincipal.requireActive}): that is an authorization refusal
     * (403), never an internal error.
     */
    @Test
    void decideApproval_operatorSessionExpiringBeforeTheDecision_returns403() throws Exception {
        UUID id = UUID.randomUUID();
        java.time.Instant sessionExpiry = java.time.Instant.parse("2026-09-22T20:00:00Z");
        io.aria.conductor.execution.security.OperatorSessionService.OperatorSession session =
                new io.aria.conductor.execution.security.OperatorSessionService.OperatorSession(
                        "session-3", "csrf-3", sessionExpiry);
        when(operatorSessions.findSession("session-3")).thenReturn(Optional.of(session));
        when(permissionCoordinator.isNativePermissionRequest(id)).thenReturn(true);
        doThrow(new SecurityException("Actor credential expired"))
                .when(permissionCoordinator).decide(id,
                        io.aria.conductor.execution.approval.PermissionChoice.ALLOW_ONCE,
                        io.aria.conductor.common.security.ActorPrincipal.operator(sessionExpiry));

        mvc.perform(post("/api/v1/approvals/" + id + "/decide")
                        .cookie(new jakarta.servlet.http.Cookie(
                                io.aria.conductor.execution.security.OperatorSessionService.COOKIE_NAME, "session-3"))
                        .header(io.aria.conductor.execution.security.OperatorSessionService.CSRF_HEADER, "csrf-3")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("approved", true, "reason", "just in time"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Actor credential expired"));

        verifyNoInteractions(approvalGate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{\"approved\":"})
    void decideApproval_malformedBody_returns400WithoutTouchingGate(String body) throws Exception {
        mvc.perform(post("/api/v1/approvals/" + UUID.randomUUID() + "/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(approvalGate, permissionCoordinator);
    }
}

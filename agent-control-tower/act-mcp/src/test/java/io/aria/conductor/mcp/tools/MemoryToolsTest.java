package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.service.MemoryCommandService;
import io.aria.conductor.memory.service.MemoryNotFoundException;
import io.aria.conductor.memory.service.MemoryProposalService;
import io.aria.conductor.memory.service.MemoryQueryService;
import io.aria.conductor.memory.service.MemoryRecallService;
import io.aria.conductor.memory.service.MemoryValidationException;
import io.aria.conductor.mcp.McpActorContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The memory MCP surface (spec §9). Identity comes from the transport context only:
 * a worker call to {@code remember_fact} writes a PENDING EXTRACTION proposal, an
 * operator call writes ACTIVE memory, and the two OPERATOR_ONLY tools refuse a
 * run-scoped worker before the memory service is reached.
 *
 * <p>Every success envelope carries ids, provenance, sensitivity and validity; the
 * manifest tool returns section ids, drops and degraded reasons and never prompt text.
 */
@ExtendWith(MockitoExtension.class)
class MemoryToolsTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
    private static final UUID FACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");
    private static final Instant VALID_FROM = Instant.parse("2026-11-01T00:00:00Z");

    @Mock MemoryCommandService commands;
    @Mock MemoryProposalService proposals;
    @Mock MemoryQueryService queries;
    @Mock MemoryRecallService recall;
    @Mock ContextManifestRepository manifests;
    @Mock MemoryFactRepository facts;
    @Mock MemoryAssertionRepository assertions;

    private McpProperties mcpProperties;
    private MemoryTools tools;

    /** The real reviewed policy: the memory tools are classified by the catalog. */
    private final ToolPolicyRegistry toolPolicies = new ToolPolicyRegistry();

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new MemoryTools(commands, proposals, queries, recall, manifests, facts, assertions,
                toolPolicies, mcpProperties);
    }

    private ToolContext context(ActorPrincipal actor) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpActorContext.transportContext(actor));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    private ToolContext workerContext() {
        return context(ActorPrincipal.worker(RUN_ID, Instant.parse("2026-11-09T12:10:00Z")));
    }

    private ToolContext operatorContext() {
        return context(ActorPrincipal.operator(null));
    }

    private static MemoryFact fact(FactStatus status, MemoryOrigin origin) {
        return fact(FACT_ID, status, origin, Sensitivity.INTERNAL, "timezone", "UTC");
    }

    private static MemoryFact fact(UUID id, FactStatus status, MemoryOrigin origin, Sensitivity sensitivity,
                                   String factKey, String valueText) {
        return MemoryFact.builder()
                .id(id)
                .entityId(ENTITY_ID)
                .factKey(factKey)
                .valueText(valueText)
                .valueType("STRING")
                .status(status)
                .origin(origin)
                .sensitivity(sensitivity)
                .validFrom(VALID_FROM)
                .createdAt(VALID_FROM)
                .version(0L)
                .build();
    }

    private static MemoryFactView factView(UUID id, Sensitivity sensitivity, String factKey, String valueText) {
        return MemoryFactView.from(fact(id, FactStatus.ACTIVE, MemoryOrigin.OPERATOR, sensitivity,
                factKey, valueText));
    }

    private static MemoryEntityView entityView() {
        return entityView(ENTITY_ID, Sensitivity.INTERNAL);
    }

    private static MemoryEntityView entityView(UUID id, Sensitivity sensitivity) {
        return new MemoryEntityView(id.toString(), "PERSON", "operator", "Operator",
                sensitivity.name(), "ACTIVE", "OPERATOR", null, 0L, VALID_FROM, VALID_FROM);
    }

    @Test
    void workerRememberFactWritesAPendingExtractionProposal() {
        when(commands.addFact(eq(ENTITY_ID), any(), any()))
                .thenReturn(fact(FactStatus.PENDING, MemoryOrigin.EXTRACTION));

        String json = tools.rememberFact(ENTITY_ID, "timezone", "UTC", null, workerContext());

        ArgumentCaptor<MemoryActor> actor = ArgumentCaptor.forClass(MemoryActor.class);
        verify(commands).addFact(eq(ENTITY_ID), any(), actor.capture());
        assertThat(actor.getValue().type()).isEqualTo(MemoryActorType.EXTRACTION);
        assertThat(actor.getValue().ref()).isEqualTo(RUN_ID.toString());
        assertThat(json).contains("\"ok\":true")
                .contains("\"status\":\"PENDING\"")
                .contains("\"origin\":\"EXTRACTION\"")
                .contains("\"id\":\"" + FACT_ID + "\"")
                .contains("\"entityId\":\"" + ENTITY_ID + "\"")
                .contains("\"sensitivity\":\"INTERNAL\"")
                .contains("\"validFrom\"");
    }

    @Test
    void operatorRememberFactWritesActiveMemory() {
        when(commands.addFact(eq(ENTITY_ID), any(), any()))
                .thenReturn(fact(FactStatus.ACTIVE, MemoryOrigin.OPERATOR));

        String json = tools.rememberFact(ENTITY_ID, "timezone", "UTC", "operator:settings", operatorContext());

        ArgumentCaptor<MemoryActor> actor = ArgumentCaptor.forClass(MemoryActor.class);
        verify(commands).addFact(eq(ENTITY_ID), any(), actor.capture());
        assertThat(actor.getValue().type()).isEqualTo(MemoryActorType.OPERATOR);
        assertThat(json).contains("\"ok\":true")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"origin\":\"OPERATOR\"");
    }

    @Test
    void workerCannotDecideAProposal() {
        String json = tools.decideMemoryProposal(FACT_ID, true, null, workerContext());

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"FORBIDDEN\"");
        verifyNoInteractions(proposals);
    }

    @Test
    void workerCannotForgetAFact() {
        String json = tools.forgetFact(FACT_ID, workerContext());

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"FORBIDDEN\"");
        verifyNoInteractions(facts, assertions);
    }

    @Test
    void operatorDecideMemoryProposalUsesTheOperatorActor() {
        when(proposals.decideProposal(eq(FACT_ID), eq(ProposalDecision.APPROVE), any()))
                .thenReturn(MemoryProposalView.fromFact(fact(FactStatus.ACTIVE, MemoryOrigin.EXTRACTION)));

        String json = tools.decideMemoryProposal(FACT_ID, true, "approved in review", operatorContext());

        ArgumentCaptor<MemoryActor> actor = ArgumentCaptor.forClass(MemoryActor.class);
        verify(proposals).decideProposal(eq(FACT_ID), eq(ProposalDecision.APPROVE), actor.capture());
        assertThat(actor.getValue().type()).isEqualTo(MemoryActorType.OPERATOR);
        assertThat(json).contains("\"ok\":true").contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void operatorRejectRoutesReject() {
        when(proposals.decideProposal(eq(FACT_ID), eq(ProposalDecision.REJECT), any()))
                .thenReturn(MemoryProposalView.fromFact(fact(FactStatus.RETIRED, MemoryOrigin.EXTRACTION)));

        String json = tools.decideMemoryProposal(FACT_ID, false, null, operatorContext());

        verify(proposals).decideProposal(eq(FACT_ID), eq(ProposalDecision.REJECT), any());
        assertThat(json).contains("\"ok\":true").contains("\"status\":\"RETIRED\"");
    }

    @Test
    void operatorForgetRetiresTheFactAndWritesAnAssertion() {
        MemoryFact active = fact(FactStatus.ACTIVE, MemoryOrigin.OPERATOR);
        when(facts.findById(FACT_ID)).thenReturn(Optional.of(active));

        String json = tools.forgetFact(FACT_ID, operatorContext());

        ArgumentCaptor<MemoryFact> saved = ArgumentCaptor.forClass(MemoryFact.class);
        verify(facts).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(saved.getValue().getValidTo()).isNotNull();

        ArgumentCaptor<MemoryAssertion> assertion = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions).save(assertion.capture());
        assertThat(assertion.getValue().getAction()).isEqualTo(MemoryAction.RETIRE);
        assertThat(assertion.getValue().getActorType()).isEqualTo(MemoryActorType.OPERATOR);
        assertThat(json).contains("\"ok\":true").contains("\"status\":\"RETIRED\"");
    }

    @Test
    void forgetAnUnknownFactIsReportedAsNotFound() {
        when(facts.findById(FACT_ID)).thenReturn(Optional.empty());

        String json = tools.forgetFact(FACT_ID, operatorContext());

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"NOT_FOUND\"");
        verify(facts, never()).save(any());
    }

    @Test
    void recallMemoryReturnsRankedFactsWithProvenance() {
        when(recall.recall(any())).thenReturn(new RecallResult(
                List.of(entityView()),
                List.of(MemoryFactView.from(fact(FactStatus.ACTIVE, MemoryOrigin.OPERATOR))),
                false));

        String json = tools.recallMemory("operator timezone", "repo@main", workerContext());

        ArgumentCaptor<RecallQuery> query = ArgumentCaptor.forClass(RecallQuery.class);
        verify(recall).recall(query.capture());
        assertThat(query.getValue().text()).isEqualTo("operator timezone");
        assertThat(query.getValue().focusRef()).isEqualTo("repo@main");
        // A worker can never widen the ceiling past the fail-closed default.
        assertThat(query.getValue().maxSensitivity()).isEqualTo(Sensitivity.INTERNAL);
        assertThat(json).contains("\"ok\":true")
                .contains("\"id\":\"" + FACT_ID + "\"")
                .contains("\"origin\":\"OPERATOR\"")
                .contains("\"sensitivity\":\"INTERNAL\"")
                .contains("\"truncated\":false");
    }

    @Test
    void listAndGetMemoryEntitiesReturnTheReadModel() {
        when(queries.listEntities(eq("PERSON"), any(), anyInt())).thenReturn(List.of(entityView()));
        when(queries.getEntity(ENTITY_ID)).thenReturn(entityView());
        when(queries.currentFacts(ENTITY_ID))
                .thenReturn(List.of(MemoryFactView.from(fact(FactStatus.ACTIVE, MemoryOrigin.OPERATOR))));

        String listed = tools.listMemoryEntities("PERSON", null, 10, operatorContext());
        String detail = tools.getMemoryEntity(ENTITY_ID, operatorContext());

        assertThat(listed).contains("\"ok\":true").contains("\"id\":\"" + ENTITY_ID + "\"");
        assertThat(detail).contains("\"ok\":true")
                .contains("\"canonicalKey\":\"operator\"")
                .contains("\"id\":\"" + FACT_ID + "\"");
    }

    @Test
    void listMemoryProposalsDefaultsToThePendingInbox() {
        when(proposals.listProposals(FactStatus.PENDING))
                .thenReturn(List.of(MemoryProposalView.fromFact(fact(FactStatus.PENDING, MemoryOrigin.EXTRACTION))));

        String json = tools.listMemoryProposals(null, workerContext());

        assertThat(json).contains("\"ok\":true")
                .contains("\"id\":\"" + FACT_ID + "\"")
                .contains("\"status\":\"PENDING\"");
    }

    // ---------------------------------------------------------------- read ceiling

    /**
     * The security regression of this round: a CONFIDENTIAL or RESTRICTED fact value
     * must never leave a read tool, even when the credential is the operator — the
     * strongest authority on the surface can only read at the deployment ceiling.
     */
    @Test
    void getMemoryEntityReturnsOnlyFactsAtOrBelowTheInternalCeiling() {
        UUID confidentialFact = UUID.fromString("00000000-0000-0000-0000-000000000804");
        UUID restrictedFact = UUID.fromString("00000000-0000-0000-0000-000000000805");
        when(queries.getEntity(ENTITY_ID)).thenReturn(entityView());
        when(queries.currentFacts(ENTITY_ID)).thenReturn(List.of(
                factView(FACT_ID, Sensitivity.INTERNAL, "timezone", "UTC"),
                factView(confidentialFact, Sensitivity.CONFIDENTIAL, "salary", "188888"),
                factView(restrictedFact, Sensitivity.RESTRICTED, "passport", "K1234567")));

        String json = tools.getMemoryEntity(ENTITY_ID, operatorContext());

        assertThat(json).contains("\"ok\":true")
                .contains("\"factKey\":\"timezone\"")
                .contains("\"sensitivity\":\"INTERNAL\"")
                .doesNotContain(confidentialFact.toString())
                .doesNotContain("salary")
                .doesNotContain("188888")
                .doesNotContain(restrictedFact.toString())
                .doesNotContain("passport")
                .doesNotContain("K1234567")
                .doesNotContain("CONFIDENTIAL")
                .doesNotContain("RESTRICTED");
    }

    @Test
    void getMemoryEntityOfAnEntityAboveTheCeilingIsNotFound() {
        when(queries.getEntity(ENTITY_ID)).thenReturn(entityView(ENTITY_ID, Sensitivity.CONFIDENTIAL));

        String json = tools.getMemoryEntity(ENTITY_ID, operatorContext());

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"NOT_FOUND\"");
        verify(queries, never()).currentFacts(any());
    }

    @Test
    void listMemoryEntitiesHidesEntitiesAboveTheCeiling() {
        UUID confidentialEntity = UUID.fromString("00000000-0000-0000-0000-000000000806");
        UUID restrictedEntity = UUID.fromString("00000000-0000-0000-0000-000000000807");
        when(queries.listEntities(eq("PERSON"), any(), anyInt())).thenReturn(List.of(
                entityView(ENTITY_ID, Sensitivity.INTERNAL),
                entityView(confidentialEntity, Sensitivity.CONFIDENTIAL),
                entityView(restrictedEntity, Sensitivity.RESTRICTED)));

        String json = tools.listMemoryEntities("PERSON", null, 10, workerContext());

        assertThat(json).contains("\"ok\":true")
                .contains(ENTITY_ID.toString())
                .doesNotContain(confidentialEntity.toString())
                .doesNotContain(restrictedEntity.toString())
                .doesNotContain("CONFIDENTIAL")
                .doesNotContain("RESTRICTED");
    }

    @Test
    void listMemoryProposalsHidesProposalsAboveTheCeiling() {
        UUID confidentialProposal = UUID.fromString("00000000-0000-0000-0000-000000000808");
        UUID restrictedRelation = UUID.fromString("00000000-0000-0000-0000-000000000809");
        MemoryRelation restricted = MemoryRelation.builder()
                .id(restrictedRelation)
                .fromEntityId(ENTITY_ID)
                .predicate("REPORTS_TO")
                .toEntityId(confidentialProposal)
                .status(FactStatus.PENDING)
                .origin(MemoryOrigin.EXTRACTION)
                .sensitivity(Sensitivity.RESTRICTED)
                .validFrom(VALID_FROM)
                .createdAt(VALID_FROM)
                .build();
        when(proposals.listProposals(FactStatus.PENDING)).thenReturn(List.of(
                MemoryProposalView.fromFact(fact(FACT_ID, FactStatus.PENDING, MemoryOrigin.EXTRACTION,
                        Sensitivity.INTERNAL, "timezone", "UTC")),
                MemoryProposalView.fromFact(fact(confidentialProposal, FactStatus.PENDING,
                        MemoryOrigin.EXTRACTION, Sensitivity.CONFIDENTIAL, "salary", "188888")),
                MemoryProposalView.fromRelation(restricted)));

        String json = tools.listMemoryProposals(null, operatorContext());

        assertThat(json).contains("\"ok\":true")
                .contains(FACT_ID.toString())
                .doesNotContain(confidentialProposal.toString())
                .doesNotContain(restrictedRelation.toString())
                .doesNotContain("salary")
                .doesNotContain("REPORTS_TO")
                .doesNotContain("CONFIDENTIAL")
                .doesNotContain("RESTRICTED");
    }

    @Test
    void recallMemoryDropsAnythingAboveTheInternalCeiling() {
        UUID confidentialFact = UUID.fromString("00000000-0000-0000-0000-000000000804");
        UUID restrictedEntity = UUID.fromString("00000000-0000-0000-0000-000000000807");
        when(recall.recall(any())).thenReturn(new RecallResult(
                List.of(entityView(ENTITY_ID, Sensitivity.INTERNAL),
                        entityView(restrictedEntity, Sensitivity.RESTRICTED)),
                List.of(factView(FACT_ID, Sensitivity.INTERNAL, "timezone", "UTC"),
                        factView(confidentialFact, Sensitivity.CONFIDENTIAL, "salary", "188888")),
                false));

        String json = tools.recallMemory("operator timezone", null, operatorContext());

        ArgumentCaptor<RecallQuery> query = ArgumentCaptor.forClass(RecallQuery.class);
        verify(recall).recall(query.capture());
        assertThat(query.getValue().maxSensitivity()).isEqualTo(Sensitivity.INTERNAL);
        assertThat(json).contains("\"ok\":true")
                .contains(ENTITY_ID.toString())
                .contains(FACT_ID.toString())
                .doesNotContain(restrictedEntity.toString())
                .doesNotContain(confidentialFact.toString())
                .doesNotContain("salary")
                .doesNotContain("CONFIDENTIAL")
                .doesNotContain("RESTRICTED");
    }

    /**
     * The ceiling is a deployment constant, not an actor grant: the operator credential
     * is the strongest authority and it still reads at INTERNAL. There is no argument
     * through which any caller could raise it.
     */
    @Test
    void noCredentialCanRaiseTheReadCeilingAboveInternal() {
        assertThat(MemoryTools.readCeiling(null)).isEqualTo(Sensitivity.INTERNAL);
        assertThat(MemoryTools.readCeiling(ActorPrincipal.operator(null))).isEqualTo(Sensitivity.INTERNAL);
        assertThat(MemoryTools.readCeiling(ActorPrincipal.worker(RUN_ID, Instant.parse("2026-11-09T12:10:00Z"))))
                .isEqualTo(Sensitivity.INTERNAL);
    }

    @Test
    void anUnknownProposalStatusIsAValidationError() {
        String json = tools.listMemoryProposals("BOGUS", workerContext());

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"VALIDATION\"");
        verifyNoInteractions(proposals);
    }

    @Test
    void aSecretLikeValueIsRejectedWithoutEchoingIt() {
        String secret = "sk-live-0123456789abcdef";
        when(commands.addFact(eq(ENTITY_ID), any(), any()))
                .thenThrow(new MemoryValidationException(
                        "valueText is rejected: memory does not store authentication material"));

        String json = tools.rememberFact(ENTITY_ID, "api_key", secret, null, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"VALIDATION\"")
                .contains("valueText")
                .doesNotContain(secret);
    }

    @Test
    void aMissingEntityIsReportedAsNotFound() {
        when(commands.addFact(eq(ENTITY_ID), any(), any()))
                .thenThrow(new MemoryNotFoundException("memory entity " + ENTITY_ID + " not found"));

        String json = tools.rememberFact(ENTITY_ID, "timezone", "UTC", null, operatorContext());

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void getContextManifestReturnsSectionIdsDropsAndDegradedReasonsButNoPromptText() {
        ContextManifest row = ContextManifest.builder()
                .id(UUID.randomUUID())
                .runId(RUN_ID)
                .turnNumber(1)
                .adapter("SYNC")
                .sectionsJson("[{\"id\":\"identity\",\"sources\":[\"operator\"],\"chars\":12,\"tokens\":4,"
                        + "\"truncated\":false,\"content\":\"PROMPT TEXT MUST NOT LEAK\"}]")
                .droppedJson("[\"knowledge:budget\"]")
                .degradedJson("[\"memory:timeout\"]")
                .maxChars(24_000)
                .estimatedTokens(1_200)
                .createdAt(VALID_FROM)
                .build();
        when(manifests.findByRunIdOrderByTurnNumberAsc(RUN_ID)).thenReturn(List.of(row));

        String json = tools.getContextManifest(RUN_ID);

        assertThat(json).contains("\"ok\":true")
                .contains("\"turnNumber\":1")
                .contains("\"adapter\":\"SYNC\"")
                .contains("\"identity\"")
                .contains("\"knowledge:budget\"")
                .contains("\"memory:timeout\"")
                .doesNotContain("PROMPT TEXT")
                .doesNotContain("\"content\"");
    }

    @Test
    void aRunWithoutManifestsReturnsAnEmptyList() {
        when(manifests.findByRunIdOrderByTurnNumberAsc(RUN_ID)).thenReturn(List.of());

        String json = tools.getContextManifest(RUN_ID);

        assertThat(json).contains("\"ok\":true").contains("\"data\":[]");
    }

    @Test
    void aWriteWithoutATransportActorIsRefused() {
        String json = tools.rememberFact(ENTITY_ID, "timezone", "UTC", null, new ToolContext(Map.of()));

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("Missing MCP exchange");
        verifyNoInteractions(commands);
    }
}

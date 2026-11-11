package io.aria.conductor.dashboard.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import io.aria.conductor.memory.service.MemoryCommandService;
import io.aria.conductor.memory.service.MemoryConflictException;
import io.aria.conductor.memory.service.MemoryNotFoundException;
import io.aria.conductor.memory.service.MemoryProposalService;
import io.aria.conductor.memory.service.MemoryQueryService;
import io.aria.conductor.memory.service.MemoryRecallService;
import io.aria.conductor.memory.service.MemoryValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice for the memory REST surface (spec §9). Every route is exercised once, each
 * governed exception is mapped once, and the manifest route is asserted to expose
 * section ids/drops/degraded reasons while carrying no prompt content.
 */
@WebMvcTest(controllers = MemoryController.class)
class MemoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemoryCommandService command;

    @MockitoBean
    private MemoryQueryService query;

    @MockitoBean
    private MemoryProposalService proposals;

    @MockitoBean
    private MemoryRecallService recall;

    @MockitoBean
    private MemoryLifecycleService lifecycle;

    @MockitoBean
    private ContextManifestRepository manifests;

    @MockitoBean
    private MemoryRelationRepository relations;

    @MockitoBean
    private MemoryKnowledgeLinkRepository links;

    @MockitoBean
    private MemoryAssertionRepository assertions;

    @MockitoBean
    private OperatorAuthorityResolver operatorAuthority;

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @BeforeEach
    void operatorResolves() {
        when(operatorAuthority.resolveOperator(any(), eq(true))).thenReturn(ActorPrincipal.operator(null));
    }

    private static MemoryEntity entity(UUID id, String displayName) {
        return MemoryEntity.builder()
                .id(id)
                .kind("PERSON")
                .canonicalKey("operator")
                .displayName(displayName)
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .version(1)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static MemoryFact fact(UUID entityId) {
        return MemoryFact.builder()
                .id(UUID.randomUUID())
                .entityId(entityId)
                .factKey("timezone")
                .valueText("UTC")
                .valueType("STRING")
                .status(FactStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(NOW)
                .version(1)
                .build();
    }

    // ---------------------------------------------------------------- entities

    @Test
    void listsEntitiesWithFilters() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.listEntities(eq("PERSON"), eq(EntityStatus.ACTIVE), anyInt()))
                .thenReturn(List.of(MemoryEntityView.from(entity(id, "Operator"))));

        mvc.perform(get("/api/v1/memory/entities")
                        .param("kind", "PERSON")
                        .param("q", "oper")
                        .param("status", "ACTIVE")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].canonicalKey").value("operator"))
                .andExpect(jsonPath("$[0].sensitivity").value("INTERNAL"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void createsAnEntity() throws Exception {
        UUID id = UUID.randomUUID();
        when(command.createEntity(any(), any())).thenReturn(entity(id, "Operator"));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\","
                                + "\"displayName\":\"Operator\",\"sensitivity\":\"INTERNAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.canonicalKey").value("operator"));
    }

    /**
     * T47 item 2. The service returns the existing row unchanged for a non-terminal key, so a
     * POST that names a display name or sensitivity the stored row does not carry created and
     * changed nothing while answering 201 with the old row — the operator saw "Entity created"
     * over an edit that never applied. The honest answer is the governed conflict; no new error
     * code and no new field.
     */
    @Test
    void aCreateThatCannotApplyItsDisplayNameIsAConflictNotACreated() throws Exception {
        UUID id = UUID.randomUUID();
        when(command.createEntity(any(), any())).thenReturn(entity(id, "Operator"));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\","
                                + "\"displayName\":\"Ops Lead\",\"sensitivity\":\"INTERNAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("already exists")))
                .andExpect(jsonPath("$.message").value(containsString("PATCH")));
    }

    @Test
    void aCreateThatCannotApplyItsSensitivityIsAConflictToo() throws Exception {
        UUID id = UUID.randomUUID();
        when(command.createEntity(any(), any())).thenReturn(entity(id, "Operator"));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\","
                                + "\"displayName\":\"Operator\",\"sensitivity\":\"CONFIDENTIAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("CONFLICT"));
    }

    /**
     * The boundary of the conflict above: a request whose fields the stored row already carries
     * (or that names none) ignored nothing, so it stays the same 201 the route always answered.
     */
    @Test
    void aCreateWhoseFieldsTheStoredRowCarriesOrThatNamesNoneStaysCreated() throws Exception {
        UUID same = UUID.randomUUID();
        when(command.createEntity(any(), any())).thenReturn(entity(same, "Operator"));
        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\","
                                + "\"displayName\":\"Operator\",\"sensitivity\":\"INTERNAL\"}"))
                .andExpect(status().isCreated());

        UUID bare = UUID.randomUUID();
        when(command.createEntity(any(), any())).thenReturn(entity(bare, "Operator"));
        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void getsEntityDetailWithFactsRelationsLinksAndTimeline() throws Exception {
        UUID id = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID knowledgeId = UUID.randomUUID();
        UUID assertionId = UUID.randomUUID();
        when(query.getEntity(id, false)).thenReturn(MemoryEntityView.from(entity(id, "Operator")));
        when(query.factHistory(id)).thenReturn(List.of(MemoryFactView.from(fact(id))));
        when(relations.findByFromEntityIdAndStatus(id, FactStatus.ACTIVE)).thenReturn(List.of(
                MemoryRelation.builder()
                        .id(UUID.randomUUID())
                        .fromEntityId(id)
                        .predicate("WORKS_ON")
                        .toEntityId(UUID.randomUUID())
                        .status(FactStatus.ACTIVE)
                        .origin(MemoryOrigin.OPERATOR)
                        .sensitivity(Sensitivity.INTERNAL)
                        .validFrom(NOW)
                        .version(1)
                        .build()));
        when(relations.findByToEntityIdAndStatus(id, FactStatus.ACTIVE)).thenReturn(List.of());
        when(links.findByEntityId(id)).thenReturn(List.of(
                MemoryKnowledgeLink.builder()
                        .id(linkId)
                        .entityId(id)
                        .knowledgeItemId(knowledgeId)
                        .status(MemoryLinkStatus.ACTIVE)
                        .createdAt(NOW)
                        .build()));
        when(assertions.findBySubjectTypeAndSubjectIdOrderByCreatedAtAsc(MemorySubjectType.ENTITY, id))
                .thenReturn(List.of(MemoryAssertion.builder()
                        .id(assertionId)
                        .subjectType(MemorySubjectType.ENTITY)
                        .subjectId(id)
                        .action(MemoryAction.CREATE)
                        .actorType(MemoryActorType.OPERATOR)
                        .createdAt(NOW)
                        .build()));

        mvc.perform(get("/api/v1/memory/entities/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity.id").value(id.toString()))
                .andExpect(jsonPath("$.facts[0].factKey").value("timezone"))
                .andExpect(jsonPath("$.facts[0].validFrom").exists())
                .andExpect(jsonPath("$.relations[0].predicate").value("WORKS_ON"))
                .andExpect(jsonPath("$.links[0].knowledgeItemId").value(knowledgeId.toString()))
                .andExpect(jsonPath("$.assertions[0].action").value("CREATE"));
    }

    /**
     * Spec :309-313 — the operator cannot approve what they cannot see. The detail read keeps
     * its fail-closed default and honours an explicit status=PENDING only after operator
     * authority is resolved, so the reviewer can open the proposal they are deciding.
     */
    @Test
    void anOperatorCanOpenAPendingEntityWithAnExplicitStatusFilter() throws Exception {
        UUID id = UUID.randomUUID();
        MemoryEntity pending = entity(id, "Candidate");
        pending.setStatus(EntityStatus.PENDING);
        when(query.getEntity(id, true)).thenReturn(MemoryEntityView.from(pending));

        mvc.perform(get("/api/v1/memory/entities/{id}", id).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity.id").value(id.toString()))
                .andExpect(jsonPath("$.entity.status").value("PENDING"));
        verify(query).getEntity(id, true);
    }

    @Test
    void theDefaultDetailReadStillHidesAPendingEntity() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.getEntity(id, false))
                .thenThrow(new MemoryNotFoundException("memory entity " + id + " not found"));

        mvc.perform(get("/api/v1/memory/entities/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("NOT_FOUND"));
        verify(query).getEntity(id, false);
    }

    /**
     * Review material is not silently downgraded for a worker: a request that asks for
     * status=PENDING is refused (403) before the read, exactly like the MCP surface's refusal.
     */
    @Test
    void aWorkerCannotRequestThePendingDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(operatorAuthority.resolveOperator(any(), eq(false)))
                .thenThrow(new OperatorSessionService.ForbiddenMutationException(
                        "Operator authority required: reading a pending memory entity is an operator decision. "
                                + "Review it on the Memory page or in the Review Queue."));

        mvc.perform(get("/api/v1/memory/entities/{id}", id).param("status", "PENDING"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(
                        "Operator authority required: reading a pending memory entity is an operator decision. "
                                + "Review it on the Memory page or in the Review Queue."));
        verify(query, never()).getEntity(any(), eq(true));
    }

    /**
     * The detail read honours exactly one status value. Typing the parameter as
     * {@link EntityStatus} while silently ignoring four of its five values told the caller that
     * {@code status=MERGED} selected MERGED rows when it selected nothing at all (and on an
     * ACTIVE id it returned the ACTIVE row). A non-PENDING value is therefore input the route
     * cannot honour: 422 VALIDATION naming the only supported value, before anything is read.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "MERGED", "RETIRED"})
    void aDetailStatusOtherThanPendingIsRejectedAsValidation(String status) throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/api/v1/memory/entities/{id}", id).param("status", status))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("status"),
                        org.hamcrest.Matchers.containsString(status),
                        org.hamcrest.Matchers.containsString("PENDING"))));

        verify(query, never()).getEntity(any(), anyBoolean());
    }

    @Test
    void patchesEntityRename() throws Exception {
        UUID id = UUID.randomUUID();
        when(lifecycle.renameEntity(eq(id), eq("Operator Prime"), any())).thenReturn(entity(id, "Operator Prime"));

        mvc.perform(patch("/api/v1/memory/entities/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Operator Prime\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Operator Prime"));
    }

    @Test
    void patchesEntityAlias() throws Exception {
        UUID id = UUID.randomUUID();
        when(lifecycle.addAliases(eq(id), eq(List.of("op", "the operator")), any()))
                .thenReturn(entity(id, "Operator"));

        mvc.perform(patch("/api/v1/memory/entities/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aliases\":[\"op\",\"the operator\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void patchesEntityMerge() throws Exception {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        MemoryEntity merged = entity(source, "Operator");
        merged.setStatus(EntityStatus.MERGED);
        merged.setMergedInto(target);
        when(command.mergeEntities(eq(source), eq(target), any())).thenReturn(merged);

        mvc.perform(patch("/api/v1/memory/entities/{id}", source)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mergeInto\":\"" + target + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.mergedInto").value(target.toString()));
    }

    @Test
    void patchesEntityRetire() throws Exception {
        UUID id = UUID.randomUUID();
        MemoryEntity retired = entity(id, "Operator");
        retired.setStatus(EntityStatus.RETIRED);
        when(lifecycle.retireEntity(eq(id), any())).thenReturn(retired);

        mvc.perform(patch("/api/v1/memory/entities/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retire\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    // ---------------------------------------------------------------- facts

    @Test
    void addsAFact() throws Exception {
        UUID entityId = UUID.randomUUID();
        when(command.addFact(eq(entityId), any(), any())).thenReturn(fact(entityId));

        mvc.perform(post("/api/v1/memory/entities/{id}/facts", entityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factKey\":\"timezone\",\"valueText\":\"UTC\",\"valueType\":\"STRING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.factKey").value("timezone"))
                .andExpect(jsonPath("$.origin").value("OPERATOR"))
                .andExpect(jsonPath("$.sensitivity").value("INTERNAL"));
    }

    @Test
    void supersedesAFact() throws Exception {
        UUID entityId = UUID.randomUUID();
        MemoryFact fresh = fact(entityId);
        fresh.setValueText("Europe/Berlin");
        when(command.supersedeFact(any(), any(), any())).thenReturn(fresh);

        mvc.perform(post("/api/v1/memory/facts/{id}/supersede", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valueText\":\"Europe/Berlin\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueText").value("Europe/Berlin"));
    }

    @Test
    void retiresAFact() throws Exception {
        UUID entityId = UUID.randomUUID();
        MemoryFact retired = fact(entityId);
        retired.setStatus(FactStatus.RETIRED);
        retired.setValidTo(NOW);
        when(lifecycle.retireFact(any(), any(), any())).thenReturn(retired);

        mvc.perform(post("/api/v1/memory/facts/{id}/retire", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRef\":\"operator-console\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.validTo").exists());
    }

    // ---------------------------------------------------------------- proposals

    /**
     * The dashboard proposal card's source. T11 C1 makes the service read actor-scoped (a
     * non-operator read omits a PENDING entity's canonical key), so this route must name the
     * operator it serves or the entity card loses its own proposal.
     */
    @Test
    void listsPendingProposals() throws Exception {
        when(proposals.listProposals(eq(FactStatus.PENDING), any(MemoryActor.class)))
                .thenReturn(List.of(new MemoryProposalView(
                        UUID.randomUUID().toString(), "FACT", UUID.randomUUID().toString(), "timezone", "UTC",
                        null, null, "PENDING", "EXTRACTION", "INTERNAL", BigDecimal.valueOf(0.9), NOW, NOW)));

        mvc.perform(get("/api/v1/memory/proposals").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectType").value("FACT"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].origin").value("EXTRACTION"));

        ArgumentCaptor<MemoryActor> reviewer = ArgumentCaptor.forClass(MemoryActor.class);
        verify(proposals).listProposals(eq(FactStatus.PENDING), reviewer.capture());
        assertThat(reviewer.getValue().type())
                .as("the operator inbox keeps the PENDING entity proposal on the card")
                .isEqualTo(MemoryActorType.OPERATOR);
    }

    /**
     * UI surface review M1: the ENTITY card read one 50-row global page and filtered it
     * client-side, so a big inbox made the card claim an empty batch. The route now takes the
     * entity id and passes it to the query-level read (which returns the whole batch); nothing is
     * filtered after pagination here.
     */
    @Test
    void listsOneEntitysProposalBatchThroughTheServerSideFilter() throws Exception {
        UUID entityId = UUID.randomUUID();
        UUID factId = UUID.randomUUID();
        when(proposals.listProposalsForEntity(eq(FactStatus.PENDING), eq(entityId), any(MemoryActor.class)))
                .thenReturn(List.of(new MemoryProposalView(
                        factId.toString(), "FACT", entityId.toString(), "timezone", "UTC",
                        null, null, "PENDING", "EXTRACTION", "INTERNAL", BigDecimal.valueOf(0.9), NOW, NOW)));

        mvc.perform(get("/api/v1/memory/proposals")
                        .param("status", "PENDING")
                        .param("entityId", entityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(factId.toString()))
                .andExpect(jsonPath("$[0].entityId").value(entityId.toString()))
                .andExpect(jsonPath("$[0].factKey").value("timezone"));

        ArgumentCaptor<MemoryActor> reviewer = ArgumentCaptor.forClass(MemoryActor.class);
        verify(proposals).listProposalsForEntity(eq(FactStatus.PENDING), eq(entityId), reviewer.capture());
        assertThat(reviewer.getValue().type()).isEqualTo(MemoryActorType.OPERATOR);
        verify(proposals, never()).listProposals(eq(FactStatus.PENDING), any(MemoryActor.class));
    }

    @Test
    void decidesAProposal() throws Exception {
        UUID subjectId = UUID.randomUUID();
        when(proposals.decideProposal(eq(subjectId), any(), any())).thenReturn(new MemoryProposalView(
                subjectId.toString(), "FACT", UUID.randomUUID().toString(), "timezone", "UTC",
                null, null, "ACTIVE", "EXTRACTION", "INTERNAL", BigDecimal.valueOf(0.9), NOW, NOW));

        mvc.perform(post("/api/v1/memory/proposals/{id}/decide", subjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.id").value(subjectId.toString()));
    }

    // ---------------------------------------------------------------- recall

    @Test
    void previewsRecallWithProvenance() throws Exception {
        UUID id = UUID.randomUUID();
        when(recall.recall(any())).thenReturn(new RecallResult(
                List.of(MemoryEntityView.from(entity(id, "Operator"))),
                List.of(MemoryFactView.from(fact(id))),
                false));

        mvc.perform(get("/api/v1/memory/recall").param("q", "operator timezone").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities[0].id").value(id.toString()))
                .andExpect(jsonPath("$.facts[0].origin").value("OPERATOR"))
                .andExpect(jsonPath("$.facts[0].validFrom").exists())
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void recallCapsFactsAndEntitiesAtCallerLimit() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(recall.recall(any())).thenReturn(new RecallResult(
                List.of(MemoryEntityView.from(entity(first, "Operator")),
                        MemoryEntityView.from(entity(second, "Second"))),
                List.of(MemoryFactView.from(fact(first)), MemoryFactView.from(fact(second))),
                false));

        mvc.perform(get("/api/v1/memory/recall").param("q", "operator").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities", hasSize(1)))
                .andExpect(jsonPath("$.facts", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    // ---------------------------------------------------------------- manifest

    @Test
    void returnsManifestSectionsDropsAndDegradedWithoutPromptText() throws Exception {
        UUID runId = UUID.randomUUID();
        ContextManifest manifest = ContextManifest.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .turnNumber(1)
                .conversationId("conv-1")
                .adapter("CORE")
                .sectionsJson("[{\"id\":\"memory\",\"sources\":[\"entity-1\"],\"chars\":120,"
                        + "\"tokens\":30,\"truncated\":false},{\"id\":\"history\",\"sources\":[],"
                        + "\"chars\":400,\"tokens\":100,\"truncated\":true,\"content\":\"SECRET PROMPT TEXT\"}]")
                .droppedJson("[\"skills\"]")
                .degradedJson("[\"memory: recall timeout\"]")
                .maxChars(8000)
                .estimatedTokens(130)
                .createdAt(NOW)
                .build();
        when(manifests.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of(manifest));

        mvc.perform(get("/api/v1/memory/runs/{id}/context-manifest", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].turnNumber").value(1))
                .andExpect(jsonPath("$[0].sections[0].id").value("memory"))
                .andExpect(jsonPath("$[0].sections[1].truncated").value(true))
                .andExpect(jsonPath("$[0].sections[0].content").doesNotExist())
                .andExpect(jsonPath("$[0].dropped[0]").value("skills"))
                .andExpect(jsonPath("$[0].degraded[0]").value("memory: recall timeout"))
                .andExpect(content().string(not(containsString("SECRET PROMPT TEXT"))));
    }

    // ---------------------------------------------------------------- error mappings

    @Test
    void mapsValidationTo422() throws Exception {
        when(command.createEntity(any(), any())).thenThrow(new MemoryValidationException("kind is required"));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canonicalKey\":\"operator\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value("kind is required"));
    }

    @Test
    void mapsNotFoundTo404() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.getEntity(id, false)).thenThrow(new MemoryNotFoundException("memory entity " + id + " not found"));

        mvc.perform(get("/api/v1/memory/entities/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("NOT_FOUND"));
    }

    @Test
    void mapsConflictTo409() throws Exception {
        when(command.addFact(any(), any(), any()))
                .thenThrow(new MemoryConflictException("an ACTIVE fact already exists"));

        mvc.perform(post("/api/v1/memory/entities/{id}/facts", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factKey\":\"timezone\",\"valueText\":\"UTC\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("CONFLICT"));
    }

    /**
     * A duplicate POST /entities (same kind+canonicalKey) violates {@code uq_memory_entity}.
     * The memory contract answers 409 CONFLICT with a fixed message; neither the h2 SQL
     * detail nor the constraint name may reach the caller.
     */
    @Test
    void mapsDataIntegrityViolationToConflictWithoutConstraintText() throws Exception {
        when(command.createEntity(any(), any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                        + "\"PUBLIC.UQ_MEMORY_ENTITY_INDEX_4 ON PUBLIC.MEMORY_ENTITY(KIND, CANONICAL_KEY) "
                        + "VALUES ('PERSON', 'operator') ...\"]; SQL statement: insert into memory_entity ..."));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(content().string(not(containsString("UQ_MEMORY_ENTITY"))))
                .andExpect(content().string(not(containsString("insert into"))))
                .andExpect(content().string(not(containsString("SQL statement"))))
                .andExpect(content().string(not(containsString("constraint"))));
    }

    /**
     * H2 raises SQLState 23505 for a duplicate against {@code uq_memory_entity}; the state
     * alone marks the collision, even when the driver text carries no "unique" word.
     */
    @Test
    void mapsUniqueSqlStateViolationToConflictWithoutDriverText() throws Exception {
        when(command.createEntity(any(), any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("record already exists", "23505", 23505)));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "the write conflicts with an existing memory row; re-read and retry with a distinct key"))
                .andExpect(content().string(not(containsString("23505"))))
                .andExpect(content().string(not(containsString("record already exists"))))
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("DataIntegrityViolationException"))));
    }

    /**
     * A value that overflows a V81 VARCHAR column (e.g. {@code kind} over VARCHAR(32)) is
     * input the row cannot hold, not a key collision: 422 VALIDATION with a fixed message.
     * The driver's truncation text, SQL statement, and state must not reach the caller.
     */
    @Test
    void mapsOversizedColumnValueToValidationWithoutSqlText() throws Exception {
        String oversizedKind = "K".repeat(300);
        when(command.createEntity(any(), any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement [Value too long for column \"KIND CHARACTER VARYING(32)\": \""
                        + oversizedKind + " (300)\"]; SQL statement: insert into memory_entities ...",
                new SQLException("Value too long for column \"KIND CHARACTER VARYING(32)\" (22001)",
                        "22001", 22001)));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"" + oversizedKind + "\",\"canonicalKey\":\"operator\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(
                        "the request data does not fit a memory field; check required values and their maximum lengths"))
                .andExpect(content().string(not(containsString("Value too long"))))
                .andExpect(content().string(not(containsString("CHARACTER VARYING"))))
                .andExpect(content().string(not(containsString("insert into"))))
                .andExpect(content().string(not(containsString("SQL statement"))))
                .andExpect(content().string(not(containsString("22001"))))
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("constraint"))));
    }

    @Test
    void mapsNonOperatorCallerTo403() throws Exception {
        when(operatorAuthority.resolveOperator(any(), eq(true)))
                .thenThrow(new OperatorSessionService.ForbiddenMutationException("Operator authority required"));

        mvc.perform(post("/api/v1/memory/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PERSON\",\"canonicalKey\":\"operator\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(content().string(not(containsString("SECRET"))));
    }

    @Test
    void mapsMalformedIdentifierTo422Validation() throws Exception {
        mvc.perform(get("/api/v1/memory/entities/not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorType").value("VALIDATION"));
    }
}

package io.aria.conductor.dashboard.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import io.aria.conductor.memory.service.MemoryCommandService;
import io.aria.conductor.memory.service.MemoryProposalService;
import io.aria.conductor.memory.service.MemoryQueryService;
import io.aria.conductor.memory.service.MemoryRecallService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authority boundary of the memory REST reads (spec :309-313, Wave C "authority honesty").
 *
 * <p>Every route in {@link MemoryController} is part of the operator surface, so a read that
 * resolved nothing handed a sandbox worker the same rows the dashboard sees — including the
 * PENDING review inbox. These tests wire the <em>real</em> {@link OperatorAuthorityResolver}
 * (the same construction the controller tests of the sibling lane use) so the worker bearer,
 * the anonymous remote peer, the anonymous loopback peer and the operator session cookie are
 * decided by the production resolver, not by a mock.
 *
 * <p>The memory error contract ({@link MemoryExceptionHandler}) answers {@code 403 FORBIDDEN}
 * for both a worker credential and an anonymous remote caller; it has no {@code UNAUTHORIZED}
 * code and that envelope is unchanged by this task. The assertions below therefore pin 403 for
 * both refusals and pin that no service was touched.
 */
class MemoryControllerAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String OPERATOR_AUTHORIZATION = "Bearer operator-bearer-secret";
    private static final String WORKER_AUTHORIZATION = "Bearer worker-token";

    private final MemoryCommandService command = mock(MemoryCommandService.class);
    private final MemoryQueryService query = mock(MemoryQueryService.class);
    private final MemoryProposalService proposals = mock(MemoryProposalService.class);
    private final MemoryRecallService recall = mock(MemoryRecallService.class);
    private final MemoryLifecycleService lifecycle = mock(MemoryLifecycleService.class);
    private final ContextManifestRepository manifests = mock(ContextManifestRepository.class);
    private final MemoryRelationRepository relations = mock(MemoryRelationRepository.class);
    private final MemoryKnowledgeLinkRepository links = mock(MemoryKnowledgeLinkRepository.class);
    private final MemoryAssertionRepository assertions = mock(MemoryAssertionRepository.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final OperatorSessionService sessions = new OperatorSessionService(
            "operator-bearer-secret", Duration.ofHours(8), "http://localhost:5173", false,
            Clock.systemUTC());

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new MemoryController(command, query, proposals, recall, lifecycle,
                        manifests, relations, links, assertions,
                        new OperatorAuthorityResolver(sessions, actorTokens, ""),
                        new ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new MemoryExceptionHandler())
                .build();
    }

    /** The sandbox worker's peer address: the bridge gateway, never loopback. */
    private static RequestPostProcessor remote() {
        return request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        };
    }

    private void workerTokenIsLive() {
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION)).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));
    }

    private static String workerRefusal() {
        return "Operator authority required";
    }

    private static String anonymousRefusal() {
        return "Operator session required";
    }

    private Cookie operatorSessionCookie() {
        return new Cookie(OperatorSessionService.COOKIE_NAME, sessions.createSession().sessionId());
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

    /** The review material a PENDING page lists: a proposal, not an ACTIVE row. */
    private static MemoryEntity pendingEntity(UUID id) {
        return MemoryEntity.builder()
                .id(id)
                .kind("PERSON")
                .canonicalKey("person:happy")
                .displayName("Happy")
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.PENDING)
                .origin(MemoryOrigin.EXTRACTION)
                .version(1)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    // ------------------------------------------------------- GET /entities

    @Test
    void listEntities_workerBearerIsForbidden_andNeverQueries() throws Exception {
        workerTokenIsLive();

        mvc.perform(get("/api/v1/memory/entities").header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(workerRefusal()));

        verifyNoInteractions(query);
    }

    @Test
    void listEntities_anonymousRemoteIsRefused_andNeverQueries() throws Exception {
        mvc.perform(get("/api/v1/memory/entities").with(remote()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(anonymousRefusal()));

        verifyNoInteractions(query);
    }

    /** The dashboard's local path: anonymous loopback is the single local operator. */
    @Test
    void listEntities_anonymousLoopbackReturnsTheSamePayload() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.listEntities(isNull(), eq(EntityStatus.ACTIVE), anyInt()))
                .thenReturn(List.of(MemoryEntityView.from(entity(id, "Operator"))));

        mvc.perform(get("/api/v1/memory/entities").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].canonicalKey").value("operator"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void listEntities_operatorSessionCookieReturnsTheSamePayload() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.listEntities(isNull(), eq(EntityStatus.ACTIVE), anyInt()))
                .thenReturn(List.of(MemoryEntityView.from(entity(id, "Operator"))));

        mvc.perform(get("/api/v1/memory/entities").param("status", "ACTIVE")
                        .with(remote())
                        .cookie(operatorSessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    /**
     * The authority gate precedes the status narrowing, and that order is load-bearing. Every
     * positive above asks for {@code status=ACTIVE}, so nothing pinned the order: the route resolves
     * authority, then narrows the read. If the gate were moved after the narrowing and left on only
     * one branch, the refusal itself would become an enumeration oracle — a worker asking for
     * {@code status=PENDING} could read 200/empty where the operator reads 403 and learn which
     * statuses exist, and by the same move which ids exist. Both statuses are asked here by the same
     * refused caller, both must answer the SAME refusal, and the service must never be reached.
     */
    @Test
    void listEntities_refusesAWorkerForEitherStatusBeforeTheNarrowing_andNeverQueries() throws Exception {
        workerTokenIsLive();

        for (String status : List.of("PENDING", "ACTIVE")) {
            mvc.perform(get("/api/v1/memory/entities").param("status", status)
                            .header("Authorization", WORKER_AUTHORIZATION))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value(workerRefusal()));
        }

        verifyNoInteractions(query);
    }

    /**
     * Positive control for the test above: the PENDING page is a normal operator read, so its 403 is
     * the authority gate and not a refusal of the status itself.
     */
    @Test
    void listEntities_anonymousLoopbackServesThePendingPage() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.listEntities(isNull(), eq(EntityStatus.PENDING), anyInt()))
                .thenReturn(List.of(MemoryEntityView.from(pendingEntity(id))));

        mvc.perform(get("/api/v1/memory/entities").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    // ------------------------------------------------------- GET /proposals

    @Test
    void listProposals_workerBearerIsForbidden_andNeverReadsTheInbox() throws Exception {
        workerTokenIsLive();

        mvc.perform(get("/api/v1/memory/proposals").header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(workerRefusal()));

        verifyNoInteractions(proposals);
    }

    @Test
    void listProposals_anonymousRemoteIsRefused_andNeverReadsTheInbox() throws Exception {
        mvc.perform(get("/api/v1/memory/proposals").with(remote()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(anonymousRefusal()));

        verifyNoInteractions(proposals);
    }

    /**
     * Positive control: the loopback read still returns the inbox and still names the operator
     * actor, so the PENDING entity card keeps its own row (T11 C1 / UI surface M1).
     */
    @Test
    void listProposals_anonymousLoopbackStillServesTheOperatorInbox() throws Exception {
        when(proposals.listProposals(eq(FactStatus.PENDING), any(MemoryActor.class)))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/memory/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        ArgumentCaptor<MemoryActor> reviewer = ArgumentCaptor.forClass(MemoryActor.class);
        verify(proposals).listProposals(eq(FactStatus.PENDING), reviewer.capture());
        assertThat(reviewer.getValue().type()).isEqualTo(io.aria.conductor.memory.model.MemoryActorType.OPERATOR);
    }

    @Test
    void listProposals_operatorSessionCookieStillServesTheOperatorInbox() throws Exception {
        when(proposals.listProposals(eq(FactStatus.PENDING), any(MemoryActor.class)))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/memory/proposals").with(remote()).cookie(operatorSessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------- GET /recall

    @Test
    void recall_workerBearerIsForbidden_andNeverRecalls() throws Exception {
        workerTokenIsLive();

        mvc.perform(get("/api/v1/memory/recall").param("q", "operator")
                        .header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(workerRefusal()));

        verifyNoInteractions(recall);
    }

    @Test
    void recall_anonymousRemoteIsRefused_andNeverRecalls() throws Exception {
        mvc.perform(get("/api/v1/memory/recall").param("q", "operator").with(remote()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(anonymousRefusal()));

        verifyNoInteractions(recall);
    }

    @Test
    void recall_anonymousLoopbackReturnsTheSamePreview() throws Exception {
        UUID id = UUID.randomUUID();
        when(recall.recall(any())).thenReturn(new RecallResult(
                List.of(MemoryEntityView.from(entity(id, "Operator"))), List.of(), false));

        mvc.perform(get("/api/v1/memory/recall").param("q", "operator").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities[0].id").value(id.toString()))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void recall_operatorSessionCookieReturnsTheSamePreview() throws Exception {
        when(recall.recall(any())).thenReturn(new RecallResult(List.of(), List.of(), false));

        mvc.perform(get("/api/v1/memory/recall").param("q", "operator")
                        .with(remote())
                        .cookie(operatorSessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities", hasSize(0)));
    }

    // ------------------------------------- GET /runs/{id}/context-manifest

    @Test
    void contextManifest_workerBearerIsForbidden_andNeverReadsTheRun() throws Exception {
        workerTokenIsLive();
        UUID runId = UUID.randomUUID();

        mvc.perform(get("/api/v1/memory/runs/{id}/context-manifest", runId)
                        .header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(workerRefusal()));

        verify(manifests, never()).findByRunIdOrderByTurnNumberAsc(any());
    }

    @Test
    void contextManifest_anonymousRemoteIsRefused_andNeverReadsTheRun() throws Exception {
        UUID runId = UUID.randomUUID();

        mvc.perform(get("/api/v1/memory/runs/{id}/context-manifest", runId).with(remote()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(anonymousRefusal()));

        verify(manifests, never()).findByRunIdOrderByTurnNumberAsc(any());
    }

    @Test
    void contextManifest_anonymousLoopbackReturnsTheSameList() throws Exception {
        UUID runId = UUID.randomUUID();
        when(manifests.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/memory/runs/{id}/context-manifest", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void contextManifest_operatorSessionCookieReturnsTheSameList() throws Exception {
        UUID runId = UUID.randomUUID();
        when(manifests.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/memory/runs/{id}/context-manifest", runId)
                        .with(remote())
                        .cookie(operatorSessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}

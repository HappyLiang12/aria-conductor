package io.aria.conductor.execution.housekeeping;

import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategorySummary;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Housekeeping S4: REST surface — read-only scan and confirm-gated execute.
 */
class HousekeepingControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String OPERATOR_AUTHORIZATION = "Bearer operator-bearer-secret";
    private static final String WORKER_AUTHORIZATION = "Bearer worker-token";

    private HousekeepingService service;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment mockEnv = mock(Environment.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    {
        when(mockEnv.getActiveProfiles()).thenReturn(new String[0]);
    }

    @BeforeEach
    void setUp() {
        service = mock(HousekeepingService.class);
        OperatorAuthorityResolver operatorAuthority = new OperatorAuthorityResolver(
                new OperatorSessionService("operator-bearer-secret", Duration.ofHours(8),
                        "http://localhost:5173", false, Clock.systemUTC()),
                actorTokens, "");
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HousekeepingController(service, operatorAuthority))
                .setControllerAdvice(new GlobalExceptionHandler(mockEnv))
                .build();
    }

    @Test
    void scan_returnsCategoryShape() throws Exception {
        when(service.scan(anyBoolean(), any()))
                .thenReturn(new ScanResult(List.of(
                        new CategorySummary("runs", 2, List.of()),
                        new CategorySummary("stuck", 0, List.of())), Instant.now()));

        mockMvc.perform(get("/api/v1/housekeeping/scan").param("includeStuck", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].key").value("runs"))
                .andExpect(jsonPath("$.categories[0].count").value(2))
                .andExpect(jsonPath("$.categories[1].key").value("stuck"));
    }

    @Test
    void execute_withoutConfirm_returns400_andNoSideEffects() throws Exception {
        String body = objectMapper.writeValueAsString(new HousekeepingModel.HousekeepingRequest(
                List.of("kanban"), false, Exclusions.empty(), false));

        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).execute(any());
    }

    @Test
    void execute_withConfirm_returnsReceipt() throws Exception {
        when(service.execute(any())).thenReturn(new HousekeepingReceipt(
                List.of(new CategoryReceipt("kanban", 3, 0, 1)), Instant.now()));
        String body = objectMapper.writeValueAsString(new HousekeepingModel.HousekeepingRequest(
                List.of("kanban"), false, Exclusions.empty(), true));

        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].key").value("kanban"))
                .andExpect(jsonPath("$.categories[0].cleared").value(3));
    }

    // ------------------------------------------------------ execute authority boundary

    private static final String EXECUTE_REFUSAL = "Operator authority required: executing"
            + " housekeeping is an operator decision. Run it from the Housekeeping panel"
            + " on the Ops page.";

    private static RequestPostProcessor remote() {
        return request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        };
    }

    private String executeBody(List<String> categories, boolean confirm) throws Exception {
        return objectMapper.writeValueAsString(new HousekeepingModel.HousekeepingRequest(
                categories, false, Exclusions.empty(), confirm));
    }

    /**
     * The confirm flag is caller-supplied, so it cannot be authority: a worker that
     * sets {@code confirm:true} is still refused and nothing is deleted.
     */
    @Test
    void execute_workerBearerWithConfirmTrue_isForbidden_andNeverExecutes() throws Exception {
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION)).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));

        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .header("Authorization", WORKER_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(List.of("kanban"), true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(EXECUTE_REFUSAL));

        verifyNoInteractions(service);
    }

    @Test
    void execute_anonymousRemoteWithConfirmTrue_isUnauthorized_andNeverExecutes() throws Exception {
        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .with(remote())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(List.of("kanban"), true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(EXECUTE_REFUSAL));

        verifyNoInteractions(service);
    }

    /** Authority is decided BEFORE the body check: a worker gets 403, not the body's 400. */
    @Test
    void execute_workerBearerWithAnInvalidBody_isRefusedByAuthorityFirst() throws Exception {
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION)).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));

        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .header("Authorization", WORKER_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(List.of(), false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(EXECUTE_REFUSAL));

        verifyNoInteractions(service);
    }

    /** Positive control: the operator bearer credential is honoured from a remote address. */
    @Test
    void execute_operatorBearerFromRemoteSucceeds() throws Exception {
        when(service.execute(any())).thenReturn(new HousekeepingReceipt(
                List.of(new CategoryReceipt("kanban", 1, 0, 0)), Instant.now()));

        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .with(remote())
                        .header("Authorization", OPERATOR_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(List.of("kanban"), true)))
                .andExpect(status().isOk());

        verify(service).execute(any());
    }

    /** The scan stays open: it is read-only and the Ops panel previews it without a session. */
    @Test
    void scan_anonymousRemoteIsStillReadable() throws Exception {
        when(service.scan(anyBoolean(), any())).thenReturn(new ScanResult(List.of(), Instant.now()));

        mockMvc.perform(get("/api/v1/housekeeping/scan").with(remote()))
                .andExpect(status().isOk());
    }
}

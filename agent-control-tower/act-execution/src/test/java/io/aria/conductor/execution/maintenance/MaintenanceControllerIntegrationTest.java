package io.aria.conductor.execution.maintenance;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.controller.MaintenanceController;
import io.aria.conductor.execution.security.ActorAuthenticationFilter;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The surviving maintenance surface against a disposable H2 database: the
 * explicit built-in setup it exposes (idempotent create, never repointing an
 * existing builtin) and the route's operator-auth semantics (401 without
 * identity, 403 worker). There is no startup, scheduler or event trigger for
 * the setup: it executes only through the operator call.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaintenanceControllerIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");

    private static final UUID BA_BUILTIN_ID = UUID.fromString("ba000000-0000-0000-0000-000000000001");
    private static final UUID DEV_BUILTIN_ID = UUID.fromString("de000000-0000-0000-0000-000000000002");
    private static final UUID QA_BUILTIN_ID = UUID.fromString("aa000000-0000-0000-0000-000000000003");

    @Autowired private AgentRepository agents;

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private LegacySetupService setupService;
    private OperatorSessionService operatorSessions;
    private MaintenanceController controller;
    private ActorPrincipal operator;
    private ActorPrincipal worker;

    @BeforeEach
    void setUp() {
        setupService = new LegacySetupService(agents, clock);
        operatorSessions = new OperatorSessionService("operator-secret", Duration.ofHours(8),
                OperatorSessionService.DEFAULT_ALLOWED_ORIGINS, false, clock);
        controller = new MaintenanceController(setupService, operatorSessions);
        operator = ActorPrincipal.operator(null);
        // The controller resolves credential expiry with the real clock (the
        // fixed service clock is not the controller's), so the worker
        // credential must not expire while the test runs.
        worker = ActorPrincipal.worker(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1)));
    }

    /* ------------------------------------------------------------------ */
    /* Explicit setup (fresh-install bootstrap)                             */
    /* ------------------------------------------------------------------ */

    @Test
    void initializeMissingBuiltinsCreatesTheThreeRoleBuiltinsWithOpenCodeSandbox() {
        assertThat(agents.findAll()).isEmpty();

        LegacySetupService.SetupReceipt receipt = setupService.initializeMissingBuiltins(operator);

        assertThat(receipt.createdAgentIds()).containsExactlyInAnyOrder(
                BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID);
        assertThat(receipt.existingAgentIds()).isEmpty();
        assertThat(builtin(BA_BUILTIN_ID).getName()).isEqualTo("SDD BA Agent");
        assertThat(builtin(BA_BUILTIN_ID).getRole()).isEqualTo("ba");
        assertThat(builtin(DEV_BUILTIN_ID).getName()).isEqualTo("SDD DEV Agent");
        assertThat(builtin(DEV_BUILTIN_ID).getRole()).isEqualTo("dev");
        assertThat(builtin(QA_BUILTIN_ID).getName()).isEqualTo("SDD QA Agent");
        assertThat(builtin(QA_BUILTIN_ID).getRole()).isEqualTo("qa");
        for (UUID id : List.of(BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID)) {
            assertThat(builtin(id).getAdkProvider()).isEqualTo("opencode");
            assertThat(builtin(id).getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
            assertThat(builtin(id).getAgentType()).isEqualTo(AgentType.NATIVE);
            assertThat(builtin(id).getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(builtin(id).getModel()).isNull();
        }

        LegacySetupService.SetupReceipt second = setupService.initializeMissingBuiltins(operator);
        assertThat(second.createdAgentIds()).isEmpty();
        assertThat(second.existingAgentIds()).containsExactlyInAnyOrder(
                BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID);
    }

    @Test
    void initializeMissingBuiltinsNeverRepointsAnExistingBuiltin() {
        agents.save(agent(BA_BUILTIN_ID, "SDD BA Agent", "ba", "langchain"));

        LegacySetupService.SetupReceipt receipt = setupService.initializeMissingBuiltins(operator);

        assertThat(receipt.createdAgentIds()).containsExactlyInAnyOrder(DEV_BUILTIN_ID, QA_BUILTIN_ID);
        assertThat(receipt.existingAgentIds()).containsExactly(BA_BUILTIN_ID);
        assertThat(builtin(BA_BUILTIN_ID).getAdkProvider()).isEqualTo("langchain");
        assertThat(builtin(BA_BUILTIN_ID).getExecutionMode()).isNull();
    }

    /* ------------------------------------------------------------------ */
    /* Operator authority, endpoints and no automatic execution path        */
    /* ------------------------------------------------------------------ */

    @Test
    void servicesAndEndpointsRefuseEveryNonOperatorCaller() {
        assertThatThrownBy(() -> setupService.initializeMissingBuiltins(worker))
                .isInstanceOf(SecurityException.class).hasMessage("Operator authority required");

        assertStatus(controller.initializeBuiltins(anonymous("POST")), HttpStatus.UNAUTHORIZED);
        assertStatus(controller.initializeBuiltins(workerRequest()), HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredActorIdentityIsUnauthorizedNotForbidden() {
        ActorPrincipal expiredOperator = ActorPrincipal.operator(Instant.now().minusSeconds(1));
        MockHttpServletRequest expiredOperatorRequest = anonymous("POST");
        expiredOperatorRequest.setAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, expiredOperator);

        assertStatus(controller.initializeBuiltins(expiredOperatorRequest), HttpStatus.UNAUTHORIZED);

        ActorPrincipal expiredWorker = ActorPrincipal.worker(UUID.randomUUID(), Instant.now().minusSeconds(1));
        MockHttpServletRequest expiredWorkerRequest = anonymous("POST");
        expiredWorkerRequest.setAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, expiredWorker);
        assertStatus(controller.initializeBuiltins(expiredWorkerRequest), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void maintenanceEndpointsExecuteOnlyWithTheOperatorCredential() {
        ResponseEntity<Object> setupResponse = controller.initializeBuiltins(operatorRequest());
        assertStatus(setupResponse, HttpStatus.OK);
        assertThat(((LegacySetupService.SetupReceipt) setupResponse.getBody()).createdAgentIds())
                .containsExactlyInAnyOrder(BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID);
    }

    @Test
    void setupHasNoAutomaticExecutionPath() {
        for (Class<?> type : List.of(LegacySetupService.class, MaintenanceController.class)) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Scheduled.class))
                        .as("%s.%s must not be scheduled", type.getSimpleName(), method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(PostConstruct.class))
                        .as("%s.%s must not run at startup", type.getSimpleName(), method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(PreDestroy.class))
                        .as("%s.%s must not run at shutdown", type.getSimpleName(), method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(EventListener.class))
                        .as("%s.%s must not react to events", type.getSimpleName(), method.getName()).isFalse();
            }
            assertThat(ApplicationRunner.class.isAssignableFrom(type)).isFalse();
            assertThat(CommandLineRunner.class.isAssignableFrom(type)).isFalse();
        }
        assertThatCode(() -> LegacySetupService.class.getMethod(
                "initializeMissingBuiltins", ActorPrincipal.class)).doesNotThrowAnyException();
    }

    /* ------------------------------------------------------------------ */
    /* Fixture and controller request helpers                               */
    /* ------------------------------------------------------------------ */

    private Agent agent(UUID id, String name, String role, String provider) {
        return Agent.builder().id(id).name(name).role(role).agentType(AgentType.NATIVE)
                .adkProvider(provider)
                .executionMode("opencode".equals(provider) ? ExecutionMode.SANDBOX : null)
                .config("{}").healthStatus(HealthStatus.HEALTHY).pickupEnabled(true).build();
    }

    private Agent builtin(UUID id) {
        return agents.findById(id).orElseThrow();
    }

    private MockHttpServletRequest anonymous(String method) {
        return new MockHttpServletRequest(method, "/api/v1/maintenance/initialize-builtins");
    }

    private MockHttpServletRequest operatorRequest() {
        MockHttpServletRequest request = anonymous("POST");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer operator-secret");
        return request;
    }

    private MockHttpServletRequest workerRequest() {
        MockHttpServletRequest request = anonymous("POST");
        request.setAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, worker);
        return request;
    }

    private void assertStatus(ResponseEntity<?> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).isEqualTo(expected);
    }
}

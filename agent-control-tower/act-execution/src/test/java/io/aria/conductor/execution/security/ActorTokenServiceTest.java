package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.controller.OperatorSessionController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 4 identity boundary, act-execution side: run-scoped worker tokens,
 * the separately configured single-operator credential/session and the
 * operator-route filter. Status codes asserted here are the boundary contract:
 * 401 = unauthenticated (absent/invalid/expired/revoked), 403 = authenticated
 * but unauthorized (worker attempting an operator action, bad CSRF/Origin).
 */
class ActorTokenServiceTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final Instant EXPIRES = Instant.parse("2026-09-22T12:30:00Z");
    private static final String OPERATOR_CREDENTIAL = "operator-credential-9f2c";

    private MutableClock clock = new MutableClock(EXPIRES.minus(Duration.ofMinutes(5)));
    private final ActorTokenService tokens = new ActorTokenService(clock);

    // ------------------------------------------------------------------
    // ActorPrincipal authority
    // ------------------------------------------------------------------

    @Test
    void aWorkerCannotBecomeAnOperator() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        var actor = new ActorPrincipal(ActorPrincipal.Role.WORKER, runId,
                Instant.parse("2026-09-22T12:30:00Z"));
        assertThat(actor.runId()).isEqualTo(runId);
        assertThatThrownBy(actor::requireOperator).isInstanceOf(SecurityException.class)
                .hasMessage("Operator authority required");
    }

    @Test
    void operatorPrincipal_hasOperatorAuthorityAndNoRun() {
        ActorPrincipal operator = ActorPrincipal.operator(null);
        operator.requireOperator();
        assertThat(operator.runId()).isNull();
        assertThat(operator.role()).isEqualTo(ActorPrincipal.Role.OPERATOR);
    }

    @Test
    void expiredPrincipal_isRejected() {
        ActorPrincipal actor = ActorPrincipal.worker(RUN_ID, EXPIRES);
        actor.requireActive(EXPIRES.minusSeconds(1));
        assertThatThrownBy(() -> actor.requireActive(EXPIRES))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Actor credential expired");
    }

    @Test
    void runScopeMismatch_isRejected() {
        ActorPrincipal actor = ActorPrincipal.worker(RUN_ID, EXPIRES);
        actor.requireRun(RUN_ID);
        assertThatThrownBy(() -> actor.requireRun(OTHER_RUN_ID))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Run scope mismatch");
    }

    // ------------------------------------------------------------------
    // Worker tokens
    // ------------------------------------------------------------------

    @Test
    void workerTokenResolvesToItsRunScope() {
        String token = tokens.issueWorker(RUN_ID, EXPIRES);

        ActorPrincipal actor = tokens.authenticateBearer("Bearer " + token);

        assertThat(actor.role()).isEqualTo(ActorPrincipal.Role.WORKER);
        assertThat(actor.runId()).isEqualTo(RUN_ID);
        assertThat(actor.expiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    void workerTokensAreOpaqueUniqueAndNotRunDerived() {
        String first = tokens.issueWorker(RUN_ID, EXPIRES);
        String second = tokens.issueWorker(RUN_ID, EXPIRES);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain(RUN_ID.toString());
        // 32 random bytes, base64url without padding
        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void tokensOfDifferentRunsStayDistinct() {
        String tokenA = tokens.issueWorker(RUN_ID, EXPIRES);
        String tokenB = tokens.issueWorker(OTHER_RUN_ID, EXPIRES);

        assertThat(tokens.authenticateBearer("Bearer " + tokenA).runId()).isEqualTo(RUN_ID);
        assertThat(tokens.authenticateBearer("Bearer " + tokenB).runId()).isEqualTo(OTHER_RUN_ID);
    }

    @Test
    void absentOrMalformedBearerIsUnauthenticated() {
        assertThatThrownBy(() -> tokens.authenticateBearer(null))
                .isInstanceOf(ActorTokenService.InvalidCredentialException.class);
        assertThatThrownBy(() -> tokens.authenticateBearer(""))
                .isInstanceOf(ActorTokenService.InvalidCredentialException.class);
        assertThatThrownBy(() -> tokens.authenticateBearer("Basic c2VjcmV0"))
                .isInstanceOf(ActorTokenService.InvalidCredentialException.class);
        assertThatThrownBy(() -> tokens.authenticateBearer("Bearer "))
                .isInstanceOf(ActorTokenService.InvalidCredentialException.class);
        assertThat(tokens.resolveBearer(null)).isEmpty();
    }

    @Test
    void unknownTokenIsUnauthenticated() {
        assertThatThrownBy(() -> tokens.authenticateBearer("Bearer forged-token"))
                .isInstanceOf(ActorTokenService.InvalidCredentialException.class);
        assertThat(tokens.resolveBearer("Bearer forged-token")).isEmpty();
    }

    @Test
    void expiredTokenIsUnauthenticated() {
        String token = tokens.issueWorker(RUN_ID, EXPIRES);
        clock.advance(Duration.ofMinutes(6));

        assertThat(tokens.resolveBearer("Bearer " + token)).isEmpty();
        assertThatThrownBy(() -> tokens.authenticateBearer("Bearer " + token))
                .isInstanceOf(ActorTokenService.InvalidCredentialException.class)
                .hasMessage("Actor token expired");
    }

    @Test
    void revokeRunInvalidatesOnlyThatRunsTokens() {
        String runToken = tokens.issueWorker(RUN_ID, EXPIRES);
        String otherToken = tokens.issueWorker(OTHER_RUN_ID, EXPIRES);

        tokens.revokeRun(RUN_ID);

        assertThat(tokens.resolveBearer("Bearer " + runToken)).isEmpty();
        assertThatThrownBy(() -> tokens.authenticateBearer("Bearer " + runToken))
                .isInstanceOf(ActorTokenService.InvalidCredentialException.class)
                .hasMessage("Actor token revoked");
        assertThat(tokens.authenticateBearer("Bearer " + otherToken).runId()).isEqualTo(OTHER_RUN_ID);
    }

    @Test
    void workerTokenIsNotAnOperatorCredential() {
        String workerToken = tokens.issueWorker(RUN_ID, EXPIRES);
        OperatorSessionService sessions = operatorSessions();

        assertThat(sessions.verifyOperatorCredential("Bearer " + workerToken)).isFalse();
    }

    // ------------------------------------------------------------------
    // Operator session lifecycle (controller contract)
    // ------------------------------------------------------------------

    @Test
    void operatorSessionCreation_issuesHttpOnlySameSiteCookieAndCsrfToken() throws Exception {
        OperatorSessionService sessions = operatorSessions();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorSessionController(sessions)).build();

        MvcResult result = mvc.perform(post("/api/v1/operator/session")
                        .header("Authorization", "Bearer " + OPERATOR_CREDENTIAL))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(OperatorSessionService.COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(result.getResponse().getContentAsString()).contains("csrfToken");
    }

    @Test
    void operatorSessionCreation_requiresTheSeparatelyConfiguredCredential() throws Exception {
        OperatorSessionService sessions = operatorSessions();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorSessionController(sessions)).build();
        String workerToken = tokens.issueWorker(RUN_ID, EXPIRES);

        assertThat(mvc.perform(post("/api/v1/operator/session")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/operator/session")
                        .header("Authorization", "Bearer wrong-credential")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/operator/session")
                        .header("Authorization", "Bearer " + workerToken)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void operatorSessionCreation_failsClosedWithoutConfiguredCredential() throws Exception {
        OperatorSessionService sessions = new OperatorSessionService("", Duration.ofHours(8),
                "http://localhost:5173", false, clock);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorSessionController(sessions)).build();

        assertThat(mvc.perform(post("/api/v1/operator/session")
                        .header("Authorization", "Bearer anything")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void operatorSessionRevocation_requiresCsrfAndAllowedOrigin() throws Exception {
        OperatorSessionService sessions = operatorSessions();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorSessionController(sessions)).build();
        OperatorSessionService.OperatorSession session = sessions.createSession();

        // missing CSRF header
        assertThat(mvc.perform(delete("/api/v1/operator/session")
                        .cookie(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()))
                        .header("Origin", "http://localhost:5173")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        // cross-origin attempt with a valid CSRF token
        assertThat(mvc.perform(delete("/api/v1/operator/session")
                        .cookie(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()))
                        .header(OperatorSessionService.CSRF_HEADER, session.csrfToken())
                        .header("Origin", "http://evil.example")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        // missing Origin on a cookie-authenticated mutation
        assertThat(mvc.perform(delete("/api/v1/operator/session")
                        .cookie(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()))
                        .header(OperatorSessionService.CSRF_HEADER, session.csrfToken())).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        // still valid afterwards - a rejected mutation must not revoke
        assertThat(sessions.findSession(session.sessionId())).isPresent();

        MvcResult revoked = mvc.perform(delete("/api/v1/operator/session")
                        .cookie(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()))
                        .header(OperatorSessionService.CSRF_HEADER, session.csrfToken())
                        .header("Origin", "http://localhost:5173"))
                .andReturn();
        assertThat(revoked.getResponse().getStatus()).isEqualTo(204);
        assertThat(sessions.findSession(session.sessionId())).isEmpty();
    }

    @Test
    void operatorSessionRevocation_isUnauthenticatedWithoutSession() throws Exception {
        OperatorSessionService sessions = operatorSessions();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorSessionController(sessions)).build();

        assertThat(mvc.perform(delete("/api/v1/operator/session")
                        .header("Origin", "http://localhost:5173")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(delete("/api/v1/operator/session")
                        .cookie(new Cookie(OperatorSessionService.COOKIE_NAME, "forged-session-id"))
                        .header(OperatorSessionService.CSRF_HEADER, "forged-csrf")
                        .header("Origin", "http://localhost:5173")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void expiredOperatorSessionIsUnauthenticated() {
        OperatorSessionService sessions = operatorSessions();
        OperatorSessionService.OperatorSession session = sessions.createSession();

        clock.advance(Duration.ofHours(9));

        assertThat(sessions.findSession(session.sessionId())).isEmpty();
        assertThat(sessions.authenticateSession(session.sessionId())).isEmpty();
    }

    // ------------------------------------------------------------------
    // ActorAuthenticationFilter (operator REST boundary, registered at cutover)
    // ------------------------------------------------------------------

    @Test
    void operatorFilter_rejectsAbsentIdentityWith401() throws Exception {
        ActorAuthenticationFilter filter = operatorFilter(operatorSessions());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/operator/probe");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void operatorFilter_rejectsWorkerTokensWith403() throws Exception {
        ActorAuthenticationFilter filter = operatorFilter(operatorSessions());
        String workerToken = tokens.issueWorker(RUN_ID, EXPIRES);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/operator/probe");
        request.addHeader("Authorization", "Bearer " + workerToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void operatorFilter_rejectsRevokedOrExpiredWorkerTokensWith401() throws Exception {
        ActorAuthenticationFilter filter = operatorFilter(operatorSessions());
        String workerToken = tokens.issueWorker(RUN_ID, EXPIRES);
        tokens.revokeRun(RUN_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/operator/probe");
        request.addHeader("Authorization", "Bearer " + workerToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void operatorFilter_populatesTheActorForOperatorSessions() throws Exception {
        OperatorSessionService sessions = operatorSessions();
        ActorAuthenticationFilter filter = operatorFilter(sessions);
        OperatorSessionService.OperatorSession session = sessions.createSession();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/operator/probe");
        request.setCookies(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
        ActorPrincipal actor = (ActorPrincipal) request.getAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE);
        assertThat(actor.role()).isEqualTo(ActorPrincipal.Role.OPERATOR);
    }

    @Test
    void operatorFilter_rejectsCookieMutationWithoutCsrfWith403() throws Exception {
        OperatorSessionService sessions = operatorSessions();
        ActorAuthenticationFilter filter = operatorFilter(sessions);
        OperatorSessionService.OperatorSession session = sessions.createSession();
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/operator/probe");
        request.setCookies(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()));
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void operatorFilter_leavesUnprotectedRoutesUntouched() throws Exception {
        ActorAuthenticationFilter filter = operatorFilter(operatorSessions());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private OperatorSessionService operatorSessions() {
        return new OperatorSessionService(OPERATOR_CREDENTIAL, Duration.ofHours(8),
                "http://localhost:5173,http://localhost:8080", false, clock);
    }

    private ActorAuthenticationFilter operatorFilter(OperatorSessionService sessions) {
        return new ActorAuthenticationFilter(tokens, sessions, List.of("/api/v1/operator"));
    }

    /** Test clock whose instant can be advanced (expiry/revocation semantics). */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

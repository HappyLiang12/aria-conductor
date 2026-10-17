package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorAuthorityResolverTest {

    private static final String OPERATOR_BEARER = "operator-secret";
    private static final String WORKER_BEARER = "worker-secret";

    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final OperatorSessionService sessions = new OperatorSessionService(
            OPERATOR_BEARER, Duration.ofHours(8),
            OperatorSessionService.DEFAULT_ALLOWED_ORIGINS, false);

    private OperatorAuthorityResolver resolver(String trustedProxies) {
        return new OperatorAuthorityResolver(sessions, actorTokens, trustedProxies);
    }

    private void workerTokenResolves() {
        when(actorTokens.resolveBearer(any())).thenAnswer(invocation ->
                ("Bearer " + WORKER_BEARER).equals(invocation.getArgument(0, String.class))
                        ? Optional.of(mock(ActorPrincipal.class))
                        : Optional.empty());
    }

    @Test
    void explicitOperatorBearerWinsFromAnywhere() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("Authorization", "Bearer " + OPERATOR_BEARER);

        ActorPrincipal principal = resolver("").resolveOperator(request, true);

        assertThat(principal).isNotNull();
    }

    @Test
    void workerBearerIsNeverPromotedEvenFromLoopback() {
        workerTokenResolves();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + WORKER_BEARER);

        assertThatThrownBy(() -> resolver("").resolveOperator(request, true))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("Operator authority required");
    }

    @Test
    void anonymousLoopbackIsOperatorWithoutAnyCredentialConfigured() {
        OperatorSessionService unconfigured = new OperatorSessionService(
                "", Duration.ofHours(8), OperatorSessionService.DEFAULT_ALLOWED_ORIGINS, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        ActorPrincipal principal =
                new OperatorAuthorityResolver(unconfigured, actorTokens, "").resolveOperator(request, false);

        assertThat(principal).isNotNull();
    }

    @Test
    void anonymousRemoteIsUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");

        assertThatThrownBy(() -> resolver("").resolveOperator(request, true))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Operator session required");
    }

    @Test
    void cookieMutationStillRequiresCsrfAndOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        OperatorSessionService.OperatorSession session = sessions.createSession();
        request.setCookies(new jakarta.servlet.http.Cookie(
                OperatorSessionService.COOKIE_NAME, session.sessionId()));
        request.addHeader("X-CSRF-Token", "wrong");
        request.addHeader("Origin", "http://localhost:5173");

        assertThatThrownBy(() -> resolver("").resolveOperator(request, true))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("CSRF validation failed");
    }

    @Test
    void trustedProxyXffLoopbackClientIsOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 127.0.0.1");

        assertThat(resolver("10.0.0.5").resolveOperator(request, false)).isNotNull();
    }

    @Test
    void untrustedPeerXffIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "127.0.0.1");

        assertThatThrownBy(() -> resolver("").resolveOperator(request, false))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void trustedProxyWithoutXffIsNotLoopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");

        assertThatThrownBy(() -> resolver("10.0.0.5").resolveOperator(request, false))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void ipv6LoopbackSpellingIsAccepted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");

        assertThat(resolver("").resolveOperator(request, false)).isNotNull();
    }

    @Test
    void expiredCookieSessionFallsThroughToLoopbackRule() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new jakarta.servlet.http.Cookie(
                OperatorSessionService.COOKIE_NAME, "no-such-session"));

        assertThat(resolver("").resolveOperator(request, false)).isNotNull();
    }

    @Test
    void requestHelperReturnsClientAddressHonoringTrustedProxyOnly() {
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("10.0.0.5");
        trusted.addHeader("X-Forwarded-For", "203.0.113.9, 127.0.0.1");
        assertThat(resolver("10.0.0.5").clientAddress(trusted)).isEqualTo("127.0.0.1");

        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("203.0.113.7");
        untrusted.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(resolver("10.0.0.5").clientAddress(untrusted)).isEqualTo("203.0.113.7");
    }
}

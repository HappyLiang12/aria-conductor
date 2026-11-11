package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The operator session's origin allowlist must cover both loopback spellings a
 * local dashboard can be opened under — {@code localhost} and {@code 127.0.0.1} —
 * on any port, because Vite takes the next free port when the configured one is
 * busy. A browser at the numeric loopback (or at a bumped port) is the same
 * operator on the same machine, and refusing it turned every cookie-authenticated
 * mutation into a 403 while reads still worked.
 */
class OperatorSessionServiceTest {

    private static final String LOCALHOST_ORIGIN = "http://localhost:5173";
    private static final String LOOPBACK_IP_ORIGIN = "http://127.0.0.1:5173";

    private OperatorSessionService serviceWithDefaults() {
        return new OperatorSessionService(
                "operator-credential",
                Duration.ofHours(8),
                OperatorSessionService.DEFAULT_ALLOWED_ORIGINS,
                false,
                Clock.systemUTC());
    }

    private OperatorSessionService serviceWith(String allowedOrigins) {
        return new OperatorSessionService(
                "operator-credential", Duration.ofHours(8), allowedOrigins, false, Clock.systemUTC());
    }

    private static void assertAccepted(OperatorSessionService service, String origin) {
        OperatorSessionService.OperatorSession session = service.createSession();
        assertThatCode(() -> service.validateMutation(session, session.csrfToken(), origin))
                .doesNotThrowAnyException();
    }

    private static void assertRefused(OperatorSessionService service, String origin) {
        OperatorSessionService.OperatorSession session = service.createSession();
        assertThatThrownBy(() -> service.validateMutation(session, session.csrfToken(), origin))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessageContaining("Cross-origin operator mutation rejected");
    }

    @Test
    void defaultOrigins_acceptBothLoopbackSpellingsOnAnyPort() {
        OperatorSessionService service = serviceWithDefaults();

        assertAccepted(service, LOCALHOST_ORIGIN);
        assertAccepted(service, LOOPBACK_IP_ORIGIN);
        assertAccepted(service, "http://localhost:5174");
        assertAccepted(service, "http://localhost:8080");
        assertAccepted(service, "http://127.0.0.1:5199");
    }

    @Test
    void defaultOrigins_rejectEverythingThatIsNotLoopback() {
        OperatorSessionService service = serviceWithDefaults();

        assertRefused(service, "http://evil.example.com");
        assertRefused(service, "http://localhost.evil.com:5173");
        assertRefused(service, "http://127.0.0.1.evil.com:5173");
        assertRefused(service, "https://localhost:5173");
        assertRefused(service, "http://localhost:");
        assertRefused(service, "http://localhost:5x73");
        assertRefused(service, "http://localhost:5173/../../etc");
    }

    @Test
    void configuredExactOrigins_stayExact() {
        OperatorSessionService service = serviceWith("http://localhost:5173");

        assertAccepted(service, LOCALHOST_ORIGIN);
        assertRefused(service, "http://localhost:5174");
        assertRefused(service, LOOPBACK_IP_ORIGIN);
    }

    @Test
    void validOrigin_withWrongCsrfToken_isRejected() {
        OperatorSessionService service = serviceWithDefaults();
        OperatorSessionService.OperatorSession session = service.createSession();

        assertThatThrownBy(() -> service.validateMutation(session, "not-the-token", LOOPBACK_IP_ORIGIN))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessageContaining("CSRF validation failed");
    }

    // ---------------------------------------------------------------- the cross-site marker (T38)

    /**
     * T38 item 1. On the loopback auto-authority branch, an Origin that is present and not ours is
     * the ONLY evidence that a web page the operator had open forged the request — the refusal
     * message is what tells the operator "the page is not trusted", and the PA front end keys on
     * this exact sentence. Pinned verbatim, because the rewording is invisible to every other test:
     * the envelope is the same 403 for the authority refusal next door.
     */
    @Test
    void aDisallowedOriginIsRefusedWithExactlyThePinnedCrossSiteMessage() {
        OperatorSessionService service = serviceWithDefaults();

        assertThatThrownBy(() -> service.rejectCrossSiteOrigin("http://evil.example.com"))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("Cross-origin operator mutation rejected");
        assertThatThrownBy(() -> service.rejectCrossSiteOrigin("http://localhost.evil.com:5173"))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("Cross-origin operator mutation rejected");
        OperatorSessionService.OperatorSession session = service.createSession();
        assertThatThrownBy(() -> service.validateMutation(session, session.csrfToken(), "http://evil.example.com"))
                .as("the sibling cookie path emits the same wording, with CSRF checked only after it")
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("Cross-origin operator mutation rejected");
    }

    /**
     * The two positive controls the cross-site rule needs. A MISSING Origin is the local-tool path
     * (curl, SDKs, the CLI) the loopback contract exists for, so it must not be refused; an
     * operator-trusted Origin must not be either. Both are asserted next to a refusal, because a
     * branch that never throws would satisfy either no-throw assertion on its own.
     */
    @Test
    void aMissingOrAllowedOriginIsNotRefusedWhileADisallowedOneIs() {
        OperatorSessionService service = serviceWithDefaults();

        assertThatCode(() -> service.rejectCrossSiteOrigin(null))
                .as("local tools send no Origin: the loopback contract's audience")
                .doesNotThrowAnyException();
        assertThatCode(() -> service.rejectCrossSiteOrigin(LOCALHOST_ORIGIN)).doesNotThrowAnyException();
        assertThatCode(() -> service.rejectCrossSiteOrigin(LOOPBACK_IP_ORIGIN)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.rejectCrossSiteOrigin("http://evil.example.com"))
                .as("the control: this branch does refuse when the Origin is not trusted")
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("Cross-origin operator mutation rejected");
    }

    /**
     * T38 item 1 (3). Both refusals below are 403 {@code FORBIDDEN} through the same advice and
     * both are {@code ForbiddenMutationException}s, so the message is the ONLY field the front end
     * can discriminate on: "the page is not trusted" must never read as "you are not the operator".
     * Both sides are driven through {@link OperatorAuthorityResolver} — the real producer on the
     * memory decide route — rather than compared as two literals in a test.
     */
    @Test
    void theCrossSiteRefusalIsNotTheAuthorityRefusalOnTheSameRoute() {
        OperatorSessionService service = serviceWithDefaults();
        ActorTokenService actorTokens = mock(ActorTokenService.class);
        when(actorTokens.resolveBearer(any())).thenAnswer(invocation ->
                ("Bearer worker-secret").equals(invocation.getArgument(0, String.class))
                        ? Optional.of(mock(ActorPrincipal.class))
                        : Optional.empty());
        OperatorAuthorityResolver resolver = new OperatorAuthorityResolver(service, actorTokens, "");

        MockHttpServletRequest forgedPage = new MockHttpServletRequest();
        forgedPage.setRemoteAddr("127.0.0.1");
        forgedPage.addHeader("Origin", "http://evil.example.com");
        Throwable crossSite = catchThrowable(() -> resolver.resolveOperator(forgedPage, true));

        MockHttpServletRequest workerCredential = new MockHttpServletRequest();
        workerCredential.setRemoteAddr("127.0.0.1");
        workerCredential.addHeader("Authorization", "Bearer worker-secret");
        Throwable notTheOperator = catchThrowable(() -> resolver.resolveOperator(workerCredential, true));

        assertThat(crossSite).isInstanceOf(OperatorSessionService.ForbiddenMutationException.class);
        assertThat(notTheOperator).isInstanceOf(OperatorSessionService.ForbiddenMutationException.class);
        assertThat(crossSite.getMessage()).isEqualTo("Cross-origin operator mutation rejected");
        assertThat(notTheOperator.getMessage())
                .as("the shipped authority wording, distinct from the cross-site one")
                .isEqualTo("Operator authority required");
        assertThat(crossSite.getMessage()).doesNotContain(notTheOperator.getMessage());
        assertThat(notTheOperator.getMessage()).doesNotContain(crossSite.getMessage());
    }
}

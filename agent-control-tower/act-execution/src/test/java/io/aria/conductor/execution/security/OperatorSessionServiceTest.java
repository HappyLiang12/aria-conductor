package io.aria.conductor.execution.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}

package io.aria.conductor.execution.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The operator session's origin allowlist must cover both loopback spellings a
 * local dashboard can be opened under: {@code localhost} and {@code 127.0.0.1}.
 * A browser at the numeric loopback is the same operator on the same machine,
 * and refusing it turned every cookie-authenticated mutation into a 403 while
 * reads still worked.
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

    @Test
    void defaultOrigins_acceptBothLocalhostAndLoopbackIp() {
        OperatorSessionService service = serviceWithDefaults();
        OperatorSessionService.OperatorSession session = service.createSession();

        assertThatCode(() -> service.validateMutation(session, session.csrfToken(), LOCALHOST_ORIGIN))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validateMutation(session, session.csrfToken(), LOOPBACK_IP_ORIGIN))
                .doesNotThrowAnyException();
    }

    @Test
    void defaultOrigins_rejectForeignOrigins() {
        OperatorSessionService service = serviceWithDefaults();
        OperatorSessionService.OperatorSession session = service.createSession();

        assertThatThrownBy(() -> service.validateMutation(
                session, session.csrfToken(), "http://evil.example.com"))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessageContaining("Cross-origin operator mutation rejected");
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

package io.aria.conductor.execution.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import io.aria.conductor.execution.security.ActorAuthenticationFilter;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator credential surface of the Qoder core (plan section 2.2, spec 6.1):
 *
 * <ul>
 *   <li>{@code GET} — masked metadata only; the stored secret is never returned
 *       and no model is called (credential health, not process liveness).</li>
 *   <li>{@code PUT} — encrypted replacement; the supplied secret is never
 *       echoed, logged or serialized into an error. The body is read and parsed
 *       by this route, so an unparseable payload is rejected with a fixed
 *       message that carries no body content into the response or the logs.</li>
 *   <li>{@code DELETE} — revokes future launches; already injected credentials
 *       end through the normal cancellation path.</li>
 *   <li>{@code POST /test} — the explicit, bounded credential test. It is the
 *       only endpoint that may spend an inference credit, it passes the
 *       memory-only {@link SecretBundle} to the probe (never through argv or a
 *       payload) and it reports provider usage honestly: unknown usage stays
 *       unknown. With no probe wired the endpoint makes no call and answers
 *       503 instead of fabricating a success or a zero cost.</li>
 * </ul>
 *
 * <p>Every method is operator-only: identity comes from the transport (the
 * committed {@code aria.actor} attribute, the operator bearer credential or a
 * mutually validated operator session cookie) and a worker principal is
 * rejected with 403 before any credential operation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/adk/providers/qoder/credential")
public class QoderCredentialController {

    /** Bound of the single credential-test call; one prompt, one timeout. */
    public static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    /** Fixed rejection when no credential value was supplied; contains no request data. */
    static final String CREDENTIAL_REQUIRED_MESSAGE = "A runtime credential value is required";

    /**
     * Flat view of this surface: the masked metadata plus whether this build can
     * actually run the bounded test. The test capability is a property of the
     * wiring -- a {@link CredentialProbe} may simply not exist in the deployed
     * component -- so the dashboard can state it instead of offering a call that
     * can only be refused. No credential value is part of the view.
     */
    public record CredentialView(String credentialRef, String coreId, String environmentVariable,
                                 boolean configured, boolean encryptionKeyConfigured,
                                 String maskedSecret, Instant updatedAt, boolean testSupported) {

        static CredentialView of(RuntimeCredentialService.MaskedMetadata metadata, boolean testSupported) {
            return new CredentialView(metadata.credentialRef(), metadata.coreId(),
                    metadata.environmentVariable(), metadata.configured(),
                    metadata.encryptionKeyConfigured(), metadata.maskedSecret(),
                    metadata.updatedAt(), testSupported);
        }
    }

    /**
     * Payload parser of this route only. Lenient about unknown fields like the
     * production mapper, and never used to raise an exception that carries the
     * body: {@link #requiredSecret(String)} converts parse failures into a
     * fixed message instead.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RuntimeCredentialService credentials;
    private final OperatorSessionService operatorSessions;
    private final CredentialProbe probe;
    private final Duration testTimeout;

    /** Production wiring: no probe exists until the core bridge supplies one (Tasks 8-11). */
    @Autowired
    public QoderCredentialController(RuntimeCredentialService credentials,
            OperatorSessionService operatorSessions,
            ObjectProvider<CredentialProbe> credentialProbe) {
        this(credentials, operatorSessions, credentialProbe.getIfAvailable(), TEST_TIMEOUT);
    }

    /** Test/override seam: explicit probe and test timeout. */
    public QoderCredentialController(RuntimeCredentialService credentials,
            OperatorSessionService operatorSessions, CredentialProbe probe, Duration testTimeout) {
        this.credentials = credentials;
        this.operatorSessions = operatorSessions;
        this.probe = probe;
        this.testTimeout = testTimeout;
    }

    @GetMapping
    public ResponseEntity<Object> getCredential(HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, false);
        if (rejection != null) {
            return rejection;
        }
        return ResponseEntity.ok(CredentialView.of(credentials.qoderMetadata(), probe != null));
    }

    @PutMapping
    public ResponseEntity<Object> putCredential(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        String secret;
        try {
            secret = requiredSecret(rawBody);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        ActorPrincipal actor = operator(request, true);
        RuntimeCredentialService.MaskedMetadata masked = credentials.putQoder(secret, actor);
        // The secret is intentionally absent from this log line and from the response.
        log.info("Qoder runtime credential replaced by operator");
        return ResponseEntity.ok(CredentialView.of(masked, probe != null));
    }

    @DeleteMapping
    public ResponseEntity<Object> deleteCredential(HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        credentials.deleteQoder(operator(request, true));
        log.info("Qoder runtime credential removed; future launches can no longer resolve it");
        return ResponseEntity.noContent().build();
    }

    /**
     * Explicit bounded credential test (never part of readiness polling). One
     * bounded call is delegated to the wired probe; when no probe exists no
     * call is made and the caller gets an honest 503 rather than a fabricated
     * result. The credential is resolved before the probe check so a missing
     * encryption key (an unusable store) is reported as the key failure it is,
     * not as a missing bridge; either way no model call is made.
     */
    @PostMapping("/test")
    public ResponseEntity<Object> testCredential(HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        if (!credentials.qoderMetadata().configured()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Qoder runtime credential is not configured"));
        }
        SecretBundle credential;
        try {
            credential = credentials.resolve(RuntimeCredentialService.QODER_CREDENTIAL_REFERENCE);
        } catch (IllegalStateException e) {
            // Missing encryption key or undecryptable ciphertext: refuse to test
            // instead of inventing an outcome.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("tested", false, "reason", e.getMessage()));
        }
        if (probe == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "tested", false,
                    "reason", "The bounded Qoder credential test requires the run-owned core bridge,"
                            + " which is not wired in this component; no model call was made."));
        }
        CredentialTestOutcome outcome = probe.test(credential, testTimeout);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tested", true);
        response.put("authenticated", outcome.authenticated());
        response.put("model", outcome.model());
        response.put("detail", outcome.detail());
        response.put("usage", outcome.usage());
        response.put("costDisclosure", "One bounded prompt through the pinned core with the configured"
                + " credential; only provider-reported usage is reported, and unknown usage stays unknown.");
        return ResponseEntity.ok(response);
    }

    /**
     * The bounded live call seam. A real Qoder credential test must drive the
     * pinned CLI through the run-owned ACP bridge (Tasks 8-11); wiring that
     * probe here is a composition concern, so this component refuses the test
     * explicitly instead of duplicating the bridge.
     */
    @FunctionalInterface
    public interface CredentialProbe {

        /** Performs exactly one bounded call with the resolved credential. */
        CredentialTestOutcome test(SecretBundle credential, Duration timeout);
    }

    /** Outcome of one bounded probe call; unknown usage is carried as nulls, never zeros. */
    public record CredentialTestOutcome(boolean authenticated, String model, String detail,
            UsageSnapshot usage) {
    }

    /** Credential replacement payload; never logged or echoed. */
    public record QoderCredentialRequest(String secret) {

        /** Redacted like the program's other secret carriers: a log line must never print the plaintext. */
        @Override
        public String toString() {
            return "QoderCredentialRequest[redacted]";
        }
    }

    /**
     * Fixed rejection of a body this route could not parse. The body is read as
     * a raw string first: letting Jackson deserialize straight into a handler
     * parameter would make it throw a parse exception whose message names the
     * offending token, and the shared error handler logs that exception -- with
     * a plaintext secret in the body, the credential prefix would reach the
     * logs. Here nothing but this fixed sentence is ever logged.
     */
    private static String requiredSecret(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new IllegalArgumentException(CREDENTIAL_REQUIRED_MESSAGE);
        }
        QoderCredentialRequest parsed;
        try {
            parsed = JSON.readValue(rawBody, QoderCredentialRequest.class);
        } catch (JsonProcessingException e) {
            // Deliberately not logging the exception: its message can carry body content.
            log.warn("Rejected a malformed Qoder credential payload; the body content is not logged");
            throw new IllegalArgumentException("Malformed Qoder credential payload");
        }
        if (parsed == null || parsed.secret() == null || parsed.secret().isBlank()) {
            throw new IllegalArgumentException(CREDENTIAL_REQUIRED_MESSAGE);
        }
        return parsed.secret();
    }

    /**
     * Null when the caller holds operator authority; otherwise the exact
     * rejection: 401 with no identity, 403 for a worker/non-operator principal
     * or a failed Origin/CSRF validation of a cookie-authenticated mutation.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, boolean mutation) {
        ActorPrincipal actor;
        try {
            actor = operator(request, mutation);
        } catch (SecurityException e) {
            return forbidden();
        }
        return actor == null ? unauthorized() : null;
    }

    /** Resolves the operator principal, or {@code null} when no valid identity is presented. */
    private ActorPrincipal operator(HttpServletRequest request, boolean mutation) {
        Object actorAttribute = request.getAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE);
        if (actorAttribute instanceof ActorPrincipal principal) {
            principal.requireActive(Instant.now());
            principal.requireOperator();
            return principal;
        }
        if (operatorSessions.verifyOperatorCredential(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return ActorPrincipal.operator(null);
        }
        String sessionId = sessionCookie(request);
        OperatorSessionService.OperatorSession session = operatorSessions.findSession(sessionId).orElse(null);
        if (session == null) {
            return null;
        }
        if (mutation) {
            operatorSessions.validateMutation(session, request.getHeader(OperatorSessionService.CSRF_HEADER),
                    request.getHeader(HttpHeaders.ORIGIN));
        }
        return ActorPrincipal.operator(session.expiresAt());
    }

    private static String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (OperatorSessionService.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Operator credential required"));
    }

    private static ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Operator authority required"));
    }
}

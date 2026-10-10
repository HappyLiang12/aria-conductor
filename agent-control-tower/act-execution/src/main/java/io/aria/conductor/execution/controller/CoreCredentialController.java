package io.aria.conductor.execution.controller;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.credential.CredentialProbe;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Plain core credential surface (2026-10-05 simplification): the same authority
 * resolution as every other operator-gated route -- which on loopback means no
 * ceremony at all -- and llm-config-style masked reads. The stored value is
 * never returned and never logged; the bounded test stays an explicit,
 * never-polled endpoint with an honest 503 when no probe is wired.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cores/{coreId}/credential")
public class CoreCredentialController {

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
    public record CoreCredentialView(String credentialRef, String coreId, String environmentVariable,
            boolean configured, String maskedSecret, Instant updatedAt, boolean testSupported) {

        static CoreCredentialView of(CoreCredentialService.MaskedMetadata metadata, boolean testSupported) {
            return new CoreCredentialView(metadata.credentialRef(), metadata.coreId(),
                    metadata.environmentVariable(), metadata.configured(), metadata.maskedSecret(),
                    metadata.updatedAt(), testSupported);
        }
    }

    private final CoreCredentialService credentials;
    private final OperatorAuthorityResolver operatorAuthority;
    private final CredentialProbe probe;
    private final Duration testTimeout;

    /** Production wiring: no probe exists until the core bridge supplies one. */
    @Autowired
    public CoreCredentialController(CoreCredentialService credentials,
            OperatorAuthorityResolver operatorAuthority,
            ObjectProvider<CredentialProbe> credentialProbe) {
        this(credentials, operatorAuthority, credentialProbe.getIfAvailable(), TEST_TIMEOUT);
    }

    /** Test/override seam: explicit probe and test timeout. */
    public CoreCredentialController(CoreCredentialService credentials,
            OperatorAuthorityResolver operatorAuthority, CredentialProbe probe, Duration testTimeout) {
        this.credentials = credentials;
        this.operatorAuthority = operatorAuthority;
        this.probe = probe;
        this.testTimeout = testTimeout;
    }

    @GetMapping
    public ResponseEntity<Object> getCredential(@PathVariable String coreId, HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, false);
        if (rejection != null) {
            return rejection;
        }
        try {
            return ResponseEntity.ok(CoreCredentialView.of(credentials.metadata(coreId), probe != null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<Object> putCredential(@PathVariable String coreId,
            @RequestBody(required = false) String rawBody, HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", CREDENTIAL_REQUIRED_MESSAGE));
        }
        // The body is read as a raw string and handed to the store as given: no
        // JSON parse step whose exception message could carry body content into
        // the logs. The secret is intentionally absent from this log line and
        // from the response.
        try {
            CoreCredentialService.MaskedMetadata masked =
                    credentials.put(coreId, rawBody.trim(), operator(request, true));
            log.info("Core credential for {} replaced by operator", coreId);
            return ResponseEntity.ok(CoreCredentialView.of(masked, probe != null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<Object> deleteCredential(@PathVariable String coreId, HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        try {
            credentials.delete(coreId, operator(request, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Explicit bounded credential test (never part of readiness polling). One
     * bounded call is delegated to the wired probe; when no probe exists no
     * call is made and the caller gets an honest 503 rather than a fabricated
     * result.
     */
    @PostMapping("/test")
    public ResponseEntity<Object> testCredential(@PathVariable String coreId, HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        boolean configured;
        try {
            configured = credentials.metadata(coreId).configured();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (!configured) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Qoder runtime credential is not configured"));
        }
        if (probe == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "tested", false,
                    "reason", "The bounded Qoder credential test requires the run-owned core bridge,"
                            + " which is not wired in this component; no model call was made."));
        }
        SecretBundle credential = credentials.resolve(CoreCredentialService.QODER_CREDENTIAL_REFERENCE);
        CredentialProbe.CredentialTestOutcome outcome = probe.test(credential, testTimeout);
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
     * Null when the caller holds operator authority; otherwise the exact
     * rejection: 401 with no verifiable identity, 403 for a worker bearer
     * credential or a failed Origin/CSRF validation of a cookie-authenticated
     * mutation.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, boolean mutation) {
        ActorPrincipal actor;
        try {
            actor = operatorAuthority.resolveOperator(request, mutation);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return forbidden();
        } catch (SecurityException e) {
            return unauthorized();
        }
        return actor == null ? unauthorized() : null;
    }

    /** Resolves the operator principal; call only after {@link #rejectNonOperator} passed. */
    private ActorPrincipal operator(HttpServletRequest request, boolean mutation) {
        return operatorAuthority.resolveOperator(request, mutation);
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

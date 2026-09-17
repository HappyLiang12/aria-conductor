package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.adk.qoder.QoderProperties;
import io.aria.conductor.execution.credential.RuntimeCredentialException;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.credential.RuntimeCredentialStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator-facing credential management for the {@code qoder} provider (the DB-backed encrypted
 * store from the runtime-credential service). Mirrors the {@code /api/v1/adk} provider surface.
 *
 * <p>Contract (pinned for the provider UI):
 * <ul>
 *   <li>{@code GET .../credential} → 200 {@code {providerId, configured, patMasked|null,
 *       updatedAt|null, model}}; no credential stored is a normal 200 with nulls.</li>
 *   <li>{@code PUT .../credential {"pat": "..."}} → 200 with the same masked status shape; the
 *       response never echoes the supplied secret.</li>
 *   <li>{@code DELETE .../credential} → 204, idempotent (deleting an absent credential is a
 *       no-op, not an error).</li>
 *   <li>{@code POST .../credential/test} → 200 bounded NON-billable structural probe
 *       ({@code {success, reason?, model, billable:false, costNote, message?}}): the credential is
 *       configured, decrypts and is non-blank. No sandbox is launched and no inference runs —
 *       that path belongs to the provider task. {@code KEY_NOT_CONFIGURED} (store cannot
 *       encrypt/decrypt because the master key is missing) → 503 {@code {code}}.</li>
 * </ul>
 *
 * <p>Security shape: the PAT travels one way only. It is handed straight to the credential
 * service, never logged, never placed in a response body or header, and never included in an
 * exception message — validation failures use a constant message and store failures are mapped
 * to fixed, credential-free bodies.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/adk/providers/qoder/credential")
public class QoderCredentialController {

    /** Provider id this surface manages (the route is provider-specific by design). */
    static final String PROVIDER_ID = "qoder";

    /** Disclosure attached to every probe result: bounded structural probe, no billing. */
    static final String COST_NOTE = "This probe runs no billable inference: it only verifies that a"
            + " stored credential is configured, decrypts and is non-blank. Using the credential"
            + " in a run may consume Qoder credits, and zero cost is not guaranteed.";

    private static final String KEY_NOT_CONFIGURED_CODE = "KEY_NOT_CONFIGURED";
    private static final String KEY_NOT_CONFIGURED_MESSAGE =
            "Runtime credential encryption is not configured (PACK_CREDENTIAL_KEY is missing);"
                    + " this store does not accept the development fallback.";
    private static final String NOT_CONFIGURED_MESSAGE =
            "No usable credential is configured for provider qoder; save a PAT and retry.";
    private static final String CIPHER_FAILED_MESSAGE =
            "The stored credential could not be decrypted; re-save the PAT.";

    private final RuntimeCredentialService credentialService;
    private final QoderProperties qoderProperties;

    public QoderCredentialController(RuntimeCredentialService credentialService,
                                     QoderProperties qoderProperties) {
        this.credentialService = credentialService;
        this.qoderProperties = qoderProperties;
    }

    /**
     * {@code GET /api/v1/adk/providers/qoder/credential} — masked status; absence is reported as
     * {@code configured:false} with nulls, still 200. 503 when a row exists but the store cannot
     * decrypt it because {@code PACK_CREDENTIAL_KEY} is not configured.
     */
    @GetMapping
    public ResponseEntity<Object> getCredential() {
        try {
            return ResponseEntity.ok(maskedStatus());
        } catch (RuntimeCredentialException e) {
            log.warn("Qoder credential status unavailable (cause={})", e.cause());
            return storeFailure(e);
        }
    }

    /**
     * {@code PUT /api/v1/adk/providers/qoder/credential} — store or replace the PAT. Responds
     * with the same masked status shape as GET; the supplied secret is never echoed (body,
     * headers or error text).
     */
    @PutMapping
    public ResponseEntity<Object> putCredential(@RequestBody QoderCredentialDtos.PatRequest request) {
        String pat = request == null ? null : request.pat();
        if (pat == null || pat.isBlank()) {
            // Constant message: the submitted value must not be echoed anywhere.
            log.warn("Rejected qoder credential update: pat is missing or blank");
            throw new IllegalArgumentException("pat is required and must not be blank");
        }
        try {
            credentialService.save(PROVIDER_ID, pat);
            log.info("Qoder runtime credential stored via API (provider={})", PROVIDER_ID);
            return ResponseEntity.ok(maskedStatus());
        } catch (RuntimeCredentialException e) {
            log.warn("Qoder credential update rejected (cause={})", e.cause());
            return storeFailure(e);
        }
    }

    /**
     * {@code DELETE /api/v1/adk/providers/qoder/credential} — always 204 (idempotent; deleting an
     * absent credential is a no-op, not an error).
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteCredential() {
        credentialService.delete(PROVIDER_ID);
        log.info("Qoder runtime credential deleted via API (provider={})", PROVIDER_ID);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /api/v1/adk/providers/qoder/credential/test} — bounded NON-billable structural
     * probe: the credential is configured, decrypts and is non-blank; the configured model is
     * reported. Credential-state failures are 200 with {@code success:false} plus a {@code reason}
     * code and a safe message; the store-level {@code KEY_NOT_CONFIGURED} failure is a 503.
     */
    @PostMapping("/test")
    public ResponseEntity<Object> testCredential() {
        String model = qoderProperties.getModel();
        try {
            if (!credentialService.configured(PROVIDER_ID)) {
                log.info("Qoder credential probe: not configured (provider={})", PROVIDER_ID);
                return ResponseEntity.ok(failure(RuntimeCredentialException.Cause.NOT_CONFIGURED, model));
            }
            String pat = credentialService.read(PROVIDER_ID);
            if (pat == null || pat.isBlank()) {
                log.warn("Qoder credential probe: stored credential is empty (provider={})", PROVIDER_ID);
                return ResponseEntity.ok(failure(RuntimeCredentialException.Cause.NOT_CONFIGURED, model));
            }
            log.info("Qoder credential probe succeeded (provider={}, model={})", PROVIDER_ID, model);
            return ResponseEntity.ok(new QoderCredentialDtos.CredentialTestResponse(
                    true, null, model, false, COST_NOTE, null));
        } catch (RuntimeCredentialException e) {
            if (e.cause() == RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED) {
                log.warn("Qoder credential probe: encryption not configured; refusing to report status");
                return storeFailure(e);
            }
            // NOT_CONFIGURED (row vanished between check and read) or CIPHER_FAILED.
            log.warn("Qoder credential probe failed (cause={})", e.cause());
            return ResponseEntity.ok(failure(e.cause(), model));
        }
    }

    // ---- helpers ----

    /**
     * Local override of the shared bad-body mapping for this endpoint. A malformed credential
     * body can carry the PAT verbatim (for example an unquoted JSON value), and Jackson quotes
     * that token inside its parse-error message. The shared handler logs the exception and, on
     * the h2 profile, echoes its message into the response — so here the body is rejected with a
     * constant, structured 400 and the exception itself is never logged. This handler is declared
     * on the controller (not the shared advice) so no other endpoint changes behaviour.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Rejected qoder credential request: body is missing or not valid JSON (provider={})",
                PROVIDER_ID);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", HttpStatus.BAD_REQUEST.getReasonPhrase());
        body.put("message", "Invalid request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private QoderCredentialDtos.CredentialStatusResponse maskedStatus() {
        RuntimeCredentialStatus status = credentialService.maskedStatus(PROVIDER_ID);
        return new QoderCredentialDtos.CredentialStatusResponse(PROVIDER_ID, status.configured(),
                status.patMasked(), isoInstant(status.updatedAt()), qoderProperties.getModel());
    }

    /** Explicit ISO-8601 wire format, independent of the runtime converter's time module. */
    private static String isoInstant(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private QoderCredentialDtos.CredentialTestResponse failure(RuntimeCredentialException.Cause cause,
                                                              String model) {
        return new QoderCredentialDtos.CredentialTestResponse(
                false, cause.name(), model, false, COST_NOTE, safeMessage(cause));
    }

    /** Fixed, credential-free bodies for store failures. Never echoes the exception message. */
    private ResponseEntity<Object> storeFailure(RuntimeCredentialException e) {
        if (e.cause() == RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", KEY_NOT_CONFIGURED_CODE,
                            "message", KEY_NOT_CONFIGURED_MESSAGE));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", e.cause().name(), "message", safeMessage(e.cause())));
    }

    private static String safeMessage(RuntimeCredentialException.Cause cause) {
        return switch (cause) {
            case KEY_NOT_CONFIGURED -> KEY_NOT_CONFIGURED_MESSAGE;
            case NOT_CONFIGURED -> NOT_CONFIGURED_MESSAGE;
            case CIPHER_FAILED -> CIPHER_FAILED_MESSAGE;
        };
    }
}

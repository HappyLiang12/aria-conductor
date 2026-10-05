package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.CoreCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.CoreCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Plain credential store for core launches. The run binding freezes the
 * reference ({@code qoder:operator}); resolve() is the single plaintext exit
 * and exists only to build the memory-only SecretBundle for the launch. A
 * missing credential fails admission loudly. Management (put/delete) requires
 * an operator principal; reads are masked, the value is never echoed and never
 * logged.
 */
@Service
public class CoreCredentialService {

    /** Reference of the single Qoder runtime credential, recorded in run bindings. */
    public static final String QODER_CREDENTIAL_REFERENCE = "qoder:operator";
    public static final String QODER_CORE_ID = "qoder";

    /** The exact variable the pinned CLI consumes (capability evidence, qoder/HOST). */
    public static final String QODER_ENVIRONMENT_VARIABLE = "QODER_PERSONAL_ACCESS_TOKEN";

    private final CoreCredentialRepository credentials;
    private final Clock clock;

    @Autowired
    public CoreCredentialService(CoreCredentialRepository credentials) {
        this(credentials, Clock.systemUTC());
    }

    /** Test/override seam: explicit clock (no {@code Clock} bean exists in the context). */
    public CoreCredentialService(CoreCredentialRepository credentials, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "Credential repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public SecretBundle resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException("Runtime credential reference is required");
        }
        String coreId = coreIdOf(credentialRef);
        CoreCredential row = credentials.findById(coreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime credential is not configured: " + credentialRef));
        return new SecretBundle(credentialRef, Map.of(row.getEnvironmentVariable(), row.getValue()));
    }

    public MaskedMetadata put(String coreId, String value, ActorPrincipal actor) {
        requireOperator(actor);
        if (!QODER_CORE_ID.equals(coreId)) {
            throw new IllegalArgumentException("Unsupported core credential: " + coreId);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A runtime credential value is required");
        }
        Instant now = clock.instant();
        CoreCredential row = credentials.findById(coreId)
                .orElseGet(() -> CoreCredential.builder()
                        .coreId(coreId)
                        .environmentVariable(QODER_ENVIRONMENT_VARIABLE)
                        .createdAt(now)
                        .build());
        row.setValue(value.trim());
        row.setUpdatedAt(now);
        credentials.save(row);
        return metadata(coreId);
    }

    public MaskedMetadata metadata(String coreId) {
        return credentials.findById(coreId)
                .map(row -> new MaskedMetadata(credentialRefOf(row.getCoreId()), row.getCoreId(),
                        row.getEnvironmentVariable(), true, mask(row.getValue()), row.getUpdatedAt()))
                .orElseGet(() -> new MaskedMetadata(credentialRefOf(coreId), coreId,
                        QODER_ENVIRONMENT_VARIABLE, false, null, null));
    }

    public void delete(String coreId, ActorPrincipal actor) {
        requireOperator(actor);
        credentials.deleteById(coreId);
    }

    private static String coreIdOf(String credentialRef) {
        int colon = credentialRef.indexOf(':');
        return colon < 0 ? credentialRef : credentialRef.substring(0, colon);
    }

    private static String credentialRefOf(String coreId) {
        return QODER_CORE_ID.equals(coreId) ? QODER_CREDENTIAL_REFERENCE : "";
    }

    static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private static void requireOperator(ActorPrincipal actor) {
        if (actor == null) {
            throw new SecurityException("Operator authority required");
        }
        actor.requireOperator();
    }

    /** Masked credential metadata safe for REST/UI responses -- never the value. */
    public record MaskedMetadata(String credentialRef, String coreId, String environmentVariable,
            boolean configured, String maskedSecret, Instant updatedAt) {
    }
}

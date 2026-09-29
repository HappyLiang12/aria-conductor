package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.RuntimeCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.RuntimeCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Managed runtime credentials for core launches (spec 6.1).
 *
 * <p>Stored values are AES-256-GCM ciphertext under a <em>required</em>
 * configured key with a fresh 96-bit nonce per encryption; the core id and
 * credential reference are authenticated data, so ciphertext moved to another
 * reference or core fails to decrypt instead of silently resolving. A missing
 * key fails loudly on first use -- there is no plaintext and no Base64
 * development fallback (the pack-credential cipher's fallback is deliberately
 * not copied), and no other component of this class ever returns a stored
 * secret: management responses carry masked metadata only.
 *
 * <p>{@link #resolve(String)} is the single plaintext exit, and it exists only
 * to build the memory-only {@link SecretBundle} handed to the launch; the
 * secret reaches the child solely as the recorded environment variable, never
 * as an argument.
 *
 * <p>The service refuses management without an operator {@link ActorPrincipal};
 * a worker or unauthenticated caller cannot store, read or delete the
 * credential, and deletion revokes future resolution (already injected
 * credentials are ended through the normal cancellation path, not here).
 */
@Service
public class RuntimeCredentialService {

    /** Reference of the single Qoder runtime credential, recorded in run bindings. */
    public static final String QODER_CREDENTIAL_REFERENCE = "qoder:operator";
    public static final String QODER_CORE_ID = "qoder";

    /** The exact variable the pinned CLI consumes (capability evidence, qoder/HOST). */
    public static final String QODER_ENVIRONMENT_VARIABLE = "QODER_PERSONAL_ACCESS_TOKEN";

    /** Fixed, non-reversible display mask; no fragment of the value is ever returned. */
    public static final String MASKED_SECRET = "********";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final byte[] DERIVATION_SALT =
            "aria-conductor-runtime-credentials".getBytes(StandardCharsets.UTF_8);
    private static final int DERIVATION_ITERATIONS = 100_000;

    private final RuntimeCredentialRepository credentials;
    /** Null when no key is configured; {@link #requireKey()} then fails loudly. */
    private final SecretKeySpec key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public RuntimeCredentialService(RuntimeCredentialRepository credentials,
            @Value("${aria.runtime-credentials.encryption-key:${ARIA_RUNTIME_CREDENTIAL_KEY:}}")
            String encryptionKey) {
        this(credentials, encryptionKey, Clock.systemUTC());
    }

    /** Test/override seam: explicit key material and clock. */
    public RuntimeCredentialService(RuntimeCredentialRepository credentials, String encryptionKey, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "Credential repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.key = encryptionKey == null || encryptionKey.isBlank()
                ? null
                : new SecretKeySpec(deriveKey(encryptionKey), "AES");
    }

    /**
     * Resolves one configured credential into the memory-only bundle a launch
     * injects as environment.
     *
     * @throws IllegalArgumentException when no credential is configured for the
     *         reference (deleted or never stored), so a launch fails admission
     *         instead of running without its credential
     * @throws IllegalStateException when the configured key is missing or the
     *         stored ciphertext cannot be authenticated
     */
    public SecretBundle resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException("Runtime credential reference is required");
        }
        RuntimeCredential row = credentials.findById(credentialRef)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime credential is not configured: " + credentialRef));
        String secret = decrypt(row.getEncValue(), binding(row));
        return new SecretBundle(credentialRef, Map.of(row.getEnvironmentVariable(), secret));
    }

    /**
     * Masked metadata of the Qoder credential; never decrypts and never returns
     * the secret. The stored ciphertext and the configured encryption key are
     * reported separately, so a stored credential whose key is missing is
     * visible as unreadable state ({@code configured=true},
     * {@code encryptionKeyConfigured=false}) instead of a misleading "ready" --
     * a later resolve of that row still fails loudly.
     */
    public MaskedMetadata qoderMetadata() {
        boolean keyConfigured = key != null;
        return credentials.findById(QODER_CREDENTIAL_REFERENCE)
                .map(row -> MaskedMetadata.configured(row, keyConfigured))
                .orElseGet(() -> MaskedMetadata.notConfigured(keyConfigured));
    }

    /**
     * Stores or replaces the Qoder runtime credential, encrypted, and returns
     * masked metadata. Operator authority is required; the secret is never
     * echoed back and never logged.
     */
    public MaskedMetadata putQoder(String secret, ActorPrincipal actor) {
        requireOperator(actor);
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Qoder runtime credential must not be blank");
        }
        // The required key is checked before any persistence interaction, so a
        // missing key can never be masked by a repository failure.
        String ciphertext = encrypt(secret,
                authenticatedData(QODER_CORE_ID, QODER_CREDENTIAL_REFERENCE));
        Instant now = clock.instant();
        RuntimeCredential row = credentials.findById(QODER_CREDENTIAL_REFERENCE)
                .orElseGet(() -> RuntimeCredential.builder()
                        .credentialRef(QODER_CREDENTIAL_REFERENCE)
                        .coreId(QODER_CORE_ID)
                        .environmentVariable(QODER_ENVIRONMENT_VARIABLE)
                        .createdAt(now)
                        .build());
        row.setEncValue(ciphertext);
        row.setUpdatedAt(now);
        credentials.save(row);
        return MaskedMetadata.configured(row, true);
    }

    /**
     * Deletes the Qoder credential so future launches can no longer resolve it
     * (spec 6.1). Operator authority is required; a missing row is a no-op.
     */
    public void deleteQoder(ActorPrincipal actor) {
        requireOperator(actor);
        credentials.deleteById(QODER_CREDENTIAL_REFERENCE);
    }

    // ------------------------------------------------------------------ crypto

    private String encrypt(String plaintext, String binding) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Runtime credential encryption failed", e);
        }
    }

    private String decrypt(String encoded, String binding) {
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= NONCE_LENGTH) {
                throw new IllegalStateException("Runtime credential decryption failed: truncated ciphertext");
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(combined, NONCE_LENGTH, combined.length - NONCE_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // AEAD tag failure: tampered ciphertext, foreign key or a ciphertext bound
            // to another core/reference. The message never echoes the value.
            throw new IllegalStateException(
                    "Runtime credential decryption failed (tampered, wrong key or wrong binding)", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Runtime credential encryption key is not configured"
                    + " (aria.runtime-credentials.encryption-key / ARIA_RUNTIME_CREDENTIAL_KEY);"
                    + " refusing to store or read runtime secrets");
        }
    }

    private static byte[] deriveKey(String keyMaterial) {
        try {
            PBEKeySpec spec = new PBEKeySpec(keyMaterial.toCharArray(),
                    DERIVATION_SALT, DERIVATION_ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Runtime credential key derivation failed", e);
        }
    }

    private static String binding(RuntimeCredential row) {
        return authenticatedData(row.getCoreId(), row.getCredentialRef());
    }

    /**
     * Authenticated data binding one ciphertext to its core id and credential
     * reference. Both components are length-prefixed: a plain
     * {@code core + ":" + ref} concatenation would let two different pairs
     * (e.g. {@code ("a", "b:c")} and {@code ("a:b", "c")}) share one AAD, so a
     * ciphertext could be rebound between references whose separator moved.
     */
    static String authenticatedData(String coreId, String credentialRef) {
        return coreId.length() + ":" + coreId + ":" + credentialRef.length() + ":" + credentialRef;
    }

    private static void requireOperator(ActorPrincipal actor) {
        if (actor == null) {
            throw new SecurityException("Operator authority required");
        }
        actor.requireOperator();
    }

    /**
     * Masked credential metadata safe for REST/UI responses: configuration
     * state, key usability, the exact injected variable and a fixed mask --
     * never the secret and never a fragment of it.
     */
    public record MaskedMetadata(String credentialRef, String coreId, String environmentVariable,
            boolean configured, boolean encryptionKeyConfigured, String maskedSecret, Instant updatedAt) {

        static MaskedMetadata configured(RuntimeCredential row, boolean encryptionKeyConfigured) {
            return new MaskedMetadata(row.getCredentialRef(), row.getCoreId(),
                    row.getEnvironmentVariable(), true, encryptionKeyConfigured,
                    MASKED_SECRET, row.getUpdatedAt());
        }

        static MaskedMetadata notConfigured(boolean encryptionKeyConfigured) {
            return new MaskedMetadata(QODER_CREDENTIAL_REFERENCE, QODER_CORE_ID,
                    QODER_ENVIRONMENT_VARIABLE, false, encryptionKeyConfigured, null, null);
        }
    }
}

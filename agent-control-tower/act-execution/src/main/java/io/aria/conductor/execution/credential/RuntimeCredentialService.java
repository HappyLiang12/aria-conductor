package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.RuntimeCredential;
import io.aria.conductor.common.repository.RuntimeCredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * DB-backed store for deployment-scoped runtime provider credentials (e.g. the Qoder PAT).
 *
 * <p>Values are encrypted at rest through {@link PackCredentialCipher}, and this store refuses to
 * operate when no real key is configured: unlike the pack credential resolver it never accepts the
 * cipher's Base64 development fallback and never reads a credential from the host environment (the
 * design's only env channel is the sandbox launch). Plaintext and ciphertext are never logged —
 * only provider ids.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeCredentialService {

    private final RuntimeCredentialRepository credentialRepo;
    private final PackCredentialCipher cipher;

    /** Whether a credential row exists for the provider. Never throws for a missing row. */
    public boolean configured(String providerId) {
        return credentialRepo.findByProviderId(providerId).isPresent();
    }

    /**
     * Store (insert or replace) the PAT for a provider, encrypted at rest.
     *
     * @throws IllegalArgumentException   when the pat is null or blank
     * @throws RuntimeCredentialException with {@code KEY_NOT_CONFIGURED} when encryption is disabled
     */
    public void save(String providerId, String pat) {
        if (pat == null || pat.isBlank()) {
            throw new IllegalArgumentException("PAT must not be null or blank");
        }
        if (!cipher.encryptionEnabled()) {
            throw new RuntimeCredentialException(RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED,
                    "Cannot store a runtime credential: PACK_CREDENTIAL_KEY is not configured,"
                            + " and this store does not accept the Base64 development fallback");
        }
        RuntimeCredential credential = credentialRepo.findByProviderId(providerId).orElseGet(() ->
                RuntimeCredential.builder()
                        .id(UUID.randomUUID().toString())
                        .providerId(providerId)
                        .build());
        credential.setEncPat(cipher.encrypt(pat));
        credentialRepo.save(credential);
        log.info("Stored runtime credential for provider {}", providerId);
    }

    /**
     * Read the decrypted PAT for a provider — the provider launch path.
     *
     * @throws RuntimeCredentialException with {@code KEY_NOT_CONFIGURED} when encryption is
     *         disabled, {@code NOT_CONFIGURED} when no row exists, or {@code CIPHER_FAILED}
     *         (cipher exception as cause) when the stored value cannot be decrypted
     */
    public String read(String providerId) {
        if (!cipher.encryptionEnabled()) {
            throw new RuntimeCredentialException(RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED,
                    "Cannot read runtime credential: PACK_CREDENTIAL_KEY is not configured,"
                            + " and this store does not accept the Base64 development fallback");
        }
        RuntimeCredential credential = credentialRepo.findByProviderId(providerId)
                .orElseThrow(() -> new RuntimeCredentialException(RuntimeCredentialException.Cause.NOT_CONFIGURED,
                        "No runtime credential configured for provider " + providerId));
        try {
            return cipher.decrypt(credential.getEncPat());
        } catch (RuntimeException e) {
            // Wrap without echoing the ciphertext or the cipher's message into ours.
            throw new RuntimeCredentialException(RuntimeCredentialException.Cause.CIPHER_FAILED,
                    "Failed to decrypt the stored runtime credential for provider " + providerId, e);
        }
    }

    /** Delete the stored credential for a provider; idempotent (no-op when absent). */
    public void delete(String providerId) {
        Optional<RuntimeCredential> existing = credentialRepo.findByProviderId(providerId);
        if (existing.isPresent()) {
            credentialRepo.delete(existing.get());
            log.info("Deleted runtime credential for provider {}", providerId);
        }
    }

    /**
     * Status safe to surface to callers: presence, masked PAT and last update time. Never exposes
     * ciphertext or plaintext. A present row with encryption disabled is rejected rather than
     * masked, because the suffix cannot be produced safely without the key.
     *
     * @throws RuntimeCredentialException with {@code KEY_NOT_CONFIGURED} when a row exists but
     *         encryption is disabled
     */
    public RuntimeCredentialStatus maskedStatus(String providerId) {
        Optional<RuntimeCredential> existing = credentialRepo.findByProviderId(providerId);
        if (existing.isEmpty()) {
            // Absence is reported as-is: no key is needed to know that nothing is stored.
            return new RuntimeCredentialStatus(false, null, null);
        }
        if (!cipher.encryptionEnabled()) {
            throw new RuntimeCredentialException(RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED,
                    "Cannot report masked status: PACK_CREDENTIAL_KEY is not configured,"
                            + " and this store does not accept the Base64 development fallback");
        }
        RuntimeCredential credential = existing.get();
        return new RuntimeCredentialStatus(true, mask(cipher.decrypt(credential.getEncPat())),
                credential.getUpdatedAt());
    }

    /** Mirrors {@code LlmProviderService.maskApiKey}: last four characters only, never more. */
    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }
}

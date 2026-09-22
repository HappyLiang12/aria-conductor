package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One stored runtime credential of a core launch (spec 6.1): the credential
 * reference recorded in the run execution binding, the exact environment
 * variable name the child process receives, and the authenticated-encryption
 * ciphertext. The encryption key is configuration only and never persists here.
 *
 * <p>This is deliberately distinct from {@link PackCredential}: runtime
 * credentials are core-specific launch secrets behind the credential
 * resolution boundary, not tool-pack credentials, and only the ciphertext is
 * ever stored or read back. {@code resolve} returns the plaintext exclusively
 * inside the memory-only
 * {@code io.aria.conductor.execution.runtime.SecretBundle} handed to a launch.
 */
@Entity
@Table(name = "runtime_credentials", indexes = {
        @Index(name = "idx_runtime_credentials_core", columnList = "core_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeCredential {

    /** Stable reference, e.g. {@code qoder:operator}; recorded in the execution binding (VARCHAR(255), as in V59). */
    @Id
    @Column(name = "credential_ref", nullable = false, length = 255)
    private String credentialRef;

    @Column(name = "core_id", nullable = false, length = 64)
    private String coreId;

    /** The exact child environment variable the secret may be injected as. */
    @Column(name = "environment_variable", nullable = false, length = 128)
    private String environmentVariable;

    /**
     * Base64 of {@code nonce || AES-256-GCM(ciphertext+tag)}; authenticated
     * against the core/credential-reference binding. Never a plaintext value
     * and never the pack-credential Base64 development fallback.
     */
    @Column(name = "enc_value", nullable = false, columnDefinition = "TEXT")
    private String encValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}

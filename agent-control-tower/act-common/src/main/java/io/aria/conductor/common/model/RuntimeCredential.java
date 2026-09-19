package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Deployment-scoped runtime provider credential (e.g. the Qoder PAT) stored encrypted at rest.
 * <p>
 * One row per provider id — the unique constraint exists so the service's find-then-save upsert
 * cannot create duplicates. The stored value is always ciphertext produced by the execution
 * module's {@code PackCredentialCipher}; plaintext never reaches this entity.
 */
@Entity
@Table(name = "runtime_credentials",
        uniqueConstraints = @UniqueConstraint(name = "uq_runtime_cred_provider", columnNames = "provider_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeCredential {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "provider_id", nullable = false, length = 50)
    private String providerId;

    @Column(name = "enc_pat", nullable = false, columnDefinition = "TEXT")
    private String encPat;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

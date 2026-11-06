package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One plain core credential row (2026-10-05 local authority simplification;
 * amends the agent-core spec §6.1): handled exactly like
 * {@code llm_providers.api_key} -- stored as given, masked on read, no
 * dedicated encryption machinery. The launch path still resolves it only into
 * the memory-only SecretBundle; the value is never logged and never returned
 * by any endpoint.
 */
@Entity
@Table(name = "core_credentials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoreCredential {

    @Id
    @Column(name = "core_id", nullable = false, length = 64)
    private String coreId;

    /** The exact child environment variable the secret may be injected as. */
    @Column(name = "environment_variable", nullable = false, length = 128)
    private String environmentVariable;

    /** Named {@code credential_value}, not {@code value}: VALUE is reserved in H2. */
    @Column(name = "credential_value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}

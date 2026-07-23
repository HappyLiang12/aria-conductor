package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "pack_credentials", indexes = {
        @Index(name = "idx_pack_cred_pack", columnList = "packId"),
        @Index(name = "idx_pack_cred_pack_agent", columnList = "packId, agentId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackCredential {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "pack_id", nullable = false, length = 36)
    private String packId;

    @Column(name = "agent_id", length = 36)
    private String agentId;

    @Column(name = "cred_key", nullable = false, length = 100)
    private String credKey;

    @Column(name = "enc_value", nullable = false, columnDefinition = "TEXT")
    private String encValue;

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

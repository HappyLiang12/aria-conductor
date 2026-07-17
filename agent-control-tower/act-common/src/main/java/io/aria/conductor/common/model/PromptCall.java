package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prompt_calls", indexes = {
        @Index(name = "idx_prompt_calls_run", columnList = "runId"),
        @Index(name = "idx_prompt_calls_agent", columnList = "agentId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID runId;

    @Column(nullable = false)
    private UUID agentId;

    private String provider;

    private String model;

    private int inputTokens;

    private int outputTokens;

    private int latencyMs;

    /**
     * Outcome of the LLM call: "success", "failure", or "partial".
     * Mirrors design spec section 4.1 (self-improvement pipeline).
     */
    @Column(length = 32)
    private String outcome;

    /**
     * Comma-separated list of tool names invoked during the call (may be empty).
     */
    @Column(name = "tools_used", length = 2048)
    private String toolsUsed;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

package io.aria.conductor.execution.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTOs for the qoder runtime-credential API ({@link QoderCredentialController}).
 *
 * <p>Security shape: no type here can render credential material. {@link PatRequest} carries the
 * operator-supplied PAT on the way in and overrides {@code toString()} to a mask (a Lombok
 * {@code @Data} bean or a bare record would print the value); {@link CredentialStatusResponse}
 * only ever carries the service-produced mask {@code "****" + last four characters}.
 */
public final class QoderCredentialDtos {

    private QoderCredentialDtos() {
    }

    /**
     * PUT request body, e.g. {@code {"pat":"..."}}.
     *
     * <p>Deliberately overrides the record's default {@code toString()} so the secret cannot
     * reach a log line, an exception message or a debugger string dump.
     */
    public record PatRequest(String pat) {

        @Override
        public String toString() {
            return "PatRequest[pat=" + (pat == null || pat.isEmpty() ? "absent" : "****") + "]";
        }
    }

    /**
     * Masked credential status — the exact response shape of {@code GET} and {@code PUT}.
     * When nothing is stored, {@code configured} is false and {@code patMasked}/{@code updatedAt}
     * are null (absence is a normal state, not an error).
     *
     * <p>{@code updatedAt} is rendered as an explicit ISO-8601 instant string rather than a
     * typed {@link java.time.Instant}: the wire format is then pinned by the controller tests
     * (which run on a plain Jackson converter) instead of depending on the time module of the
     * runtime converter.
     */
    public record CredentialStatusResponse(String providerId, boolean configured,
                                           String patMasked, String updatedAt, String model) {
    }

    /**
     * Bounded credential-probe result. {@code billable} is always false: the probe performs no
     * inference, and {@code costNote} discloses that using the credential in a run may still
     * consume credits. On success {@code reason} and {@code message} are omitted; on a
     * credential-state failure they carry a code and a constant, safe message.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CredentialTestResponse(boolean success, String reason, String model,
                                         boolean billable, String costNote, String message) {
    }
}

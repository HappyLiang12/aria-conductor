package io.aria.conductor.execution.credential;

import java.time.Instant;

/**
 * Status view of a stored runtime credential, safe to return to callers/APIs: presence,
 * a mask ({@code "****"} + last four characters) and the last update time. Never carries
 * ciphertext or plaintext.
 */
public record RuntimeCredentialStatus(boolean configured, String patMasked, Instant updatedAt) {
}

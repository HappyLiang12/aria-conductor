package io.aria.conductor.execution.credential;

import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.UsageSnapshot;

import java.time.Duration;

/**
 * The bounded live call seam. A real Qoder credential test must drive the
 * pinned CLI through the run-owned ACP bridge (Tasks 8-11); wiring that
 * probe here is a composition concern, so this component refuses the test
 * explicitly instead of duplicating the bridge.
 */
@FunctionalInterface
public interface CredentialProbe {

    /** Performs exactly one bounded call with the resolved credential. */
    CredentialTestOutcome test(SecretBundle credential, Duration timeout);

    /** Outcome of one bounded probe call; unknown usage is carried as nulls, never zeros. */
    record CredentialTestOutcome(boolean authenticated, String model, String detail,
            UsageSnapshot usage) {
    }
}

package io.aria.conductor.execution.engine;

import io.aria.conductor.common.exception.ActionableIllegalArgumentException;
import io.aria.conductor.common.exception.ActionableIllegalStateException;
import io.aria.conductor.common.exception.ErrorSurface;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run's persisted repair identity is the first actionable failure the loop
 * recorded: later failures (and plain non-actionable messages around them) must
 * not rewrite it, while a non-actionable failure must not hide a later
 * actionable one.
 */
class RunContextTest {

    private static RunContext context() {
        return new RunContext(UUID.randomUUID(), UUID.randomUUID(), null, null);
    }

    @Test
    void keepsTheFirstActionableIdentityWhenLaterFailuresCarryAnother() {
        RunContext ctx = context();

        ctx.addError(new ActionableIllegalArgumentException(
                "Runtime credential is not configured: qoder:operator",
                "RUNTIME_CREDENTIAL_NOT_CONFIGURED", ErrorSurface.LLM_PROVIDERS));
        ctx.addError(new ActionableIllegalStateException(
                "Max iterations reached", "MAX_ITERATIONS_EXCEEDED", ErrorSurface.SYSTEM_CONFIG));

        assertThat(ctx.getErrorCode()).isEqualTo("RUNTIME_CREDENTIAL_NOT_CONFIGURED");
        assertThat(ctx.getErrorSurface()).isEqualTo("LLM_PROVIDERS");
    }

    @Test
    void capturesAnActionableFailureRecordedAfterANonActionableOne() {
        RunContext ctx = context();

        ctx.addError("plain transport hiccup");
        ctx.addError(new ActionableIllegalStateException(
                "The fleet runtime is mixed", "FLEET_RUNTIME_MIXED", ErrorSurface.RUNTIME));

        assertThat(ctx.getErrorCode()).isEqualTo("FLEET_RUNTIME_MIXED");
        assertThat(ctx.getErrorSurface()).isEqualTo("RUNTIME");
    }

    @Test
    void leavesTheIdentityNullWhenNoFailureIsActionable() {
        RunContext ctx = context();

        ctx.addError("plain failure");
        ctx.addError("Run failed without an error message; check the backend log for the failure details.");

        assertThat(ctx.getErrorCode()).isNull();
        assertThat(ctx.getErrorSurface()).isNull();
    }
}

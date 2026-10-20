package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.exception.ActionableIllegalArgumentException;
import io.aria.conductor.common.exception.ErrorSurface;

/**
 * Shared operator guidance for workspace refusals: every one of these is fixed
 * in the agent's Crew settings, so they carry the CREW surface and the same hint.
 */
final class WorkspaceGuidance {

    static final String SETTINGS_HINT = " Edit the agent's workspace settings in Crew.";

    static ActionableIllegalArgumentException refusal(String message, String code) {
        return new ActionableIllegalArgumentException(message, code, ErrorSurface.CREW);
    }

    static ActionableIllegalArgumentException refusal(String message, String code, Throwable cause) {
        return new ActionableIllegalArgumentException(message, code, ErrorSurface.CREW, cause);
    }

    private WorkspaceGuidance() {
    }
}

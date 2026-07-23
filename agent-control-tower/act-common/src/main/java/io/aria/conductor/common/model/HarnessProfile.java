package io.aria.conductor.common.model;

import java.util.List;

/**
 * A reusable, named "harness profile" that tunes the agent execution loop so that even a
 * weak model (e.g. deepseek-v4-flash) stays usable and safe. Referenced by name from an
 * agent's config JSON ({@code {"harnessProfile":"weak-model-safe"}}) and resolved once per run.
 *
 * <p>The {@link #defaults()} profile is a pure no-op that reproduces the historical
 * (pre-profile) behaviour, so agents that do not opt in are entirely unaffected.
 */
public record HarnessProfile(
        String name,
        List<String> toolDenylist,
        Steering steering,
        SelfVerify selfVerify,
        int maxToolCallRounds,
        int maxToolOutputChars
) {
    /** Steering rules that nudge a weak model away from ungoverned tool use. */
    public record Steering(boolean shellExecToGitPack) {}

    /**
     * LLM self-verification (pipeline Stage 3) tuning. When {@link #escalateTiers} is non-empty
     * the reviewer may return ESCALATE to force a human approval gate for actions of those risk
     * tiers, even when the action would not otherwise require approval.
     */
    public record SelfVerify(
            boolean enabled,
            List<String> escalateTiers,
            int maxResponseTokens,
            String promptOverride
    ) {}

    /** The default no-op profile == historical behaviour (steering off, no escalation). */
    public static HarnessProfile defaults() {
        return new HarnessProfile(
                "default",
                List.of(),
                new Steering(false),
                new SelfVerify(true, List.of(), 200, null),
                0,
                16_000);
    }

    /** True when this profile denies the named tool (case-insensitive, null-safe). */
    public boolean denies(String toolName) {
        if (toolName == null || toolDenylist == null) return false;
        for (String denied : toolDenylist) {
            if (toolName.equalsIgnoreCase(denied)) return true;
        }
        return false;
    }

    /** True when the reviewer may escalate an action of the given risk tier to a human gate. */
    public boolean canEscalateTier(String tierName) {
        return selfVerify != null
                && selfVerify.escalateTiers() != null
                && tierName != null
                && selfVerify.escalateTiers().contains(tierName);
    }

    /** Effective single-tool output cap (chars), falling back to the given default when unset. */
    public int effectiveOutputCap(int fallback) {
        return maxToolOutputChars > 0 ? maxToolOutputChars : fallback;
    }
}

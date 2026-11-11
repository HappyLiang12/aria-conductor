package io.aria.conductor.app.backfill;

/**
 * What one backfill pass wrote. A second pass on a completed database returns
 * {@link #noop()}, and a pass over an already-populated database reports zero for every
 * row it would otherwise duplicate.
 */
public record BackfillReport(int agentsCreated, int reposCreated, int linksProposed) {

    public static BackfillReport noop() {
        return new BackfillReport(0, 0, 0);
    }
}

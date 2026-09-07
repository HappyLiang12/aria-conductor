-- Persist run progress fragments so the AgentDrawer Live Activity Stream can
-- replay history (previously broadcast-only per the S8 "transient" decision —
-- reversed by the 2026-09-06 UI audit).
--
-- Deliberate deviations from the plan draft:
-- 1. No FK to runs(id): run_progress_events is the highest-insert-volume table
--    in the schema (multiple fragments per iteration per run), and
--    HousekeepingService.purgeRuns deletes runs "children first, parent last"
--    with a hand-maintained child-table list that does not know about this
--    table yet — a new FK would fail every purge chunk for runs with progress
--    events. Orphans are harmless: retention (Task 4) deletes by created_at and
--    replay for a purged run id simply returns an empty backlog. The plain
--    (run_id, seq) index below keeps the replay path fast.
-- 2. TIMESTAMP instead of TIMESTAMPTZ: neither MariaDB (docker prod profile)
--    nor H2 MODE=MySQL (h2 profile / CI) accepts TIMESTAMPTZ; the rest of the
--    schema standardizes on TIMESTAMP (see V1).
-- 3. seq BIGINT (spec draft said INT): safe widening for a monotonic counter —
--    avoids a future migration if a long-lived run exceeds INT range.
CREATE TABLE run_progress_events (
    id         UUID         NOT NULL PRIMARY KEY,
    run_id     UUID         NOT NULL,
    agent_id   UUID,
    iteration  INT          NOT NULL,
    kind       VARCHAR(32)  NOT NULL,
    seq        BIGINT       NOT NULL,
    content    TEXT,
    tool_name  VARCHAR(255),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_progress_run_seq ON run_progress_events (run_id, seq);
CREATE INDEX idx_progress_created ON run_progress_events (created_at);

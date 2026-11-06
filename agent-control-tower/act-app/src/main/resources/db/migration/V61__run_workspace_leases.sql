-- Cross-core Host/Sandbox execution (spec 5.2), migration 3 of the reserved
-- V59-V62 set. Additive only; no data purge and no applied migration is touched.
--
-- One persisted ownership record per run workspace lease: canonical local root,
-- the user-owned source root (never recursively removed by the platform), the
-- platform-owned run configuration root outside the source/worktree, the
-- resolved base commit for worktrees, the lifecycle state and the separate
-- retention flag. Cleanup resolves a directory back to this table before
-- removing anything, so an age-only sweeper can never delete a user repository
-- or a retained worktree.
CREATE TABLE run_workspace_leases (
    lease_id       UUID         NOT NULL PRIMARY KEY,
    run_id         UUID         NOT NULL,
    workspace_kind VARCHAR(32)  NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    local_root     TEXT         NOT NULL,
    source_root    TEXT,
    runtime_root   TEXT,
    base_commit    VARCHAR(64),
    retained       BOOLEAN      NOT NULL DEFAULT FALSE,
    acquired_at    TIMESTAMP    NOT NULL,
    released_at    TIMESTAMP,
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_run_workspace_leases_run ON run_workspace_leases (run_id);
CREATE INDEX idx_run_workspace_leases_state ON run_workspace_leases (state);

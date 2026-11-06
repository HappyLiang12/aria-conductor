-- V4: Stage 5 of the action execution pipeline — pre-execution shadow copies
-- of reversible actions. Audit-only; there is NO automatic rollback.
-- MariaDB-compatible (TEXT for arbitrary-length JSON state, simple indexes).

CREATE TABLE action_shadow_copies (
    id              VARCHAR(36) PRIMARY KEY,
    run_id          VARCHAR(36) NOT NULL,
    action_id       VARCHAR(36) NOT NULL,
    action_type     VARCHAR(50),
    original_state  TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_shadow_run ON action_shadow_copies(run_id);
CREATE INDEX idx_shadow_action ON action_shadow_copies(action_id);

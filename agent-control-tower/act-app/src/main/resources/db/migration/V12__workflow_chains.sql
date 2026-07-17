-- Workflow chains table for multi-agent sequential orchestration
CREATE TABLE workflow_chains (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    current_step_index INT     NOT NULL DEFAULT 0,
    steps_json    TEXT,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP,
    completed_at  TIMESTAMP
);

CREATE INDEX idx_wf_status ON workflow_chains(status);

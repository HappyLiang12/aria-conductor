-- V5: Knowledge governance — Git submission saga, review SLA, repo registry.
-- H2-compatible (no MySQL ENUMs, TEXT for large text, simple ALTERs).

CREATE TABLE knowledge_submission_intents (
    id           VARCHAR(36) PRIMARY KEY,
    item_id      VARCHAR(36)  NOT NULL,
    repo_name    VARCHAR(100) NOT NULL,
    branch_name  VARCHAR(64),
    file_path    VARCHAR(255),
    content      TEXT,
    status       VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    retry_count  INT          DEFAULT 0,
    max_retries  INT          DEFAULT 5,
    last_error   VARCHAR(2000),
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_intent_status ON knowledge_submission_intents(status);
CREATE INDEX idx_intent_item ON knowledge_submission_intents(item_id);

-- Governance fields on existing knowledge_items table.
ALTER TABLE knowledge_items ADD COLUMN review_deadline   TIMESTAMP;
ALTER TABLE knowledge_items ADD COLUMN escalation_count  INT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_items ADD COLUMN reviewer_id       VARCHAR(36);
ALTER TABLE knowledge_items ADD COLUMN reviewer_name     VARCHAR(100);
ALTER TABLE knowledge_items ADD COLUMN rejection_reason  VARCHAR(1000);

CREATE TABLE knowledge_repos (
    id           VARCHAR(36) PRIMARY KEY,
    name         VARCHAR(100) UNIQUE NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    local_path   VARCHAR(500),
    last_sync_at TIMESTAMP,
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- V8: DoD (Definition of Done) stage-gate workflow + evidence collection.
-- Backend for plan/dashboard-workflows.md Section 8.
-- All tables use H2-compatible SQL syntax.

CREATE TABLE dod_records (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL UNIQUE,
    task_type VARCHAR(50),
    current_stage VARCHAR(20) NOT NULL DEFAULT 'dev',
    overall_status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE dod_stage_reviews (
    id VARCHAR(36) PRIMARY KEY,
    dod_id VARCHAR(36) NOT NULL,
    stage VARCHAR(20) NOT NULL,
    reviewer_id VARCHAR(36) NOT NULL,
    reviewer_name VARCHAR(100),
    passed BOOLEAN NOT NULL,
    evidence TEXT,
    comment VARCHAR(1000),
    reviewed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dod_stage_reviews_dod FOREIGN KEY (dod_id) REFERENCES dod_records(id)
);

CREATE TABLE evidence_items (
    id VARCHAR(36) PRIMARY KEY,
    dod_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(255),
    content TEXT,
    artifact_path VARCHAR(500),
    source_run_id VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evidence_items_dod FOREIGN KEY (dod_id) REFERENCES dod_records(id)
);

CREATE INDEX idx_dod_task ON dod_records(task_id);
CREATE INDEX idx_dod_stage_reviews_dod ON dod_stage_reviews(dod_id);
CREATE INDEX idx_evidence_task ON evidence_items(task_id);
CREATE INDEX idx_evidence_dod ON evidence_items(dod_id);

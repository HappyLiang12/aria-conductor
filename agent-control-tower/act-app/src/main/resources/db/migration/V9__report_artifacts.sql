-- V9: Generative UI report artifacts.
-- Stores LLM-generated HTML reports rendered inside sandboxed iframes
-- on the dashboard. The HTML payload itself lives on disk under
-- ./data/reports/{id}/v{version}/index.html; this table only carries
-- metadata + an amendment audit trail.
-- All SQL is H2-compatible.

CREATE TABLE report_artifacts (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source_run_id VARCHAR(36),
    owner VARCHAR(100),
    sensitivity VARCHAR(20) DEFAULT 'internal',
    data_scope VARCHAR(500),
    html_path VARCHAR(500),
    version INT DEFAULT 1,
    status VARCHAR(20) DEFAULT 'GENERATED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    amended_at TIMESTAMP,
    amendment_history TEXT
);

CREATE INDEX idx_report_owner ON report_artifacts(owner);
CREATE INDEX idx_report_source ON report_artifacts(source_run_id);

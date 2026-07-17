-- V7: Kanban items table for the dashboard's kanban board
-- H2-compatible SQL syntax

CREATE TABLE kanban_items (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'TODO',
    priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    assignee VARCHAR(100),
    labels VARCHAR(500),
    linked_run_id VARCHAR(36),
    linked_agent_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_kanban_status ON kanban_items(status);
CREATE INDEX idx_kanban_assignee ON kanban_items(assignee);

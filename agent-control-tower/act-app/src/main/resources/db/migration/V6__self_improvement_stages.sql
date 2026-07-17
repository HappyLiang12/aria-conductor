-- V6: Self-improvement pipeline stages 3-5 (skill, script, workflow).
-- H2-compatible only; TEXT for large text, IF NOT EXISTS for idempotent ALTERs.

CREATE TABLE skill_definitions (
    id                  VARCHAR(36) PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    description         VARCHAR(1000),
    template            TEXT,
    trigger_conditions  TEXT,
    examples            TEXT,
    source_prompt_ids   VARCHAR(1000),
    knowledge_item_id   VARCHAR(36),
    usage_count         INT          DEFAULT 0,
    stage               VARCHAR(20)  DEFAULT 'SKILL',
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_lineage (
    id              VARCHAR(36) PRIMARY KEY,
    ancestor_id     VARCHAR(36) NOT NULL,
    descendant_id   VARCHAR(36) NOT NULL,
    depth           INT         DEFAULT 1,
    relation_type   VARCHAR(50),
    created_at      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

-- prompt_calls table created by V1; previous migrations ALTERed it. Keep
-- IF NOT EXISTS so this migration is idempotent on re-runs against an
-- already-extended schema.
ALTER TABLE prompt_calls ADD COLUMN IF NOT EXISTS prompt_hash      VARCHAR(64);
ALTER TABLE prompt_calls ADD COLUMN IF NOT EXISTS task_pattern     VARCHAR(255);
ALTER TABLE prompt_calls ADD COLUMN IF NOT EXISTS embedding_cache  TEXT;

CREATE INDEX idx_lineage_ancestor   ON knowledge_lineage(ancestor_id);
CREATE INDEX idx_lineage_descendant ON knowledge_lineage(descendant_id);
CREATE INDEX idx_skill_knowledge    ON skill_definitions(knowledge_item_id);

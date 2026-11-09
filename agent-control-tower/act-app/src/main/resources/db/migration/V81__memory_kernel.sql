-- V81: the memory kernel and the provider sensitivity ceiling.
--
-- Six tables: the governed ontology (entities, relations, facts, knowledge links), the
-- append-only assertion log, and the per-turn context manifest. Ids are UUIDs and the
-- cross-module references (knowledge_item_id, agent ids) are logical only -- there is no
-- foreign key across modules.
--
-- proposal_hash is the idempotency key of an extraction proposal: the unique index
-- allows many NULLs (operator/run writes) and exactly one row per proposal, so a retried
-- extraction cannot duplicate a fact or relation.
--
-- llm_providers.sensitivity_ceiling is what the context pipeline resolves per run. The
-- column is NOT NULL with a database-level default, so rows written before this
-- migration (and rows written by raw SQL or an older binary) read back as INTERNAL.

CREATE TABLE memory_entities (
    id UUID PRIMARY KEY,
    owner_id UUID,
    kind VARCHAR(32) NOT NULL,
    canonical_key VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    attributes TEXT,
    sensitivity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    merged_into UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_memory_entity UNIQUE (kind, canonical_key)
);
CREATE INDEX idx_memory_entity_kind_status ON memory_entities (kind, status);

CREATE TABLE memory_relations (
    id UUID PRIMARY KEY,
    owner_id UUID,
    from_entity_id UUID NOT NULL,
    predicate VARCHAR(64) NOT NULL,
    to_entity_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL,
    confidence DECIMAL(3,2),
    proposal_hash VARCHAR(64),
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_memory_relation_proposal UNIQUE (proposal_hash)
);
CREATE INDEX idx_memory_relation_from ON memory_relations (from_entity_id, predicate);
CREATE INDEX idx_memory_relation_to ON memory_relations (to_entity_id, predicate);

CREATE TABLE memory_facts (
    id UUID PRIMARY KEY,
    owner_id UUID,
    entity_id UUID NOT NULL,
    fact_key VARCHAR(128) NOT NULL,
    value_text TEXT,
    value_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL,
    confidence DECIMAL(3,2),
    proposal_hash VARCHAR(64),
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_memory_fact_proposal UNIQUE (proposal_hash)
);
CREATE INDEX idx_memory_fact_entity_key ON memory_facts (entity_id, fact_key, valid_to);

CREATE TABLE memory_assertions (
    id UUID PRIMARY KEY,
    subject_type VARCHAR(16) NOT NULL,
    subject_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(255),
    before_json TEXT,
    after_json TEXT,
    source_ref VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_memory_assertion_subject ON memory_assertions (subject_type, subject_id, created_at);

CREATE TABLE memory_knowledge_links (
    id UUID PRIMARY KEY,
    entity_id UUID NOT NULL,
    knowledge_item_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    decided_by VARCHAR(255),
    CONSTRAINT uq_memory_knowledge_link UNIQUE (entity_id, knowledge_item_id)
);
CREATE INDEX idx_memory_knowledge_link_knowledge ON memory_knowledge_links (knowledge_item_id);

CREATE TABLE context_manifests (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    turn_number INT NOT NULL,
    conversation_id VARCHAR(255),
    agent_id UUID,
    provider_id UUID,
    adapter VARCHAR(16) NOT NULL,
    sections_json TEXT NOT NULL,
    dropped_json TEXT,
    degraded_json TEXT,
    max_chars INT NOT NULL,
    estimated_tokens INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_context_manifest_turn UNIQUE (run_id, turn_number)
);

ALTER TABLE llm_providers ADD COLUMN sensitivity_ceiling VARCHAR(16) NOT NULL DEFAULT 'INTERNAL';

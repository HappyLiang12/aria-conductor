-- V59: deployment-scoped runtime credentials (e.g. the Qoder PAT) consumed by the
-- provider launch path. Deliberate DDL choices:
-- 1. No FK: there is no parent table — the row is deployment-scoped and keyed by
--    provider_id (an opaque provider identifier, not a reference to another row).
-- 2. UNIQUE(provider_id): no precedent elsewhere in this schema, but the store is a
--    singleton per provider and the service does a find-then-save upsert; the
--    constraint makes a duplicate row impossible even under concurrent saves.
-- 3. TIMESTAMP (not TIMESTAMPTZ): matches the rest of the schema; H2 MODE=MySQL
--    (h2 profile / CI) and MariaDB both reject TIMESTAMPTZ.
CREATE TABLE runtime_credentials (
    id          VARCHAR(36) PRIMARY KEY,
    provider_id VARCHAR(50) NOT NULL,
    enc_pat     TEXT        NOT NULL,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_runtime_cred_provider UNIQUE (provider_id)
);

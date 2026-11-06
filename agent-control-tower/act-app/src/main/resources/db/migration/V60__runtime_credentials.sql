-- Cross-core Host/Sandbox execution (spec 6.1), migration 2 of the reserved
-- V59-V62 set. Additive only; no data purge and no applied migration is touched.
--
-- One stored runtime credential per credential reference: the reference is the
-- exact value recorded in the run execution binding (V59 stores it as
-- VARCHAR(255), so this column is 255 wide too), the environment variable is
-- the exact name the child process receives, and only AES-GCM ciphertext is
-- persisted (the encryption key lives in configuration, never in the row).
-- Deliberately distinct from pack_credentials: these are core runtime secrets
-- behind the credential resolution boundary, not tool-pack credentials, and no
-- existing protected platform credential is moved, duplicated or deleted.
CREATE TABLE runtime_credentials (
    credential_ref       VARCHAR(255) NOT NULL PRIMARY KEY,
    core_id              VARCHAR(64)  NOT NULL,
    environment_variable VARCHAR(128) NOT NULL,
    enc_value            TEXT         NOT NULL,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP,
    version              BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_runtime_credentials_core ON runtime_credentials (core_id);

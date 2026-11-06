-- Local authority simplification (2026-10-05 spec): the Qoder runtime
-- credential moves from the encrypted runtime_credentials store to a plain
-- row handled like llm_providers.api_key. No data carryover: the encrypted
-- store has never been configured in this deployment; the operator re-enters
-- the credential once. Append-only; no applied migration is touched.
--
-- Note: runtime_credentials is intentionally NOT dropped here. The old
-- RuntimeCredential entity/repository are still mapped and deleted in the
-- follow-up task, whose migration (V68) drops the table. Dropping it now
-- would break JPA-backed tests in between.
CREATE TABLE core_credentials (
    core_id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    environment_variable VARCHAR(128) NOT NULL,
    credential_value     TEXT         NOT NULL,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP
);

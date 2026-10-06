-- Local authority simplification (2026-10-05): the Qoder runtime credential
-- moved from the encrypted runtime_credentials store to core_credentials
-- (V67). The RuntimeCredential entity and RuntimeCredentialRepository that
-- mapped this table were removed in the same change, so runtime_credentials is
-- now orphaned: no JPA-mapped entity references it any more. This migration
-- drops it. No data carryover (see V67's header note about the deferred drop).
-- Append-only; no applied migration is touched.
DROP TABLE IF EXISTS runtime_credentials;

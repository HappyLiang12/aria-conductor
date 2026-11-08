-- V76: SDD hardening, API lane.
--
-- F7: an ACP native permission ask has no companion tool_calls row, so the
-- operator-facing approval used to expose toolName/arguments as null. Persist
-- the ask's nullable tool identity, its credential-redacted argument document
-- and the governance risk tier resolved at registration time.
ALTER TABLE approvals ADD COLUMN IF NOT EXISTS tool_name VARCHAR(255);
ALTER TABLE approvals ADD COLUMN IF NOT EXISTS arguments TEXT;
ALTER TABLE approvals ADD COLUMN IF NOT EXISTS risk_tier VARCHAR(32);

-- F9: provider-reported usage becomes observable per run. Unknown usage stays
-- NULL (the bridge may not report it); it is never invented as zero.
ALTER TABLE runs ADD COLUMN IF NOT EXISTS input_tokens BIGINT;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS output_tokens BIGINT;

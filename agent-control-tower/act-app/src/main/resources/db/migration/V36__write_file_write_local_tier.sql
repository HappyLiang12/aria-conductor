-- V36: Correct write_file risk tier from READ to WRITE_LOCAL.
-- write_file modifies files; it was backfilled as READ (legacy default). WRITE_LOCAL is the
-- accurate governance tier (consistent with git_add/git_commit). This corrects audit/display
-- labeling only; it does NOT add an approval gate (WRITE_LOCAL does not require approval).
-- H2 + MariaDB compatible.
UPDATE tool_definitions SET risk_tier = 'WRITE_LOCAL' WHERE name = 'write_file';

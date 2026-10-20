-- V70: stable repair identity for run failures. The dashboard uses the
-- surface to offer the fixing action instead of matching the message text.
ALTER TABLE runs ADD COLUMN error_code VARCHAR(64);
ALTER TABLE runs ADD COLUMN error_surface VARCHAR(32);

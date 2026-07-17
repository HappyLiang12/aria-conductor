-- V28: Alter agents.role from VARCHAR(255) to TEXT to accommodate Aria's system prompt (>3K chars)
ALTER TABLE agents MODIFY COLUMN role TEXT;

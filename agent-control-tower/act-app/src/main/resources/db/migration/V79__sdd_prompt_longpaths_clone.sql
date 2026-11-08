-- V79: the agent-side clone honours long Windows paths (SDD hardening, R1).
--
-- A live SDD run had the DEV and QA agents' clone die with "Filename too long"
-- and leave a broken checkout: the backend clone already passes
-- -c core.longpaths=true, the prompts did not. Prefixing the clone command is
-- platform-neutral (git only acts on core.longpaths on Windows) and cannot
-- match its own output, so replaying the statement is harmless.
--
-- The template row carries exactly two clone commands: one in the DEV prompt
-- and one in the QA prompt.

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'git clone --branch',
    'git -c core.longpaths=true clone --branch')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
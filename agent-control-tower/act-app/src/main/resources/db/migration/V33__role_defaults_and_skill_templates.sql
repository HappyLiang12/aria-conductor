-- V33: Role-based default tool sets for worker roles (ba/dev/qa) + role_skill_templates table.
--
-- Fixes the live gap where role_tool_templates was seeded only for ARIA/WORKER (V22),
-- leaving ba/dev/qa agents with ZERO default tools at runtime. Idempotent
-- (INSERT ... SELECT ... WHERE NOT EXISTS), H2 + MariaDB compatible (no MySQL-specific
-- syntax). Tool ids are selected by name to stay UUID-agnostic across environments.

-- === Business Analyst (ba) ===
INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'ba', td.id, TRUE FROM tool_definitions td
WHERE td.name IN (
    'read_file', 'list_files', 'web_search', 'web_fetch',
    'search_knowledge', 'query_knowledge', 'list_knowledge', 'create_knowledge',
    'create_kanban_item', 'list_kanban_items', 'update_kanban_item',
    'generate_report', 'list_reports', 'init_dod', 'get_dod_status'
)
AND NOT EXISTS (
    SELECT 1 FROM role_tool_templates rtt WHERE rtt.role = 'ba' AND rtt.tool_id = td.id
);

-- === Developer (dev) ===
INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'dev', td.id, TRUE FROM tool_definitions td
WHERE td.name IN (
    'read_file', 'write_file', 'list_files', 'shell_exec', 'http_request', 'web_search', 'web_fetch',
    'search_knowledge', 'query_knowledge',
    'list_kanban_items', 'update_kanban_item', 'transition_kanban_item',
    'init_dod', 'submit_dod_review', 'get_dod_status', 'generate_report'
)
AND NOT EXISTS (
    SELECT 1 FROM role_tool_templates rtt WHERE rtt.role = 'dev' AND rtt.tool_id = td.id
);

-- === QA (qa) ===
INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'qa', td.id, TRUE FROM tool_definitions td
WHERE td.name IN (
    'read_file', 'write_file', 'list_files', 'shell_exec', 'http_request', 'web_search', 'web_fetch',
    'search_knowledge', 'query_knowledge',
    'list_kanban_items', 'update_kanban_item', 'transition_kanban_item',
    'init_dod', 'submit_dod_review', 'get_dod_status', 'review_knowledge',
    'generate_report', 'list_reports'
)
AND NOT EXISTS (
    SELECT 1 FROM role_tool_templates rtt WHERE rtt.role = 'qa' AND rtt.tool_id = td.id
);

-- === Role-based default skills (mirrors role_tool_templates) ===
-- No seed rows are inserted: no SKILL-stage skills are seeded yet. The table + FK are
-- created so the skill-recommendation and role-default machinery works as soon as skills
-- are authored and approved. This empty seed is expected, not a defect.
CREATE TABLE role_skill_templates (
    role        VARCHAR(50)  NOT NULL,
    skill_id    VARCHAR(36)  NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (role, skill_id),
    CONSTRAINT fk_rst_skill FOREIGN KEY (skill_id) REFERENCES skill_definitions(id) ON DELETE CASCADE
);

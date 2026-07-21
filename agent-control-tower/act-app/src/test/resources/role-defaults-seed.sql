-- Re-seeds role_tool_templates for ba/dev/qa after cleanup-all.sql truncation, mirroring
-- V34's idempotent INSERT ... SELECT ... WHERE NOT EXISTS (selecting tool ids by name).
-- Used by RoleDefaultsIntegrationTest to verify the resolver returns role defaults at runtime.

INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'ba', td.id, TRUE FROM tool_definitions td
WHERE td.name IN ('read_file', 'list_files', 'web_search', 'generate_report')
  AND NOT EXISTS (SELECT 1 FROM role_tool_templates rtt WHERE rtt.role = 'ba' AND rtt.tool_id = td.id);

INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'dev', td.id, TRUE FROM tool_definitions td
WHERE td.name IN ('read_file', 'write_file', 'shell_exec', 'http_request')
  AND NOT EXISTS (SELECT 1 FROM role_tool_templates rtt WHERE rtt.role = 'dev' AND rtt.tool_id = td.id);

INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'qa', td.id, TRUE FROM tool_definitions td
WHERE td.name IN ('read_file', 'write_file', 'shell_exec', 'review_knowledge')
  AND NOT EXISTS (SELECT 1 FROM role_tool_templates rtt WHERE rtt.role = 'qa' AND rtt.tool_id = td.id);

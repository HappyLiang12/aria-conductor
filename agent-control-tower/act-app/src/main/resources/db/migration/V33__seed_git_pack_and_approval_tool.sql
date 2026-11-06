-- V33: Seed git tool pack + request_approval HITL tool
-- Mirrors V22 seed pattern: deterministic IDs, knowledge_items approval proxy, handler_class dispatch.

-- Git pack registration (APPROVED + enabled for immediate use)
INSERT INTO tool_packs (id, name, kind, status, sandbox_mode, enabled, created_at, updated_at) VALUES
('pack-git-0001', 'git', 'SCRIPT', 'APPROVED', 'NONE', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Knowledge items for approval proxy
INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count) VALUES
('b0000001-0000-0000-0000-000000000001', 'Seed tool: request_approval', 'TOOL', 'HITL approval request tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000002', 'Seed tool: git_status', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000003', 'Seed tool: git_diff', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000004', 'Seed tool: git_log', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000005', 'Seed tool: git_add', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000006', 'Seed tool: git_commit', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000007', 'Seed tool: git_checkout', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000008', 'Seed tool: git_clone', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-000000000009', 'Seed tool: git_push', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-00000000000a', 'Seed tool: git_create_pr', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-00000000000b', 'Seed tool: git_reset_hard', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('b0000001-0000-0000-0000-00000000000c', 'Seed tool: git_force_push', 'TOOL', 'Git pack tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0);

-- request_approval: core HITL tool (always available, no pack)
INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at, risk_tier, status, kind) VALUES
('seed-tool-request_approval', 'request_approval', 'request_approval', 'Request human approval for a proposed action. Blocks until approved/denied.', 'TIER_1', 'GENERAL', 'requestApprovalHandler', '{"type":"object","properties":{"summary":{"type":"string","description":"What you want to do"},"reason":{"type":"string","description":"Why approval is needed"}},"required":["summary"]}', 'NONE', 1800000, 'b0000001-0000-0000-0000-000000000001', TRUE, 1, CURRENT_TIMESTAMP, 'READ', 'APPROVED', 'HANDLER');

-- Git pack tools (pack_id = pack-git-0001, handler = gitPackHandler)
INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at, pack_id, risk_tier, status, kind) VALUES
('seed-tool-git_status',     'git_status', 'git_status', 'Show git working tree status', 'TIER_1', 'GENERAL', 'gitPackHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'b0000001-0000-0000-0000-000000000002', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'READ', 'APPROVED', 'SCRIPT'),
('seed-tool-git_diff',       'git_diff', 'git_diff', 'Show git diff of changes', 'TIER_1', 'GENERAL', 'gitPackHandler', '{"type":"object","properties":{"path":{"type":"string","description":"Optional file path"}}}', 'NONE', 30000, 'b0000001-0000-0000-0000-000000000003', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'READ', 'APPROVED', 'SCRIPT'),
('seed-tool-git_log',        'git_log', 'git_log', 'Show recent git commit history', 'TIER_1', 'GENERAL', 'gitPackHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'b0000001-0000-0000-0000-000000000004', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'READ', 'APPROVED', 'SCRIPT'),
('seed-tool-git_add',        'git_add', 'git_add', 'Stage files for commit', 'TIER_1', 'GENERAL', 'gitPackHandler', '{"type":"object","properties":{"path":{"type":"string","description":"File path or . for all"}},"required":["path"]}', 'NONE', 30000, 'b0000001-0000-0000-0000-000000000005', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'WRITE_LOCAL', 'APPROVED', 'SCRIPT'),
('seed-tool-git_commit',     'git_commit', 'git_commit', 'Commit staged changes', 'TIER_1', 'GENERAL', 'gitPackHandler', '{"type":"object","properties":{"message":{"type":"string","description":"Commit message"}},"required":["message"]}', 'NONE', 30000, 'b0000001-0000-0000-0000-000000000006', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'WRITE_LOCAL', 'APPROVED', 'SCRIPT'),
('seed-tool-git_checkout',   'git_checkout', 'git_checkout', 'Checkout or create a branch', 'TIER_1', 'GENERAL', 'gitPackHandler', '{"type":"object","properties":{"branch":{"type":"string"},"create":{"type":"boolean","description":"Create new branch"}},"required":["branch"]}', 'NONE', 30000, 'b0000001-0000-0000-0000-000000000007', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'WRITE_LOCAL', 'APPROVED', 'SCRIPT'),
('seed-tool-git_clone',      'git_clone', 'git_clone', 'Clone a repository into the workspace', 'TIER_1', 'GENERAL', 'gitPackHandler', '{"type":"object","properties":{"url":{"type":"string","description":"Repository URL"}},"required":["url"]}', 'NONE', 120000, 'b0000001-0000-0000-0000-000000000008', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'WRITE_LOCAL', 'APPROVED', 'SCRIPT'),
('seed-tool-git_push',       'git_push', 'git_push', 'Push commits to remote (requires approval)', 'TIER_2', 'ADVANCED', 'gitPackHandler', '{"type":"object","properties":{"remote":{"type":"string","description":"Remote name (default: origin)"},"branch":{"type":"string","description":"Branch to push"}}}', 'NONE', 60000, 'b0000001-0000-0000-0000-000000000009', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'PUSH', 'APPROVED', 'SCRIPT'),
('seed-tool-git_create_pr',  'git_create_pr', 'git_create_pr', 'Create a pull request via GitHub CLI (requires approval)', 'TIER_2', 'ADVANCED', 'gitPackHandler', '{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title"]}', 'NONE', 60000, 'b0000001-0000-0000-0000-00000000000a', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'PUSH', 'APPROVED', 'SCRIPT'),
('seed-tool-git_reset_hard', 'git_reset_hard', 'git_reset_hard', 'Hard reset to HEAD (destructive, requires approval)', 'TIER_3', 'ADVANCED', 'gitPackHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'b0000001-0000-0000-0000-00000000000b', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'DESTRUCTIVE', 'APPROVED', 'SCRIPT'),
('seed-tool-git_force_push', 'git_force_push', 'git_force_push', 'Force push to remote (destructive, requires approval)', 'TIER_3', 'ADVANCED', 'gitPackHandler', '{"type":"object","properties":{"remote":{"type":"string"},"branch":{"type":"string"}}}', 'NONE', 60000, 'b0000001-0000-0000-0000-00000000000c', TRUE, 1, CURRENT_TIMESTAMP, 'pack-git-0001', 'DESTRUCTIVE', 'APPROVED', 'SCRIPT');

-- Grant request_approval to all default roles
INSERT INTO role_tool_templates (role, tool_id, is_default) VALUES
('WORKER', 'seed-tool-request_approval', TRUE),
('dev', 'seed-tool-request_approval', TRUE),
('qa', 'seed-tool-request_approval', TRUE),
('ba', 'seed-tool-request_approval', TRUE);

-- Grant git pack tools to dev role by default
INSERT INTO role_tool_templates (role, tool_id, is_default) VALUES
('dev', 'seed-tool-git_status', TRUE),
('dev', 'seed-tool-git_diff', TRUE),
('dev', 'seed-tool-git_log', TRUE),
('dev', 'seed-tool-git_add', TRUE),
('dev', 'seed-tool-git_commit', TRUE),
('dev', 'seed-tool-git_checkout', TRUE),
('dev', 'seed-tool-git_clone', TRUE),
('dev', 'seed-tool-git_push', TRUE),
('dev', 'seed-tool-git_create_pr', TRUE),
('dev', 'seed-tool-git_reset_hard', TRUE),
('dev', 'seed-tool-git_force_push', TRUE);

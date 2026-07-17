-- V27: Fix generate_report tool schema to match ReportToolHandler requirements
-- The handler requires both title AND description, but V22 only required title.
UPDATE tool_definitions
SET parameters = '{"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},"owner":{"type":"string"},"sourceRunId":{"type":"string"}},"required":["title","description"]}'
WHERE name = 'generate_report';

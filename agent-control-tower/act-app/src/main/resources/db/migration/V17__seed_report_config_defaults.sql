-- V17: Seed report generation configuration defaults
-- Report max-tokens controls the LLM output length for report HTML generation
-- and amendment. Higher values allow more complete reports but increase latency.

INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('report.generate.max.tokens', '16384', 'Max tokens for report HTML generation (range: 4000-131072)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('report.amend.max.tokens', '16384', 'Max tokens for report amendment (range: 4000-131072)', CURRENT_TIMESTAMP);

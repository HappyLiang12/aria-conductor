-- V16: Seed LLM retry configuration defaults
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('llm.retry.max.attempts', '3', 'Max LLM call retries (range: 0-10)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('llm.retry.backoff.base.ms', '2000', 'Base backoff in ms for exponential retry (range: 500-30000)', CURRENT_TIMESTAMP);

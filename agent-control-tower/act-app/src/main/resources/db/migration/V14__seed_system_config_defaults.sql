-- V14: Seed default system configuration values
-- These settings are runtime-configurable via REST API without rebuild

-- LLM Timeouts
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('llm.request.timeout.seconds', '600', 'LLM HTTP request timeout in seconds (range: 30-3600)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('aria.sse.timeout.ms', '600000', 'Aria SSE emitter timeout in milliseconds (range: 30000-3600000)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('llm.max.tokens.ceiling', '32768', 'Maximum allowed tokens per LLM request (range: 1024-131072)', CURRENT_TIMESTAMP);

-- Circuit Breaker
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('circuit.breaker.max.tokens.per.run', '100000', 'Max tokens consumed per run (range: 1000-10000000)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('circuit.breaker.max.iterations', '50', 'Max loop iterations per run (range: 1-500)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('circuit.breaker.error.rate.threshold', '0.5', 'Error rate to trip circuit breaker (range: 0.0-1.0)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('circuit.breaker.max.iteration.latency.ms', '300000', 'Max single iteration latency in ms (range: 10000-3600000)', CURRENT_TIMESTAMP);

-- Aria
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('aria.max.history.turns', '20', 'Max conversation history turns (range: 1-100)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('aria.session.ttl.minutes', '60', 'Session TTL before expiry in minutes (range: 5-1440)', CURRENT_TIMESTAMP);

-- ADK Runtime
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('adk.health.check.interval.ms', '30000', 'ADK health check interval in ms (range: 5000-300000)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('adk.shutdown.timeout.ms', '10000', 'ADK graceful shutdown timeout in ms (range: 1000-60000)', CURRENT_TIMESTAMP);
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('adk.max.restart.backoff.ms', '30000', 'Max backoff between ADK restarts in ms (range: 1000-300000)', CURRENT_TIMESTAMP);

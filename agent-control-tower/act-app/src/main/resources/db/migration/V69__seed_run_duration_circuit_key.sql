-- V69: seed the total run-duration cap read by CircuitBreakerProperties.
-- Without this row the key is not readable or updatable through System Config
-- (PUT /api/v1/system-config/{key} answers 404 for unknown keys), so the
-- breaker's "raise it in Configure > System Config" guidance would be false.
INSERT INTO system_config (config_key, config_value, description, updated_at)
SELECT 'circuit.breaker.max.run.duration.ms', '7200000', 'Max total run wall-clock time in ms (range: 60000-86400000)', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM system_config WHERE config_key = 'circuit.breaker.max.run.duration.ms');

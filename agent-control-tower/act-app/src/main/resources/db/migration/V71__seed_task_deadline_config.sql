-- V71: seed the run deadline so the operator can actually change it from
-- Configure > System Config. New runs freeze the value at admission.
INSERT INTO system_config (config_key, config_value, description, updated_at)
SELECT 'aria.tasks.deadline-minutes', '45', 'Hard run deadline in minutes (range: 1-1440)', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM system_config WHERE config_key = 'aria.tasks.deadline-minutes');

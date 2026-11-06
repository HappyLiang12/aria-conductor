-- V31: Remove orphaned Aria session config keys.
-- 'aria.max.history.turns' and 'aria.session.ttl.minutes' were seeded in V14 but have had
-- no runtime consumer since AriaProperties was reduced to systemPrompt only (live config
-- reload change). They are no longer exposed in the Settings UI either, so drop the rows
-- to avoid implying they still affect behaviour.
DELETE FROM system_config WHERE config_key = 'aria.max.history.turns';
DELETE FROM system_config WHERE config_key = 'aria.session.ttl.minutes';

-- V82: mark the Aria agent's stored prompt as a managed prompt (design P1, section 10).
--
-- Aria's prompt moves into code: act-aria assembles it at runtime, and this marker tells
-- readers that the stored config no longer carries the authoritative prompt text. Only a
-- pre-existing installation has an Aria row here -- a fresh database gets the row from
-- AriaDefaultAgentInitializer after Flyway, so this statement is a no-op there.
--
-- Data-only and idempotent: the WHERE clause excludes a config that already carries the
-- marker (and the CASE echoes it unchanged as a second guard), so replaying the statement
-- -- or a second boot that re-executes it -- changes nothing. The config is a JSON object,
-- so a non-empty object gets the marker inserted before its closing brace.
--
-- H2 MODE=MySQL and MariaDB 10.7 are the only supported targets; only plain string
-- functions are used and no column (in particular no timestamp) is created or altered.

UPDATE agents
SET config = CASE
        WHEN config IS NULL OR TRIM(config) = '' OR TRIM(config) = '{}'
            THEN '{"managedPromptVersion":1}'
        WHEN TRIM(config) LIKE '%"managedPromptVersion":%'
            THEN config
        ELSE CONCAT(SUBSTRING(TRIM(config), 1, LENGTH(TRIM(config)) - 1),
                    ',"managedPromptVersion":1}')
    END
WHERE id = CAST('00000000-0000-0000-0000-000000000001' AS UUID)
  AND (config IS NULL OR config NOT LIKE '%"managedPromptVersion":%')
  AND (config IS NULL
       OR TRIM(config) = ''
       OR TRIM(config) = '{}'
       OR TRIM(config) LIKE '{%}');

-- V82: mark the Aria agent's stored prompt as a managed prompt (design P1, section 10).
--
-- Aria's prompt moves into code: act-aria assembles it at runtime, and this marker tells
-- readers that the stored config no longer carries the authoritative prompt text. Only a
-- pre-existing installation has an Aria row here -- a fresh database gets the row from
-- AriaDefaultAgentInitializer after Flyway, so this statement is a no-op there.
--
-- Data-only and idempotent: the WHERE clause excludes a config that already carries the
-- marker (and the CASE echoes it unchanged as a second guard), so replaying the statement
-- -- or a second boot that re-executes it -- changes nothing. Marker detection is
-- '"managedPromptVersion"' only, never with the colon attached, so a hand-edited
-- '"managedPromptVersion" : 2' is still recognised as marked and never gets a second key.
--
-- The statement marks exactly three shapes and leaves every other row untouched, because
-- a config it cannot append to safely must not be rewritten into invalid JSON:
--   * NULL, blank, '{}' and the whitespace-only object '{ }' -> the exact object literal;
--   * a compact object whose last character before '}' is neither '{', whitespace nor ','
--     -> the marker is inserted before that closing brace;
--   * anything else ('{"a":1,}', '{\' + newline + '}', a non-object, a malformed
--     fragment) -> not selected at all, so no character is dropped and the column is
--     never set to NULL. The CASE has no NULL-producing branch, and the WHERE selects
--     exactly the rows the CASE can handle.
--
-- H2 MODE=MySQL and MariaDB 10.7 are the only supported targets; only plain string
-- functions are used and no column (in particular no timestamp) is created or altered.
-- CHAR_LENGTH (not LENGTH) measures characters on both: MariaDB's LENGTH counts bytes,
-- which would slice a non-ASCII config mid-character. CHAR(n) names the whitespace set
-- portably: MySQL/MariaDB string literals interpret backslash escapes and H2 does not, so
-- a literal tab or newline would mean a different character on each target.

UPDATE agents
SET config = CASE
        WHEN config IS NULL OR TRIM(config) = '' OR TRIM(config) IN ('{}', '{ }')
            THEN '{"managedPromptVersion":1}'
        WHEN TRIM(config) LIKE '%"managedPromptVersion"%'
            THEN config
        ELSE CONCAT(SUBSTRING(TRIM(config), 1, CHAR_LENGTH(TRIM(config)) - 1),
                    ',"managedPromptVersion":1}')
    END
WHERE id = CAST('00000000-0000-0000-0000-000000000001' AS UUID)
  AND (config IS NULL OR config NOT LIKE '%"managedPromptVersion"%')
  AND (config IS NULL
       OR TRIM(config) = ''
       OR TRIM(config) IN ('{}', '{ }')
       OR (TRIM(config) LIKE '{%}'
           AND CHAR_LENGTH(TRIM(config)) > 2
           AND SUBSTRING(TRIM(config), CHAR_LENGTH(TRIM(config)) - 1, 1)
               NOT IN ('{', ',', CHAR(9), CHAR(10), CHAR(13), CHAR(32))));

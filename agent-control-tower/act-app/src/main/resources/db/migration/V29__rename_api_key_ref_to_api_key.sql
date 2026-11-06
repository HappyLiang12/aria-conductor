ALTER TABLE llm_providers ADD COLUMN api_key TEXT;
UPDATE llm_providers SET api_key = api_key_ref;
ALTER TABLE llm_providers DROP COLUMN api_key_ref;

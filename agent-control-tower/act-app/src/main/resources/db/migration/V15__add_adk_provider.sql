-- Add adk_provider column to agents table
-- Allows specifying which ADK provider to use (e.g. 'langchain')
ALTER TABLE agents ADD COLUMN adk_provider VARCHAR(50);

-- Backfill existing agents with the default LangChain provider
-- so that all agents (including those created before this migration) have
-- a valid provider and clean-build scenarios don't lose routing info.
UPDATE agents SET adk_provider = 'langchain' WHERE adk_provider IS NULL;

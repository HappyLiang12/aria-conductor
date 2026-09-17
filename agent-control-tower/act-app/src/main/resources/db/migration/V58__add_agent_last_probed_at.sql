-- When the agent's runtime was last probed. NULL means never probed: the
-- health_status stamp has never been checked against reality for this agent.
-- Kept separate from updated_at, which means "the agent was edited".
ALTER TABLE agents ADD COLUMN last_probed_at TIMESTAMP;

-- Operator-controlled switch: may this agent be handed a kanban card?
-- Defaults to TRUE so existing rows (including the seeded role agents) become
-- pickup-eligible on upgrade. Defaulting to FALSE would reintroduce the bug
-- where a fresh install can dispatch nothing.
ALTER TABLE agents ADD COLUMN pickup_enabled BOOLEAN NOT NULL DEFAULT TRUE;

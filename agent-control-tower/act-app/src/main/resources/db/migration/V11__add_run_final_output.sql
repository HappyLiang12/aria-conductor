-- V11: Add final_output column to runs table
-- Stores the agent's final answer/output when a run completes.
ALTER TABLE runs ADD COLUMN final_output TEXT;

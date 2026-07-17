-- Stage 1 of self-improvement pipeline: relax run_id and add recording fields.
-- Aria chat invocations record LLM calls without a run; allow run_id to be NULL.
ALTER TABLE prompt_calls MODIFY COLUMN run_id UUID NULL;

-- Outcome of the LLM call (success | failure | partial).
ALTER TABLE prompt_calls ADD COLUMN outcome VARCHAR(32) DEFAULT 'success';

-- Comma-separated list of tool names invoked during the call.
ALTER TABLE prompt_calls ADD COLUMN tools_used VARCHAR(2048);

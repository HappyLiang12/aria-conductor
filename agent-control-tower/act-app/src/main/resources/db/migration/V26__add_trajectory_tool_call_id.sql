-- V26: Add tool_call_id to session_trajectory for tool-role tracking
ALTER TABLE session_trajectory ADD COLUMN tool_call_id VARCHAR(255);
CREATE INDEX idx_trajectory_tool_call ON session_trajectory(tool_call_id);

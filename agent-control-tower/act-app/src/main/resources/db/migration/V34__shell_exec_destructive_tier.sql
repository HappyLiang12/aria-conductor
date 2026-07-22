-- V34: Upgrade shell_exec risk tier to DESTRUCTIVE
-- shell_exec runs arbitrary shell commands with no path jail; must require approval gate.

UPDATE tool_definitions SET risk_tier = 'DESTRUCTIVE' WHERE name = 'shell_exec';

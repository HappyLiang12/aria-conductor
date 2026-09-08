-- V55: Align the seeded Aria tool schema for transition_kanban_item with the
-- HITL transition contract. V22 seeded the legacy shape (id + newStatus, no
-- enum, no HITL params); the Aria tool registry sends this parameters JSON
-- verbatim to the LLM, so the stale schema contradicted the updated prompt
-- (which says `status`) and hid the feedback/agentTemplateId params. V22 is
-- already applied on existing databases, so the seeded row is UPDATEd in place.

UPDATE tool_definitions
SET parameters = '{"type":"object","properties":{"id":{"type":"string","description":"Kanban item ID"},"status":{"type":"string","enum":["BACKLOG","TODO","IN_PROGRESS","REVIEW","DONE","CANCELLED"],"description":"Target status; Todo entry triggers agent pickup and a run; moving back to Todo/Backlog pauses the linked run"},"comment":{"type":"string","description":"Optional transition comment"},"feedback":{"type":"string","description":"Feedback sent when returning a Review item to Todo"},"agentTemplateId":{"type":"string","description":"Agent template hint for pickup"}},"required":["id","status"]}'
WHERE name = 'transition_kanban_item';

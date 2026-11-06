-- V39: advertise body + headers on the http_request tool schema (#65 Option C).
-- The handler already forwarded `body` and now also applies `headers`, but the schema exposed to
-- the model only declared method+url. Without these properties the model never sends an
-- Authorization / Content-Type header or a request body, so authenticated REST calls (e.g.
-- creating a GitHub pull request) are impossible even though the transport supports them.

UPDATE tool_definitions
SET parameters = '{"type":"object","properties":{"method":{"type":"string","enum":["GET","POST","PUT","DELETE"]},"url":{"type":"string"},"body":{"type":"string","description":"Request body for POST/PUT"},"headers":{"type":"object","description":"Custom request headers such as Authorization and Content-Type","additionalProperties":{"type":"string"}}},"required":["method","url"]}'
WHERE name = 'http_request';

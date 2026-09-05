# Security Policy

## Reporting a Vulnerability

Please report security vulnerabilities **privately** via GitHub Security Advisories
(the repository's **Security** tab → **Report a vulnerability**) rather than opening a
public issue. We aim to acknowledge reports within a few business days.

## Known Limitations (read before deploying)

Aria Conductor is an early-stage (v0.1.x) project. The current release makes several
**security trade-offs for ease of local evaluation**. Do **not** expose a default
deployment to untrusted networks or the public internet.

- **Built-in authentication for the REST API (opt-in in dev, on by default for the
  `mariadb` profile).** All `/api/v1/**` endpoints are protected by a shared-secret API key when
  `app.security.enabled=true`. Local development (default/`h2` profile) keeps the API open for
  zero-friction evaluation unless you explicitly enable auth. The `mariadb` (production) profile
  enables auth by default and **fails to start** unless a key is configured, so a non-local
  deployment cannot come up with an open API.
  - Enable/disable: `app.security.enabled` (env `APP_SECURITY_ENABLED`).
  - Configure keys: `app.security.api-keys` (env `AUTH_API_KEYS`) — a comma/space separated list
    of shared secrets, or of hex SHA-256 hashes prefixed `sha256:` (hash-at-rest is preferred).
  - Authenticate requests with `Authorization: Bearer <key>` or `X-API-Key: <key>`. Requests
    without a valid key get `401`; `/actuator/health` and `/actuator/info` stay reachable without
    credentials.
  - Keys are never logged and never exposed through any endpoint. Key **rotation**: accept two
    keys (old + new) in `AUTH_API_KEYS`, deploy, then remove the old one. Sandboxed agents receive
    the first configured plaintext key as `ARIA_API_KEY` in `opencode.sandbox-env` so they can call
    the API authenticated (only when auth is enabled and a plaintext key is configured).
  - **Still front non-local deployments with a gateway/reverse proxy.** Built-in auth authenticates
    callers; it does not add transport-layer protections, rate limiting, or fine-grained
    authorization (any valid key is fully authorized today). Keep the service on a trusted network
    and terminate TLS at your gateway.
- **Shell execution tool is disabled by default.** The `shell_exec` tool is gated
  behind `tools.shell.enabled` (default `false`). Enabling it lets agent/LLM output
  run shell commands inside the container — enable only in trusted, sandboxed setups.
  While disabled, only whitelisted first-token commands run
  (`tools.shell.whitelist` / `TOOLS_SHELL_WHITELIST`) and shell metacharacters are refused.
  **Since #65 the default whitelist includes `curl`**, so agents can call REST APIs (e.g.
  create a pull request). `curl` is refused for `file://` URLs and for local/internal targets
  (loopback, `0.0.0.0`, cloud metadata) so it cannot double as a local-file reader or an SSRF
  probe, and every `shell_exec` call still passes the HIGH-risk approval gate.
  `docker-compose` now honours `TOOLS_SHELL_WHITELIST` (it was previously hardcoded and
  silently ignored) — re-check your value after upgrading. To restore the previous set:
  `TOOLS_SHELL_WHITELIST=git,ls,cat,find,echo,mvn,npm,pnpm`.
- **LLM provider API keys are stored unencrypted at rest** in the database. Protect
  your database accordingly; encryption at rest is on the roadmap.
- **Change all default credentials.** `.env.example` ships placeholder DB passwords
  (`change-me*`). Set strong values in `.env` before any non-local use.
- **CORS** defaults to a localhost allow-list (`app.cors.allowed-origins`) and does
  not allow credentials. Configure it for your deployment.
- **H2 console and dev SQL endpoints** are restricted to the `h2` dev profile and are
  not loaded in the production (`mariadb`) profile.

See the issues labelled `security` for hardening work in progress.

# Security Policy

## Reporting a Vulnerability

Please report security vulnerabilities **privately** via GitHub Security Advisories
(the repository's **Security** tab → **Report a vulnerability**) rather than opening a
public issue. We aim to acknowledge reports within a few business days.

## Known Limitations (read before deploying)

Aria Conductor is an early-stage (v0.1.x) project. The current release makes several
**security trade-offs for ease of local evaluation**. Do **not** expose a default
deployment to untrusted networks or the public internet.

- **No built-in authentication/authorization.** All `/api/v1/**` endpoints are
  currently unauthenticated. Place the service behind your own gateway/reverse proxy
  with authentication, or keep it on a trusted local network. By default the Docker
  services bind to `127.0.0.1` only.
- **Agents run with the permissions of their core.** Shell, file and network access
  is owned by the selected core (opencode or qoder) in its `HOST` or `SANDBOX` mode;
  native permission asks are surfaced through the approval gate. Run untrusted agents
  in `SANDBOX` mode and keep the approval gate enabled.
- **LLM provider API keys are stored unencrypted at rest** in the database. Protect
  your database accordingly; encryption at rest is on the roadmap.
- **Change all default credentials.** `.env.example` ships placeholder DB passwords
  (`change-me*`). Set strong values in `.env` before any non-local use.
- **CORS** defaults to the loopback dev origins (`http://localhost:*` and
  `http://127.0.0.1:*`) and does not allow credentials. Cookie-authenticated operator
  mutations are gated separately by `aria.operator.allowed-origins` (default: the same
  loopback hosts on any port) plus a CSRF token. Configure both for your deployment.
- **H2 console and dev SQL endpoints** are restricted to the `h2` dev profile and are
  not loaded in the production (`mariadb`) profile.
- **MCP endpoint ships open by default** (`aria.mcp.auth-mode=none`), mirroring the
  unauthenticated REST API posture; every tool call is audit-logged, and Bearer-token
  mode (`ARIA_MCP_AUTH_MODE=token`) is available — see README "MCP endpoint (aria.mcp.*)".

See the issues labelled `security` for hardening work in progress.

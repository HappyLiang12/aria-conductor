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
- **Shell execution tool is disabled by default.** The `shell_exec` tool is gated
  behind `tools.shell.enabled` (default `false`). Enabling it lets agent/LLM output
  run shell commands inside the container — enable only in trusted, sandboxed setups.
- **LLM provider API keys are stored unencrypted at rest** in the database. Protect
  your database accordingly; encryption at rest is on the roadmap.
- **Change all default credentials.** `.env.example` ships placeholder DB passwords
  (`change-me*`). Set strong values in `.env` before any non-local use.
- **CORS** defaults to a localhost allow-list (`app.cors.allowed-origins`) and does
  not allow credentials. Configure it for your deployment.
- **H2 console and dev SQL endpoints** are restricted to the `h2` dev profile and are
  not loaded in the production (`mariadb`) profile.

See the issues labelled `security` for hardening work in progress.

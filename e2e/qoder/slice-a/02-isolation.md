# Task A2 evidence — Qoder CLI config isolation and plugin loading (Slice A gate)

Inside a **real OpenSandbox sandbox** created from the pinned image
`aria-conductor/qoder-sandbox:0.1`, a hostile workspace/user configuration must not be
able to add MCP servers, relax the permission mode or inject plugins; `--plugin-dir`
must load only the pinned bundle; and sandbox-level isolation (no container-runtime
socket, no host credential mount reachable) must hold.

No credentials were used anywhere. Every assertion is derived from the CLI's own debug
run log and its config surfaces (`plugins`, `agents`, `skills`, `mcp list`).

## Provenance (this evidence)

| Item | Value |
|---|---|
| Test | `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderConfigIsolationE2ETest.java` (env-gated: `-Dqoder.e2e.enabled=true`) |
| Driver | `QoderSandboxHarness` (validates the zero-credit model pin fail-closed, creates the sandbox via `OpenCodeSandboxManager`, waits for execd, uploads staging to `/workspace`, runs the script, kills the sandbox) |
| Probe script | `e2e/qoder/slice-a/02-isolation.sh` (uploaded to `/workspace/02-isolation.sh`) |
| Plugin bundle | `agent-control-tower/qoder-sandbox/plugin/` (uploaded to `/workspace/plugin`) |
| Image | `aria-conductor/qoder-sandbox:0.1` (A1 pin; see `01-boot.md`) |
| CLI | Qoder CLI 1.1.41 (Linux x64 artifact pinned in `agent-control-tower/qoder-sandbox/Dockerfile`) |
| Model pin | `-m efficient` (plan Global Constraint; value = `QODER_E2E_MODEL`, harness fails closed unless in `{efficient, lite}` or `QODER_E2E_ALLOW_PAID=1`) |
| Sandbox | OpenSandbox sandbox `d734a8f8-2a40-4bf0-a055-9346aff67585` (created 01:01:47, killed 01:02:55 by the test; `Terminating sandbox: d734a8f8-...` in the run log) |
| Run | 2026-09-18T01:02 +08:00, `BUILD SUCCESS` (`Total time: 02:30 min`), `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, A2 test time 70.37 s; unit lane `Tests run: 864, Failures: 0` |

**Provenance is the commands, not log files** — all `target/**` output is uncommitted and is
deleted by the next `mvn clean`; the `target/` paths below are regeneration pointers only:

Regeneration command (from `agent-control-tower`):

```bash
mvn clean verify -pl act-execution -Dit.test=QoderConfigIsolationE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
```

`clean` is required: `jacoco:check` is bound to the `test` phase and is only skipped by
`-Dskip.unit.tests=true`, so a stale `act-execution/target/jacoco.exec` fails the build
before Failsafe runs. Prerequisite: the local OpenSandbox server
(`podman compose up -d opensandbox-server`, health at `http://localhost:8090/health`).

The same command regenerates the raw sandbox capture
`act-execution/target/qoder-a2-sandbox-output.txt` (written by the test) and the Failsafe
report `act-execution/target/failsafe-reports/TEST-...QoderConfigIsolationE2ETest.xml`.

SUMMARY block and counts, quoted from the capture of the green run (the execd rendering
folds these markers onto one line, see limitation 3; the count commands are re-runnable
on the regenerated file):

```
==================== SUMMARY ====================failed cases: 0A2-ISOLATION-RESULT: PASSA2_SCRIPT_EXIT=0
```

```bash
grep -o '\[A2\] PASS' act-execution/target/qoder-a2-sandbox-output.txt | wc -l   # 48
grep -o '\[A2\] FAIL' act-execution/target/qoder-a2-sandbox-output.txt | wc -l   # 0
```

## Step 1 — plugin format discovered from the CLI itself

Raw help output relied on (captured with
`podman run --rm -i aria-conductor/qoder-sandbox:0.1 qodercli <args>`):

```
$ qodercli --version
1.1.41

$ qodercli --help          # flag lines used below (verbatim excerpts)
  -d, --debug                          Run in debug mode (default: false)
  -m, --model <model>                  Model for the current session. Default
                                       and New Models use model name; Custom
                                       uses modelID
  --config-dir <dir>                   Use a custom user-level config root for
                                       this run
  --permission-mode <mode>             Set the permission mode (choices:
                                       default, accept_edits,
                                       bypass_permissions, dont_ask, auto)
  --plugin-dir <dir>                   Plugin directories to load
  -p, --print                          Print response and exit (non-interactive)
  -o, --output-format <format>         The format of the CLI output
  --mcp-config <config>                Load MCP servers from JSON file(s) or
                                       inline JSON
  --strict-mcp-config                  Only use MCP servers from --mcp-config
                                       (default: false)
  --setting-sources <source>           Setting sources to load (user, project,
                                       local)
  --settings <json>                    Load additional settings from a JSON file
                                       path or inline JSON string

$ qodercli plugins --help  # list|validate|install|uninstall|enable|disable|update|marketplace
$ qodercli mcp --help      # add|add-json|remove|get|list|enable|disable|reset-project-choices
$ qodercli agents --help   # list; --setting-sources <sources...>
$ qodercli skills --help   # list (--all), enable, disable, install, link, uninstall
```

Raw output of the validate surface (captured with
`podman run --rm -i aria-conductor/qoder-sandbox:0.1 qodercli plugins validate --help`;
the same invocation is part of CASE 0 of the probe):

```
Usage: qodercli plugins validate [options] <path>

Validates a plugin from a local directory or manifest.

Arguments:
  path        The path to a plugin directory or plugin manifest.

Options:
  --json      Output a structured validation report as JSON. (default: false)
  --strict    Exit with status 1 when validation produces warnings. (default:
              false)
  -h, --help  display help for command
```

Observed manifest format (from the CLI's own `plugins validate` output, including the raw
`--json` report captured below): plugin directory with `.qoder-plugin/plugin.json`
(required `name`; optional `version`, `description`) plus at least one component directory
(`skills/<name>/SKILL.md`, `agents/`, `commands/`, ...). Diagnostics observed so far:
`plugin.manifest.schema_invalid` (`/name` Required), `plugin.installability.no_content`,
`plugin.component.frontmatter_missing`.

The bundle in this repository therefore contains:

```
agent-control-tower/qoder-sandbox/plugin/
  manifest.json                        # brief-required skeleton (byte-identical copy)
  .qoder-plugin/plugin.json            # manifest path named by `plugins validate --json`
  skills/aria-pinned/SKILL.md
```

Raw validation inside the sandbox:

```
$ qodercli -m efficient plugins validate /workspace/plugin
Validating plugin: /workspace/plugin
  Plugin name: aria-pinned

  Components loaded successfully:
    - skills (1)

Plugin "aria-pinned" is valid and ready to install.
```

Raw manifest-path report inside the sandbox (CASE 3a-2; the probe asserts the
`manifestPath` value and `"valid":true` on this output):

```
$ qodercli -m efficient plugins validate /workspace/plugin --json
{"schemaVersion":1,"ok":true,"report":{"schemaVersion":1,"validator":{"name":"qodercli","version":"1.1.41"},"valid":true,"target":{"inputPath":"/workspace/plugin","resolvedPath":"/workspace/plugin","manifestPath":".qoder-plugin/plugin.json"},"plugin":{"name":"aria-pinned","version":"0.1.0"},"diagnostics":[],"summary":{"errorCount":0,"warningCount":0,"componentCount":1},"resources":{"skills":[{"name":"aria-pinned","description":"Aria Conductor pinned skill; proves --plugin-dir loads exactly the pinned bundle."}],"agents":[],"mcpServers":[],"commands":[],"hooks":[]}}}
```

The CLI's own report names `target.manifestPath: ".qoder-plugin/plugin.json"`; the bundle
also ships a byte-identical root `manifest.json` (the brief-required skeleton), which no
captured CLI output references.

## Step 2/3 — hostile fixture, differential runs, chosen flag set

The script plants a hostile environment (`HOSTILE_HOME=/tmp/a2-hostile-home`) and first
proves it is load-bearing, then runs the isolation command:

| Scope | File | Hostile content |
|---|---|---|
| user | `/tmp/a2-hostile-home/.qoder/settings.json` | `general.defaultPermissionMode="bypass_permissions"`, MCP `a2-hostile-user-settings` (127.0.0.1:9) |
| project | `/workspace/.qoder/settings.json` | same permission mode, MCP `a2-hostile-project-settings` (127.0.0.1:8) |
| local | `/workspace/.qoder/settings.local.json` | same permission mode, MCP `a2-hostile-local-settings` (127.0.0.1:7) |
| workspace | `/workspace/.mcp.json` | MCP `a2-hostile-workspace-mcpjson` (127.0.0.1:6) |
| user plugin | installed with `qodercli plugins install /workspace/hostile-plugin` | `a2-hostile-plugin` with skill `a2-hostile-skill` in the user plugin store |

Distinct ports keep every server individually observable (identical transports are
de-duplicated by the CLI with `Skipping "<name>" — duplicate of ...`).

### Baseline: hostile config is effective (load-bearing proof)

`HOME=/tmp/a2-hostile-home qodercli -m efficient -d -p hi --output-format json` (no
isolation flags):

```
WARN  debug.message Bypass permissions mode is enabled. All tool calls will be automatically approved.
INFO  info Loaded project MCP config from /workspace/.mcp.json: 1 server(s)
INFO  [session=...] session.config.loaded project_root="/workspace" target_dir="/workspace" debug_mode=true interactive=false model="efficient" permission_mode="yolo"
DEBUG [MCP] isDisabledByUser("a2-hostile-user-settings"): enabled
DEBUG [MCP] isDisabledByUser("a2-hostile-project-settings"): enabled
DEBUG [MCP] isDisabledByUser("a2-hostile-workspace-mcpjson"): enabled
DEBUG [MCP] isDisabledByUser("a2-hostile-local-settings"): enabled
```

```
$ qodercli -m efficient mcp list           # stored-config surface
✗ a2-hostile-user-settings: http://127.0.0.1:9/mcp (http) - Disconnected
✗ a2-hostile-project-settings: http://127.0.0.1:8/mcp (http) - Disconnected
✗ a2-hostile-local-settings: http://127.0.0.1:7/mcp (http) - Disconnected
✗ a2-hostile-workspace-mcpjson: http://127.0.0.1:6/mcp (http) - Disconnected

$ qodercli -m efficient plugins list        # hostile user plugin store
a2-hostile-plugin@local v0.1.0
  Scope: user
  Status: enabled

$ qodercli -m efficient skills list --all
a2-hostile-plugin:a2-hostile-skill [Enabled]
  Location: /tmp/a2-hostile-home/.qoder/plugins/cache/local/a2-hostile-plugin/0.1.0/skills/a2-hostile-skill/SKILL.md
```

### Isolation run (the flag set chosen for B4)

```bash
qodercli -m efficient -d -p hi --output-format json \
  --config-dir /tmp/a2-clean-conf \
  --setting-sources "" \
  --strict-mcp-config \
  --mcp-config '{"mcpServers":{"a2-pinned":{"type":"http","url":"http://127.0.0.1:8099/mcp"}}}' \
  --permission-mode default \
  --plugin-dir /workspace/plugin
```

Raw result (same hostile HOME, hostile files still in place):

```
INFO  [session=...] session.config.loaded project_root="/workspace" target_dir="/workspace" debug_mode=true interactive=false model="efficient" permission_mode="default"
DEBUG [MCP] isDisabledByUser("a2-pinned"): enabled
```

Assertions evaluated on that run log: `permission_mode="default"`; exactly one effective
server, `a2-pinned`; **the string `a2-hostile` does not occur anywhere in the run log**;
no `Bypass permissions mode is enabled` warning; the run log reports the pinned model
(`model="efficient"`). All PASS.

### Model pin and recorded usage/credit fields (plan Global Constraint)

Every `qodercli` invocation the probe makes is pinned with `-m efficient`
(`QODER_E2E_MODEL`, default `efficient`); the shared `QoderSandboxHarness` resolves the
value at boot and fails closed unless it is in `{efficient, lite}` or
`QODER_E2E_ALLOW_PAID=1` is set explicitly. The probe's raw argv echo (CASE 2a;
`printf '%q'` keeps the empty `--setting-sources` argument reconstructible):

```
--- qodercli -m efficient -d -p hi --output-format json --config-dir /tmp/a2-clean-conf --setting-sources '' --strict-mcp-config --mcp-config \{\"mcpServers\":\{\"a2-pinned\":\{\"type\":\"http\"\,\"url\":\"http://127.0.0.1:8099/mcp\"\}\}\} --permission-mode default --plugin-dir /workspace/plugin
```

The isolated run log reports the pin (CASE 2a):

```
INFO  [session=...] session.config.loaded project_root="/workspace" target_dir="/workspace" debug_mode=true interactive=false model="efficient" permission_mode="default"
```

Usage/credit fields as reported by the CLI for that run (A2 is pre-auth: the run aborts
with `"result":"Not logged in · Please run /login"`, so the usage block is the CLI's
zero/empty one). Recorded, not asserted — the plan forbids asserting zero cost as a
guarantee:

```
--- isolated run reported usage/credit fields (recorded, not asserted):
"total_cost_usd":0 "total_credits":0 "input_tokens":0 "output_tokens":0 "modelUsage":{}
```

The corresponding result JSON line (CASE 2a, verbatim):

```
{"type":"result","subtype":"success","is_error":true,"duration_ms":0,"duration_api_ms":0,"num_turns":1,"result":"Not logged in · Please run /login","stop_reason":"stop_sequence","session_id":"e325271c-8de3-4e1b-bc34-97eee85e9893","total_cost_usd":0,"total_credits":0,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0,"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":0},"inference_geo":"","iterations":[],"speed":"standard"},"modelUsage":{},"permission_denials":[],"terminal_reason":"completed","uuid":"6be6914e-14c1-43b6-9884-4ef4c3423eba"}
```

A3-A5 (authenticated) inherit the validated pin from the same harness and must record the
reported usage of their runs the same way (no zero-cost assertion).

### Flag-coverage pins (which flag blocks which surface)

| Surface | Blocked by | Raw evidence |
|---|---|---|
| user/project/local settings (MCP + permission) | `--config-dir <fresh>` and `--setting-sources ""` | sources-empty run log: no `isDisabledByUser("a2-hostile-user-settings"/"-project-settings"/"-local-settings")`, `permission_mode="default"` |
| workspace `.mcp.json` | `--strict-mcp-config` (not covered by `--setting-sources`, it is not a setting source) | sources-empty run log still shows `isDisabledByUser("a2-hostile-workspace-mcpjson")`; strict-only run (no `--mcp-config`) shows **no** `isDisabledByUser` line at all |
| permission relaxation | `--setting-sources ""` + `--permission-mode default` | baseline `permission_mode="yolo"` vs isolated `permission_mode="default"` |
| model / credit pin | `-m <zero-credit model>` on every invocation (argv) | CASE 2a run log `model="efficient"`; reported usage block recorded (`total_cost_usd:0`, `total_credits:0` on the pre-auth run — recorded, not asserted) |
| user plugin store | `--config-dir <fresh>` | `--config-dir /tmp/a2-clean-conf plugins list` → `No plugins installed.`; `agents list` → only the 5 built-ins; `skills list --all` → no `a2-hostile` entry |
| plugin sources | `--plugin-dir` only | `--config-dir ... plugins list --plugin-dir /workspace/plugin --json` → exactly one plugin |

`mcp list` variant with `--setting-sources ""` (stored-config view): the three
settings-file servers are gone, the `.mcp.json` server is still listed — the same
coverage split as above.

### `--plugin-dir` loads exactly the pinned bundle

```
$ qodercli -m efficient --config-dir /tmp/a2-clean-conf plugins list --plugin-dir /workspace/plugin --json
[
  {
    "id": "aria-pinned@flag",
    "name": "aria-pinned",
    "source": "aria-pinned@inline",
    "version": "0.1.0",
    "scope": "flag",
    "enabled": true,
    "canDisable": false,
    "installPath": "/workspace/plugin",
    "description": "Aria Conductor pinned plugin bundle (loaded into qoder-sandbox only via --plugin-dir)",
    "resources": {
      "skills": [
        { "name": "aria-pinned", "description": "Aria Conductor pinned skill; proves --plugin-dir loads exactly the pinned bundle." }
      ],
      "agents": [], "mcpServers": [], "commands": [], "hooks": []
    }
  }
]
```

Exactly one `"id"` entry, `"scope": "flag"`, `"installPath": "/workspace/plugin"`, and no
`a2-hostile` entry.

## Step 4 — sandbox-level isolation (same sandbox, same script)

| Check | Result (raw) |
|---|---|
| Container provenance | `[A2] PASS: containerized: /.dockerenv or /run/.containerenv or cgroup or overlay root filesystem` |
| Container-runtime sockets | `find /run -type s` → none; none of `/run/docker.sock`, `/run/podman/podman.sock`, `/run/containerd/containerd.sock`, `/run/crio/crio.sock`, `/run/k3s/containerd/containerd.sock` exist → `[A2] PASS: no container-runtime socket reachable inside the sandbox` |
| Runtime clients | `docker: <absent>`, `podman: <absent>`, `nerdctl: <absent>`, `crictl: <absent>`, `ctr: <absent>` (informational) |
| mountinfo (raw, all lines printed) | sources are only podman-internal paths, e.g. `.../overlay-containers/40dc6ce8a02a.../userdata/run/secrets`, `.../userdata/hosts`, `/etc/hostname`, `/etc/resolv.conf`; overlay root `lowerdir=/home/user/.local/share/containers/storage/...` (podman machine storage); no `*.sock` mount, no host home/credential path → PASS |
| Sandbox HOME | `ls -la /root` → only `.bashrc`/`.profile`; `/root/.qoder` absent → PASS |
| Environment | full `env` printed: `EXECD_ENVS=/opt/opensandbox/.env`, `HOME=/root`, `HOSTNAME=40dc6ce8a02a`, `NODE_VERSION=22.23.2`, `PATH=...`, `PWD=/workspace`, `SHLVL=2`, `YARN_VERSION=1.22.22`, `container=podman` — no credential/runtime-socket variable → PASS |
| Secrets mount | full-depth scan (`find /run/secrets -type f` plus the raw tree listing): the only files are the RHEL subscription files injected by the podman machine (`/run/secrets/rhsm/ca/redhat-uep.pem`, `/run/secrets/rhsm/ca/redhat-entitlement-authority.pem`, `/run/secrets/rhsm/rhsm.conf`, `/run/secrets/rhsm/syspurpose/valid_fields.json`; the probe prints `disclosed (not a platform credential): 4 file(s) under /run/secrets/rhsm`); no podman secret file directly under `/run/secrets` and no file outside the rhsm tree → PASS (see concern 5) |
| Sandbox runtime env file | `/opt/opensandbox/.env` inspected by key names only (values never printed): no keys, no credential-looking assignment with a non-empty value → PASS |

## Chosen flags for B4 (exact argv template)

```
qodercli -m efficient -d -p <prompt> --output-format json \
  --config-dir <fresh-user-config-root> \
  --setting-sources "" \
  --strict-mcp-config \
  --mcp-config '<inline JSON holding only the pinned servers>' \
  --permission-mode <explicit mode, never inherited from files> \
  --plugin-dir <pinned bundle dir>
```

B4 notes:

- the plugin bundle must be present in the final image (or uploaded before the run);
  `--plugin-dir` is per-run argv, not persisted;
- the model is pinned in argv (`-m efficient`, plan Global Constraint); the shared E2E
  harness fails closed unless `QODER_E2E_MODEL` is in `{efficient, lite}` or
  `QODER_E2E_ALLOW_PAID=1` is set explicitly, and the reported usage/credit fields are
  recorded per run (A2's pre-auth run reports the CLI's zero/empty block above; never
  assert zero cost as a guarantee);
- `--strict-mcp-config` alone still prints the loader info line
  `Loaded project MCP config from /workspace/.mcp.json: 1 server(s)` but the servers are
  **not** registered — assert on `isDisabledByUser`/effective servers, never on the
  loader info line;
- prefer asserting on the run log (`logs/runs/<ts>/qodercli.log` under the config root):
  `mcp list` shows the stored config (it honours `--setting-sources` but ignores
  `--strict-mcp-config`/`--mcp-config` of a session).

## Not verified / limitations

1. **Session-time plugin activation is NOT VERIFIED.** Without credentials the CLI aborts
   after MCP initialization with
   `WARN process.exiting exit_code=1 reason="auth_error" message="Not logged in · Please run /login"`
   before session-time plugin activation; no plugin/skill activation line and no
   SessionStart-hook marker appears in the run log. Loading is proven at the config
   surface level (`plugins list --plugin-dir ... --json` = exactly the pinned bundle).
   A3/A5 (authenticated) must observe whether the flag-scope plugin's components become
   active in a session.
2. **Config root is writable in the sandbox.** The CLI writes into the config root
   (`logs/`, `.auth/machine_id`, `security-resources/...`, see the `security-scan` skill
   `Location: /tmp/a2-clean-conf/security-resources/...` in `skills list --all`), so
   `--config-dir` isolation is about *content* (fresh, no hostile files), not
   write-protection. A read-only mount would break CLI logging; B4 should decide.
3. **Output fidelity through execd.** The execd output rendering can join adjacent lines
   (a few newlines are dropped where chunks meet) — e.g. the `[A2]` section marker and the
   following `--- qodercli ...` argv echo, or the SUMMARY lines, appear on one line in the
   capture; the block quotes above normalize those joins. The returned text (also written
   to `target/qoder-a2-sandbox-output.txt`, regenerated by the provenance command) has the
   same artifact. All decisive markers (`[A2] PASS`, `[A2] FAIL`, `A2-ISOLATION-RESULT`,
   `A2_SCRIPT_EXIT`) are intact and were asserted by the test on the returned string, not
   on the folded rendering.
4. **`qodercli mcp list` session-flag semantics** (ignores `--strict-mcp-config` and
   `--mcp-config`) are recorded here as observed behaviour only; the session-time truth
   is taken from the run log (see chosen flags note).
5. **`/run/secrets/rhsm`** is injected by the podman machine into every container on this
   host (RHEL subscription CA/config data). The probe scans `/run/secrets` to full depth
   and fails on any file that is neither a direct podman-secret mount nor part of the rhsm
   tree; the rhsm tree (4 files) is printed raw and disclosed as above. It is not a
   credential of this platform and A2 does not fail on it, but it is reported for the
   B4/security review: if the podman machine's subscription data must not be visible in
   sandboxes, the sandbox runtime configuration needs a separate hardening item.
6. **Auto-update** remains not disabled in the image (carried over from A1); A2 runs were
   not blocked by it.

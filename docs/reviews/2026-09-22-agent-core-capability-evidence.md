# Native capability evidence — Agent Core execution modes (Task 1)

Raw captured output of `e2e/agent-core/probe-native.mjs` runs performed on this operator
machine, plus the explicit BLOCKED section required by the Task 1 gate for every
core/mode combination that did not run.

- Harness: `e2e/agent-core/probe-native.mjs` (operator-invoked; never runs in CI)
- Gate: `e2e/agent-core/capability-gate.mjs` (`requireCapabilities`, `matrixStatus`)
- Matrix written by the harness: `e2e/agent-core/fixtures/capability-matrix.json`
- Sanitized recordings: `e2e/agent-core/fixtures/` (rules: `e2e/agent-core/fixtures/README.md`)

Redaction convention in this document: every block is verbatim harness output with the
harness's own placeholders (`<USER>`, `<RUN>`, `<WORKSPACE>`, `<FIXTURES>`) in place of
operator-specific absolute paths, and the credential value never appears (the harness
injects it only as a child-process environment variable read from `--pat-file`). The
console excerpts are truncated at the harness's own 220-character event-log width where
the harness truncated them.

## Commands actually run

The token itself was never printed, copied or echoed; only the path of the operator-supplied
credential file was passed to the harness.

```
# Qoder — HOST, all nine checks, pinned model efficient
node e2e/agent-core/probe-native.mjs --core qoder --mode HOST \
  --workspace <disposable temp dir> --output <temp>/qoder-host-full.json \
  --run-dir <disposable temp dir> --recording-dir e2e/agent-core/fixtures \
  --matrix-out e2e/agent-core/fixtures/capability-matrix.json \
  --cli C:/Users/<USER>/.qoder/bin/qodercli/qodercli.exe \
  --pat-file .superpowers/sdd/2026-09-22-agent-core-execution-modes/.qoder-pat \
  --model efficient --profile-seed <disposable seed dir> --timeout-ms 120000

# Qoder — SANDBOX, prerequisite probe
node e2e/agent-core/probe-native.mjs --core qoder --mode SANDBOX ... \
  --sandbox-endpoint http://127.0.0.1:8090 --timeout-ms 30000

# OpenCode — HOST
node e2e/agent-core/probe-native.mjs --core opencode --mode HOST ... --timeout-ms 60000

# OpenCode — SANDBOX, prerequisite probe
node e2e/agent-core/probe-native.mjs --core opencode --mode SANDBOX ... \
  --sandbox-endpoint http://127.0.0.1:8090 --timeout-ms 30000
```

Environment: `win32 10.0.19045`, `x64`, Node `v24.14.1`; qodercli `1.1.61`
(`sha256=8d0c537f26549d57e6eda67a3b79f08b4a40125e2033f645e6dc60fde0ed9c58`); opencode
`1.14.31` from the global npm root.

## 1. qoder/HOST — probe run output

```
probe qoder/HOST
  workspace  <RUN-ROOT>\ws4
  scratch    <RUN-ROOT>\run4
  model      efficient
  runtime: qodercli 1.1.61 sha256=8d0c537f2654…
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  ✔ handshake
  · acp.terminate {"reason":"did not exit after stdin close"}
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · acp.exit {"code":null}
  · acp.exit {"code":0}
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · acp.exit {"code":0}
  ✔ managedAuth
  · isolated.hostile_mcp_connection {"method":"POST","url":"/mcp","body":"{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"mcp-test-client\",\"version\":\"0.0.1\"}},\"jsonrpc\":\"2.0\"
  · isolated.hostile_mcp_connection {"method":"POST","url":"/mcp","body":"{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}"}
  · isolated.hostile_mcp_connection {"method":"POST","url":"/mcp","body":"{\"method\":\"ping\",\"jsonrpc\":\"2.0\",\"id\":1}"}
  · isolated.mcp_source_control {"hostileMarkerPort":58880,"withoutStrictStdout":"Configured MCP servers:\n\n✗ hostile-marker: http://127.0.0.1:58880/mcp (http) - Disconnected\n","withStrictStdout":""}
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · acp.exit {"code":0}
  ✔ isolatedConfig
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · acp.exit {"code":0}
  ✔ allowOnce
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · acp.exit {"code":0}
  ✔ denyWithoutWrite
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · acp.exit {"code":0}
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · cancel.pending_approval {"permissionObserved":true}
  · acp.terminate {"reason":"did not exit after stdin close"}
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · acp.exit {"code":null}
  · acp.terminate {"reason":"did not exit after stdin close"}
  · cancel.arms {"beforeSession":{"sent":"session/cancel notification carrying an uncreated session id while a live session was open","liveSessionOpen":true,"liveSessionStartupUpdate":"available_commands_update","observedResponse":null,
  ✔ cancel
  · acp.exit {"code":null}
  · control.supervisor_started {"technique":"windows-job-object + NtSuspendProcess/NtResumeProcess"}
  · control.job_object_technique {"label":"job-object-membership","pid":15428,"prepare":{"ok":true,"results":["15428:ok"]},"suspend":{"ok":true,"results":["ok"]},"resume":{"ok":true,"results":["ok"]},"byteSamples":{"beforeSuspend":133,"duringSuspendA":1
  · control.tree_technique {"label":"descendant-tree-enumeration","pid":9920,"prepare":{"ok":true,"pids":[9920]},"suspend":{"first":{"ok":true,"results":["9920:ok"]},"second":{"ok":true,"results":["9920:ok"]}},"resume":{"first":{"ok":true,"results
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}"],"cwd":"<WORKSPACE>"
  · writer.owned_set_captured {"toolCallSeen":true,"tickBytes":57,"ownedSet":["17776@20260923004625721","24472@20260923004625727","1944@20260923004635960","1280@20260923004635970","17296@20260923004635974","24428@20260923004636629","11608@20260923004
  ✔ pauseResume
  · acp.exit {"code":1}
  ✔ writersStopped
  ✔ stableExport
  · writer.scenario {"prompt":"Run this exact command with the Bash tool, do not modify it: node spawn-writer.mjs ticks.log. Then reply with the exact text DONE.","promptStopReason":"end_turn","promptError":null,"permissionLog":[{"title":"n

  ✔ pauseResume
  ✔ handshake
  ✔ managedAuth
  ✔ isolatedConfig
  ✔ allowOnce
  ✔ denyWithoutWrite
  ✔ cancel
  ✔ writersStopped
  ✔ stableExport
  status: verified
  row: <RUN-ROOT>\out\qoder-host-final3.json
  artifacts: e2e/agent-core/fixtures/qoder-host-cli-1.1.61-protocol.jsonl, e2e/agent-core/fixtures/qoder-host-cli-1.1.61-events.json, e2e/agent-core/fixtures/qoder-host-stable-export-1.1.61.json
EXIT=0
```

This run (re-observed end-to-end with the harness corrections of section 5.5) wrote the
`qoder/HOST` row of `e2e/agent-core/fixtures/capability-matrix.json` with all nine check booleans
`true` (`status: verified`), and refreshed
`e2e/agent-core/fixtures/qoder-host-cli-1.1.61-protocol.jsonl`,
`qoder-host-cli-1.1.61-events.json` and `qoder-host-stable-export-1.1.61.json`. The per-check
evidence behind those booleans (protocol capture, offered permission options, the three cancel
arms, the live writer scenario with its byte samples and the post-termination snapshot pair) is
in those fixtures. The block above is the harness's stdout/stderr verbatim; the operator temp
paths are replaced with `<RUN-ROOT>`, and the event lines carry the harness's own
`<USER>`/`<RUN>`/`<WORKSPACE>`/`<FIXTURES>` placeholders (the row additionally records
`<PROFILE-SEED>` and `<CREDENTIAL-FILE>` where the harness consumed those operator inputs).

## 2. qoder/SANDBOX — probe run output (BLOCKED)

```
probe qoder/SANDBOX
  workspace  <RUN-ROOT>\ws11
  scratch    <RUN-ROOT>\run11
  model      efficient
  · sandbox.prerequisites {"sandboxEndpoint":"http://127.0.0.1:8090","sandboxServerReachable":false,"sandboxServerProbe":{"reachable":false,"reason":"ECONNREFUSED"},"sandboxHealth":null,"requestedImage":null,"containerImages":{"docker":{"exitCode
  ✖ pauseResume — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ handshake — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ managedAuth — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ isolatedConfig — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ allowOnce — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ denyWithoutWrite — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ cancel — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ writersStopped — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ stableExport — OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman

  ✖ pauseResume
  ✖ handshake
  ✖ managedAuth
  ✖ isolatedConfig
  ✖ allowOnce
  ✖ denyWithoutWrite
  ✖ cancel
  ✖ writersStopped
  ✖ stableExport
  status: blocked
  blocked: OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  row: <RUN-ROOT>\out\qoder-sandbox.json
  artifacts: e2e/agent-core/fixtures/qoder-sandbox-cli-unknown-protocol.jsonl, e2e/agent-core/fixtures/qoder-sandbox-cli-unknown-events.json
EXIT=1
```

Recorded container-image inventory from the same run (`sandbox.prerequisites` event, verbatim
from `e2e/agent-core/fixtures/qoder-sandbox-cli-unknown-events.json`):

```
"containerImages": {
  "docker": { "exitCode": 1, "imageCount": 0, "sandboxLike": [] },
  "podman": { "exitCode": 125, "imageCount": 0, "sandboxLike": [] }
}
```

## 3. opencode/HOST — probe run output (BLOCKED)

**Provenance note (fix round A2).** The block below is a captured **earlier** `opencode/HOST`
attempt (its own row file `opencode-host3.json`, dynamic port 58574) - not the run behind the
committed `opencode/HOST` row and fixtures, whose recordings come from a later run (probe window
`2026-09-22T16:51:50.405Z` to `2026-09-22T16:52:03.153Z`; its `opencode.host_probe` event records
`"argv": ["serve","--port","60184","--hostname","127.0.0.1"]` in
`e2e/agent-core/fixtures/opencode-host-cli-1.14.31-events.json`, matching the committed row's
probe window). The committed run's own console output was not preserved verbatim in this
document; the `EXIT=1` in the block is the harness exit of the quoted earlier attempt (the
harness exits non-zero unless the row is verified).

```
probe opencode/HOST
  workspace  <RUN-ROOT>\ws-oc3
  scratch    <RUN-ROOT>\run-oc3
  model      efficient
  · sandbox.prerequisites {"sandboxEndpoint":"http://127.0.0.1:8090","sandboxServerReachable":false,"sandboxServerProbe":{"reachable":false,"reason":"ECONNREFUSED"},"sandboxHealth":null,"requestedImage":null,"containerImages":{"docker":{"exitCode
  · opencode.host_probe {"argv":["serve","--port","58574","--hostname","127.0.0.1"],"sanitizedXdgRoots":{"XDG_DATA_HOME":"<RUN>\\xdg\\data","XDG_CONFIG_HOME":"<RUN>\\xdg\\config","XDG_CACHE_HOME":"<RUN>\\xdg\\cache"},"scrubbedEnvKeys":["QODERCL
  ✖ handshake — no model-bound response (HTTP 200, provider error {"name":"APIError","data":{"message":"Error from provider (Console): OpenCode 1.18.0 or newer is required to use the free tier","statusCode":426,"isRetryable":false,"responseHeaders":{"cf-placement":"remote-ORD","cf-ray")
  ✖ managedAuth — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset; the operator personal provider credential is deliberately excluded by run-owned XDG roots)
  ✖ allowOnce — no model run is possible without a credential; nothing to observe (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset; the operator personal provider credential is deliberately excluded by run-owned XDG roots))
  ✖ denyWithoutWrite — no model run is possible without a credential; nothing to observe (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset; the operator personal provider credential is deliberately excluded by run-owned XDG roots))
  ✖ cancel — no model run is possible without a credential; nothing to observe (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset; the operator personal provider credential is deliberately excluded by run-owned XDG roots))
  ✖ isolatedConfig — OpenCode host configuration isolation (run-owned XDG roots) was applied but hostile-project control was not exercised (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset))
  ✖ pauseResume — no model run and no verified OpenCode process-control workflow (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset))
  ✖ writersStopped — no writer scenario can run without a model result (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset))
  ✖ stableExport — no writer scenario can run without a model result (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset))

  ✖ pauseResume
  ✖ handshake
  ✖ managedAuth
  ✖ isolatedConfig
  ✖ allowOnce
  ✖ denyWithoutWrite
  ✖ cancel
  ✖ writersStopped
  ✖ stableExport
  status: blocked
  blocked: no model run and no verified OpenCode process-control workflow (no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset)) | not verified in this environment | no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset; the operator personal provider credential is deliberately excluded by run-owned XDG roots) | OpenCode host configu
  row: <RUN-ROOT>\out\opencode-host3.json
  artifacts: e2e/agent-core/fixtures/opencode-host-cli-1.14.31-protocol.jsonl, e2e/agent-core/fixtures/opencode-host-cli-1.14.31-events.json
EXIT=1
```

Exact unmet prerequisite (as recorded in the row): no run-owned model-provider credential in this
environment - the harness excludes the operator's personal provider credential by using run-owned
`XDG_*` roots (see `opencode-host-cli-1.14.31-events.json`, `authList.credentialCount: 0`),
and the bundled free tier answers HTTP 200 with an embedded provider error
(`statusCode: 426, "OpenCode 1.18.0 or newer is required to use the free tier"`) against the
installed `opencode 1.14.31`, which the harness records as a failed handshake.

## 4. opencode/SANDBOX — probe run output (BLOCKED)

```
probe opencode/SANDBOX
  workspace  <RUN-ROOT>\ws12
  scratch    <RUN-ROOT>\run12
  model      efficient
  · sandbox.prerequisites {"sandboxEndpoint":"http://127.0.0.1:8090","sandboxServerReachable":false,"sandboxServerProbe":{"reachable":false,"reason":"ECONNREFUSED"},"sandboxHealth":null,"requestedImage":null,"containerImages":{"docker":{"exitCode
  ✖ pauseResume — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ handshake — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ managedAuth — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ isolatedConfig — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ allowOnce — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ denyWithoutWrite — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ cancel — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ writersStopped — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  ✖ stableExport — no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman

  ✖ pauseResume
  ✖ handshake
  ✖ managedAuth
  ✖ isolatedConfig
  ✖ allowOnce
  ✖ denyWithoutWrite
  ✖ cancel
  ✖ writersStopped
  ✖ stableExport
  status: blocked
  blocked: no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset); OpenSandbox server not reachable at http://127.0.0.1:8090 (nothing listens) and no sandbox image present in docker/podman
  row: <RUN-ROOT>\out\opencode-sandbox.json
  artifacts: e2e/agent-core/fixtures/opencode-sandbox-cli-unknown-protocol.jsonl, e2e/agent-core/fixtures/opencode-sandbox-cli-unknown-events.json
EXIT=1
```

## 5. Harness defects found while running this gate

The gate harness itself was corrected four times during this run. Each fix is listed with the
raw output that exposed it; the fixtures in `e2e/agent-core/fixtures/` were produced by runs
using the corrected harness.

### 5.1 Descendant-tree suspend/resume counter asymmetry (blocked pauseResume/writersStopped/stableExport)

Raw output of the first full `qoder/HOST` run:

```
  · control.supervisor_started {"technique":"windows-job-object + NtSuspendProcess/NtResumeProcess"}
  · control.job_object_technique {"label":"job-object-membership","pid":11116,"prepare":{"ok":true,"results":["11116:ok"]},"suspend":{"ok":true,"results":["ok"]},"resume":{"ok":true,"results":["ok"]},"byteSamples":{"beforeSuspend":152,"duringSuspendA":1
  · control.tree_technique {"label":"descendant-tree-enumeration","pid":23716,"prepare":{"ok":true,"pids":[23716]},"suspend":{"first":{"ok":true,"results":["23716:ok"]},"second":{"ok":true,"results":["23716:ok"]}},"resume":{"ok":true,"results":["2
  ✖ pauseResume — no OS-owned suspend/resume technique was verified on a controlled tree
  ✖ writersStopped — no OS-owned termination technique was verified on a controlled tree
  ✖ stableExport — writer quiescence cannot be established without a verified termination technique
```

Executed-code evidence from `qoder-host.json` of that run (byte samples of the controlled
writer's tick file; the tree technique suspended twice and resumed once, so
`NtSuspendProcess`'s per-process suspend count never reached zero):

```
jobObjectMembership.verified      = true
  byteSamples {"beforeSuspend":152,"duringSuspendA":152,"duringSuspendB":152,"afterResume":304}
  frozenWhileSuspended=true grewAfterResume=true
descendantTreeEnumeration.verified = false
  suspend {"first":{"ok":true,"results":["23716:ok"]},"second":{"ok":true,"results":["23716:ok"]}}
  resume  {"ok":true,"results":["23716:ok"]}
  byteSamples {"beforeSuspend":152,"duringSuspendA":152,"duringSuspendB":152,"afterResume":152}
  frozenWhileSuspended=true grewAfterResume=false frozenAfterKill=true
```

After the minimal fix (the race re-sweep suspend is now matched by a second resume), the same
three checks were re-probed and verified in one run:

```
probe qoder/HOST
  workspace  <RUN-ROOT>\ws4
  scratch    <RUN-ROOT>\run4
  model      efficient
  runtime: qodercli 1.1.61 sha256=8d0c537f2654…
  · control.supervisor_started {"technique":"windows-job-object + NtSuspendProcess/NtResumeProcess"}
  · control.job_object_technique {"label":"job-object-membership","pid":24224, ...
  · control.tree_technique {"label":"descendant-tree-enumeration","pid":14160,"prepare":{"ok":true,"pids":[14160]},"suspend":{"first":{"ok":true,"results":["14160:ok"]},"second":{"ok":true,"results":["14160:ok"]}},"resume":{"first":{"ok":true,"res
  · acp.spawn {"exe":"C:\\Users\\<USER>\\.qoder\\bin\\qodercli\\qodercli.exe","args":["--acp","--config-dir","<RUN>\\config","--setting-sources","project","--strict-mcp-config","--m
  · writer.owned_set_captured {"toolCallSeen":true,"tickBytes":95,"ownedSet":["9828@20260923000446365","14056@20260923000446372","24164@20260923000453258", ...
  ✔ pauseResume
  · acp.exit {"code":1}
  ✔ writersStopped
  ✔ stableExport
  · writer.scenario {"prompt":"Run this exact command with the Bash tool, do not modify it: node spawn-writer.mjs ticks.log. Then reply with the exact text DONE.","promptStopReason":"end_turn","promptError":null,"permissionLog":[{"title":"n

  ✔ pauseResume
  ✖ handshake
  ✖ managedAuth
  ✖ isolatedConfig
  ✖ allowOnce
  ✖ denyWithoutWrite
  ✖ cancel
  ✔ writersStopped
  ✔ stableExport
  status: partial
  blocked: not verified in this environment
```

### 5.2 Node cannot spawn the Windows npm `.cmd` shim (blocked opencode/HOST)

Raw output of the first `opencode/HOST` attempt:

```
probe-native: Error: spawn EINVAL
    at ChildProcess.spawn (node:internal/child_process:421:11)
    at spawn (node:child_process:796:9)
    at file:///C:/.../e2e/agent-core/probe-native.mjs:304:19
    at runProcess (file:///C:/.../e2e/agent-core/probe-native.mjs:303:10)
    at probeOpenCodeHost (file:///C:/.../e2e/agent-core/probe-native.mjs:1859:25)
EXIT=2
```

The npm shim `%APPDATA%\npm\opencode.cmd` is unwrapped to
`…\node_modules\opencode-ai\bin\opencode` and launched with the current Node binary; the
recorded invocation keeps both the operator path and the unwrapped entry.

### 5.3 A provider error payload was being accepted as a verified opencode handshake

Raw evidence from `opencode-host-cli-1.14.31-events.json` of the run before the fix - HTTP 200
with an embedded provider error was treated as a model-bound response (`status < 400`):

```
"messageAttempt": {
  "status": 200,
  "body": "{\"info\":{...,\"modelID\":\"big-pickle\",\"providerID\":\"opencode\",...
    \"error\":{\"name\":\"APIError\",\"data\":{\"message\":\"Error from provider (Console): OpenCode 1.18.0 or newer is required to use the free tier\",\"statusCode\":426,\"isRetryable\":false,...}}}"
}
```

That run therefore reported `✔ handshake` for `opencode/HOST` with no working model provider,
which contradicts the gate's contract. The check now requires a model identity **and** the
absence of a provider error in the assistant message, and the failure reason quotes the
provider error.

### 5.4 Credential-shaped or identity-bearing strings could reach a fixture

Two independent gaps were observed in the first committed fixtures; both are now closed.

**(a) Structured evidence strings bypassed the text redactor.** The first
`qoder-host-cli-1.1.61-events.json` (probe run of 2026-09-22T15:59Z) contained the operator
account name verbatim in 47 escaped path forms and contained no `<USER>` placeholder at all.
Raw form (the account name is redacted to `<ACCOUNT>` here, exactly as this document must;
the pre-fix fixture printed it verbatim):

```
"C:\\Users\\<ACCOUNT>\\.qoder\\bin\\qodercli\\qodercli.exe"
```

**(b) JSON-escaped (doubled) separators survived path replacement**, because nested wire text
inside an evidence field carries `\\` where the replacement path has `\`:

```
"body": "{\"info\":{...\"path\":{\"cwd\":\"C:\\\\Users\\\\<ACCOUNT>\\\\AppData\\\\Local\\\\Temp\\\\<RUN-ROOT-LEGACY>\\\\ws13\", ...}}}"
```

Fix: structured evidence strings are routed through the full text redactor (paths, user
identities, e-mail addresses, token-like strings), and every replacement path is also replaced
in its JSON-escaped form.

Verification after the fix (scan of every file committed under `e2e/agent-core/fixtures/`):
zero matches for the operator account name, zero matches for an e-mail-shaped string, and the
`<USER>`/`<RUN>`/`<WORKSPACE>`/`<FIXTURES>` placeholders present where paths occur. Section 5.5(c)
records the two forms that this first redaction fix did not cover and that the review-fix round
closed.

### 5.5 Review-fix round: three further harness defects (2026-09-23)

**(a) The deepest-first kill sweep threw on an empty tree.** In the previously frozen fixture
(the verified run of 2026-09-22T16:12Z, before this fix round) the
`writer.scenario.terminationEvidence.killSweep` field was:

```
killSweep: {"ok":false,"error":"unparseable: {\"ok\":false,\"error\":\"Exception calling Reverse with 1 argument(s): Value cannot be null."}
```

`Get-TreePids` returned `$null` for an empty tree and that value reached `[array]::Reverse`; the
recorded `technique` still claimed the deepest-first sweep had run. The pid-list getter now
always returns an array, the sweep skips the reverse for 0/1 pids and emits `[]` (not `[""]`) for
an empty result, the recorded `technique` is assembled only from steps that returned a parseable
response (with the process counts they acted on), and a step that did not run becomes a run
limitation instead of part of the technique. Standalone smoke test of the supervisor against a
live process tree (script outside the repository):

```
assign       {"rootAssigned":true,...,"pids":[23144,3496,3340],"rootInJob":true}
killtree     {"ok":true,"terminated":["3340:ok","3496:ok","23144:ok"],"resweep":[]}
killtree2    {"ok":true,"terminated":[],"resweep":[]}
```

`killtree2` is the previously failing case (empty tree). The `writersStopped` technique recorded
by the run of section 1 now reads
`identity-validated owned records (pid + creation time) terminated via TerminateProcess (N record(s)) + deepest-first descendant-tree TerminateProcess sweep (N process(es) still present) + re-enumerated live-tree sweep via TerminateProcess (N record(s))`,
and `controlTechnique.jobObjectSelectionBlockedBy` records why job-object membership (verified on
a controlled tree) is not the live-core technique: the live core refuses
`AssignProcessToJobObject` with access denied (recorded in the row's limitations as well).

**(b) Cancel arm (a) demanded a rejection the core does not produce.** The pinned core answers a
`session/cancel` for an unknown session with silence, so the arm recorded a `pass` that asserted
almost nothing. It now records the observed silence verbatim
(`"observedResponseNote": "... the core answered the unknown-session cancel with silence ..."`,
with the live session's startup update settled first) and requires the live owned tree to be
untouched: the core process still answers, the workspace snapshot sha256 is unchanged and zero
session updates arrive during the window. Arm (c) now requires the documented "without granting":
`stopReason === 'cancelled'` **and** the target file absent.

**(c) The redactor missed forward-slash and multi-level-escaped path forms.** The first fix-round
fixtures contained the operator temp root at 8 backslashes per separator (a tool-call argument
nested three string levels deep), and the POSIX rule mangled a forward-slash drive path into
`C:<HOME>/...`, leaving the remainder visible. Raw shapes (the temp root is redacted to
`<RUN-ROOT>` here, exactly as this document must; the pre-fix fixtures printed it verbatim):

```
"cwd":"C:\\\\\\\\Users\\\\\\\\<ACCOUNT>\\\\\\\\AppData\\\\\\\\...\\\\\\\\<RUN-ROOT>\\\\\\\\ws4\\\\\\\\probe-deny.txt"
"printf 'beta-should-not-exist' > \"C:<HOME>/AppData/Local/Temp/<RUN-ROOT>/ws2/probe-deny.txt\""
```

Replacement paths are now applied in raw, forward-slash, 2x, 4x and 8x escaped forms; the
user-path rules cover 1x/2x/4x/8x escapes and forward-slash drive paths; the POSIX rule is
lookbehind-guarded so it no longer cuts a Windows drive path down to `C:<HOME>`; and
`--profile-seed`/`--pat-file` become `<PROFILE-SEED>`/`<CREDENTIAL-FILE>`. Self-test (script
outside the repository):

```
ok   raw workspace      -> x <WORKSPACE>\probe.txt y
ok   forward workspace  -> x <WORKSPACE>/probe.txt y
ok   2x workspace       -> x <WORKSPACE>\\probe.txt y
ok   4x workspace       -> x <WORKSPACE>\\\\probe.txt y
ok   8x workspace       -> x <WORKSPACE>\\\\\\\\probe.txt y
ok   raw cli            -> x C:\Users\<USER>\.qoder\bin\qodercli\qodercli.exe y
ok   forward cli        -> x C:/Users/<USER>/.qoder/bin/qodercli/qodercli.exe y
ok   2x cli             -> x C:\\Users\\<USER>\\.qoder\\bin y
ok   4x cli             -> x C:\\\\Users\\\\<USER>\\\\.qoder\\\\bin y
ok   8x cli             -> x C:\\\\\\\\Users\\\\\\\\<USER>\\\\\\\\.qoder\\\\\\\\bin y
ok   posix home         -> x <HOME>/work y
ok   posix users        -> x <HOME>/work y
ok   secret             -> x [REDACTED-SECRET] y
REDACTOR SELF-TEST PASS
```

A scan of every committed fixture for `C:` + backslash runs + `Users` + a non-placeholder name
(regex `C:\\{0,16}Users\\{0,16}([^\\"'\s,]{1,24})`) reports no account-name form at any escape
level. Fixtures and the verified row were regenerated with the corrected harness (section 1); the
fix-round hygiene scan (quoted in the task report) reports no credential value, no operator temp
path and no unredacted user path across the committed fixtures, this document and the report.

Fix round A2 (2026-09-23) amended `e2e/agent-core/probe-native.mjs` **after** the rows and
fixtures above were frozen - the cancel arm (a) note now states the observational limits of the
arm instead of the wire behaviour of the core, and `controlTechnique.jobObjectSelectionBlockedBy`
is set only from the observed live assignment outcome - with no check semantics changed; the
recorded rows therefore come from the pre-A2 harness revision, and the next live matrix run
(Task 20) will produce rows from the amended harness.

## 6. Gate status

```
qoder/HOST: verified
qoder/SANDBOX: BLOCKED (qoder/SANDBOX: pauseResume not verified; qoder/SANDBOX: handshake not verified; qoder/SANDBOX: managedAuth not verified; qoder/SANDBOX: isolatedConfig not verified; qoder/SANDBOX: allowOnce not verified; qoder/SANDBOX: denyWithoutWrite not verified; qoder/SANDBOX: cancel not verified; qoder/SANDBOX: writersStopped not verified; qoder/SANDBOX: stableExport not verified)
opencode/HOST: BLOCKED (opencode/HOST: pauseResume not verified; opencode/HOST: handshake not verified; opencode/HOST: managedAuth not verified; opencode/HOST: isolatedConfig not verified; opencode/HOST: allowOnce not verified; opencode/HOST: denyWithoutWrite not verified; opencode/HOST: cancel not verified; opencode/HOST: writersStopped not verified; opencode/HOST: stableExport not verified)
opencode/SANDBOX: BLOCKED (opencode/SANDBOX: pauseResume not verified; opencode/SANDBOX: handshake not verified; opencode/SANDBOX: managedAuth not verified; opencode/SANDBOX: isolatedConfig not verified; opencode/SANDBOX: allowOnce not verified; opencode/SANDBOX: denyWithoutWrite not verified; opencode/SANDBOX: cancel not verified; opencode/SANDBOX: writersStopped not verified; opencode/SANDBOX: stableExport not verified)
capability gate failed: 3/4 combinations blocked
EXIT=1
```

The CLI exits non-zero while any combination is unmet; `qoder/HOST` is the only verified
combination, and every blocked row carries its prerequisite in
`e2e/agent-core/fixtures/capability-matrix.json` (section 7 below).

## 7. BLOCKED combinations and their exact unmet prerequisite

| Combination | Status | Exact unmet prerequisite |
|---|---|---|
| `qoder/HOST` | verified | none: all nine checks observed `true` in the run of section 1 (`status: verified`, harness exit 0) |
| `qoder/SANDBOX` | BLOCKED | No OpenSandbox server on this machine (`http://127.0.0.1:8090` → `ECONNREFUSED`) and no sandbox image in the local docker/podman stores (`imageCount: 0`). Additionally no verified Linux qodercli artifact exists: the only installed CLI is the operator's Windows build (`qodercli 1.1.61`, `C:/Users/<USER>/.qoder/bin/qodercli/qodercli.exe`), and the sandbox image work of Task 10 has not produced a frozen artifact yet. |
| `opencode/HOST` | BLOCKED | No run-owned model-provider credential in this environment: the repository has no `.env` (the harness deliberately excludes the operator's personal provider credential by using run-owned `XDG_*` roots, and `opencode auth list` reports `0 credentials` in the run-owned store). The only model round-trip available is the bundled free tier, which answers `426 OpenCode 1.18.0 or newer is required to use the free tier` against the installed `opencode 1.14.31`. |
| `opencode/SANDBOX` | BLOCKED | Both prerequisites unmet: no run-owned model-provider credential (as above) **and** no OpenSandbox server/image (as in `qoder/SANDBOX`). |

Nothing in this document claims a capability for a combination whose behavior was not
observed. The harness defects of sections 5.1, 5.3 and 5.5 (cancel-arm contract, kill-sweep
technique, multi-level escaping) would have produced exactly such a claim or a leaked path; all
were fixed and the fixtures re-frozen before this document was finalised.

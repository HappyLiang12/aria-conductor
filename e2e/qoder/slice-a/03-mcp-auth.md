# Task A3 evidence — authenticated MCP round-trip with real headers (Slice A gate)

Inside a **real OpenSandbox sandbox** created from the pinned image
`aria-conductor/qoder-sandbox:0.1`, a **real authenticated Qoder session** (PAT injected
into the sandbox environment only) must complete an MCP round-trip over HTTP against a
**header-validating** stub: the call succeeds when
`Authorization: Bearer test-worker-token` is present, and fails when the header is
missing or wrong — that negative case is what proves the header path is load-bearing.

This is the first authenticated Slice A gate; A2 (pre-auth) and A1 (boot) used no
credentials. The Qoder PAT was read from the local credential file into the host
environment, travelled into the sandbox container environment (`createSandbox(agentId,
image, env)`), and was never an argv entry, never written to a file and never printed.

## Provenance (this evidence)

| Item | Value |
|---|---|
| Test | `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderMcpAuthE2ETest.java` (env-gated: `-Dqoder.e2e.enabled=true`; skips with the credential named when `QODER_E2E_PAT` is absent) |
| Driver | `QoderSandboxHarness#boot(serverUrl, image, sandboxEnv)` — creates the sandbox via `OpenCodeSandboxManager#createSandbox(UUID, String, Map)`, waits for execd, uploads staging to `/workspace`, runs the script, kills the sandbox |
| Probe script | `e2e/qoder/slice-a/03-mcp-auth.mjs` (uploaded to `/workspace/03-mcp-auth.mjs`, executed by the in-image Node 22) |
| Plugin bundle | `agent-control-tower/qoder-sandbox/plugin/` (uploaded to `/workspace/plugin`, passed as `--plugin-dir`) |
| Image | `aria-conductor/qoder-sandbox:0.1` — image ID `sha256:004f664e90105f0a75433d9e73b4ffd53281414ef75ae3bf11c4bb2e8c0341a9`, repo digest `sha256:d7e3f4d5abc0c9b96507aa8902dbf21fb72705d1fc037f89e9379a13896c471b` (A1 pin; `01-boot.md`) |
| CLI | Qoder CLI 1.1.41 (Linux x64 artifact, sha256-pinned in `agent-control-tower/qoder-sandbox/Dockerfile`) |
| Model pin | `-m efficient` on the ACP process **and** `session/set_model {sessionId, modelId: "efficient"}` (accepted, `{}`); the harness fails closed off `{efficient, lite}` unless `QODER_E2E_ALLOW_PAID=1` |
| Credential path | host env `QODER_E2E_PAT` → sandbox container env `QODER_PERSONAL_ACCESS_TOKEN` (manager log: `Injecting 1 env var(s) into sandbox`, count only). The CLI's own env var name was confirmed inside the pinned binary: `grep -aoE 'QODER_[A-Z_]*TOKEN[A-Z_]*' /usr/local/bin/qodercli` → `QODER_AUTH_MANAGED_TOKEN`, `QODER_DEVICE_TOKEN`, `QODER_ENV_JOB_TOKEN`, **`QODER_PERSONAL_ACCESS_TOKEN`**, `QODER_SDK_ACCESS_TOKEN` |
| Sandbox | OpenSandbox sandbox `a595922c-87ae-472e-a24a-713f22e67105` for agent `50af0a20-efb9-4e60-ac5b-93ba65d31161` (env injected 01:30:20.138, created 01:30:22.141, `Terminating sandbox:` 01:31:13.954, killed 01:31:15.393 — all from the run log) |
| Run | 2026-09-18T01:28:57 +08:00 (start) → 01:31:15 (finish), `BUILD SUCCESS`, `Total time: 02:15 min`, unit lane `Tests run: 864, Failures: 0`, A3 test `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `Time elapsed: 56.05 s`; four cases (an earlier three-case run at 01:25, 42.27 s, is superseded by this one) |
| Probe runs | 2026-09-18T01:14 (`podman run --rm -i`, same image, same script, no plugin dir) for `session/new` schema discovery; the decisive raw responses are quoted in §2. The fourth case (`call-401`) was verified in the same podman dev loop before the Maven run |

**Provenance is the commands, not log files** — all `target/**` output is uncommitted and
is deleted by the next `mvn clean`; the `target/` paths below are regeneration pointers
only.

Regeneration command (from `agent-control-tower`; local only — the PAT must be in the
host environment, never on the command line):

```bash
export QODER_E2E_PAT="$(cat /c/Users/User/.qoder/qoder-pat.txt)"   # local machine only; never echoed
mvn clean verify -pl act-execution -Dit.test=QoderMcpAuthE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
```

`clean` is required: `jacoco:check` is bound to the `test` phase and is only skipped by
`-Dskip.unit.tests=true`, so a stale `act-execution/target/jacoco.exec` fails the build
before Failsafe runs. Prerequisite: the local OpenSandbox server
(`podman compose up -d opensandbox-server`, health at `http://localhost:8090/health`).

The same command regenerates the raw sandbox capture
`act-execution/target/qoder-a3-sandbox-output.txt` (written by the test) and the Failsafe
report `act-execution/target/failsafe-reports/TEST-...QoderMcpAuthE2ETest.xml`.

Marker counts, re-runnable on the regenerated capture:

```bash
cd agent-control-tower/act-execution
grep -o '\[A3\] PASS' target/qoder-a3-sandbox-output.txt | wc -l   # 4
grep -o '\[A3\] FAIL' target/qoder-a3-sandbox-output.txt | wc -l   # 0
grep -o 'A3-CASE-JSON' target/qoder-a3-sandbox-output.txt | wc -l  # 4
grep -o 'A3-MCP-AUTH-RESULT: PASS' target/qoder-a3-sandbox-output.txt | wc -l  # 1
```

Raw verdict lines (quoted from the capture of the green run; the execd rendering folds
adjacent lines, see limitation 1):

```text
[A3] model pin: QODER_E2E_MODEL=efficient (spawn -m efficient + session/set_model)[A3] plugin dir: /workspace/plugin[A3] MCP stub bearer: synthetic placeholder (expected header present in the positive case)[A3] PASS: positive (header present) sessionCreated=true stopReason=end_turn answer="pong-from-aria-stub" pingCalls=1 permissionGrants=1 permissionRequests=1 authFailures=0 modelSet=accepted note=prompt response received
[A3] PASS: negative (header omitted) stub 401s=1 (initialize:missing) pingCalls=0 attemptedCall=false cliToolFailureObserved=false answer="I don't have access to a tool named `aria_ping`. The only `aria-stub` MCP tool currently available is `mcp__aria-stub__a" stopReason=end_turn note=prompt response received
[A3] PASS: negative (wrong header) stub 401s=1 (initialize:mismatch) pingCalls=0 attemptedCall=false cliToolFailureObserved=false answer="I don't have a tool named `aria_ping` available. The only `aria-stub` MCP tool I currently have access to is `mcp__aria-" stopReason=end_turn note=prompt response received
[A3] PASS: negative (call itself 401s) stub 401s=1 (tools/call:missing) publicRequests=3 pingCalls=0 attemptedCall=true cliToolFailureObserved=true permissionGrants=1 answer="Streamable HTTP error: Error POSTing to endpoint: {\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32001,\"message\":\"Unauthorized: missing or invalid Authorization heade" stopReason=end_turn note=prompt response received
A3-SUMMARY-JSON: {"pingCalls":1,"permissionGrants":2,"authFailures":3,...}
A3-MCP-AUTH-RESULT: PASSA3_SCRIPT_EXIT=0
```

## 1. The probe design (four cases, one stub per case)

Each case starts a fresh loopback HTTP MCP stub (`127.0.0.1:<ephemeral>/mcp`) offering one
tool `aria_ping` that returns `pong-from-aria-stub`, spawns a fresh `qodercli -m efficient
[--plugin-dir /workspace/plugin] --acp`, drives the ACP handshake (initialize →
`session/new` → `session/set_model` → `session/prompt`) and answers permission requests by
**kind**: only a positively identified `aria_ping` call gets the offered option whose
`kind` is `allow_once`; anything else is cancelled. `allow_always` is never selected.

The stub validates the header on **every** JSON-RPC POST (the three header cases use the
default scope `all`):

```text
if (req.headers['authorization'] !== 'Bearer test-worker-token') → HTTP 401
  recorded as {method}:missing | {method}:mismatch   (the header value is never recorded)
```

The fourth case (`call-401`) uses the stub scope `call-only`: `initialize` and
`tools/list` are served **without** a header (MCP metadata is not secret), and only
`tools/call` is validated. The CLI therefore obtains `aria_ping` and actually attempts the
call, which the stub then refuses with 401 — isolating "the CLI's call fails because the
server rejected the header" from "the CLI never learned the tool".

Cases differ only in the `session/new.mcpServers[0].headers` entry and the stub scope:

| Case | `headers` sent | Stub scope | Expected | Verdict |
|---|---|---|---|---|
| positive | `[{name:"Authorization", value:"Bearer test-worker-token"}]` | all | stub accepts; CLI tool call succeeds; `allow_once` granted; agent replies `pong-from-aria-stub` | PASS |
| negative-missing | `[]` | all | stub answers 401 at `initialize`; CLI call cannot succeed | PASS |
| negative-wrong | `[{name:"Authorization", value:"Bearer wrong-synthetic-token"}]` | all | stub answers 401 with a recorded mismatch at `initialize`; CLI call cannot succeed | PASS |
| negative-call-401 | `[]` | call-only | metadata served, CLI attempts `aria_ping`, stub answers 401 at `tools/call`; the CLI's tool call is recorded `failed` and its error quotes the 401 | PASS |

## 2. ACP contract facts this gate established (probe, unauthenticated)

The schema check of `session/new` runs **before** the auth check, so the accepted
`mcpServers` shape was probed without credentials (`podman run --rm -i`, 2026-09-18T01:14,
raw responses):

```text
headers omitted        -> {"code":-32602,"message":"Invalid params",...,"headers":{"_errors":["Invalid input: expected array, received undefined",...]}}
headers=[]             -> {"code":-32000,"message":"Authentication required: Authentication is required."}
headers=[{name,value}] -> {"code":-32000,"message":"Authentication required: Authentication is required."}   (accepted schema)
headers={Authorization:...} (object map) -> {"code":-32602,"message":"Invalid params",...,"headers":{"_errors":["Invalid input: expected array, received object",...]}}
```

**`headers` is a required array of `{name, value}` pairs** (an object map is invalid). The
Section 8 spike already verified the empty-array form; A3 verifies the non-empty form.

Second contract fact, observed in the sandbox: in this Linux build the ACP permission
request for an MCP tool announces the MCP tool name directly —

```json
{"toolCallId":"call_86b98dbff20d42098a0f6ed9","status":"pending","title":"aria_ping (aria-stub)",
 "kind":"other","rawInput":{},"_meta":{"qoder":{"toolName":"mcp__aria-stub__aria_ping"}},
 "optionKinds":["allow_always","allow_once","reject_once"]}
```

— whereas the Windows spike saw the wrapper `_meta.qoder.toolName = "mcp_call"` with
`rawInput.toolName = "mcp__aria-stub__aria_ping"`. The probe accepts both shapes and
identifies the call by the exact MCP tool name; B3a/C0.4 must match on the tool name, not
on a hardcoded wrapper.

Third fact, observed in both negative cases: when the MCP server answers 401, the CLI
exposes a synthesized tool `mcp__<server>__authenticate` instead of the server's tools
(agent text: "The only `aria-stub` MCP tool I can see is `mcp__aria-stub__authenticate`"),
i.e. the CLI classifies the server as unauthenticated. No OAuth flow was exercised.

## 3. Positive case — the round-trip with the header

Authorized stub traffic for the positive case (`A3-CASE-JSON` of the capture):

```json
"stub":{"posts":4,"authorized":4,"publicRequests":0,"unauthorized":0,"unauthorizedDetail":[],"toolCalls":1}
"permission": {"permissionRequests":1,"permissionGrants":1,"permissionCancels":0,"identifiedToolName":"mcp__aria-stub__aria_ping"}
"toolEvents":[{"type":"tool_call","tracked":true,"toolCallId":"call_101a69e","qoderTool":"mcp__aria-stub__aria_ping","title":"aria_ping (aria-stub MCP Server)","kind":"other","status":"pending","rawInputExcerpt":"{}"},
              {"type":"tool_call_update","tracked":true,"toolCallId":"call_101a69e","status":"completed","outputExcerpt":"pong-from-aria-stub"}]
"stopReason":"end_turn","answerExcerpt":"pong-from-aria-stub"
```

The stub accepted all four JSON-RPC posts (initialize, tools/list, notification,
tools/call), executed exactly one `aria_ping` call, and the agent's final answer is the
stub's output. The permission request was answered with the option whose `kind` is
`allow_once`; `allow_always` was offered and not selected.

## 4. Negative cases — the header is load-bearing

```json
missing: "stub":{"posts":1,"authorized":0,"publicRequests":0,"unauthorized":1,"unauthorizedDetail":["initialize:missing"],"toolCalls":0},"attemptedCall":false
wrong:   "stub":{"posts":1,"authorized":0,"publicRequests":0,"unauthorized":1,"unauthorizedDetail":["initialize:mismatch"],"toolCalls":0},"attemptedCall":false
```

In the `missing`/`wrong` cases the stub refused the very first JSON-RPC POST (MCP
`initialize`) with HTTP 401 and recorded the refusal as `missing` / `mismatch`
respectively. Because the MCP server never became usable, the CLI never obtained
`aria_ping` (`toolCalls: 0`, no `tools/call` on the stub) and the agent reports the tool as
unavailable — the call fails *because* the 401 was observed. The corresponding assertion in
the probe is `unauthorizedObserved && noSuccessfulCall && noStubTextInAnswer && (no attempt
|| the attempt failed)`; the `wrong` case additionally requires a recorded `mismatch`, so a
stub that merely checked header presence would fail this gate.

The fourth case closes the literal reading of the criterion — the CLI's **call itself**
fails on the 401, not just the session handshake:

```json
call-401: "stub":{"posts":4,"authorized":0,"publicRequests":3,"unauthorized":1,"unauthorizedDetail":["tools/call:missing"],"toolCalls":0}
          "attemptedCall":true,"cliToolFailureObserved":true,"permissionGrants":1,"identifiedToolName":"mcp__aria-stub__aria_ping"
          tool_event: {"type":"tool_call_update","tracked":true,"toolCallId":"call_7ba03a6","status":"failed",
                       "outputExcerpt":"Streamable HTTP error: Error POSTing to endpoint: {\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32001,\"message\":\"Unauthorized: missing or invalid Authorization header\"}}"}
          answerExcerpt: "Streamable HTTP error: Error POSTing to endpoint: {\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32001,\"message\":\"Unauthorized: missing or invalid Authorization header\"}}"
```

Here the stub served the three unauthenticated metadata posts (`publicRequests: 3` —
`initialize`, notification, `tools/list`), the CLI obtained the tool, the permission
request for the positively identified `aria_ping` call was granted `allow_once`, and the
actual `tools/call` POST was refused (`tools/call:missing`) — the CLI recorded the tool
call as `failed` and surfaced the stub's 401 in its own error text. `pingCalls: 0`: the
stub executed nothing.

## 5. JSON summary, permission accounting and recorded usage

```text
A3-SUMMARY-JSON: {"pingCalls":1,"permissionGrants":2,"authFailures":3,
 "cases":[{"case":"positive","pingCalls":1,"permissionGrants":1,"authFailures":0},
          {"case":"missing","pingCalls":0,"permissionGrants":0,"authFailures":1},
          {"case":"wrong","pingCalls":0,"permissionGrants":0,"authFailures":1},
          {"case":"call-401","pingCalls":0,"permissionGrants":1,"authFailures":1}],
 "usageFragments":["\"usage\":{\"inputTokens\":0","\"input_tokens\":0","\"output_tokens\":0", ...]}
```

(quoted verbatim from the capture; `usageFragments` repeats the zero-usage triple once per
case, 4 times.)

- `pingCalls = 1`: exactly one successful tool execution on the stub, in the positive case.
- `permissionGrants = 2`: exactly two `allow_once` selections, both for positively
  identified `aria_ping` calls (positive case and `call-401` case); `allow_always` was
  offered in both and never selected.
- `authFailures = 3`: one recorded 401 per negative case (1 missing at `initialize`,
  1 mismatch at `initialize`, 1 missing at `tools/call`).

**Recorded usage (not a guarantee):** the ACP transcript exposed only zero-valued counters
in this run — `"usage":{"inputTokens":0`, `"input_tokens":0`, `"output_tokens":0` — and no
`total_credits` / `total_cost_usd` / `credits` field. The model pin was `efficient`
(0.00x Credit per the plan's frozen CLI facts) and `session/set_model` returned `{}`
(accepted); whether the effective model is observable in ACP remains A5's gate. A zero or
absent accounting block must be treated as unavailable accounting, never as proof that the
run was free.

Session-time configuration observed in the same authenticated sessions
(`available_commands_update` notification, bounded sample):

```json
{"name":"aria-pinned:aria-pinned","description":"Aria Conductor pinned skill; proves --plugin-dir loads exactly the pinned bundle."}
```

The A2 pinned plugin bundle's skill is announced by the CLI **at session time** in every
case (`pluginMentions: 1` per case), which answers A2's concern 1 at the announcement
level. That the bundle's components *execute* (hooks/agents/skill invocation) was not
exercised and remains NOT VERIFIED.

## 6. Criterion table

| Criterion | Result | Evidence |
|---|---|---|
| Stub validates `Authorization: Bearer <synthetic>` on every JSON-RPC POST | PASS | `unauthorizedDetail: initialize:missing` / `initialize:mismatch` / `tools/call:missing`; 4/4 authorized posts in the positive case, 0/1 in `call-401` |
| Missing header → 401 and recorded | PASS | negative-missing case (`posts:1, unauthorized:1`), negative-call-401 case (`tools/call:missing`) |
| Wrong header → 401 and recorded | PASS | negative-wrong case (`initialize:mismatch`) |
| Positive: `session/new` with the header entry → tool call succeeds | PASS | `toolCalls: 1`, `status: completed`, answer `pong-from-aria-stub` |
| Positive: permission `allow_once` selected by `kind` | PASS | `permissionGrants: 1`, `identifiedToolName: mcp__aria-stub__aria_ping`, `allow_always` not selected |
| Negative: header omitted → CLI call fails, 401 observed | PASS | `toolCalls: 0`, tool unavailable to the agent, 401 recorded |
| Negative: wrong header → same, with value comparison | PASS | `mismatch` recorded, `toolCalls: 0` |
| Negative: the CLI's attempted call itself fails on the 401 | PASS | `call-401`: `attemptedCall: true`, `cliToolFailureObserved: true`, tool_call_update `status: failed`, error quotes code `-32001` / `Unauthorized` |
| Real authenticated session runs to completion | PASS | `sessionCreated: true`, `stopReason: end_turn` in all 4 cases |
| PAT never on argv / in files / in output | PASS | see §7 |
| Plugin bundle active at session time (optional) | PASS (announcement level) | `aria-pinned:aria-pinned` in `available_commands_update` |
| Reported usage/credit fields recorded | PASS | §5 (all zero; no credit field exposed) |
| Effective model observable in ACP | NOT VERIFIED (A5) | `session/set_model` accepted; no model field observed |
| OAuth flow behind `mcp__*__authenticate` | NOT VERIFIED | not exercised |

## 7. Credential hygiene (value-free checks)

- The PAT was read from the local file into the host environment in the same shell command
  that ran Maven; it was never a command-line argument.
- Inside the sandbox it existed only as `QODER_PERSONAL_ACCESS_TOKEN` in the container
  environment (injected by `createSandbox`); the manager logs only the variable count.
- The script wraps `console.log` in a redactor that replaces the token value (when present,
  ≥ 8 chars) with `[redacted]`, so even CLI stderr tails or agent text cannot leak it into
  the capture, the Failsafe report or this document.
- Leak checks (count only; ran on the final run's artifacts):

```bash
grep -c "$QODER_E2E_PAT" e2e/qoder/slice-a/03-mcp-auth.md                        # 0
grep -c "$QODER_E2E_PAT" e2e/qoder/slice-a/03-mcp-auth.mjs                       # 0
grep -c "$QODER_E2E_PAT" agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderMcpAuthE2ETest.java   # 0
grep -c "$QODER_E2E_PAT" agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderSandboxHarness.java  # 0
grep -c "$QODER_E2E_PAT" agent-control-tower/act-execution/target/qoder-a3-sandbox-output.txt                                        # 0
grep -c "$QODER_E2E_PAT" .superpowers/sdd/2026-09-17-qoder-cli-provider/task-A3-report.md                                             # 0
grep -c "$QODER_E2E_PAT" /tmp/a3-maven-final2.log                                # 0 (local temp log, not repo content)
```

All counts were 0 on the final run (counts only, the value is never printed).

The synthetic MCP token `test-worker-token` is a placeholder and may appear freely.

## 8. Limitations

1. execd output chunking joins adjacent lines (cosmetic); the decisive markers are intact
   and the Java test asserts the exit marker and result line, not the rendering.
2. In the `missing`/`wrong` cases the failure manifests at MCP session establishment (401
   on `initialize`), which is the behaviour of a header-validating server: the CLI never
   obtains the tool, so its call cannot succeed. The `call-401` case therefore isolates the
   call-level requirement directly (metadata served, header enforced only on `tools/call`):
   the CLI attempts the call, receives the 401 and records the call as `failed`.
3. The exit code of the `--acp` child process is not asserted (the CLI is the agent side);
   the gate asserts ACP responses, stub-side facts and the CLI's own event stream.
4. The stub is not the Aria MCP server, and the synthetic bearer is not Aria's MCP
   authentication; this gate proves the CLI's header path, not the platform's server.
5. Credit accounting: see §5 — reported counters were zero and no credit field was exposed;
   zero cost is not asserted.

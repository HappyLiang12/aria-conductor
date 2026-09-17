# Qoder CLI agent core: Phase 0 evidence

Date: 2026-09-17
Repository baseline: `c6d37f8`.
Status: **PARTIAL FEASIBILITY EVIDENCE; NOT AN E2E OR PHASE 0 PASS.**

Windows-host probes demonstrated PAT-backed headless output, ACP initialization and session
creation, a file-write permission request, and one HTTP MCP tool round-trip. Linux installation,
sandbox execution, hooks, cancellation, and multi-session isolation remain NOT VERIFIED.
Recommendation: retain the ACP-bridge direction, subject to those implementation entry gates.

## 1. Method and evidence limits

Observed environment: Windows 10, standalone `qodercli` 1.1.41, Node reporting v24.14.1 in the
probe output. `qoder` on PATH launched the IDE, not the agent CLI. The standalone binary was
`%USERPROFILE%\.qodersec\bin\qodercli.exe`.

All probes were throwaway experiments outside the repository. Excerpts below are selected fields
from captured tool output in this conversation, not complete raw transcripts; abbreviated IDs and
paths are marked explicitly. No screenshot, log-file, or uncommitted script path is cited as
repository evidence. Section 8 supplies a self-contained reproduction recipe; that consolidated
recipe was not itself run in this session.

No product code, platform approval UI, production bridge, or sandbox was tested. Advertising an
ACP capability is not evidence that the advertised operation works.

## 2. Headless authentication

The initial probe inherited the parent harness environment and failed with this captured error:

```text
sdk_invalid_args: Agent SDK entrypoint env is set but required flags are missing.
Expected --print --input-format stream-json --output-format stream-json,
got print=true, inputFormat=undefined, outputFormat=json
```

After removing the inherited SDK entrypoint/worker environment variables, the executed CLI
arguments were:

```sh
qodercli -p "Reply with exactly: pong" --output-format json --no-session-persistence
```

Without PAT, selected captured result fields were:

```json
{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login","terminal_reason":"completed"}
```

That unauthenticated probe captured exit code 1. With `QODER_PERSONAL_ACCESS_TOKEN` supplied to
the child environment, selected captured result fields were:

```json
{"type":"result","subtype":"success","duration_ms":14688,"duration_api_ms":14328,"is_error":false,"num_turns":1,"result":"pong","stop_reason":"end_turn","total_credits":1.380073762142857,"permission_denials":[]}
```

The authenticated command was piped without capturing the CLI's own exit status; exit code 0
is therefore **NOT VERIFIED**. Application-level success and the returned `pong` are observed.
The reported credit value is specific to this request, not a cost forecast.

The same response reported zero token counters despite a real response. Treat missing or zero
usage as unavailable accounting, not proof that inference was free. No billing API was verified.
The PAT value and its prefix are deliberately omitted; do not infer a token-format validator
from this single credential.

## 3. ACP handshake and session

The probe spawned `qodercli --acp`, wrote newline-delimited JSON-RPC to stdin, and parsed stdout
by line. Actual initialization request:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
```

Selected captured response fields:

```json
{"protocolVersion":1,"agentInfo":{"name":"qoder-cli","title":"Qoder CLI","version":"1.1.41"},"authMethods":[{"id":"qodercli-login","name":"Use qodercli login","description":"Use your existing qodercli login for this agent. If needed, sign in from qodercli first."}],"agentCapabilities":{"loadSession":true,"sessionCapabilities":{"additionalDirectories":{},"close":{},"delete":{},"fork":{},"list":{},"resume":{}},"promptCapabilities":{"image":true,"embeddedContext":true},"mcpCapabilities":{"http":true,"sse":true},"_meta":{"qoder":{"promptQueueing":true}}}}
```

`session/new` with `{cwd: <absolute temporary directory>, mcpServers: []}` failed without auth:

```json
{"code":-32000,"message":"Authentication required: Authentication is required."}
```

With PAT it returned a session ID and mode/model lists. Captured mode IDs were `default`,
`acceptEdits`, `auto`, `dontAsk`, `yolo`; the current mode was `default`. Model listings were
account/version-specific; availability and credit descriptions must not be hardcoded into Aria.
Only the default model was exercised; model switching was not.

A fixed-delay prototype sent a prompt before `session/new` returned and received invalid params
for a null session ID. The event-driven retry waited for the matching successful response and
completed. Startup must be response-driven, not based on sleeps.

Multi-session use of one process, resume/load/fork, prompt queueing, and SSE MCP transport were
advertised or suggested but **NOT VERIFIED**. `--acp` was hidden from help in this binary;
its long-term compatibility is not established.

## 4. File-write permission

The successful event-driven probe sent a `session/prompt` text block:
`Create a file named hello.txt containing exactly: hi`.

Captured event sequence, with request identifiers and absolute paths shortened:

```text
+20.4s session/update: tool_call
  status=pending, kind=edit, _meta.qoder.toolName=Write
  content: diff with oldText=null, newText="hi", target hello.txt
  rawInput: content="hi", file_path=<temporary workspace>/hello.txt
+20.7s session/request_permission
  title="Edit hello.txt", kind=edit, diff supplied
  options:
    {optionId:"proceed_always", name:"Allow for this session", kind:"allow_always"}
    {optionId:"proceed_once", name:"Allow", kind:"allow_once"}
    {optionId:"cancel", name:"Reject", kind:"reject_once"}
+21.9s current_mode_update: currentModeId=acceptEdits
+22.9s tool_call_update: status=completed, rawOutput="File created successfully at: <path>"
+25.8s session/prompt response: stopReason=end_turn
```

Important correction: this probe's loose option matching accidentally selected
`proceed_always`, not `proceed_once`. The observed mode change demonstrates why the production
bridge must select by the offered option's `kind` and must never fall back to the first option.
These three option IDs were observed in two requests; they are not guaranteed protocol constants.

The client did not advertise filesystem capabilities in this successful probe. The CLI reported
performing its own file write. No independent byte-for-byte content check was captured.
Deny, expiry, repeated allow-once writes, and cancellation were not tested.

## 5. MCP round-trip

A loopback HTTP MCP stub offered one harmless tool, `aria_ping`, returning
`pong-from-aria-stub`. The working entry in `session/new.mcpServers` was exactly:

```json
{"type":"http","name":"aria-stub","url":"http://127.0.0.1:19123/mcp","headers":[]}
```

Earlier requests missing `headers`, then `type`, returned invalid-params errors. An empty header
list was verified; a nonempty Authorization header and Aria's actual MCP authentication were not.

Prompt: `Call the tool named aria_ping from the aria-stub MCP server, then reply with its exact
output text and nothing else.` Selected captured events:

```text
+28.6s tool_call: mcp_get, input toolName=mcp__aria-stub__aria_ping
+29.1s tool_call_update: completed, tool schema returned
+32.2s tool_call: mcp_call, input toolName=mcp__aria-stub__aria_ping, arguments={}
+32.2s session/request_permission:
  title="Allow MCP tool aria-stub/aria_ping?"
  options=proceed_always,proceed_once,cancel
  probe selected proceed_once
+33.1s tool_call_update: completed, rawOutput="pong-from-aria-stub"
+36.8s session/prompt response: stopReason=end_turn
FINAL TEXT: pong-from-aria-stub
```

This establishes one real CLI-to-stub HTTP MCP call after a permission response. It does not
establish policy enforcement for all MCP tools, platform audit persistence, or self-approval
protection. A stub is not the Aria MCP server.

## 6. Configuration and hooks

Observed commands and results:

```text
qodercli --mcp-config <temporary JSON file> --strict-mcp-config mcp list
  No MCP servers configured.
qodercli mcp list                 # temporary cwd containing .mcp.json
  aria-conductor-stub: http://127.0.0.1:9/mcp (streamableHttp) - Disconnected
qodercli --config-dir <temporary config dir> mcp list
  aria-conductor-stub: http://127.0.0.1:9/mcp (streamableHttp) - Disconnected
```

The last invocation still had the project `.mcp.json` present with the same entry: independent
loading from the alternate config directory is **NOT VERIFIED**. The first command's result
does not prove `--mcp-config` is ignored by other CLI modes.

An ACP session announced 67 commands, including installed skills/plugins. Host customization
leaked into the experiment. A clean home/config, controlled project settings, and an explicit
plugin bundle require their own isolation test; `--config-dir` alone is not proven sufficient.

`qodercli hooks --help` exposed a migration subcommand. Binary string searches found names such
as `PreToolUse`, `PostToolUse`, `PermissionRequest`, `hookSpecificOutput`, and
`permissionDecision`. These are **static clues only**, not verification of supported hook schema,
allow/deny semantics, or execution under ACP. Hooks must not be called an established enforcement
boundary on this evidence.

## 7. Decisions and remaining gates

User-approved direction:
- Sandbox + ACP bridge, shared sandbox lifecycle with opencode, provider ID `qoder`.
- Per-tool HITL for both kanban-dispatched and ordinary runs.
- Auto-allow explicitly classified read-only platform MCP tools; gate write operations.
- Store/manage the runtime PAT through Aria, with an experience like LLM key configuration.
- Real local E2E; credential-dependent tests skip in CI with a stated reason.
- Keep opencode default until a separately approved replacement milestone.

NOT VERIFIED before implementation can claim readiness:
- Linux CLI distribution/install method and version pin; OpenSandbox image startup/networking.
- Config/plugin isolation, hooks under ACP, platform MCP auth/header injection.
- Allow-once without mode escalation, denial/no-side-effect, cancel during a pending permission.
- Multiple sessions/processes, restart recovery, scoped authorization against self-approval.
- Task deadline versus human wait, sandbox renewal, reconnect/replay, accounting fidelity.
- UI exposure of run-scoped approvals and persisted audit evidence on the actual platform.

No npm installation was attempted. A package name extracted from a binary is not proof of a
published Linux package. Distribution and license verification are explicit entry gates, not
an assumed `npm install` recipe.

## 8. Reproduction recipe (not executed verbatim)

This consolidated diagnostic starts its own harmless MCP stub on an ephemeral loopback port.
It writes no project files and never selects `allow_always`. Use a throwaway directory, the
verified standalone executable, and a PAT injected securely into the environment. Do not paste
credentials into this command or a tracked file. It can spend Qoder credits and still inherits
user customization; it is not the production isolation design.

Set `QODER_SPIKE_CLI` to the standalone executable and `QODER_SPIKE_CWD` to an existing absolute
temporary directory, then run from Git Bash:

```sh
node --input-type=module <<'JS'
import http from 'node:http';
import { spawn } from 'node:child_process';
import { isAbsolute } from 'node:path';
import { statSync } from 'node:fs';
const { QODER_SPIKE_CLI: cli, QODER_SPIKE_CWD: cwd } = process.env;
if (!cli || !cwd || !isAbsolute(cwd) || !statSync(cwd).isDirectory() || !process.env.QODER_PERSONAL_ACCESS_TOKEN) {
  throw new Error('Set executable, existing absolute temporary cwd, and PAT environment variables');
}
let pingCalls = 0;
let permissionGrants = 0;
let answer = '';
const stub = http.createServer((req, res) => {
  if (req.method !== 'POST') { res.writeHead(405).end(); return; }
  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    let m;
    try { m = JSON.parse(body); } catch { res.writeHead(400).end(); return; }
    if (m.id === undefined) { res.writeHead(202).end(); return; }
    if (m.method === 'tools/call' && m.params?.name === 'aria_ping') pingCalls++;
    const result = m.method === 'initialize'
      ? { protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'spike', version: '1' } }
      : m.method === 'tools/list'
      ? { tools: [{ name: 'aria_ping', description: 'Return a fixed diagnostic string', inputSchema: { type: 'object', properties: {} } }] }
      : m.method === 'tools/call' && m.params?.name === 'aria_ping'
      ? { content: [{ type: 'text', text: 'pong-from-aria-stub' }], isError: false }
      : null;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: m.id, ...(result ? { result } : { error: { code: -32601, message: 'Unsupported method' } }) }));
  });
});
await new Promise(resolve => stub.listen(0, '127.0.0.1', resolve));
const env = { ...process.env };
for (const key of ['QODER_AGENT_SDK_ENTRYPOINT', 'QODER_WORKER_RUNTIME_ASSET_ROOT', 'QODER_AGENT_SDK_VERSION', 'QODERCLI_RUNTIME_PACKAGING', 'QODER_SESSION_TYPE', 'QODER_WORKER_CWD', 'QODER_SDK_AUTH_PAYLOAD_FILE']) delete env[key];
const child = spawn(cli, ['--acp'], { cwd, env, stdio: ['pipe', 'pipe', 'pipe'] });
let buffer = '';
let finished = false;
const finish = code => {
  if (finished) return;
  finished = true;
  process.exitCode = code;
  clearTimeout(timer);
  child.kill();
  stub.closeAllConnections();
  stub.close();
};
const timer = setTimeout(() => finish(2), 180000);
const send = m => child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...m }) + '\n');
child.on('error', () => finish(1));
child.stdin.on('error', () => finish(1));
child.on('exit', code => { if (!finished) finish(code || 1); });
child.stderr.on('data', () => {});
child.stdout.setEncoding('utf8');
child.stdout.on('data', text => {
  buffer += text;
  let end;
  while ((end = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, end);
    buffer = buffer.slice(end + 1);
    let m;
    try { m = JSON.parse(line); } catch { continue; }
    if (m.error) { console.log('RPC error code', m.error.code); finish(1); return; }
    if (m.method && m.id !== undefined) {
      if (m.method !== 'session/request_permission') {
        send({ id: m.id, error: { code: -32601, message: 'Unsupported client method' } });
        continue;
      }
      const tool = m.params?.toolCall;
      const ownPing = tool?._meta?.qoder?.toolName === 'mcp_call'
        && tool?.rawInput?.toolName === 'mcp__aria-stub__aria_ping';
      const once = ownPing && m.params.options.find(o => o.kind === 'allow_once');
      if (once) permissionGrants++;
      console.log('Permission request', ownPing ? 'diagnostic ping' : 'unrecognized; cancelled');
      send({ id: m.id, result: { outcome: once ? { outcome: 'selected', optionId: once.optionId } : { outcome: 'cancelled' } } });
    } else if (m.id === 1) {
      console.log('Protocol', m.result.protocolVersion);
      send({ id: 2, method: 'session/new', params: { cwd, mcpServers: [{ type: 'http', name: 'aria-stub', url: `http://127.0.0.1:${stub.address().port}/mcp`, headers: [] }] } });
    } else if (m.id === 2) {
      send({ id: 3, method: 'session/prompt', params: { sessionId: m.result.sessionId, prompt: [{ type: 'text', text: 'Call aria_ping on aria-stub once; reply with only its exact output. Do not use filesystem or shell tools.' }] } });
    } else if (m.id === 3) {
      const verified = m.result.stopReason === 'end_turn' && pingCalls === 1
        && permissionGrants === 1 && answer.trim() === 'pong-from-aria-stub';
      console.log('MCP round-trip', verified ? 'verified' : 'NOT VERIFIED', { pingCalls, permissionGrants });
      finish(verified ? 0 : 3);
    } else if (m.params?.update?.sessionUpdate === 'agent_message_chunk') {
      answer += m.params.update.content?.text || '';
    } else if (m.params?.update?.sessionUpdate === 'tool_call_update') {
      console.log('Tool status', m.params.update.status);
    }
  }
});
send({ id: 1, method: 'initialize', params: { protocolVersion: 1 } });
JS
```

The exact metadata on MCP permission requests must be inspected in the next probe; this recipe
cancels anything it cannot positively identify. A cancelled diagnostic request is inconclusive,
not a successful MCP round-trip. Do not broaden it to approve arbitrary tools automatically.

## 9. Credential handling

The supplied PAT appeared in this conversation and was saved outside the repository for the
approved probes. Rotate it before further use. Its temporary plaintext storage was a spike-only
arrangement, not the product credential design. No token value is included in this report.

## 10. Verification status

Verified: PAT-backed response, ACP initialization/session creation, permission payloads on the
observed write/MCP requests, and one successful stub MCP result (Sections 2-5).

Unverified: all gates in Section 7, the consolidated reproduction recipe, authentication with
nonempty MCP headers, and isolation from host/project customization.

Remaining risks: hidden-flag/version drift, mode escalation through persistent grants,
incomplete usage reporting, and treating audit logging as authorization. The companion design
must address these before claiming a governed provider is ready.

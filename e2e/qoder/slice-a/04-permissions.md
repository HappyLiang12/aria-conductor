# Task A4 evidence — permission semantics: allow-once, deny, cancel (Slice A gate)

Inside a **real OpenSandbox sandbox** created from the pinned image
`aria-conductor/qoder-sandbox:0.1`, a **real authenticated Qoder session** (PAT injected into
the sandbox environment only, model `efficient`) runs three cases in three fresh ACP
sessions:

1. **allow-once** — the agent requests permission to write a file; the client selects the
   offered option whose `kind` is `allow_once`; the file is created; a second write in the
   same session **asks again** (the grant is one-shot).
2. **deny** — the same write request is answered with the option whose `kind` is
   `reject_once`; the file is **not** created and the turn still completes.
3. **cancel-while-pending** — the permission request is left unanswered and the turn is
   aborted with `session/cancel`; the file is not created and no orphan process remains.

This gate pins the semantics that frozen contracts **C0.3** (ACP sequence) and **C0.4**
(option selection) depend on: the exact option `kind`/`optionId` vocabulary, the reply
shape, one-shot allow semantics, and that **`allow_always` is never selected** and **no
`acceptEdits` mode change ever occurs** (escalation is not a side effect of the flow).

This document was regenerated in the A4 **fix round 1** (probe changes: derived
`allowAlwaysSelections` counter, explicit cancel-timing deltas, unverified-shape
annotations); every raw block below is from the re-run that followed those changes and
supersedes the draft's numbers.

## Provenance (this evidence)

| Item | Value |
|---|---|
| Test | `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderPermissionsE2ETest.java` (Failsafe, env-gated: `-Dqoder.e2e.enabled=true`; skips with the credential env var named when `QODER_E2E_PAT` is absent) |
| Driver | `QoderSandboxHarness#boot(serverUrl, image, sandboxEnv)` — creates the sandbox via `OpenCodeSandboxManager#createSandbox(UUID, String, Map)`, waits for execd, uploads the staging dir to `/workspace`, runs the probe, kills the sandbox in `close()` |
| Probe script | `e2e/qoder/slice-a/04-permissions.mjs` (uploaded to `/workspace/04-permissions.mjs`, executed by the in-image Node 22; spawns `qodercli -m efficient --acp` itself once per case) |
| Image | `aria-conductor/qoder-sandbox:0.1` (A1 pin; see `01-boot.md`) |
| CLI | Qoder CLI 1.1.41 (Linux x64 artifact, sha256-pinned in `agent-control-tower/qoder-sandbox/Dockerfile`) |
| Model pin | `-m efficient` on every ACP spawn **and** `session/set_model {sessionId, modelId:"efficient"}`; recorded `modelSet:"accepted"` in all three cases; the harness fails closed off `{efficient, lite}` unless `QODER_E2E_ALLOW_PAID=1` |
| Credential path | host env `QODER_E2E_PAT` → sandbox container env `QODER_PERSONAL_ACCESS_TOKEN` (env map only; manager logs the count only). The probe redacts the token value from all console output |
| Sandbox | OpenSandbox sandbox `63606461-b069-4e5a-9b40-6685c3abe5ef` for agent `b4ff6ab3-f132-4963-b9cd-e7bed5e78157` (created 02:03:11.547, script uploaded 02:03:13.289, `Terminating sandbox:` 02:04:18.532, killed 02:04:19.736 — from the run log pasted below) |
| Run | 2026-09-18, `BUILD SUCCESS`, `Total time: 02:31 min`, finished `2026-09-18T02:04:19+08:00`; unit lane `Tests run: 864, Failures: 0, Errors: 0, Skipped: 0`; A4 test `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `Time elapsed: 71.98 s` |
| Probe bounds | `PERMISSION_WAIT_MS=75000`, `TURN_WAIT_MS=90000`, `CANCEL_EFFECT_WAIT_MS=30000`, `CASE_TIMEOUT_MS=240000`; fixture content is exactly `hi`, write targets live under `/tmp/a4-perm/<case>/` **inside the sandbox**, never in the repo |

**Provenance is the command plus the pasted raw output below, not `target/**` files** —
the raw capture and the Failsafe report live under `act-execution/target/**`, which is
uncommitted, recreated by every run and deleted by the next `mvn clean`; the decisive
content is therefore pasted verbatim in this document (see also the labelled references to
the transient capture in §1–§3).

Regeneration command (single command; run from the repo root; local only — the PAT is read
into the host environment inside the same shell invocation, never echoed, never on the
command line, never written to a file):

```bash
export QODER_E2E_PAT="$(cat /c/Users/User/.qoder/qoder-pat.txt)" && cd agent-control-tower && \
mvn clean verify -pl act-execution -Dit.test=QoderPermissionsE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
```

`clean` is required: `jacoco:check` does not honour `-Djacoco.skip=true`, so a stale
`act-execution/target/jacoco.exec` fails the build before Failsafe runs. Prerequisite: the
local OpenSandbox server (`podman compose up -d opensandbox-server` from the repo root;
health at `http://localhost:8090/health`).

### Maven console of the green run (verbatim, elided middle marked)

`[INFO] Results:` / `Tests run: 864` is the unit (Surefire) lane, then the selected A4
Failsafe test, then the sandbox boot lines, then — after the elision — the sandbox teardown,
the A4 `Tests run: 1` line and the build tail. Every quoted console line below is byte-exact;
every omitted gap is marked `[...elided ...]` (nothing is silently dropped):

```text
[INFO] Results:
[INFO] Tests run: 864, Failures: 0, Errors: 0, Skipped: 0
[...elided: the Surefire summary's `[INFO] ` blank lines and the jacoco/jar/failsafe
 section headers ...]
[INFO] Running io.aria.conductor.execution.qoder.QoderPermissionsE2ETest
02:03:08.566 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Injecting 1 env var(s) into sandbox for agent b4ff6ab3-f132-4963-b9cd-e7bed5e78157
[...elided: the `Starting create sandbox` / `Creating sandbox with startup source` lines ...]
02:03:11.547 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully created sandbox: 63606461-b069-4e5a-9b40-6685c3abe5ef
[...elided: one transient execd-readiness retry — the harness's bounded readiness poll hit a
 connection refusal (one ERROR + stack trace, `127.0.0.1:47911`) and retried; the run
 stayed green ...]
02:03:13.289 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Uploaded 1 file(s) from workspace C:\Users\User\AppData\Local\Temp\a4-permissions-staging-4345711364072995202 into sandbox for agent b4ff6ab3-f132-4963-b9cd-e7bed5e78157
[...elided: the probe's stdout (one folded line, 38,562 chars — it contains the four
 [A4] PASS lines, the CANCEL-METHOD-DECISION line, the 4 A4-PERMISSION-JSON, 3
 A4-CASE-JSON and 3 A4-EVENT-LOG-JSON records, and the A4-SUMMARY-JSON record, all
 quoted verbatim in the sections below) ...]
[A4] un-folded sandbox output written to D:\project\aria-conductor\agent-control-tower\act-execution\target\qoder-a4-sandbox-output.txt
02:04:18.532 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Terminating sandbox: 63606461-b069-4e5a-9b40-6685c3abe5ef
02:04:19.736 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully terminated sandbox: 63606461-b069-4e5a-9b40-6685c3abe5ef
02:04:19.736 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox 63606461-b069-4e5a-9b40-6685c3abe5ef killed
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 71.98 s -- in io.aria.conductor.execution.qoder.QoderPermissionsE2ETest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] 
[INFO] --- failsafe:3.5.3:verify (default) @ act-execution ---
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:31 min
[INFO] Finished at: 2026-09-18T02:04:19+08:00
[INFO] ------------------------------------------------------------------------

```

The `target/**` path named in the first elided-tail line is where the test writes the
capture; it is uncommitted and recreated by each run — the provenance is this command and
the pasted output, not the path.

Marker counts, re-runnable on the regenerated capture (the capture is a single folded line,
so `grep -o … | wc -l` counts occurrences, not lines):

```bash
cd agent-control-tower/act-execution
grep -o '\[A4\] PASS' target/qoder-a4-sandbox-output.txt | wc -l            # 4
grep -o '\[A4\] FAIL' target/qoder-a4-sandbox-output.txt | wc -l            # 0
grep -o 'A4-PERMISSION-JSON' target/qoder-a4-sandbox-output.txt | wc -l     # 4
grep -o 'A4-CASE-JSON' target/qoder-a4-sandbox-output.txt | wc -l           # 3
grep -o 'A4-EVENT-LOG-JSON' target/qoder-a4-sandbox-output.txt | wc -l      # 3
grep -o 'A4-PS-JSON' target/qoder-a4-sandbox-output.txt | wc -l             # 3
grep -o 'CANCEL-METHOD-DECISION:' target/qoder-a4-sandbox-output.txt | wc -l # 1
grep -o 'A4-SUMMARY-JSON' target/qoder-a4-sandbox-output.txt | wc -l        # 1
grep -o 'A4-PERMISSIONS-RESULT: PASS' target/qoder-a4-sandbox-output.txt | wc -l # 1
grep -o 'A4-PERMISSION-UNIDENTIFIED' target/qoder-a4-sandbox-output.txt | wc -l # 0
```

## Raw verdict lines (quoted verbatim from the capture of this green run)

The execd rendering folded **everything into one line** in this run (38,562 characters,
38,564 bytes, no newline anywhere in the file). The line breaks inside the blocks below are
presentation only; each block is an exact substring of that one line. `A4_SCRIPT_EXIT=0` is
echoed by the harness command line that ran the script; the script's exit code equals its
failure count.

Verdict prefix (model pin, bounds, fixtures, the three `/proc`-scan records and the stable
decision line), then the four PASS lines:

```text
[A4] model pin: QODER_E2E_MODEL=efficient (spawn -m efficient + session/set_model)[A4] bounds: PERMISSION_WAIT_MS=75000 TURN_WAIT_MS=90000 CANCEL_EFFECT_WAIT_MS=30000 CASE_TIMEOUT_MS=240000[A4] fixtures: file content="hi"; write targets live under /tmp/a4-perm/ (sandbox /tmp, never the repo)/bin/sh: 1: ps: not foundA4-PS-JSON: {"case":"allow-once","method":"/proc scan (ps unavailable)","orphanCount":0,"qodercliProcesses":[],"snapshotError":"Command failed: ps -eo pid,ppid,stat,args\n/bin/sh: 1: ps: not found\n"}/bin/sh: 1: ps: not foundA4-PS-JSON: {"case":"deny","method":"/proc scan (ps unavailable)","orphanCount":0,"qodercliProcesses":[],"snapshotError":"Command failed: ps -eo pid,ppid,stat,args\n/bin/sh: 1: ps: not found\n"}CANCEL-METHOD-DECISION: session/cancel exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted); request-form={"errorCode":-32601,"message":"\"Method not found\": session/cancel"}; notification-form=sent (no id); turn-abort=prompt-response; fallback=none/bin/sh: 1: ps: not foundA4-PS-JSON: {"case":"cancel","method":"/proc scan (ps unavailable)","orphanCount":0,"qodercliProcesses":[],"snapshotError":"Command failed: ps -eo pid,ppid,stat,args\n/bin/sh: 1: ps: not found\n"}[A4] PASS: allow-once: write -> allow_once -> file created, second write asks again turn1[requests=1 identified=1 allowOnce=1 stop=end_turn file=true/true] turn2[requests=1 identified=1 allowOnce=1 stop=end_turn file=true/true] secondAskedAgain=true note=all turns completed[A4] PASS: deny: reject_once/cancelled -> file absent requests=1 identified=1 rejectSelections=1 cancelledReplies=0 stop=end_turn fileExists=false note=all turns completed[A4] PASS: cancel while pending: session/cancel probe (or terminate + no orphan) pendingRequest=0 replied=false requestForm=method-not-found notificationForm=sent (no id) abortSignals=prompt-response verdict=exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted) orphans=0 fileExists=false note=cancel probe concluded: exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted)[A4] PASS: no escalation: allow_always never selected; no acceptEdits mode change allowAlwaysOffered=4 allowAlwaysSelected=0 acceptEditsModeChanges=0 modeUpdates=0/0/0

```

Summary record and script exit (end of the capture):

```text
A4-SUMMARY-JSON: {"cases":[{"case":"allow-once","mode":"allow-once","sessionCreated":true,"modelSet":"accepted","permissionRequests":2,"grantsAllowOnce":2,"rejectSelections":0,"cancelledReplies":0,"unidentifiedReplies":0,"modeUpdates":[],"acceptEditsModeChanges":0,"fileChecks":[{"path":"/tmp/a4-perm/allow-once/first.txt","exists":true,"contentOk":true},{"path":"/tmp/a4-perm/allow-once/second.txt","exists":true,"contentOk":true}],"orphans":0,"stopReasons":["end_turn","end_turn"]},{"case":"deny","mode":"deny","sessionCreated":true,"modelSet":"accepted","permissionRequests":1,"grantsAllowOnce":0,"rejectSelections":1,"cancelledReplies":0,"unidentifiedReplies":0,"modeUpdates":[],"acceptEditsModeChanges":0,"fileChecks":[{"path":"/tmp/a4-perm/deny/denied.txt","exists":false,"contentOk":false}],"orphans":0,"stopReasons":["end_turn"]},{"case":"cancel","mode":"cancel","sessionCreated":true,"modelSet":"accepted","permissionRequests":1,"grantsAllowOnce":0,"rejectSelections":0,"cancelledReplies":0,"unidentifiedReplies":0,"modeUpdates":[],"acceptEditsModeChanges":0,"fileChecks":[{"path":"/tmp/a4-perm/cancel/pending.txt","exists":false,"contentOk":false}],"orphans":0,"stopReasons":["cancelled"]}],"totals":{"permissionRequests":4,"grantsAllowOnce":2,"rejectSelections":1,"cancelledReplies":0,"unidentifiedReplies":0,"orphans":0},"hardConstraints":{"allowAlwaysOffered":4,"allowAlwaysSelections":0,"acceptEditsModeChanges":0},"optionKindsObserved":["allow_always","allow_once","reject_once"],"optionIdsObserved":["proceed_always","proceed_once","cancel"],"cancelMethodDecision":"session/cancel exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted); fallback=none","usageFragments":["\"usage\":{\"inputTokens\":0","\"input_tokens\":0","\"output_tokens\":0","\"usage\":{\"inputTokens\":0","\"input_tokens\":0","\"output_tokens\":0","\"usage\":{\"inputTokens\":0","\"input_tokens\":0","\"output_tokens\":0"],"modelPin":"efficient"}A4-PERMISSIONS-RESULT: PASSA4_SCRIPT_EXIT=0
```

## 1. Case `allow-once` — one-shot grant, and the second write asks again

Each turn prompts the CLI to write a new file with the Write tool. Turn 1 targets
`/tmp/a4-perm/allow-once/first.txt`, turn 2 targets `…/second.txt`. Both permission
requests were identified as the expected write (by `_meta.qoder.toolName = "Write"` and
`rawInput.file_path` equal to the expected target) and answered with the option whose
`kind` is `allow_once`. Raw permission records (verbatim substrings of the capture, one
record each):

```text
A4-PERMISSION-JSON: {"case":"allow-once","seq":1,"requestId":"0","toolCallId":"call_73c7c12","title":"null","qoderToolName":"Write","toolKind":"edit","status":"pending","filePath":"/tmp/a4-perm/allow-once/first.txt","resolvedFilePath":"/tmp/a4-perm/allow-once/first.txt","expectedTarget":"/tmp/a4-perm/allow-once/first.txt","identifiedBy":"file_path","identified":true,"offeredOptionIds":["proceed_always","proceed_once","cancel"],"offeredKinds":["allow_always","allow_once","reject_once"],"options":[{"optionId":"proceed_always","name":"Allow for this session","kind":"allow_always"},{"optionId":"proceed_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Reject","kind":"reject_once"}],"allowAlwaysOffered":true,"decision":"allow_once:proceed_once","reply":{"outcome":{"outcome":"selected","optionId":"proceed_once"}},"replied":true,"t":22054}
A4-PERMISSION-JSON: {"case":"allow-once","seq":2,"requestId":"1","toolCallId":"call_f790423","title":"null","qoderToolName":"Write","toolKind":"edit","status":"pending","filePath":"/tmp/a4-perm/allow-once/second.txt","resolvedFilePath":"/tmp/a4-perm/allow-once/second.txt","expectedTarget":"/tmp/a4-perm/allow-once/second.txt","identifiedBy":"file_path","identified":true,"offeredOptionIds":["proceed_always","proceed_once","cancel"],"offeredKinds":["allow_always","allow_once","reject_once"],"options":[{"optionId":"proceed_always","name":"Allow for this session","kind":"allow_always"},{"optionId":"proceed_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Reject","kind":"reject_once"}],"allowAlwaysOffered":true,"decision":"allow_once:proceed_once","reply":{"outcome":{"outcome":"selected","optionId":"proceed_once"}},"replied":true,"t":30335}
```

Turn and file outcome (verbatim excerpts from the `A4-CASE-JSON` of the case):

```text
"turns":[{"promptIndex":0,"stopReason":"end_turn","permissionRequests":1,"identifiedWrites":1,"allowOnceSelections":1,"rejectSelections":0,"answerExcerpt":"Let me verify the file contents are exactly as requested.\n\nCreated `/tmp/a4-perm/allow-once/first.txt` containing exactly \"hi\".","promptResponseExcerpt":"{\"stopReason\":\"end_turn\",\"userMessageId\":\"78b81568-5534-4878-84e2-34149b7b8100\",\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0},\"_meta\":{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}}"},{"promptIndex":1,"stopReason":"end_turn","permissionRequests":1,"identifiedWrites":1,"allowOnceSelections":1,"rejectSelections":0,"answerExcerpt":"Created `/tmp/a4-perm/allow-once/second.txt` containing exactly \"hi\".","promptResponseExcerpt":"{\"stopReason\":\"end_turn\",\"userMessageId\":\"31e3b2ec-5b52-4d45-a9d9-f4f81d8d06b6\",\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0},\"_meta\":{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}}"}]
"fileChecks":[{"path":"/tmp/a4-perm/allow-once/first.txt","expectedContent":"hi","exists":true,"contentExcerpt":"hi","contentOk":true},{"path":"/tmp/a4-perm/allow-once/second.txt","expectedContent":"hi","exists":true,"contentExcerpt":"hi","contentOk":true}]
"totalEvents":103,"eventsTruncated":0
```

Tool events prove the files were written by the Write tool after the grant, not by shell
commands (the agent also issued a Read between the two writes — visible here and not
suppressed):

```text
"toolEvents":[{"t":21888,"type":"tool_call","toolCallId":"call_73c7c12","qoderTool":"Write","kind":"edit","status":"pending","title":"Write /tmp/a4-perm/allow-once/first.txt","rawInputExcerpt":"{\"content\":\"hi\",\"file_path\":\"/tmp/a4-perm/allow-once/first.txt\"}","outputExcerpt":null},{"t":22438,"type":"tool_call_update","toolCallId":"call_73c7c12","qoderTool":null,"kind":null,"status":"completed","title":null,"rawInputExcerpt":null,"outputExcerpt":"File created successfully at: /tmp/a4-perm/allow-once/first.txt"},{"t":25296,"type":"tool_call","toolCallId":"call_31077f6","qoderTool":"Read","kind":"read","status":"pending","title":"Read /tmp/a4-perm/allow-once/first.txt","rawInputExcerpt":"{\"file_path\":\"/tmp/a4-perm/allow-once/first.txt\"}","outputExcerpt":null},{"t":25574,"type":"tool_call_update","toolCallId":"call_31077f6","qoderTool":null,"kind":null,"status":"completed","title":null,"rawInputExcerpt":null,"outputExcerpt":"1\thi"},{"t":30322,"type":"tool_call","toolCallId":"call_f790423","qoderTool":"Write","kind":"edit","status":"pending","title":"Write /tmp/a4-perm/allow-once/second.txt","rawInputExcerpt":"{\"content\":\"hi\",\"file_path\":\"/tmp/a4-perm/allow-once/second.txt\"}","outputExcerpt":null},{"t":30672,"type":"tool_call_update","toolCallId":"call_f790423","qoderTool":null,"kind":null,"status":"completed","title":null,"rawInputExcerpt":null,"outputExcerpt":"File created successfully at: /tmp/a4-perm/allow-once/second.txt"}]
```

Permission-relevant event-log slice (shortened request ids; the full log is in the
transient capture `act-execution/target/qoder-a4-sandbox-output.txt`, which `mvn clean`
deletes — that is why the decisive blocks are pasted here rather than cited by path):

```text
{"t":9018,"dir":"out","tag":"rpc","id":"10","method":"session/prompt","promptIndex":0,"target":"/tmp/a4-perm/allow-once/first.txt"}
{"t":21888,"dir":"in","tag":"update","method":"session/update","type":"tool_call","toolCallId":"call_73c7c12","status":"pending","tool":"Write","kind":"edit"}
{"t":22054,"dir":"in","tag":"permission","id":"0","method":"session/request_permission","seq":1,"toolCallId":"call_73c7c12","title":"null","kind":"edit","tool":"Write","optionKinds":["allow_always","allow_once","reject_once"],"identified":true,"identifiedBy":"file_path"}
{"t":22055,"dir":"out","tag":"permission-reply","id":"0","seq":1,"reply":"{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"proceed_once\"}}"}
{"t":22438,"dir":"in","tag":"update","method":"session/update","type":"tool_call_update","toolCallId":"call_73c7c12","status":"completed","tool":null,"kind":null}
{"t":25296,"dir":"in","tag":"update","method":"session/update","type":"tool_call","toolCallId":"call_31077f6","status":"pending","tool":"Read","kind":"read"}
{"t":25574,"dir":"in","tag":"update","method":"session/update","type":"tool_call_update","toolCallId":"call_31077f6","status":"completed","tool":null,"kind":null}
{"t":28346,"dir":"in","tag":"rpc-response","id":"10","promptIndex":0,"stopReason":"end_turn"}
{"t":28346,"dir":"out","tag":"rpc","id":"11","method":"session/prompt","promptIndex":1,"target":"/tmp/a4-perm/allow-once/second.txt"}
{"t":30322,"dir":"in","tag":"update","method":"session/update","type":"tool_call","toolCallId":"call_f790423","status":"pending","tool":"Write","kind":"edit"}
{"t":30335,"dir":"in","tag":"permission","id":"1","method":"session/request_permission","seq":2,"toolCallId":"call_f790423","title":"null","kind":"edit","tool":"Write","optionKinds":["allow_always","allow_once","reject_once"],"identified":true,"identifiedBy":"file_path"}
{"t":30335,"dir":"out","tag":"permission-reply","id":"1","seq":2,"reply":"{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"proceed_once\"}}"}
{"t":30672,"dir":"in","tag":"update","method":"session/update","type":"tool_call_update","toolCallId":"call_f790423","status":"completed","tool":null,"kind":null}
{"t":32954,"dir":"in","tag":"rpc-response","id":"11","promptIndex":1,"stopReason":"end_turn"}
```

**Key semantics proven:** even though the CLI offers "Allow for this session"
(`allow_always` / `proceed_always`) on every request, selecting `allow_once` grants exactly
one write; the second write raises a **new** `session/request_permission` (request id `1`
in the same session) that must be answered again — `secondAskedAgain=true`.

## 2. Case `deny` — `reject_once` leaves the file absent and the turn completes

```text
A4-PERMISSION-JSON: {"case":"deny","seq":1,"requestId":"0","toolCallId":"call_506b138","title":"null","qoderToolName":"Write","toolKind":"edit","status":"pending","filePath":"/tmp/a4-perm/deny/denied.txt","resolvedFilePath":"/tmp/a4-perm/deny/denied.txt","expectedTarget":"/tmp/a4-perm/deny/denied.txt","identifiedBy":"file_path","identified":true,"offeredOptionIds":["proceed_always","proceed_once","cancel"],"offeredKinds":["allow_always","allow_once","reject_once"],"options":[{"optionId":"proceed_always","name":"Allow for this session","kind":"allow_always"},{"optionId":"proceed_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Reject","kind":"reject_once"}],"allowAlwaysOffered":true,"decision":"reject_once:cancel","reply":{"outcome":{"outcome":"selected","optionId":"cancel"}},"replied":true,"t":7642}
```

The write was rejected with the offered option whose `kind` is `reject_once`
(`optionId: "cancel"`, name `"Reject"` — note: a *selected* outcome, not the
`cancelled` outcome). The CLI marked the tool call failed and the agent reported the
denial; the turn still completed and the file does not exist:

```text
"turns":[{"promptIndex":0,"stopReason":"end_turn","permissionRequests":1,"identifiedWrites":1,"allowOnceSelections":0,"rejectSelections":1,"answerExcerpt":"The write was denied by your permission settings — the path `/tmp/a4-perm/deny/` appears to be configured to block writes. You'd need to adjust your permission settings to allow writes to this directo","promptResponseExcerpt":"{\"stopReason\":\"end_turn\",\"userMessageId\":\"55ac4651-6a69-43a9-b3fb-586277dbd145\",\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0},\"_meta\":{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}}"}]
"toolEvents":[{"t":7545,"type":"tool_call","toolCallId":"call_506b138","qoderTool":"Write","kind":"edit","status":"pending","title":"Write /tmp/a4-perm/deny/denied.txt","rawInputExcerpt":"{\"content\":\"hi\",\"file_path\":\"/tmp/a4-perm/deny/denied.txt\"}","outputExcerpt":null},{"t":8080,"type":"tool_call_update","toolCallId":"call_506b138","qoderTool":null,"kind":null,"status":"failed","title":null,"rawInputExcerpt":null,"outputExcerpt":"Error: Allow writing to /tmp/a4-perm/deny/denied.txt?"}]
"fileChecks":[{"path":"/tmp/a4-perm/deny/denied.txt","expectedContent":"hi","exists":false,"contentExcerpt":"null","contentOk":false}]
```

Event-log slice (same transient-capture caveat as §1):

```text
{"t":4528,"dir":"out","tag":"rpc","id":"10","method":"session/prompt","promptIndex":0,"target":"/tmp/a4-perm/deny/denied.txt"}
{"t":7545,"dir":"in","tag":"update","method":"session/update","type":"tool_call","toolCallId":"call_506b138","status":"pending","tool":"Write","kind":"edit"}
{"t":7642,"dir":"in","tag":"permission","id":"0","method":"session/request_permission","seq":1,"toolCallId":"call_506b138","title":"null","kind":"edit","tool":"Write","optionKinds":["allow_always","allow_once","reject_once"],"identified":true,"identifiedBy":"file_path"}
{"t":7642,"dir":"out","tag":"permission-reply","id":"0","seq":1,"reply":"{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"cancel\"}}"}
{"t":8080,"dir":"in","tag":"update","method":"session/update","type":"tool_call_update","toolCallId":"call_506b138","status":"failed","tool":null,"kind":null}
{"t":10519,"dir":"in","tag":"rpc-response","id":"10","promptIndex":0,"stopReason":"end_turn"}
```

## 3. Case `cancel` — cancel while a permission request is pending

Probe design: prompt a write to `/tmp/a4-perm/cancel/pending.txt`, and once
`session/request_permission` arrives, **do not reply**. Instead probe `session/cancel`:
first in request form (with a client id), then — when that fails — as a JSON-RPC
notification (no id). A bounded effect window (`CANCEL_EFFECT_WAIT_MS=30000`) watches for
the pending turn to abort; the file must not exist afterwards.

Raw `cancelProbe` record (verbatim; `notificationSentAt` / `notificationToAbortMs` are
recorded by the current probe so the two timing deltas are separately observable):

```json
"cancelProbe":{"startedAt":10190,"pendingPermissionSeq":1,"pendingPermissionRequestId":"0","requestForm":{"errorCode":-32601,"message":"\"Method not found\": session/cancel"},"requestFormVerdict":"method-not-found","notificationForm":"sent (no id)","notificationSentAt":10206,"abortSignals":[{"signal":"prompt-response","stopReason":"cancelled","t":10686}],"effectWaitMs":496,"notificationToAbortMs":480,"verdict":"exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted)","concludedOutcome":"prompt-response","fallback":"none"}
```

The pending permission request itself, never answered (verbatim record):
 
```text
A4-PERMISSION-JSON: {"case":"cancel","seq":1,"requestId":"0","toolCallId":"call_ebfdfa7","title":"null","qoderToolName":"Write","toolKind":"edit","status":"pending","filePath":"/tmp/a4-perm/cancel/pending.txt","resolvedFilePath":"/tmp/a4-perm/cancel/pending.txt","expectedTarget":"/tmp/a4-perm/cancel/pending.txt","identifiedBy":"file_path","identified":true,"offeredOptionIds":["proceed_always","proceed_once","cancel"],"offeredKinds":["allow_always","allow_once","reject_once"],"options":[{"optionId":"proceed_always","name":"Allow for this session","kind":"allow_always"},{"optionId":"proceed_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Reject","kind":"reject_once"}],"allowAlwaysOffered":true,"decision":null,"reply":null,"replied":false,"t":10189}
```

Timing of this run, with the definitions the probe implements:

- **`notificationToAbortMs: 480`** — from the notification send (`notificationSentAt`,
  case-relative `10206`) to the first observed abort signal (`abortSignals[0].t: 10686`,
  the prompt response carrying `stopReason:"cancelled"`): the notification was accepted
  and the pending turn aborted **480 ms after it was sent**.
- **`effectWaitMs: 496`** — from the probe start (`startedAt: 10190`, i.e. when the pending
  permission request arrived and the probe began) to the probe's conclusion
  (`10686 − 10190`). It **includes the failed request-form round trip** and is *not* the
  notification → abort delta.

(An earlier draft wrote "321 ms after the notification": that figure was measured from the
probe start, i.e. it was an `effectWaitMs`-style delta, not the notification → abort delta.
This document states the two measured deltas explicitly: 480 ms
notification → abort, 496 ms probe start → abort conclusion.)

The CLI's own stderr tail in the capture confirms it *handled* the request-form cancel as
an unknown method (the session id is a per-session identifier, not a credential):

```text
Error handling request {
  jsonrpc: '2.0',
  id: 'a4-cancel-probe',
  method: 'session/cancel',
  params: { sessionId: 'd21a83c0-a9f5-47ca-b635-5ac154ee9f25' }
} {
  code: -32601,
  message: '"Method not found": session/cancel',
  data: { method: 'session/cancel' }
}
```

Event-log slice for the cancel case (shortened request ids; the outbound cancel is recorded
with the shortened id `a4-cancel-pr` for the request form and no id for the notification;
the prompt response appears here at `t:10685` and the probe stamped its abort signal 1 ms
later at `10686` while handling that same response — same event):

```text
{"t":4313,"dir":"out","tag":"rpc","id":"10","method":"session/prompt","promptIndex":0,"target":"/tmp/a4-perm/cancel/pending.txt"}
{"t":10054,"dir":"in","tag":"update","method":"session/update","type":"tool_call","toolCallId":"call_ebfdfa7","status":"pending","tool":"Write","kind":"edit"}
{"t":10189,"dir":"in","tag":"permission","id":"0","method":"session/request_permission","seq":1,"toolCallId":"call_ebfdfa7","title":"null","kind":"edit","tool":"Write","optionKinds":["allow_always","allow_once","reject_once"],"identified":true,"identifiedBy":"file_path"}
{"t":10190,"dir":"out","tag":"rpc","id":"a4-cancel-pr","method":"session/cancel","form":"request"}
{"t":10206,"dir":"out","tag":"rpc","method":"session/cancel","form":"notification"}
{"t":10685,"dir":"in","tag":"rpc-response","id":"10","promptIndex":0,"stopReason":"cancelled"}
```

Outcome: the pending permission request was **never replied to** (`"decision":null`,
`"reply":null`, `"replied":false`), the prompt response arrived with
`stopReason:"cancelled"`, the target file does not exist, and the post-kill process scan
found **no orphan** `qodercli` process (the graceful SIGTERM path was not needed, but the
snapshot was still taken):

```text
"turns":[{"promptIndex":0,"stopReason":"cancelled","permissionRequests":1,"identifiedWrites":0,"allowOnceSelections":0,"rejectSelections":0,"answerExcerpt":"","promptResponseExcerpt":"{\"stopReason\":\"cancelled\",\"userMessageId\":\"c440d26e-16e9-479b-9670-fc6d343422bd\",\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0},\"_meta\":{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}}"}]
"fileChecks":[{"path":"/tmp/a4-perm/cancel/pending.txt","expectedContent":"hi","exists":false,"contentExcerpt":"null","contentOk":false}]
{"method":"/proc scan (ps unavailable)","orphanCount":0,"qodercliProcesses":[]}
```

`/proc` fallback process table at snapshot time (verbatim `killEvidence.tableExcerpt`):

```text
PID PPID STAT ARGS (/proc fallback)
1 S bootstrap.sh /bin/sh /opt/opensandbox/bootstrap.sh tail -f /dev/null
10 S tail tail -f /dev/null
22 S bash bash -c QODER_E2E_MODEL=efficient node /workspace/04-permissions.mjs 2>&1; echo A4_SCRIPT_EXIT=$?
23 R node node /workspace/04-permissions.mjs
8 S execd /opt/opensandbox/execd
```

**Stable decision line (deliverable for C0.3 / B3a).** This is the emitted stdout line,
quoted in full (grep the capture for it to reproduce an exact match):

```text
CANCEL-METHOD-DECISION: session/cancel exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted); request-form={"errorCode":-32601,"message":"\"Method not found\": session/cancel"}; notification-form=sent (no id); turn-abort=prompt-response; fallback=none
```

The `A4-SUMMARY-JSON` record carries the same decision as the JSON field
`cancelMethodDecision` — a *summary field*, not a stdout line of its own, and shorter than
the emitted line (it omits the `request-form` / `notification-form` / `turn-abort`
fields):

```json
"cancelMethodDecision":"session/cancel exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted); fallback=none"
```

The fallback path (terminate the CLI, then assert no orphan process remains) was
implemented in the probe but was **not exercised in this run** — the notification path
aborted the turn well inside the effect window.

## 4. Option kinds and IDs — evidence for C0.4

Every permission request in the run carried exactly these three options, in this order, and
nothing else (4 requests / 4 identical menus, from the `A4-PERMISSION-JSON` records and the
summary's `optionKindsObserved` / `optionIdsObserved`):

| `kind` | `optionId` | `name` (as sent) | Meaning | Policy applied in this gate |
|---|---|---|---|---|
| `allow_always` | `proceed_always` | `Allow for this session` | session-wide grant (escalation) | **never selected** (hard constraint); offered in 4/4 requests |
| `allow_once` | `proceed_once` | `Allow` | one-shot grant | selected for each identified Write request (2×) |
| `reject_once` | `cancel` | `Reject` | reject this request | selected for the denied Write request (1×) |

Client → agent reply shapes on the CLI's own request id. The **only** reply shape exercised
in this run is the nested selected outcome (used in §1 and §2):

```json
{"outcome":{"outcome":"selected","optionId":"proceed_once"}}    // allow (exercised, §1)
{"outcome":{"outcome":"selected","optionId":"cancel"}}          // reject (exercised, §2)
{"outcome":"cancelled"}                                         // flat fallback: NOT EXERCISED
```

Notes for C0.4/B3a implementation:

- Selection is by `kind`, never by position: the probe resolves the option whose `kind` is
  `allow_once` (resp. `reject_once`) and refuses to fall back to "first option".
- Request ids of the CLI's own requests are numeric and per-session, starting at `0` (the
  capture records them as strings `"0"`, `"1"`); `session/request_permission` arrives as a
  request (has an `id` and expects a response on the same id).
- Identification of the write is by `_meta.qoder.toolName = "Write"` plus
  `rawInput.file_path` equal to the expected target (`identifiedBy: "file_path"`); the
  `title` field was `null` in this build, so title matching alone would be wrong.
- The flat `{"outcome":"cancelled"}` client reply form is **NOT EXERCISED** in any gate so
  far (this run achieved cancellation by not replying plus `session/cancel`); it is
  therefore **shape-unverified**. The probe sends it only on unexercised fallback paths
  (cancel-mode second request, unidentified request, no-`allow_once` menu,
  no-`reject_once` menu); those sites are annotated in `04-permissions.mjs` as
  shape-unverified / NOT EXERCISED. **B3a must not copy the flat shape as if it were
  verified.**

## 5. No-escalation hard constraints

From the summary line `[A4] PASS: no escalation: allow_always never selected; no acceptEdits
mode change allowAlwaysOffered=4 allowAlwaysSelected=0 acceptEditsModeChanges=0
modeUpdates=0/0/0`:

- `allowAlwaysOffered: 4` / `allowAlwaysSelections: 0` — the escalation option was offered
  on every request and never selected (`grantsAllowOnce: 2`, `rejectSelections: 1`).
- `allowAlwaysSelections` is a **real observation, not a constant**: the probe resolves the
  option it actually sends (`optionId`), maps it back to the offered menu object of that
  request, and increments the counter only when that object's `kind === 'allow_always'`
  (the single client code path `replySelected(optionId)` in `04-permissions.mjs`); the PASS
  gate requires `allowAlwaysSelected === 0` **and** `allowAlwaysOffered >= 1`, so it fails
  both when a menu omits `allow_always` (vacuous pass) and when the probe ever selects it.
- `acceptEditsModeChanges: 0` in all three cases (`"modeUpdates":[]` per case): no
  `session/update` of type `current_mode_update` ever arrived (`grep -o current_mode_update`
  → 0) and the `session/new` result reported `"currentModeId":"default"` in all three
  cases (in particular no `acceptEdits` mode change as seen in the Windows spike when
  `allow_always` is selected).
- `A4-PERMISSIONS-RESULT: PASS` requires `allowAlwaysSelected === 0`,
  `acceptEditsModeChanges === 0` **and** `allowAlwaysOffered >= 1` (a vacuous pass is not
  possible — the probe fails if the menu lacks `allow_always`).

## 6. Reported usage (recorded, not asserted)

The prompt responses carried a zero-valued usage block and a quota block naming the model;
`usageFragments` in the summary captured exactly these three fragments once per case:

```text
"usage":{"inputTokens":0   "input_tokens":0   "output_tokens":0
```

Example prompt response (allow-once turn 1, verbatim excerpt of the inner JSON string —
the backslash escaping of the nested-JSON capture is removed for readability, the string
itself is unmodified):

```json
{"stopReason":"end_turn","userMessageId":"78b81568-5534-4878-84e2-34149b7b8100","usage":{"inputTokens":0,"outputTokens":0,"totalTokens":0},"_meta":{"quota":{"token_count":{"input_tokens":0,"output_tokens":0},"model_usage":[{"model":"efficient","token_count":{"input_tokens":0,"output_tokens":0}}]}}}
```

**Recorded usage, not a guarantee.** All counters are zero and no credit/cost field is
exposed; `_meta.quota.model_usage[0].model` reports `efficient`, which is the model-pin
attestation at the quota layer (A5 owns effective-model observability). Zero counters must
be treated as unavailable accounting, never as proof that the run was free.

## 7. Criterion table

| Criterion | Result | Evidence |
|---|---|---|
| allow-once: identified write request → `allow_once` selected → file created | PASS | §1: `decision:"allow_once:proceed_once"`, `fileChecks` `contentOk:true`, tool event `File created successfully at: …` |
| allow-once: a second write in the same session asks again (grant is one-shot) | PASS | §1: second `session/request_permission` (id `1`) after `stopReason:end_turn`; `secondAskedAgain=true` |
| deny: `reject_once` selected → file absent | PASS | §2: `decision:"reject_once:cancel"`, `exists:false`, tool call `status:"failed"` |
| deny: turn still completes after rejection | PASS | §2: `stopReason:"end_turn"`, agent reports the denial |
| cancel while pending: request unanswered, turn aborted | PASS | §3: `replied:false`, `abortSignals:[{prompt-response, cancelled}]`, `stopReasons:["cancelled"]` |
| cancel: no orphan process remains after the abort/kill | PASS | §3: `/proc` scan `orphanCount:0`, `qodercliProcesses:[]`, table pasted |
| no escalation: `allow_always` never selected | PASS | §5: offered 4/4, selected 0 (counter derived from the sent `optionId`) |
| no escalation: no `acceptEdits` mode change | PASS | §5: `acceptEditsModeChanges:0`, `modeUpdates:[]`, `"currentModeId":"default"` ×3, `current_mode_update` ×0 |
| Option `kind`/`optionId` vocabulary recorded for C0.4 | PASS | §4 table; `optionKindsObserved:["allow_always","allow_once","reject_once"]`, `optionIdsObserved:["proceed_always","proceed_once","cancel"]` |
| Cancel-method decision recorded as a stable line | PASS | §3: the emitted `CANCEL-METHOD-DECISION:` line quoted in full, plus the summary field `cancelMethodDecision` |
| Reported usage recorded (not asserted) | PASS (recorded) | §6: zero counters + quota model `efficient` |
| Model pinned to `efficient` (zero-credit gate) | PASS | run header line; `modelSet:"accepted"` in all 3 cases |
| PAT never on argv / in files / in output | PASS | §8 leak check (5 paths, counts 0) |
| Maven run green on the regenerated probe | PASS | Provenance: pasted `Tests run: 864` unit lane, `Tests run: 1 … Time elapsed: 71.98 s`, `BUILD SUCCESS`, `Total time: 02:31 min` |
| Orphan-process fallback (terminate the CLI) | NOT EXERCISED | notification path worked; the fallback branch is implemented but untested in this run |
| Flat `{"outcome":"cancelled"}` client reply form | NOT EXERCISED (shape-unverified) | §4 note; `04-permissions.mjs` fallback sites annotated; B3a must not copy it |

## 8. Credential hygiene (value-free checks)

- The PAT was read from the local credential file into the host environment in the same
  shell command that ran Maven; it was never a command-line argument, never written to a
  file and never printed.
- Inside the sandbox it existed only as `QODER_PERSONAL_ACCESS_TOKEN` in the container
  environment (injected by `createSandbox` from the host env map); the manager logs only the
  variable count.
- The probe wraps `console.log` in a redactor that replaces the token value (when present,
  ≥ 8 chars) with `[redacted]`, so CLI stderr tails or agent text cannot leak it into the
  capture, the Failsafe report or this document. Fixtures use synthetic tokens only.
- Leak checks (count only; the PAT itself is passed to `grep` as a **pattern file**, never
  as an argv string; ran on this fix round's final artifacts, including this document after
  the paste below):

```bash
grep -c -F -f /c/Users/User/.qoder/qoder-pat.txt e2e/qoder/slice-a/04-permissions.mjs                                               # 0
grep -c -F -f /c/Users/User/.qoder/qoder-pat.txt e2e/qoder/slice-a/04-permissions.md                                              # 0
grep -c -F -f /c/Users/User/.qoder/qoder-pat.txt .superpowers/sdd/2026-09-17-qoder-cli-provider/task-A4-report.md                  # 0
grep -c -F -f /c/Users/User/.qoder/qoder-pat.txt agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderPermissionsE2ETest.java  # 0
grep -c -F -f /c/Users/User/.qoder/qoder-pat.txt agent-control-tower/act-execution/target/qoder-a4-sandbox-output.txt               # 0
```

Actual output of those five checks (counts only; `grep` exits "No matches found" because no
line matched in any file):

```text
e2e/qoder/slice-a/04-permissions.mjs:0
e2e/qoder/slice-a/04-permissions.md:0
.superpowers/sdd/2026-09-17-qoder-cli-provider/task-A4-report.md:0
agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderPermissionsE2ETest.java:0
agent-control-tower/act-execution/target/qoder-a4-sandbox-output.txt:0
No matches found
```

All counts were 0 (counts only; the value is never printed anywhere in this evidence). No
uncommitted temporary log file is part of this evidence — the pasted Maven console excerpt
in the Provenance section is reproduced from the terminal output of the same command.

## 9. Limitations

1. execd output folding is cosmetic; in this run the whole capture is a **single line**
   (38,562 chars, no newline). The decisive markers are intact (marker counts above) and
   the Java test asserts the result marker and the script exit code, not the rendering.
   Each raw block above is pasted from the capture, with line breaks only for presentation.
2. `ps` is absent in the pinned image (`/bin/sh: 1: ps: not found`), so the orphan and
   process-table checks use a `/proc` scan fallback (same pattern as
   `OpenCodeSandboxManager#diagnose`); the recorded `method` field states this.
3. The three cases are three fresh ACP sessions run sequentially in **one** sandbox in a
   **single** green run (2026-09-18). Semantics were also observed in the pre-Maven dev
   loop (`podman` + same image/script), but this document quotes only the Maven run.
4. The probe never selects `allow_always`; therefore the escalation behaviour (the spike's
   accidental `acceptEdits` mode change) is only asserted **absent**, and the exact menu
   after an `allow_always` grant remains unverified by design.
5. The cancel fallback (terminate the CLI, then assert no orphan) was implemented and
   recorded but not exercised, because `session/cancel` as a notification aborted the turn
   (`notificationToAbortMs: 480`).
6. Cancellation semantics are asserted at the client level: the request was left
   unanswered, the prompt response carried `stopReason:"cancelled"`, the target file is
   absent and no orphan process remains. Whether the CLI would additionally accept the
   **flat** `{"outcome":"cancelled"}` reply form was **not tested** — that shape is
   **unverified** (the only exercised reply shape is the nested
   `{"outcome":{"outcome":"selected","optionId":…}}`). **B3a must not copy the unverified
   flat shape**; it needs its own gate before it can be relied on.
7. Request ids of the CLI's own requests: numeric, starting at `0`, per session — observed
   in the dev loop where a mis-dispatch (matching responses by id range instead of "no
   `method` field") consumed a permission request as an RPC response; the probe now matches
   responses by absence of `method` and handles every CLI request by method first. B3a
   should keep that dispatch rule.
8. Zero counters in the usage block are unavailable accounting, not proof of zero cost
   (§6); the `efficient` model pin is what bounds credit exposure, and the harness fails
   closed off `{efficient, lite}`.
9. `title` was `null` on every permission request in this run; identification must use
   `_meta.qoder.toolName` + `rawInput.file_path` (the Windows spike saw non-null titles —
   B3a must not depend on `title`).

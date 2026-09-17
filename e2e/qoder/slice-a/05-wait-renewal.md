# Task A5 evidence — long wait, TTL renewal and effective-model observability (Slice A gate)

Inside a **real OpenSandbox sandbox** created from the pinned image
`aria-conductor/qoder-sandbox:0.1`, a **real authenticated Qoder session** (PAT injected into
the sandbox environment only, model `efficient`) runs one long case in a single ACP session:

1. **production renewal path** — `OpenCodeSandboxManager#renewSandbox(id, 30m)` is called
   through the test harness and the effect is read back with a real SDK round trip
   (`Sandbox` handle + `getInfo().getExpiresAt()`), before and after;
2. **long wait + renewal interleaving** — the probe raises a write permission request, holds
   it pending for a **5-minute** window, the host driver renews the sandbox TTL **while the
   request is still pending**, and the request is then answered with the offered
   `allow_once` option; the CLI must still execute (file written, turn completes);
3. **effective-model observability** — every inbound ACP message is scanned for
   model / usage / credit fields and the exact event/field that carries the effective model
   is pinned (recorded, not asserted);
4. **prompt deadline anchors** — the `session/prompt` send epoch and the prompt response
   epoch are recorded, with the derivation rule a hard deadline can use.

This is the final Slice A gate: it pins the facts **B6** (run deadlines) and **C2** (TTL
renewal / host-side permission coordinator) must build on instead of guessing, and it
records what the CLI does **not** expose (credits, counters, effective model outside the
prompt response).

## Provenance (this evidence)

| Item | Value |
|---|---|
| Test | `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderWaitRenewalE2ETest.java` (Failsafe, env-gated: `-Dqoder.e2e.enabled=true`; skips with the credential env var named when `QODER_E2E_PAT` is absent) |
| Driver | `QoderSandboxHarness#boot(serverUrl, image, sandboxEnv)` — creates the sandbox via `OpenCodeSandboxManager#createSandbox(UUID, String, Map)`, waits for execd, uploads the staging dir to `/workspace`, runs the probe, kills the sandbox in `close()` |
| Harness change (test support only) | `QoderSandboxHarness#renew(Duration)` — a passthrough to `OpenCodeSandboxManager#renewSandbox(String, Duration)`, needed because a manager instance only renews sandboxes in its own registry (`requireSandbox` reads the instance's `sandboxes` map); A2–A4 signatures unchanged, their tests untouched |
| Probe script | `e2e/qoder/slice-a/05-wait-renewal.mjs` (uploaded to `/workspace/05-wait-renewal.mjs`, executed by the in-image Node 22; spawns `qodercli -m efficient --acp` itself) |
| Image | `aria-conductor/qoder-sandbox:0.1` (A1 pin; see `01-boot.md`) |
| CLI | Qoder CLI 1.1.41 (Linux x64 artifact, sha256-pinned in `agent-control-tower/qoder-sandbox/Dockerfile`) |
| Model pin | `-m efficient` on the ACP spawn **and** `session/set_model {sessionId, modelId:"efficient"}`; the harness fails closed off `{efficient, lite}` unless `QODER_E2E_ALLOW_PAID=1` (not set in this run) |
| Credential path | host env `QODER_E2E_PAT` → sandbox container env `QODER_PERSONAL_ACCESS_TOKEN` (env map only; manager logs the count only). The probe redacts the token value from all console output |
| Sandbox | OpenSandbox sandbox `ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85` for agent `f5dff10c-c8ed-4dc5-b30d-c29c04d61ab1` (created 02:26:30.235, script uploaded 02:26:31.935, `Terminating sandbox:` 02:32:01.357, killed 02:32:03.160 — from the run log pasted below) |
| Run | 2026-09-18, `BUILD SUCCESS`, `Total time: 06:58 min`, finished `2026-09-18T02:32:03+08:00`; unit lane `Tests run: 864, Failures: 0, Errors: 0, Skipped: 0`; A5 test `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `Time elapsed: 336.9 s` |
| Probe bounds | `HOLD_MS=300000`, `PERMISSION_WAIT_MS=90000`, `TURN_WAIT_MS=90000`, `CASE_TIMEOUT_MS=600000`; fixture content is exactly `renewed`, write target `/tmp/a5/answer-under-renewal/written.txt` **inside the sandbox**, never in the repo |
| Test timeout | JUnit `@Timeout(20 min)`; the probe's own hard case timeout is 10 min, so a JUnit-level timeout means the sandbox/tooling is stuck |

**Provenance is the command plus the pasted raw output below, not `target/**` files** —
the raw capture and the Failsafe report live under `act-execution/target/**`, which is
uncommitted, recreated by every run and deleted by the next `mvn clean`; the decisive
content is therefore pasted verbatim in this document (see also the labelled references to
the transient capture in §1–§4).

Regeneration command (single command; run from the repo root; local only — the PAT is read
into the host environment inside the same shell invocation, never echoed, never on the
command line, never written to a file):

```bash
export QODER_E2E_PAT="$(cat /c/Users/User/.qoder/qoder-pat.txt)" && cd agent-control-tower && \
mvn clean verify -pl act-execution -Dit.test=QoderWaitRenewalE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
```

`clean` is required: `jacoco:check` does not honour `-Djacoco.skip=true`, so a stale
`act-execution/target/jacoco.exec` fails the build before Failsafe runs. Prerequisite: the
local OpenSandbox server (`podman compose up -d opensandbox-server` from the repo root;
health at `http://localhost:8090/health`).

### Maven console of the green run (verbatim, elided middle marked)

`[INFO] Results:` / `Tests run: 864` is the unit (Surefire) lane, then the selected A5
Failsafe test, then the sandbox boot lines, then — after the elision — the sandbox
teardown, the A5 `Tests run: 1` line and the build tail. Every quoted console line below is
byte-exact; every omitted gap is marked `[...elided ...]` (nothing is silently dropped):

```text
[INFO] Results:
[INFO] Tests run: 864, Failures: 0, Errors: 0, Skipped: 0
[...elided: the Surefire summary's `[INFO] ` blank lines and the jacoco/jar/failsafe
 section headers ...]
[INFO] Running io.aria.conductor.execution.qoder.QoderWaitRenewalE2ETest
02:26:27.123 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Injecting 1 env var(s) into sandbox for agent f5dff10c-c8ed-4dc5-b30d-c29c04d61ab1
[...elided: `Starting create sandbox with startup source aria-conductor/qoder-sandbox:0.1
 (timeout: 1800s) operation`, `Creating sandbox with startup source`, and one transient
 execd-readiness connection refusal (one ERROR `Failed to run command` + `ConnectException
 Failed to connect to /127.0.0.1:52846` stack trace) — the harness's bounded readiness poll
 retried it — the same execd-readiness transient carried over from A2–A4 ...]
02:26:30.235 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully created sandbox: ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85
02:26:30.313 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- OpenSandbox sandbox created for agent f5dff10c-c8ed-4dc5-b30d-c29c04d61ab1: ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85
02:26:31.935 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Uploaded 1 file(s) from workspace C:\Users\User\AppData\Local\Temp\a5-wait-renewal-staging-13863597441111491010 into sandbox for agent f5dff10c-c8ed-4dc5-b30d-c29c04d61ab1
[A5] sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 ready (model pin efficient)
[...elided: the first `expiresAt` read — one resumer attempt rejected with
 `Client error : 409 Conflict` (the SDK itself logs the ERROR + stack trace, see §1), then
 the connector handle read below ...]
[A5] expiresAt read: sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 via connector handle -> 2026-09-17T18:56:27.447168Z (resumer path failed: Client error : 409 Conflict)
[...elided: the SDK's own renew log lines (see §1), then ...]
02:26:32.137 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 renewed until 2026-09-17T18:56:32.063708Z
[A5] expiresAt read: sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 via connector handle -> 2026-09-17T18:56:32.063708Z (resumer path failed: Client error : 409 Conflict)
[A5] STEP1 renewal: extension=PT30M expiresBefore=2026-09-17T18:56:27.447168Z expiresAfter=2026-09-17T18:56:32.063708Z expiresForward=4616ms hostRenew=75ms
[A5] STEP2 pending observed on host at epochMs=1789669611337 state="phase=PENDING model=efficient t_start=1789669592636 t_prompt_sent=1789669598672 t_pending=1789669610120 t_answer=0 t_prompt_end=0 hold_ms=300000 held_ms=0 request_id=0 stop_reason=- file_ok=0"
[...elided: the `expiresAt` reads around the mid-window renewal (same 409-then-connector
 pattern) and the SDK's second renew log ...]
[A5] STEP2 mid-window renewal: hostRenew=[1789669612525..1789669612695] (170ms) sandboxClockBracket=[1789669611378..1789669612777] expiresBefore=2026-09-17T18:56:32.063708Z expiresAfter=2026-09-17T18:56:52.525197Z expiresForward=20461ms stateAfterRenewal="phase=PENDING model=efficient t_start=1789669592636 t_prompt_sent=1789669598672 t_pending=1789669610120 t_answer=0 t_prompt_end=0 hold_ms=300000 held_ms=0 request_id=0 stop_reason=- file_ok=0"
[A5] STEP2 final state="phase=COMPLETED model=efficient t_start=1789669592636 t_prompt_sent=1789669598672 t_pending=1789669610120 t_answer=1789669910132 t_prompt_end=1789669912930 hold_ms=300000 held_ms=300012 request_id=0 stop_reason=end_turn file_ok=1"
[A5] STEP2 completed observed on host at epochMs=1789669920317 hostProbeWallClockMs=308980
[A5] STEP2 interleaving (sandbox clock): t_pending=1789669610120 sandbox_before=1789669611378 sandbox_after=1789669612777 t_answer=1789669910132 held_ms=300012
=== [A5] sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 raw output begin ===
[...elided: the probe's stdout (one folded line, 14,075 bytes — it contains the five [A5]
 PASS lines and the A5-STATE-JSON / A5-PENDING-JSON / A5-ANSWER-JSON / A5-DEADLINE-JSON /
 A5-MODEL-OBSERVABILITY-JSON / A5-PERMISSION-JSON / A5-EVENT-LOG-JSON / A5-HOST-RENEWAL /
 A5-SUMMARY-JSON records and the final result line, all quoted verbatim in §1–§4) ...]
=== [A5] sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 raw output end ===
[A5] un-folded sandbox output written to D:\project\aria-conductor\agent-control-tower\act-execution\target\qoder-a5-sandbox-output.txt
[A5] host evidence written to D:\project\aria-conductor\agent-control-tower\act-execution\target\qoder-a5-host-evidence.txt
02:32:01.357 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Terminating sandbox: ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85
02:32:03.159 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully terminated sandbox: ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85
02:32:03.160 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 killed
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 336.9 s -- in io.aria.conductor.execution.qoder.QoderWaitRenewalE2ETest
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
[INFO] Total time:  06:58 min
[INFO] Finished at: 2026-09-18T02:32:03+08:00
[INFO] ------------------------------------------------------------------------
```

The `target/**` paths named in the last two `[A5]` lines are where the test writes the
capture; both are uncommitted and recreated by each run — the provenance is this command
and the pasted output, not the paths.

### Raw verdict lines (quoted verbatim from the transient capture)

The execd rendering folded **everything into one line** in this run (14,075 bytes, no
newline anywhere in the file). The line breaks inside the blocks below are presentation
only; each block is an exact substring of that one line. `A5_SCRIPT_EXIT=0` is echoed by
the harness command line that ran the script; the script's exit code equals its failure
count.

```text
[A5] PASS: permission request: the prompted write raised an identified session/request_permission requests=1 identifiedWrites=1 identifiedBy=file_path offeredKinds=["allow_always","allow_once","reject_once"]
[A5] PASS: long wait: the request was held pending >= 5 min and then answered by kind (allow_once) t_pending=1789669610120 t_answer=1789669910132 held_ms=300012 hold_ms=300000 decision=allow_once:proceed_once allowOnceSelections=1 allowAlwaysSelections=0
[A5] PASS: turn completed after the hold: prompt response arrived after the answer stopReason=end_turn t_answer=1789669910132 t_prompt_end=1789669912930
[A5] PASS: CLI still executes: the Write tool created the target file with the exact content after the hold exists=true contentOk=true content="renewed"
[A5] PASS: interleaving evidence: host renewal bracket sits inside the pending window sandbox_before=1789669611378 sandbox_after=1789669612777 t_pending=1789669610120 t_answer=1789669910132
A5-WAIT-RENEWAL-RESULT: PASSA5_SCRIPT_EXIT=0
```

Marker counts, re-runnable on the regenerated capture (the capture is a single folded line,
so `grep -o … | wc -l` counts occurrences):

```bash
cd agent-control-tower/act-execution
grep -o '\[A5\] PASS' target/qoder-a5-sandbox-output.txt | wc -l                       # 5
grep -o '\[A5\] FAIL' target/qoder-a5-sandbox-output.txt | wc -l                       # 0
grep -o 'A5-STATE-JSON' target/qoder-a5-sandbox-output.txt | wc -l                     # 5
grep -o 'A5-PENDING-JSON' target/qoder-a5-sandbox-output.txt | wc -l                   # 1
grep -o 'A5-ANSWER-JSON' target/qoder-a5-sandbox-output.txt | wc -l                    # 1
grep -o 'A5-DEADLINE-JSON' target/qoder-a5-sandbox-output.txt | wc -l                  # 1
grep -o 'A5-MODEL-OBSERVABILITY-JSON' target/qoder-a5-sandbox-output.txt | wc -l       # 1
grep -o 'A5-PERMISSION-JSON' target/qoder-a5-sandbox-output.txt | wc -l                # 1
grep -o 'A5-EVENT-LOG-JSON' target/qoder-a5-sandbox-output.txt | wc -l                 # 1
grep -o 'A5-HOST-RENEWAL:' target/qoder-a5-sandbox-output.txt | wc -l                  # 1
grep -o 'A5-SUMMARY-JSON' target/qoder-a5-sandbox-output.txt | wc -l                   # 1
grep -o 'A5-WAIT-RENEWAL-RESULT: PASS' target/qoder-a5-sandbox-output.txt | wc -l      # 1
```

## 1. Step 1 — production renewal path: `expiresAt` moves forward (with a real SDK read)

`OpenCodeSandboxManager#renewSandbox` returns `void` and only logs
(`Sandbox {} renewed until {}`), so the effect is read back through the SDK. The test
attaches a handle per read. The brief named `Sandbox.resumer()`; the run shows that path is
**rejected with HTTP 409 for an already-running sandbox**, and the working attach for reads
is `Sandbox.connector()` — the test records which path produced each reading:

```text
02:26:31.936 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Starting resume sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 operation
02:26:31.957 [main] ERROR com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Failed initiate resume sandbox: ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85
com.alibaba.opensandbox.sandbox.api.infrastructure.ClientException: Client error : 409 Conflict
	at com.alibaba.opensandbox.sandbox.api.SandboxesApi.sandboxesSandboxIdResumePost(SandboxesApi.kt:707)
	at com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter.resumeSandbox(SandboxesAdapter.kt:334)
```

(The full stack trace continues into the JUnit frames; elided here because it is SDK
noise. It is emitted by the **SDK's own logger** — the test itself prints only the
one-line `(resumer path failed: Client error : 409 Conflict)` suffix below. The same 409
was logged on **every** read in this run: 2 reads in Step 1 and 2 reads in Step 2; the
connector fallback succeeded every time.)

The Step 1 sequence, verbatim (host log):

```text
[A5] expiresAt read: sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 via connector handle -> 2026-09-17T18:56:27.447168Z (resumer path failed: Client error : 409 Conflict)
02:26:32.063 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Renew sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 timeout, estimated expiration to 2026-09-18T02:56:32.063189300+08:00
02:26:32.063 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Renew sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 expiration to 2026-09-18T02:56:32.063708400+08:00
02:26:32.137 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully renewed sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 expiration
02:26:32.137 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 renewed until 2026-09-17T18:56:32.063708Z
[A5] expiresAt read: sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 via connector handle -> 2026-09-17T18:56:32.063708Z (resumer path failed: Client error : 409 Conflict)
[A5] STEP1 renewal: extension=PT30M expiresBefore=2026-09-17T18:56:27.447168Z expiresAfter=2026-09-17T18:56:32.063708Z expiresForward=4616ms hostRenew=75ms
```

Readings: **before `2026-09-17T18:56:27.447168Z` → after `2026-09-17T18:56:32.063708Z`**
(forward `4616 ms`; `hostRenew=75ms` is the host wall time of the `renewSandbox` call, not
the extension). `expiresAfter` equals the value the manager itself logged
(`renewed until 2026-09-17T18:56:32.063708Z`) — one instant, two renderings
(`02:56:32.063708400+08:00` in the SDK log = `18:56:32.063708Z`).

**Renewal semantics observed: `renew(X)` sets `expiresAt = call instant + X`, it does not
add `X` to the previous expiry.** The SDK's own log line says it as "estimated expiration
to 2026-09-18T02:56:32.063189300+08:00" for a call made at `02:26:32.063` — exactly
`+30:00`. The observed `expiresBefore 18:56:27.447168Z` is also consistent with the
sandbox's original 30-minute TTL (the create request was logged at `02:26:27.133`,
`Starting create sandbox`). The second renewal in §2 pins the same rule to sub-millisecond
agreement. **Consequence for C2/B6:** each renewal re-arms for `X` from *now*; renewing
early does not accumulate, and the "renewed until" value can be *earlier* than
`previous expiry + X` (in this run Step 2's new expiry, `18:56:52.525197Z`, was only
`20.5 s` after Step 1's, because only ~20.5 s had elapsed since the Step-1 renewal).
Whether the server clamps a larger `X` (a maximum TTL) was **not exercised** — see
§limitations.

The `resumer()` 409/`connector()` finding is A5-specific and matters to B1/C2: **reading a
running sandbox's `expiresAt` must attach with `Sandbox.connector()`**; `Sandbox.resumer()`
is a *server-side* resume request and is rejected for a running sandbox. (`Sandbox#close()`
only releases the handle's HTTP client — it does not kill the sandbox; each read closes its
handle.)

## 2. Step 2 — 5-minute pending hold with the host renewal inside the window

The probe prompts a Write to `/tmp/a5/answer-under-renewal/written.txt` and, when the
permission request arrives, holds it pending for `HOLD_MS=300000` before answering; the
host driver (a virtual thread runs the blocking probe command) observes `phase=PENDING` in
`/tmp/a5/state`, renews the TTL through the production path, writes its renewal bracket to
`/tmp/a5/host-renewal` for the probe to lift into its summary, verifies the state is still
`PENDING`, and then lets the probe answer. The probe answers with the option whose `kind`
is `allow_once` (never `allow_always` — the A4 hard constraint), waits for the prompt
response and verifies the file.

Timeline of this run (all values from the records below; sandbox and host clocks are on the
same machine and agree to well under 0.2 s — the host bracket `[1789669612525..695]` sits
inside the sandbox-clock bracket `[1789669611378..777]`):

| Anchor | Sandbox epoch ms | UTC |
|---|---|---|
| probe start (`t_start`) | 1789669592636 | 2026-09-17T18:26:32.636Z |
| `session/prompt` sent (`t_prompt_sent`) | 1789669598672 | 2026-09-17T18:26:38.672Z |
| permission request arrives (`t_pending`) — hold starts | 1789669610120 | 2026-09-17T18:26:50.120Z |
| host sees `phase=PENDING` | 1789669611337 | 2026-09-17T18:26:51.337Z |
| sandbox-clock bracket around the host renewal | [1789669611378 .. 1789669612777] | [18:26:51.378 .. 18:26:52.777Z] |
| host renewal bracket (host clock) | [1789669612525 .. 1789669612695] | [18:26:52.525 .. 18:26:52.695Z] |
| answer sent (`t_answer`) — hold ends | 1789669910132 | 2026-09-17T18:31:50.132Z |
| prompt response (`t_prompt_end`) | 1789669912930 | 2026-09-17T18:31:52.930Z |

Derived: `held_ms = 300012` (the 5-minute bound crossed by 12 ms), `promptWallClockMs =
314258`, `promptToPermissionMs = 11448`, `answerToResponseMs = 2798`.

**Interleaving is asserted entirely in the sandbox's clock** (no cross-clock inference):
the host read the sandbox clock immediately before and after the production renewal call
(`sandbox_before`, `sandbox_after` in the host-renewal line), and both lie strictly inside
`[t_pending, t_answer]` — the renewal happened while the permission request was pending:

```text
[A5] STEP2 interleaving (sandbox clock): t_pending=1789669610120 sandbox_before=1789669611378 sandbox_after=1789669612777 t_answer=1789669910132 held_ms=300012
```

The same condition is asserted inside the probe (`hostRenewalInterleaving.withinPendingWindow`)
and by the Java driver from the state file, so both sides independently require the bracket
inside the pending window.

Renewal effect measured mid-window (SDK reads + the SDK's own renew log):

```text
[A5] expiresAt read: sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 via connector handle -> 2026-09-17T18:56:32.063708Z (resumer path failed: Client error : 409 Conflict)
02:26:52.525 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Renew sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 timeout, estimated expiration to 2026-09-18T02:56:52.525197+08:00
02:26:52.525 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Renew sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 expiration to 2026-09-18T02:56:52.525197+08:00
02:26:52.695 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully renewed sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 expiration
02:26:52.695 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 renewed until 2026-09-17T18:56:52.525197Z
[A5] expiresAt read: sandbox ee405a88-bda8-4dd5-ba7a-f0ee8f1abc85 via connector handle -> 2026-09-17T18:56:52.525197Z (resumer path failed: Client error : 409 Conflict)
[A5] STEP2 mid-window renewal: hostRenew=[1789669612525..1789669612695] (170ms) sandboxClockBracket=[1789669611378..1789669612777] expiresBefore=2026-09-17T18:56:32.063708Z expiresAfter=2026-09-17T18:56:52.525197Z expiresForward=20461ms stateAfterRenewal="phase=PENDING model=efficient t_start=1789669592636 t_prompt_sent=1789669598672 t_pending=1789669610120 t_answer=0 t_prompt_end=0 hold_ms=300000 held_ms=0 request_id=0 stop_reason=- file_ok=0"
```

Mid-window readings: **before `2026-09-17T18:56:32.063708Z` → after
`2026-09-17T18:56:52.525197Z`** (forward `20461 ms`). The after value is the renewal call
instant (`18:26:52.525Z` host clock = the same instant the SDK logged) plus exactly
`30:00` — the sub-millisecond-level confirmation of the §1 semantics. The state read
immediately after the renewal is still `phase=PENDING` (never answered during the renewal),
so the CLI session survived the renewal mid-hold.

Probe-side state machine (verbatim `A5-STATE-JSON` records; STARTING → PROMPTED → PENDING →
ANSWERED → COMPLETED) and the permission/answer records:

```text
A5-STATE-JSON: {"phase":"STARTING","model":"efficient","t_start":1789669592636,"t_prompt_sent":0,"t_pending":0,"t_answer":0,"t_prompt_end":0,"hold_ms":300000,"held_ms":0,"request_id":"-","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"PROMPTED","model":"efficient","t_start":1789669592636,"t_prompt_sent":1789669598672,"t_pending":0,"t_answer":0,"t_prompt_end":0,"hold_ms":300000,"held_ms":0,"request_id":"-","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"PENDING","model":"efficient","t_start":1789669592636,"t_prompt_sent":1789669598672,"t_pending":1789669610120,"t_answer":0,"t_prompt_end":0,"hold_ms":300000,"held_ms":0,"request_id":"0","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"ANSWERED","model":"efficient","t_start":1789669592636,"t_prompt_sent":1789669598672,"t_pending":1789669610120,"t_answer":1789669910132,"t_prompt_end":0,"hold_ms":300000,"held_ms":300012,"request_id":"0","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"COMPLETED","model":"efficient","t_start":1789669592636,"t_prompt_sent":1789669598672,"t_pending":1789669610120,"t_answer":1789669910132,"t_prompt_end":1789669912930,"hold_ms":300000,"held_ms":300012,"request_id":"0","stop_reason":"end_turn","file_ok":1}
A5-PENDING-JSON: {"seq":1,"requestId":"0","tool":"Write","kind":"edit","identified":true,"identifiedBy":"file_path","resolvedFilePath":"/tmp/a5/answer-under-renewal/written.txt","expectedTarget":"/tmp/a5/answer-under-renewal/written.txt","offeredKinds":["allow_always","allow_once","reject_once"],"t_pending_sandbox_ms":1789669610120,"hold_ms":300000}
A5-ANSWER-JSON: {"seq":1,"first":true,"requestId":"0","t_pending_sandbox_ms":1789669610120,"t_answer_sandbox_ms":1789669910132,"held_ms":300012,"decision":"allow_once:proceed_once","identified":true,"answeredBy":"allow_once"}
```

The permission request record (verbatim; A4's identification rule — `_meta.qoder.toolName`
plus `rawInput.file_path` — and option selection **by `kind`, never by position**, are
reused as-is from the A4 probe):

```text
A5-PERMISSION-JSON: {"seq":1,"rawRequestId":0,"requestId":"0","toolCallId":"call_2c57aed","title":"null","qoderToolName":"Write","toolKind":"edit","filePath":"/tmp/a5/answer-under-renewal/written.txt","expectedTarget":"/tmp/a5/answer-under-renewal/written.txt","identifiedBy":"file_path","identified":true,"offeredOptionIds":["proceed_always","proceed_once","cancel"],"offeredKinds":["allow_always","allow_once","reject_once"],"options":[{"optionId":"proceed_always","name":"Allow for this session","kind":"allow_always"},{"optionId":"proceed_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Reject","kind":"reject_once"}],"allowAlwaysOffered":true,"decision":"allow_once:proceed_once","reply":{"outcome":{"outcome":"selected","optionId":"proceed_once"}},"replied":true,"answeredBy":"allow_once","t":17482}
```

Host-side renewal bracket as the probe lifted it from `/tmp/a5/host-renewal` (verbatim;
this is the file the probe reads at completion, so both the host (writer) and the probe
(reader) record the same line):

```text
A5-HOST-RENEWAL: host_renew host_before=1789669612525 host_after=1789669612695 sandbox_before=1789669611378 sandbox_after=1789669612777 expires_before=2026-09-17T18:56:32.063708Z expires_after=2026-09-17T18:56:52.525197Z
```

Turn outcome after the hold: `stopReason=end_turn`, the Write tool created the target file
with exactly `renewed` (`contentOk=true`, `file_ok=1`), and the driver observed the probe
command complete (`A5_SCRIPT_EXIT=0`). Full machine-readable summary of the case
(verbatim; `hostRenewalRaw`, `hostRenewalInterleaving`, timings, `turnExecutedAfterAnswer`
and the embedded `modelObservability` below):

```text
A5-SUMMARY-JSON: {"case":"long-pending-wait-with-renewal","model":"efficient","holdMs":300000,"timings":{"probeStartEpochMs":1789669592636,"promptSentEpochMs":1789669598672,"promptResponseEpochMs":1789669912930,"promptWallClockMs":314258,"permissionRequestEpochMs":1789669610120,"permissionAnswerEpochMs":1789669910132,"heldMs":300012},"session":{"sessionCreated":true,"modelSet":"accepted","setModelResponse":"{}","currentModeId":"default","permissionRequests":1,"identifiedWrites":1,"allowOnceSelections":1,"allowAlwaysSelections":0,"stopReason":"end_turn"},"turnExecutedAfterAnswer":{"fileChecks":[{"path":"/tmp/a5/answer-under-renewal/written.txt","expectedContent":"renewed","exists":true,"contentExcerpt":"renewed","contentOk":true}]},"modelObservability":{"effectiveModel":"efficient","effectiveModelPath":"response:prompt result._meta.quota.model_usage[0].model","promptResponseUsage":"{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0}","promptResponseMeta":"{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}","setModelResponse":"{}","observations":[{"kind":"update:tool_call","path":"params.update._meta","value":"{\"qoder\":{\"toolName\":\"Write\"}}"},{"kind":"response:prompt","path":"result._meta","value":"{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}"}],"modelFieldMatches":[{"kind":"response:session/new","match":"\"modelId\":\"auto\""},{"kind":"response:session/new","match":"\"modelId\":\"ultimate\""},{"kind":"response:session/new","match":"\"modelId\":\"performance\""},{"kind":"response:session/new","match":"\"modelId\":\"efficient\""},{"kind":"response:prompt","match":"\"model_usage\":[{\"model\":\"efficient\""}],"creditFieldMatches":[],"usageFieldMatches":[{"kind":"response:prompt","match":"\"usage\":{\"inputTokens\":0"},{"kind":"response:prompt","match":"\"outputTokens\":0"},{"kind":"response:prompt","match":"\"totalTokens\":0"},{"kind":"response:prompt","match":"\"input_tokens\":0"}],"updateTypesObserved":["available_commands_update","agent_thought_chunk","tool_call","tool_call_update","agent_message_chunk"],"notObservable":["credit/cost fields (\"credits\", \"total_credits\", \"cost\", \"total_cost_usd\", \"cost_usd\"): 0 occurrences in any inbound message","model field matches outside the prompt response: [{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"auto\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"ultimate\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"performance\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"efficient\\\"\"}]","usage counters: all zero when present (prompt response result.usage and result._meta.quota.token_count: {\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0})","session_started update: not observed; update types observed: [\"available_commands_update\",\"agent_thought_chunk\",\"tool_call\",\"tool_call_update\",\"agent_message_chunk\"]"]},"hostRenewalRaw":"host_renew host_before=1789669612525 host_after=1789669612695 sandbox_before=1789669611378 sandbox_after=1789669612777 expires_before=2026-09-17T18:56:32.063708Z expires_after=2026-09-17T18:56:52.525197Z","hostRenewalInterleaving":{"sandboxBefore":1789669611378,"sandboxAfter":1789669612777,"tPending":1789669610120,"tAnswer":1789669910132,"withinPendingWindow":true},"totalEvents":35,"eventsTruncated":0,"stderrTail":null,"note":"prompt response received"}
```

## 3. Step 3 — effective-model observability (recorded, not asserted)

The probe scans **every** inbound ACP message (all responses and all `session/update`
notifications) for model / usage / credit fields and pins the effective model. Verbatim
`A5-MODEL-OBSERVABILITY-JSON` record of the run:

```text
A5-MODEL-OBSERVABILITY-JSON: {"effectiveModel":"efficient","effectiveModelPath":"response:prompt result._meta.quota.model_usage[0].model","promptResponseUsage":"{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0}","promptResponseMeta":"{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}","setModelResponse":"{}","observations":[{"kind":"update:tool_call","path":"params.update._meta","value":"{\"qoder\":{\"toolName\":\"Write\"}}"},{"kind":"response:prompt","path":"result._meta","value":"{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}"}],"modelFieldMatches":[{"kind":"response:session/new","match":"\"modelId\":\"auto\""},{"kind":"response:session/new","match":"\"modelId\":\"ultimate\""},{"kind":"response:session/new","match":"\"modelId\":\"performance\""},{"kind":"response:session/new","match":"\"modelId\":\"efficient\""},{"kind":"response:prompt","match":"\"model_usage\":[{\"model\":\"efficient\""}],"creditFieldMatches":[],"usageFieldMatches":[{"kind":"response:prompt","match":"\"usage\":{\"inputTokens\":0"},{"kind":"response:prompt","match":"\"outputTokens\":0"},{"kind":"response:prompt","match":"\"totalTokens\":0"},{"kind":"response:prompt","match":"\"input_tokens\":0"}],"updateTypesObserved":["available_commands_update","agent_thought_chunk","tool_call","tool_call_update","agent_message_chunk"],"notObservable":["credit/cost fields (\"credits\", \"total_credits\", \"cost\", \"total_cost_usd\", \"cost_usd\"): 0 occurrences in any inbound message","model field matches outside the prompt response: [{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"auto\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"ultimate\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"performance\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"efficient\\\"\"}]","usage counters: all zero when present (prompt response result.usage and result._meta.quota.token_count: {\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0})","session_started update: not observed; update types observed: [\"available_commands_update\",\"agent_thought_chunk\",\"tool_call\",\"tool_call_update\",\"agent_message_chunk\"]"]}
```

**Facts pinned (for B6/B9):**

- **The effective model is carried only by the `session/prompt` response**, at path
  `result._meta.quota.model_usage[0].model`; in this run the value is `"efficient"`, equal
  to the model pin (`-m efficient` + `session/set_model` accepted). This is the only
  per-turn attestation of which model actually served the turn.
- **`session/set_model`'s response is `{}`** (`setModelResponse:"{}"`) — it does not echo
  the accepted model; `session/new` reports `currentModeId:"default"` and carries the model
  **menu** (`"modelId"` values `auto`, `ultimate`, `performance`, `efficient`) — those are
  selectable options, **not** the effective model.
- **No model field appears outside the prompt response**: no `session/update`
  notification carries one, and the `initialize` / `session/new` / `set_model` responses
  carry none (`modelFieldMatches` shows only the session/new menu entries and the
  `model_usage` entry; the `notObservable` entry records exactly this).
- **No credit/cost field exists anywhere**: `creditFieldMatches:[]` for the literal
  `credits`, `total_credits`, `cost`, `total_cost_usd`, `cost_usd` — 0 occurrences in any
  inbound message. A5 confirms A4's finding with a targeted scan.
- **Usage counters are all zero when present** (`{"inputTokens":0,"outputTokens":0,
  "totalTokens":0}` and `quota.token_count: {input_tokens:0, output_tokens:0}`) — the
  counters cannot be used to attest anything (not the model, not the cost). Zero counters
  are unavailable accounting, never proof that a run was free.
- **No `session_started` update was observed**; the update types seen in this run were
  `available_commands_update`, `agent_thought_chunk`, `tool_call`, `tool_call_update`,
  `agent_message_chunk`.
- The only `_meta` carriers seen in the whole session: `session/update` → `tool_call`'s
  `params.update._meta` (`{"qoder":{"toolName":"Write"}}`, used by A4/B3a for permission
  identification) and the prompt response's `result._meta` (quota block above).

**Implication.** Effective-model attestation must be read from the **prompt response**,
which exists only after the turn: a caller that needs to know the model *before* the turn
cannot get it from this CLI. There is no credit signal to monitor; the zero-credit bounding
mechanism is the fail-closed model pin (`efficient`/`lite`), not an accounting field.

## 4. Step 4 — prompt deadline anchors

The probe records the `session/prompt` send epoch and the prompt response epoch (both in
the sandbox clock). Verbatim:

```text
A5-DEADLINE-JSON: {"promptSentEpochMs":1789669598672,"promptResponseEpochMs":1789669912930,"promptWallClockMs":314258,"promptToPermissionMs":11448,"heldMs":300012,"answerToResponseMs":2798,"deadlineRule":"hard deadline = promptSentEpochMs + maxDuration; the prompt response epoch closes the turn"}
```

Wall clock of this turn: **send `2026-09-17T18:26:38.672Z` → response
`2026-09-17T18:31:52.930Z`, total `314,258 ms`**, of which `11448 ms` elapsed before the
permission request arrived, `300,012 ms` was the pending hold and `2798 ms` was the
post-answer completion. The hold is 95.5 % of the turn's wall clock — **the permission wait
sits inside the same prompt window**, it is not a separate protocol turn.

**How a hard deadline is derived (fact base for B6/C2):**

- Anchor the deadline at the `session/prompt` send instant: `deadline = promptSentEpochMs +
  maxDuration`. The prompt response closes the turn (here `stopReason:"end_turn"` after
  2.8 s of post-answer work), so a run that has not seen the response by the deadline is
  over-budget.
- **While the permission request is pending there is no traffic at all.** The event log
  (verbatim entries from the run's `A5-EVENT-LOG-JSON`; the gap between the last two is
  ~300 s with zero events in either direction):

```text
{"t":6034,"dir":"out","tag":"rpc","id":"10","method":"session/prompt","promptIndex":0,"target":"/tmp/a5/answer-under-renewal/written.txt"}
{"t":17482,"dir":"in","tag":"permission","id":"0","method":"session/request_permission","seq":1,"toolCallId":"call_2c57aed","kind":"edit","tool":"Write","optionKinds":["allow_always","allow_once","reject_once"],"identified":true,"identifiedBy":"file_path"}
{"t":317494,"dir":"out","tag":"permission-reply","id":"0","seq":1,"reply":"{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"proceed_once\"}}"}
{"t":320292,"dir":"in","tag":"rpc-response","id":"10","kind":"session/prompt","stopReason":"end_turn"}
```

  A deadline enforced by observing traffic alone would never fire during a hold — the host
  must run the clock itself (this matches C2's design: the coordinator owns the pending
  request and its budget).
- Timeout of the *hold itself* is a separate caller decision; A5 exercised a full 5-minute
  hold (and the run proves the session survives it, and a mid-window TTL renewal does not
  disturb it). The probe's own `TURN_WAIT_MS=90000` after the answer was ample
  (`answerToResponseMs=2798`).
- Client-side request ids in this run (event log): `initialize`=1, `session/new`=2,
  `session/set_model`=3, `session/prompt`=10; the CLI's own `session/request_permission`
  request id is `"0"`. The A4 dispatch rule (a response has no `method` field; route by
  method first) is what the probe uses — ids alone are ambiguous because both sides number
  their own requests from 0/1 per session.

## 5. Criterion table

| Criterion | Result | Evidence |
|---|---|---|
| Step 1: production `renewSandbox(id, 30m)` moves `expiresAt` forward, read with a real SDK round trip | PASS | §1: `18:56:27.447168Z → 18:56:32.063708Z` (forward 4616 ms), value equal to the manager's own log line; Java asserts `isAfter` |
| Step 1: renewal semantics pinned (renew sets `expiresAt = now + X`, not additive) | PASS (recorded) | §1/§2: both renewals' new expiry = call instant + 30:00 (SDK's own "estimated expiration" lines agree); §limitations notes larger-X clamping NOT VERIFIED |
| Step 2: request held pending ≥ 5 min and answered by `kind` | PASS | §2: `t_answer − t_pending = 300012 ≥ 300000`, `decision:"allow_once:proceed_once"`, `allowAlwaysSelections:0` |
| Step 2: host renewal executed while the request was still pending (interleaving) | PASS | §2: sandbox-clock bracket `[1789669611378..1789669612777]` strictly inside `[t_pending 1789669610120, t_answer 1789669910132]`; `withinPendingWindow:true`; Java asserts both bounds; state after renewal still `phase=PENDING` |
| Step 2: mid-window renewal also moves `expiresAt` forward | PASS | §2: `18:56:32.063708Z → 18:56:52.525197Z` (forward 20461 ms) |
| Step 2: turn completes after the hold and the CLI still executes | PASS | §2: `stopReason:"end_turn"`, `t_prompt_end > t_answer`, file `exists:true contentOk:true content:"renewed"` |
| Step 2: probe exit and result markers | PASS | §Raw verdict lines: 5 `[A5] PASS`, 0 FAIL, `A5-WAIT-RENEWAL-RESULT: PASS`, `A5_SCRIPT_EXIT=0`; Java asserts each marker |
| Step 3: effective-model carrier pinned | PASS (recorded) | §3: `response:prompt result._meta.quota.model_usage[0].model = "efficient"` |
| Step 3: what is NOT observable recorded | PASS (recorded) | §3: `creditFieldMatches:[]`, menu-only model fields, zero usage counters, no `session_started` |
| Step 4: prompt wall clock and deadline derivation recorded | PASS (recorded) | §4: `promptSentEpochMs`/`promptResponseEpochMs`, `promptWallClockMs=314258`, derivation rule |
| Reading `expiresAt` of a running sandbox | PASS (finding) | §1: `resumer()` → 409 Conflict (SDK-logged), `connector()` attach works and was used for all 4 reads |
| Model pinned to `efficient` (zero-credit gate) | PASS | run header line; `modelSet:"accepted"`; harness default `efficient`, fails closed; `QODER_E2E_ALLOW_PAID` not set |
| PAT never in argv / files / output | PASS | §6 leak check (7 paths, counts 0) |
| Maven run green | PASS | Provenance: pasted `Tests run: 864` unit lane, `Tests run: 1 … Time elapsed: 336.9 s`, `BUILD SUCCESS`, `Total time: 06:58 min` |
| Server-side maximum TTL / clamping of a larger renewal | NOT VERIFIED | only `X=30m` was exercised; no larger-X run exists |
| Renewal of an *expired* sandbox (does `renewSandbox` revive it, or does it fail `SANDBOX_UNAVAILABLE`?) | NOT VERIFIED | deliberately not exercised: the 5-min hold stays inside the 30-min TTL; revocation paths belong to other gates |
| Effective model before the turn completes | NOT OBSERVABLE (by design of this CLI) | §3: only the prompt response carries it |

## 6. Credential hygiene (value-free checks)

- The PAT was read from the local credential file into the host environment in the same
  shell command that ran Maven; it was never a command-line argument, never written to a
  file and never printed.
- Inside the sandbox it existed only as `QODER_PERSONAL_ACCESS_TOKEN` in the container
  environment (injected by `createSandbox` from the host env map); the manager logs only the
  variable count.
- The probe wraps `console.log` in a redactor that replaces the token value (when present,
  ≥ 8 chars) with `[redacted]`, so CLI stderr tails or agent text cannot leak it into the
  capture, the Failsafe report or this document.
- Leak checks (count only; the PAT itself is passed to `grep` as a **pattern file**, never
  as an argv string; one command over all of this run's artifacts, including this document
  and the transient Maven console log):

```bash
cd "D:/project/aria-conductor" && grep -c -F -f /c/Users/User/.qoder/qoder-pat.txt \
  e2e/qoder/slice-a/05-wait-renewal.mjs \
  e2e/qoder/slice-a/05-wait-renewal.md \
  .superpowers/sdd/2026-09-17-qoder-cli-provider/task-A5-report.md \
  agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderWaitRenewalE2ETest.java \
  agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderSandboxHarness.java \
  agent-control-tower/act-execution/target/qoder-a5-sandbox-output.txt \
  agent-control-tower/act-execution/target/qoder-a5-host-evidence.txt \
  /tmp/a5-maven-run.log
```

Actual output (counts only; the value is never printed anywhere in this evidence):

```text
e2e/qoder/slice-a/05-wait-renewal.mjs:0
e2e/qoder/slice-a/05-wait-renewal.md:0
.superpowers/sdd/2026-09-17-qoder-cli-provider/task-A5-report.md:0
agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderWaitRenewalE2ETest.java:0
agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderSandboxHarness.java:0
agent-control-tower/act-execution/target/qoder-a5-sandbox-output.txt:0
agent-control-tower/act-execution/target/qoder-a5-host-evidence.txt:0
/tmp/a5-maven-run.log:0
```

All counts 0 (`grep` exit code 1: no match in any file). The `/tmp/a5-maven-run.log` entry
is a transient console capture on the host (outside the repo); it was checked for leaks and
then deleted, and it is not part of this evidence — the pasted console block in
§Provenance is.

## 7. Limitations

1. **Single green evidence run.** One sandbox, one ACP session, one 5-minute hold
   (2026-09-18). The interleaving and renewal readings are from that run; the pre-Maven dev
   loop was not used for any number quoted here.
2. **`renew(X)` semantics were observed only for `X=30m` and only against an unexpired
   sandbox.** Both renewals gave `expiresAt = call instant + 30:00`, i.e. a
   re-arm, not an additive extension (§1/§2). Whether the server clamps a larger `X` or
   accepts a very small one is **NOT VERIFIED**; what happens when a sandbox *has expired*
   before a renewal is **NOT VERIFIED** (never exercised). C2 must not assume "renew
   rescues an expired sandbox".
3. **The `resumer()` 409 finding is about this server/SDK combination** (OpenSandbox server
   from the repo's compose file, SDK 1.0.18): resuming a *running* sandbox is rejected with
   HTTP 409; `connector()` attach works and was used for all four `expiresAt` reads. The
   409 is logged by the SDK itself (ERROR + stack trace) on every read — expected noise,
   not a failure.
4. **execd output folding is cosmetic**: the whole probe capture is a **single line**
   (14,075 bytes, no newline). The decisive markers are intact (marker counts above) and
   the Java test asserts the result marker and the script exit code, not the rendering.
   The driver's own `[A5]` lines are printed to the Failsafe console *outside* the folded
   capture and are unaffected.
5. **Model observability is a record of absence as much as presence**: the effective model
   exists only in the prompt response's `_meta.quota.model_usage[0].model` (§3). The scan
   looked for literal field names (`model`, `modelId`, `model_usage`, `credits`,
   `total_credits`, `cost`, `total_cost_usd`, `cost_usd`, `usage`, `inputTokens`,
   `outputTokens`, `totalTokens`, `input_tokens`, `output_tokens`) in every inbound
   message; a differently-named field was not searched for. Zero counters are unavailable
   accounting, not proof of zero cost.
6. **A4 carried-over facts applied as-is** (not re-derived here): route inbound messages by
   `method` first — responses carry no `method` field and both sides number their own
   requests from 0 per session; select permission options by `kind`, never `allow_always`;
   `ps` is absent in the image (this run needed no process scan, so the `/proc` fallback
   was not exercised again).
7. **A5 does not test run-deadline enforcement or holder code** — it records the wall-clock
   anchors and the zero-traffic-during-hold property (§4); B6/C2 own the policy. The
   in-sandbox probe's own bounds (`HOLD_MS + TURN_WAIT_MS + 30000` case timer) are probe
   limits, not product semantics.
8. **The harness's `renew(Duration)` passthrough is test-support only** (§Provenance); no
   `src/main` file was touched by A5. B1's lifecycle extraction is expected to absorb the
   production renewal path.

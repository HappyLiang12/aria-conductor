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
records what the CLI does **not** expose (credits, counters, an *effective*-model field
outside the `session/prompt` response — the only model fields elsewhere are the
`session/new` model-menu entries, which are selectable options, not the effective model).

## Provenance (this evidence)

| Item | Value |
|---|---|
| Test | `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderWaitRenewalE2ETest.java` (Failsafe, env-gated: `-Dqoder.e2e.enabled=true`; skips with the credential env var named when `QODER_E2E_PAT` is absent) |
| Driver | `QoderSandboxHarness#boot(serverUrl, image, sandboxEnv)` — creates the sandbox via `OpenCodeSandboxManager#createSandbox(UUID, String, Map)`, waits for execd, uploads the staging dir to `/workspace`, runs the probe, and — beyond the probe's self-report — reads the written file itself (`cat /tmp/a5/answer-under-renewal/written.txt` over the exec channel, asserted host-side), then kills the sandbox in `close()` |
| Harness change (test support only) | `QoderSandboxHarness#renew(Duration)` — a passthrough to `OpenCodeSandboxManager#renewSandbox(String, Duration)`, needed because a manager instance only renews sandboxes in its own registry (`requireSandbox` reads the instance's `sandboxes` map); A2–A4 signatures unchanged, their tests untouched |
| Probe script | `e2e/qoder/slice-a/05-wait-renewal.mjs` (uploaded to `/workspace/05-wait-renewal.mjs`, executed by the in-image Node 22; spawns `qodercli -m efficient --acp` itself) |
| Image | `aria-conductor/qoder-sandbox:0.1` (A1 pin; see `01-boot.md`) |
| CLI | Qoder CLI 1.1.41 (Linux x64 artifact, sha256-pinned in `agent-control-tower/qoder-sandbox/Dockerfile`) |
| Model pin | `-m efficient` on the ACP spawn **and** `session/set_model {sessionId, modelId:"efficient"}`; the harness fails closed off `{efficient, lite}` unless `QODER_E2E_ALLOW_PAID=1` (not set in this run) |
| Credential path | host env `QODER_E2E_PAT` → sandbox container env `QODER_PERSONAL_ACCESS_TOKEN` (env map only; manager logs the count only). The probe redacts the token value from all console output |
| Sandbox | OpenSandbox sandbox `a204ceae-3fb1-417f-9e5f-0f7b1b8b897c` for agent `ff6d4019-1bd9-4dd6-919c-2836beb39975` (created 03:07:49.960, script uploaded 03:07:51.187, `Terminating sandbox:` 03:13:20.707, killed 03:13:22.305 — from the run log pasted below) |
| Run | 2026-09-18, `BUILD SUCCESS`, `Total time: 07:01 min`, finished `2026-09-18T03:13:22+08:00`; unit lane `Tests run: 864, Failures: 0, Errors: 0, Skipped: 0`; A5 test `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `Time elapsed: 338.5 s` |
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
03:07:44.795 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Injecting 1 env var(s) into sandbox for agent ff6d4019-1bd9-4dd6-919c-2836beb39975
[...elided: `Starting create sandbox with startup source aria-conductor/qoder-sandbox:0.1
 (timeout: 1800s) operation` (03:07:44.802), `Creating sandbox with startup source:
 aria-conductor/qoder-sandbox:0.1` (03:07:44.910) and the create-completed line
 (03:07:50.030); no execd-readiness retry was logged this run ...]
03:07:49.960 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully created sandbox: a204ceae-3fb1-417f-9e5f-0f7b1b8b897c
03:07:50.038 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- OpenSandbox sandbox created for agent ff6d4019-1bd9-4dd6-919c-2836beb39975: a204ceae-3fb1-417f-9e5f-0f7b1b8b897c
03:07:51.187 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Uploaded 1 file(s) from workspace C:\Users\User\AppData\Local\Temp\a5-wait-renewal-staging-9727343805026821298 into sandbox for agent ff6d4019-1bd9-4dd6-919c-2836beb39975
[A5] sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c ready (model pin efficient)
[...elided: read 1's handle attach — the SDK's `Starting resume sandbox ... operation`
 rejected with ERROR `Failed initiate resume sandbox` + `Client error : 409 Conflict`
 (full block and its meaning in §1), then `Starting connect to sandbox ...` ...]
[A5] expiresAt read: sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c via connector handle -> 2026-09-17T19:37:45.103462Z (resumer path failed: Client error : 409 Conflict)
03:07:51.353 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Renew sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c timeout, estimated expiration to 2026-09-18T03:37:51.353488600+08:00
03:07:51.354 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Renew sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c expiration to 2026-09-18T03:37:51.354488700+08:00
03:07:51.481 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully renewed sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c expiration
03:07:51.481 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c renewed until 2026-09-17T19:37:51.354488Z
[...elided: read 2's handle attach (same 409 + `Starting connect to sandbox` pattern,
 03:07:51.482–03:07:51.516) ...]
[A5] expiresAt read: sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c via connector handle -> 2026-09-17T19:37:51.354488Z (resumer path failed: Client error : 409 Conflict)
[A5] STEP1 renewal: extension=PT30M expiresBefore=2026-09-17T19:37:45.103462Z expiresAfter=2026-09-17T19:37:51.354488Z expiresForward=6251ms hostRenew=128ms
[A5] STEP2 pending observed on host at epochMs=1789672090745 state="phase=PENDING model=efficient t_start=1789672072064 t_prompt_sent=1789672078105 t_pending=1789672087833 t_answer=0 t_prompt_end=0 hold_ms=300000 held_ms=0 request_id=0 stop_reason=- file_ok=0"
[...elided: read 3's handle attach (same 409 pattern, 03:08:11.785–03:08:11.946), the
 STEP2 mid-window renewal's own SDK log lines (see §2) and read 4's handle attach ...]
[A5] expiresAt read: sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c via connector handle -> 2026-09-17T19:38:11.965220Z (resumer path failed: Client error : 409 Conflict)
[A5] STEP2 mid-window renewal: hostRenew=[1789672091964..1789672092201] (237ms) sandboxClockBracket=[1789672090776..1789672092282] expiresBefore=2026-09-17T19:37:51.354488Z expiresAfter=2026-09-17T19:38:11.965220Z expiresForward=20610ms stateAfterRenewal="phase=PENDING model=efficient t_start=1789672072064 t_prompt_sent=1789672078105 t_pending=1789672087833 t_answer=0 t_prompt_end=0 hold_ms=300000 held_ms=0 request_id=0 stop_reason=- file_ok=0"
[A5] STEP2 final state="phase=COMPLETED model=efficient t_start=1789672072064 t_prompt_sent=1789672078105 t_pending=1789672087833 t_answer=1789672387878 t_prompt_end=1789672391338 hold_ms=300000 held_ms=300045 request_id=0 stop_reason=end_turn file_ok=1"
[A5] STEP2 completed observed on host at epochMs=1789672398679 hostProbeWallClockMs=307934
[A5] STEP2 interleaving (sandbox clock): t_pending=1789672087833 sandbox_before=1789672090776 sandbox_after=1789672092282 t_answer=1789672387878 held_ms=300045
[A5] STEP2 host-side read of /tmp/a5/answer-under-renewal/written.txt -> "renewed"
=== [A5] sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c raw output begin ===
[...elided: the probe's stdout (one folded line, 13,508 bytes — it contains the five [A5]
 PASS lines and the A5-STATE-JSON / A5-PENDING-JSON / A5-ANSWER-JSON / A5-DEADLINE-JSON /
 A5-MODEL-OBSERVABILITY-JSON / A5-PERMISSION-JSON / A5-EVENT-LOG-JSON / A5-HOST-RENEWAL /
 A5-SUMMARY-JSON records and the final result line, all quoted verbatim in §1–§4) ...]
=== [A5] sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c raw output end ===
[A5] un-folded sandbox output written to D:\project\aria-conductor\agent-control-tower\act-execution\target\qoder-a5-sandbox-output.txt
[A5] host evidence written to D:\project\aria-conductor\agent-control-tower\act-execution\target\qoder-a5-host-evidence.txt
03:13:20.707 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Terminating sandbox: a204ceae-3fb1-417f-9e5f-0f7b1b8b897c
03:13:22.305 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully terminated sandbox: a204ceae-3fb1-417f-9e5f-0f7b1b8b897c
03:13:22.305 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c killed
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 338.5 s -- in io.aria.conductor.execution.qoder.QoderWaitRenewalE2ETest
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
[INFO] Total time:  07:01 min
[INFO] Finished at: 2026-09-18T03:13:22+08:00
[INFO] ------------------------------------------------------------------------
```

The `target/**` paths named in the last two `[A5]` lines are where the test writes the
capture; both are uncommitted and recreated by each run — the provenance is this command
and the pasted output, not the paths.

### Raw verdict lines (quoted verbatim from the transient capture)

The execd rendering folded **everything into one line** in this run (13,508 bytes, no
newline anywhere in the file). The line breaks inside the blocks below are presentation
only; each quoted line is an exact substring of that one folded line, and the file has no
whitespace between records. `A5_SCRIPT_EXIT=0` is echoed by the harness command line that
ran the script (`... node /workspace/05-wait-renewal.mjs 2>&1; echo A5_SCRIPT_EXIT=$?`);
the script's exit code equals its failure count.

```text
[A5] PASS: permission request: the prompted write raised an identified session/request_permission requests=1 identifiedWrites=1 identifiedBy=file_path offeredKinds=["allow_always","allow_once","reject_once"]
[A5] PASS: long wait: the request was held pending >= 5 min and then answered by kind (allow_once) t_pending=1789672087833 t_answer=1789672387878 held_ms=300045 hold_ms=300000 decision=allow_once:proceed_once allowOnceSelections=1 allowAlwaysSelections=0
[A5] PASS: turn completed after the hold: prompt response arrived after the answer stopReason=end_turn t_answer=1789672387878 t_prompt_end=1789672391338
[A5] PASS: CLI still executes: the Write tool created the target file with the exact content after the hold exists=true contentOk=true content="renewed"
[A5] PASS: interleaving evidence: host renewal bracket sits inside the pending window sandbox_before=1789672090776 sandbox_after=1789672092282 t_pending=1789672087833 t_answer=1789672387878
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
attaches a handle per read, trying `Sandbox.resumer()` first; the run shows that path is
**rejected with HTTP 409 for a running sandbox**, and the working attach for reads is
`Sandbox.connector()` — the test records which path produced each reading:

```text
03:07:51.481 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Starting resume sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c operation
03:07:51.482 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Resuming sandbox: a204ceae-3fb1-417f-9e5f-0f7b1b8b897c
03:07:51.493 [main] ERROR com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Failed initiate resume sandbox: a204ceae-3fb1-417f-9e5f-0f7b1b8b897c
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
[A5] expiresAt read: sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c via connector handle -> 2026-09-17T19:37:45.103462Z (resumer path failed: Client error : 409 Conflict)
03:07:51.353 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Renew sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c timeout, estimated expiration to 2026-09-18T03:37:51.353488600+08:00
03:07:51.354 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Renew sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c expiration to 2026-09-18T03:37:51.354488700+08:00
03:07:51.481 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully renewed sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c expiration
03:07:51.481 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c renewed until 2026-09-17T19:37:51.354488Z
[...elided: the second read's handle attach (resume rejected 409 again, `connector()` connect) ...]
[A5] expiresAt read: sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c via connector handle -> 2026-09-17T19:37:51.354488Z (resumer path failed: Client error : 409 Conflict)
[A5] STEP1 renewal: extension=PT30M expiresBefore=2026-09-17T19:37:45.103462Z expiresAfter=2026-09-17T19:37:51.354488Z expiresForward=6251ms hostRenew=128ms
```

Readings: **before `2026-09-17T19:37:45.103462Z` → after `2026-09-17T19:37:51.354488Z`**
(forward `6251 ms`; `hostRenew=128ms` is the host wall time of the `renewSandbox` call, not
the extension). `expiresAfter` equals the value the manager itself logged
(`renewed until 2026-09-17T19:37:51.354488Z`) — one instant, two renderings: the SDK's
server-receipt line says `03:37:51.354488700+08:00` (= `19:37:51.354488Z`) while its local
estimate for the same call said `03:37:51.353488600+08:00` (1.0 ms apart).

**Renewal semantics observed: `renew(X)` sets `expiresAt = call instant + X`, it does not
add `X` to the previous expiry.** The SDK's own log line says it as "estimated expiration
to 2026-09-18T03:37:51.353488600+08:00" for a call made at `03:07:51.353` — exactly
`+30:00`. The observed `expiresBefore 19:37:45.103462Z` is also consistent with the
sandbox's original 30-minute TTL (`03:37:45.103462+08:00` is ≈30 min after the create
operation started at `03:07:44.802`, +0.30 s — the first create log line). The second
renewal in §2 pins the same rule to
sub-millisecond agreement (0.52 ms). **Consequence for C2/B6:** each renewal re-arms for
`X` from *now*; renewing early does not accumulate, and the "renewed until" value can be
*earlier* than `previous expiry + X` (in this run Step 2's new expiry,
`19:38:11.965220Z`, was only `20.61 s` after Step 1's, because only ~`20.61 s` had elapsed
since the Step-1 renewal). Whether the server clamps a larger `X` (a maximum TTL) was
**not exercised** — see §limitations.

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
response and verifies the file. After the probe command completes, the driver does **not**
rest on that self-report alone: it reads the target file itself over the sandbox exec
channel (`cat /tmp/a5/answer-under-renewal/written.txt`) and asserts the exact content
host-side — the independent "the CLI still executed" observation quoted in the run console
below.

Timeline of this run (all values from the records below; sandbox and host clocks are on the
same machine and agree closely — the host renewal bracket `[1789672091964..201]` sits
inside the sandbox-clock bracket `[1789672090776..282]` that encloses it):

| Anchor | Sandbox epoch ms | UTC |
|---|---|---|
| probe start (`t_start`) | 1789672072064 | 2026-09-17T19:07:52.064Z |
| `session/prompt` sent (`t_prompt_sent`) | 1789672078105 | 2026-09-17T19:07:58.105Z |
| permission request arrives (`t_pending`) — hold starts | 1789672087833 | 2026-09-17T19:08:07.833Z |
| host sees `phase=PENDING` | 1789672090745 | 2026-09-17T19:08:10.745Z |
| sandbox-clock bracket around the host renewal | [1789672090776 .. 1789672092282] | [19:08:10.776 .. 19:08:12.282Z] |
| host renewal bracket (host clock) | [1789672091964 .. 1789672092201] | [19:08:11.964 .. 19:08:12.201Z] |
| answer sent (`t_answer`) — hold ends | 1789672387878 | 2026-09-17T19:13:07.878Z |
| prompt response (`t_prompt_end`) | 1789672391338 | 2026-09-17T19:13:11.338Z |

Derived: `held_ms = 300045` (the 5-minute bound crossed by 45 ms), `promptWallClockMs =
313233`, `promptToPermissionMs = 9728`, `answerToResponseMs = 3460`.

**Interleaving is asserted entirely in the sandbox's clock** (no cross-clock inference):
the host read the sandbox clock immediately before and after the production renewal call
(`sandbox_before`, `sandbox_after` in the host-renewal line), and both lie strictly inside
`[t_pending, t_answer]` — the renewal happened while the permission request was pending:

```text
[A5] STEP2 interleaving (sandbox clock): t_pending=1789672087833 sandbox_before=1789672090776 sandbox_after=1789672092282 t_answer=1789672387878 held_ms=300045
```

The same condition is asserted inside the probe (`hostRenewalInterleaving.withinPendingWindow`)
and by the Java driver from the state file, so both sides independently require the bracket
inside the pending window.

Renewal effect measured mid-window (SDK reads + the SDK's own renew log):

```text
[A5] expiresAt read: sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c via connector handle -> 2026-09-17T19:37:51.354488Z (resumer path failed: Client error : 409 Conflict)
03:08:11.964 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Renew sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c timeout, estimated expiration to 2026-09-18T03:38:11.964702300+08:00
03:08:11.965 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Renew sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c expiration to 2026-09-18T03:38:11.965220700+08:00
03:08:12.200 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully renewed sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c expiration
03:08:12.201 [main] INFO io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c renewed until 2026-09-17T19:38:11.965220Z
[...elided: the fourth read's handle attach (resume rejected 409 again, `connector()` connect) ...]
[A5] expiresAt read: sandbox a204ceae-3fb1-417f-9e5f-0f7b1b8b897c via connector handle -> 2026-09-17T19:38:11.965220Z (resumer path failed: Client error : 409 Conflict)
[A5] STEP2 mid-window renewal: hostRenew=[1789672091964..1789672092201] (237ms) sandboxClockBracket=[1789672090776..1789672092282] expiresBefore=2026-09-17T19:37:51.354488Z expiresAfter=2026-09-17T19:38:11.965220Z expiresForward=20610ms stateAfterRenewal="phase=PENDING model=efficient t_start=1789672072064 t_prompt_sent=1789672078105 t_pending=1789672087833 t_answer=0 t_prompt_end=0 hold_ms=300000 held_ms=0 request_id=0 stop_reason=- file_ok=0"
```

Mid-window readings: **before `2026-09-17T19:37:51.354488Z` → after
`2026-09-17T19:38:11.965220Z`** (forward `20610 ms`). The after value equals the SDK's own
estimate of the renewal instant (`03:08:11.964702300+08:00`, host clock) plus exactly
`30:00` to within 0.52 ms (the server-returned value was `03:08:11.965220700+08:00`) — the
§1 semantics confirmed a second time. The state read immediately after the renewal is still
`phase=PENDING` (never answered during the renewal), so the CLI session survived the
renewal mid-hold.

Probe-side state machine (verbatim `A5-STATE-JSON` records; STARTING → PROMPTED → PENDING →
ANSWERED → COMPLETED) and the permission/answer records:

```text
A5-STATE-JSON: {"phase":"STARTING","model":"efficient","t_start":1789672072064,"t_prompt_sent":0,"t_pending":0,"t_answer":0,"t_prompt_end":0,"hold_ms":300000,"held_ms":0,"request_id":"-","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"PROMPTED","model":"efficient","t_start":1789672072064,"t_prompt_sent":1789672078105,"t_pending":0,"t_answer":0,"t_prompt_end":0,"hold_ms":300000,"held_ms":0,"request_id":"-","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"PENDING","model":"efficient","t_start":1789672072064,"t_prompt_sent":1789672078105,"t_pending":1789672087833,"t_answer":0,"t_prompt_end":0,"hold_ms":300000,"held_ms":0,"request_id":"0","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"ANSWERED","model":"efficient","t_start":1789672072064,"t_prompt_sent":1789672078105,"t_pending":1789672087833,"t_answer":1789672387878,"t_prompt_end":0,"hold_ms":300000,"held_ms":300045,"request_id":"0","stop_reason":"-","file_ok":0}
A5-STATE-JSON: {"phase":"COMPLETED","model":"efficient","t_start":1789672072064,"t_prompt_sent":1789672078105,"t_pending":1789672087833,"t_answer":1789672387878,"t_prompt_end":1789672391338,"hold_ms":300000,"held_ms":300045,"request_id":"0","stop_reason":"end_turn","file_ok":1}
A5-PENDING-JSON: {"seq":1,"requestId":"0","tool":"Write","kind":"edit","identified":true,"identifiedBy":"file_path","resolvedFilePath":"/tmp/a5/answer-under-renewal/written.txt","expectedTarget":"/tmp/a5/answer-under-renewal/written.txt","offeredKinds":["allow_always","allow_once","reject_once"],"t_pending_sandbox_ms":1789672087833,"hold_ms":300000}
A5-ANSWER-JSON: {"seq":1,"first":true,"requestId":"0","t_pending_sandbox_ms":1789672087833,"t_answer_sandbox_ms":1789672387878,"held_ms":300045,"decision":"allow_once:proceed_once","identified":true,"answeredBy":"allow_once"}
```

The permission request record (verbatim; A4's identification rule — `_meta.qoder.toolName`
plus `rawInput.file_path` — and option selection **by `kind`, never by position**, are
reused as-is from the A4 probe):

```text
A5-PERMISSION-JSON: {"seq":1,"rawRequestId":0,"requestId":"0","toolCallId":"call_eec95f5","title":"null","qoderToolName":"Write","toolKind":"edit","filePath":"/tmp/a5/answer-under-renewal/written.txt","expectedTarget":"/tmp/a5/answer-under-renewal/written.txt","identifiedBy":"file_path","identified":true,"offeredOptionIds":["proceed_always","proceed_once","cancel"],"offeredKinds":["allow_always","allow_once","reject_once"],"options":[{"optionId":"proceed_always","name":"Allow for this session","kind":"allow_always"},{"optionId":"proceed_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Reject","kind":"reject_once"}],"allowAlwaysOffered":true,"decision":"allow_once:proceed_once","reply":{"outcome":{"outcome":"selected","optionId":"proceed_once"}},"replied":true,"answeredBy":"allow_once","t":15766}
```

Host-side renewal bracket as the probe lifted it from `/tmp/a5/host-renewal` (verbatim;
this is the file the probe reads at completion, so both the host (writer) and the probe
(reader) record the same line):

```text
A5-HOST-RENEWAL: host_renew host_before=1789672091964 host_after=1789672092201 sandbox_before=1789672090776 sandbox_after=1789672092282 expires_before=2026-09-17T19:37:51.354488Z expires_after=2026-09-17T19:38:11.965220Z
```

Turn outcome after the hold: `stopReason=end_turn`, the Write tool created the target file
with exactly `renewed` (`contentOk=true`, `file_ok=1`), the driver observed the probe
command complete (`A5_SCRIPT_EXIT=0`), and the driver's own host-side read of the file
returned exactly `renewed` (printed as `[A5] STEP2 host-side read of
/tmp/a5/answer-under-renewal/written.txt -> "renewed"`, quoted in the run console below,
and recorded as `step2.hostFileRead=renewed` in its transient host-evidence capture). Full
machine-readable summary of the case
(verbatim; `hostRenewalRaw`, `hostRenewalInterleaving`, timings, `turnExecutedAfterAnswer`
and the embedded `modelObservability` below):

```text
A5-SUMMARY-JSON: {"case":"long-pending-wait-with-renewal","model":"efficient","holdMs":300000,"timings":{"probeStartEpochMs":1789672072064,"promptSentEpochMs":1789672078105,"promptResponseEpochMs":1789672391338,"promptWallClockMs":313233,"permissionRequestEpochMs":1789672087833,"permissionAnswerEpochMs":1789672387878,"heldMs":300045},"session":{"sessionCreated":true,"modelSet":"accepted","setModelResponse":"{}","currentModeId":"default","permissionRequests":1,"identifiedWrites":1,"allowOnceSelections":1,"allowAlwaysSelections":0,"stopReason":"end_turn"},"turnExecutedAfterAnswer":{"fileChecks":[{"path":"/tmp/a5/answer-under-renewal/written.txt","expectedContent":"renewed","exists":true,"contentExcerpt":"renewed","contentOk":true}]},"modelObservability":{"effectiveModel":"efficient","effectiveModelPath":"response:prompt result._meta.quota.model_usage[0].model","promptResponseUsage":"{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0}","promptResponseMeta":"{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}","setModelResponse":"{}","observations":[{"kind":"update:tool_call","path":"params.update._meta","value":"{\"qoder\":{\"toolName\":\"Write\"}}"},{"kind":"response:prompt","path":"result._meta","value":"{\"quota\":{\"token_count\":{\"input_tokens\":0,\"output_tokens\":0},\"model_usage\":[{\"model\":\"efficient\",\"token_count\":{\"input_tokens\":0,\"output_tokens\":0}}]}}"}],"modelFieldMatches":[{"kind":"response:session/new","match":"\"modelId\":\"auto\""},{"kind":"response:session/new","match":"\"modelId\":\"ultimate\""},{"kind":"response:session/new","match":"\"modelId\":\"performance\""},{"kind":"response:session/new","match":"\"modelId\":\"efficient\""},{"kind":"response:prompt","match":"\"model_usage\":[{\"model\":\"efficient\""}],"creditFieldMatches":[],"usageFieldMatches":[{"kind":"response:prompt","match":"\"usage\":{\"inputTokens\":0"},{"kind":"response:prompt","match":"\"outputTokens\":0"},{"kind":"response:prompt","match":"\"totalTokens\":0"},{"kind":"response:prompt","match":"\"input_tokens\":0"}],"updateTypesObserved":["available_commands_update","agent_thought_chunk","tool_call","tool_call_update","agent_message_chunk"],"notObservable":["credit/cost fields (\"credits\", \"total_credits\", \"cost\", \"total_cost_usd\", \"cost_usd\"): 0 occurrences in any inbound message","model field matches outside the prompt response: [{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"auto\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"ultimate\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"performance\\\"\"},{\"kind\":\"response:session/new\",\"match\":\"\\\"modelId\\\":\\\"efficient\\\"\"}]","usage counters: all zero when present (prompt response result.usage and result._meta.quota.token_count: {\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0})","session_started update: not observed; update types observed: [\"available_commands_update\",\"agent_thought_chunk\",\"tool_call\",\"tool_call_update\",\"agent_message_chunk\"]"]},"hostRenewalRaw":"host_renew host_before=1789672091964 host_after=1789672092201 sandbox_before=1789672090776 sandbox_after=1789672092282 expires_before=2026-09-17T19:37:51.354488Z expires_after=2026-09-17T19:38:11.965220Z","hostRenewalInterleaving":{"sandboxBefore":1789672090776,"sandboxAfter":1789672092282,"tPending":1789672087833,"tAnswer":1789672387878,"withinPendingWindow":true},"totalEvents":29,"eventsTruncated":0,"stderrTail":null,"note":"prompt response received"}
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
- **No *effective*-model field appears outside the `session/prompt` response**: no
  `session/update` notification carries one, and the `initialize` / `session/set_model`
  responses carry no model field at all; the `session/new` response's only model fields are
  its model **menu** entries (`"modelId"` values `auto`, `ultimate`, `performance`,
  `efficient`) — selectable options, not the effective model (`modelFieldMatches` shows
  exactly those menu entries plus the `model_usage` entry; the `notObservable` entry
  records exactly this).
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
A5-DEADLINE-JSON: {"promptSentEpochMs":1789672078105,"promptResponseEpochMs":1789672391338,"promptWallClockMs":313233,"promptToPermissionMs":9728,"heldMs":300045,"answerToResponseMs":3460,"deadlineRule":"hard deadline = promptSentEpochMs + maxDuration; the prompt response epoch closes the turn"}
```

Wall clock of this turn: **send `2026-09-17T19:07:58.105Z` → response
`2026-09-17T19:13:11.338Z`, total `313,233 ms`**, of which `9728 ms` elapsed before the
permission request arrived, `300,045 ms` was the pending hold and `3460 ms` was the
post-answer completion. The hold is 95.8 % of the turn's wall clock — **the permission wait
sits inside the same prompt window**, it is not a separate protocol turn.

**How a hard deadline is derived (fact base for B6/C2):**

- Anchor the deadline at the `session/prompt` send instant: `deadline = promptSentEpochMs +
  maxDuration`. The prompt response closes the turn (here `stopReason:"end_turn"` after
  3.5 s of post-answer work), so a run that has not seen the response by the deadline is
  over-budget.
- **While the permission request is pending there is no traffic at all.** The event log
  (verbatim entries from the run's `A5-EVENT-LOG-JSON`; the gap between the permission
  request and the reply — the middle of the four entries — is ~300 s with zero events in
  either direction):

```text
{"t":6040,"dir":"out","tag":"rpc","id":"10","method":"session/prompt","promptIndex":0,"target":"/tmp/a5/answer-under-renewal/written.txt"}
{"t":15767,"dir":"in","tag":"permission","id":"0","method":"session/request_permission","seq":1,"toolCallId":"call_eec95f5","kind":"edit","tool":"Write","optionKinds":["allow_always","allow_once","reject_once"],"identified":true,"identifiedBy":"file_path"}
{"t":315812,"dir":"out","tag":"permission-reply","id":"0","seq":1,"reply":"{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"proceed_once\"}}"}
{"t":319272,"dir":"in","tag":"rpc-response","id":"10","kind":"session/prompt","stopReason":"end_turn"}
```

  A deadline enforced by observing traffic alone would never fire during a hold — the host
  must run the clock itself (this matches C2's design: the coordinator owns the pending
  request and its budget).
- Timeout of the *hold itself* is a separate caller decision; A5 exercised a full 5-minute
  hold (and the run proves the session survives it, and a mid-window TTL renewal does not
  disturb it). The probe's own `TURN_WAIT_MS=90000` after the answer was ample
  (`answerToResponseMs=3460`).
- Client-side request ids in this run (event log): `initialize`=1, `session/new`=2,
  `session/set_model`=3, `session/prompt`=10; the CLI's own `session/request_permission`
  request id is `"0"`. The A4 dispatch rule (a response has no `method` field; route by
  method first) is what the probe uses — ids alone are ambiguous because both sides number
  their own requests from 0/1 per session.

## 5. Criterion table

| Criterion | Result | Evidence |
|---|---|---|
| Step 1: production `renewSandbox(id, 30m)` moves `expiresAt` forward, read with a real SDK round trip | PASS | §1: `19:37:45.103462Z → 19:37:51.354488Z` (forward 6251 ms), value equal to the manager's own log line (server receipt 1.0 ms after the SDK's estimate); Java asserts `isAfter` |
| Step 1: renewal semantics pinned (renew sets `expiresAt = now + X`, not additive) | PASS (recorded) | §1/§2: both renewals' new expiry = call instant + 30:00 (SDK's own "estimated expiration" lines agree; §2 receipt within 0.52 ms); §limitations notes larger-X clamping NOT VERIFIED |
| Step 2: request held pending ≥ 5 min and answered by `kind` | PASS | §2: `t_answer − t_pending = 300045 ≥ 300000`, `decision:"allow_once:proceed_once"`, `allowAlwaysSelections:0` |
| Step 2: host renewal executed while the request was still pending (interleaving) | PASS | §2: sandbox-clock bracket `[1789672090776..1789672092282]` strictly inside `[t_pending 1789672087833, t_answer 1789672387878]`; `withinPendingWindow:true`; Java asserts both bounds; state after renewal still `phase=PENDING` |
| Step 2: mid-window renewal also moves `expiresAt` forward | PASS | §2: `19:37:51.354488Z → 19:38:11.965220Z` (forward 20610 ms) |
| Step 2: turn completes after the hold and the CLI still executes | PASS | §2: `stopReason:"end_turn"`, `t_prompt_end > t_answer`, probe-side check `exists:true contentOk:true content:"renewed"`, **and** the driver's own host-side read of the file returns exactly `renewed` (`[A5] STEP2 host-side read of /tmp/a5/answer-under-renewal/written.txt -> "renewed"` in the run console, asserted Java-side) — an independent observation, not the probe's self-report alone |
| Step 2: probe exit and result markers | PASS | §Raw verdict lines: 5 `[A5] PASS`, 0 FAIL, `A5-WAIT-RENEWAL-RESULT: PASS`, `A5_SCRIPT_EXIT=0`; Java asserts each marker |
| Step 3: effective-model carrier pinned | PASS (recorded) | §3: `response:prompt result._meta.quota.model_usage[0].model = "efficient"` |
| Step 3: what is NOT observable recorded | PASS (recorded) | §3: `creditFieldMatches:[]`; no *effective*-model field outside the prompt response — the only other model fields are the `session/new` model-menu entries (`modelId` options, not the effective model); zero usage counters; no `session_started` |
| Step 4: prompt wall clock and deadline derivation recorded | PASS (recorded) | §4: `promptSentEpochMs`/`promptResponseEpochMs`, `promptWallClockMs=313233`, derivation rule |
| Reading `expiresAt` of a running sandbox | PASS (finding) | §1: `resumer()` → 409 Conflict (SDK-logged) on all four reads, `connector()` attach works and was used for all 4 reads |
| Model pinned to `efficient` (zero-credit gate) | PASS | pasted console line `[A5] sandbox … ready (model pin efficient)`; summary JSON `"model":"efficient"`, `modelSet:"accepted"`, `modelObservability.effectiveModel:"efficient"`; harness default `efficient`, fails closed; `QODER_E2E_ALLOW_PAID` not set |
| PAT never in argv / files / output | PASS | §6 leak check (8 paths, counts 0) |
| Maven run green | PASS | Provenance: pasted `Tests run: 864` unit lane, `Tests run: 1 … Time elapsed: 338.5 s`, `BUILD SUCCESS`, `Total time: 07:01 min` |
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
  capture, the Failsafe report or this document. The same redacted lines are mirrored to
  `/tmp/a5/console.log` inside the sandbox (durable, best-effort), which the driver dumps
  into the Failsafe console only if the post-probe section fails.
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
  /c/Users/User/AppData/Local/Temp/a5-run3-retry-console.log
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
/c/Users/User/AppData/Local/Temp/a5-run3-retry-console.log:0
```

All counts 0 (`grep` exit code 1: no match in any file). Positive control for the checker
itself (count only, no value printed): pointing the same `grep` at the pattern file yields
`1`, so the pattern file is effective. The `a5-run3-retry-console.log`
entry is this run's transient Maven console capture on the host (under the OS temp dir,
outside the repo); it is not part of this evidence — the pasted console block in
§Provenance is.

## 7. Limitations

1. **Single green evidence run.** One sandbox, one ACP session, one 5-minute hold
   (2026-09-18). The interleaving and renewal readings are from that run; the pre-Maven dev
   loop was not used for any number quoted here. (A first gate attempt the same day flaked
   in the unrelated unit lane — `CircuitBreakerTest`, timing-sensitive, files untouched by
   this work — before the A5 E2E started and produced no A5 evidence; the green re-run
   above is the recorded one.)
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
   (13,508 bytes, no newline). The decisive markers are intact (marker counts above) and
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

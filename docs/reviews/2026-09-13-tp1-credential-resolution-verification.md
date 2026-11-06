# TP1 credential-resolution verification (live stack)

Date: 2026-09-13
Scope: Task 4 of `docs/superpowers/plans/2026-09-13-tool-availability-tp1-credential-resolution.md`
Code under verification: `c62b468` (`test(sdd): declare a git credential in the fixtures the TP1 gate now requires`)
Verdict: **PASS for the credential precondition and the fail-fast gate**; **a full SDD run through to a real GitHub push is NOT VERIFIED**.

This is a verification-only task. No production code was changed.

## What was verified

The SDD git handoff must resolve its GitHub credential from the tool-pack credential store
(`pack-git-0001`), not from a raw environment variable, and `WorkflowTemplateService.instantiateTemplate`
must refuse a `{repoUrl}`-declaring template before a chain exists when no credential resolves.

| # | Claim | Result |
|---|-------|--------|
| 1 | With no credential stored, instantiation is refused with an actionable `GITHUB_TOKEN` error | PASS |
| 2 | A credential stored through `POST /api/v1/packs/{id}/credentials` opens that gate | PASS |
| 3 | A credential present only in the store is sufficient and is consulted (both `GITHUB_TOKEN` and `GH_TOKEN` unset) | PASS |
| 4 | Functionally clearing the credential closes the gate again | PASS |
| 5 | Full SDD handoff through to a real GitHub push | NOT VERIFIED |

Claim 3 is deliberately scoped to what this evidence measures. Both `GITHUB_TOKEN` and `GH_TOKEN`
were unset, so store-before-env and store-after-env are indistinguishable here: the file proves a
store-only credential is consulted and is sufficient, not precedence over a populated environment.

## Precondition (checked, not assumed)

The Step 4 proof only holds if no `GITHUB_TOKEN`/`GH_TOKEN` is exported or in `.env`.

```
$ grep -E "^(GITHUB_TOKEN|GH_TOKEN)=" .env || echo "PASS: neither name is in .env"
PASS: neither name is in .env

$ env | grep -E "^(GITHUB_TOKEN|GH_TOKEN)=" || echo "PASS: neither name is in the process environment"
PASS: neither name is in the process environment
```

Both checks pass. The backend was launched from this same shell via `scripts/start.ps1`, so it
inherits that clean environment. The claim that only `./.env` and `./.env.example` exist is
supported by this command and its output, run as part of the same precondition check:

```
$ find . -maxdepth 3 -name ".env*" -not -path "./node_modules/*" -not -path "*/node_modules/*"
./.env
./.env.example
```

`.env.example` is **tracked** — `git ls-files` lists it and `.gitignore:4` un-ignores it
(`!.env.example`) — and it was not consulted as a source of truth. The Step 4 proof is
therefore valid.

Credential encryption runs in dev mode in this environment, so `enc_value` is Base64, not AES-GCM:

```
2026-09-13T17:22:27.369+08:00  WARN 18408 --- [aria-conductor] [           main] i.a.c.e.credential.PackCredentialCipher  : PACK_CREDENTIAL_KEY not set — credentials stored as Base64 (dev mode only)
```

## Step 1: rebuild and restart

```
$ pwsh -NoProfile -File scripts/stop.ps1
Stopping Aria Conductor services...
  stopped backend (PID 23548)
  stopped frontend (PID 13296)
  stopped OpenSandbox
Done.

$ pwsh -NoProfile -File scripts/start.ps1 -NonInteractive
...
  Aria Conductor - READY
  Topology : local-dev (backend + frontend on host)
  Provider : opencode
  Runtime  : podman (explicit)
  Dashboard  : http://localhost:5173                    [OK]
  Backend    : http://localhost:8080                    [OK]
  OpenSandbox: http://localhost:8090                    [OK]
All services healthy.
```

`start.ps1` runs a reactor-wide `mvn install -DskipTests` before launching, so this restart also
picked up Tasks 1-3 (`8aa788b`, `c830ba2`, `a462e4e`, `cae0f5f`, `c62b468`).

## Baseline database state

The H2 database is a persistent file (`jdbc:h2:file:./data/act_db`), so the baseline was measured
rather than assumed:

```
$ curl -s -X POST http://localhost:8080/api/v1/dev/sql/execute \
    -H 'Content-Type: application/json' \
    -d '{"sql":"SELECT id, pack_id, agent_id, cred_key, enc_value, updated_at FROM pack_credentials"}'
{"statementType":"SELECT","columns":["ID","PACK_ID","AGENT_ID","CRED_KEY","ENC_VALUE","UPDATED_AT"],
 "rows":[],"rowCount":0,"truncated":false, ...}
```

Zero credential rows. The chain baseline, needed to prove Steps 2 and 5 are side-effect free:

```
$ curl -s -X POST http://localhost:8080/api/v1/dev/sql/execute \
    -H 'Content-Type: application/json' \
    -d '{"sql":"SELECT id, name, status, created_at FROM workflow_chains ORDER BY created_at"}'
rows: [{"ID":"11584a7c-16e6-41cc-af4b-37ffc9dc6e4c","NAME":"development-workflow-instance",
        "STATUS":"WAITING_APPROVAL","CREATED_AT":"2026-09-13T07:15:07.833+00:00"}]
rowCount: 1
```

One pre-existing chain, created at 07:15:07 UTC — more than two hours before this verification.

## Step 2: the gate is closed with no credential stored

```
$ POST_BODY='{"parameters":{"issueRef":"42","issueRepo":"HappyLiang12/aria-conductor","repoUrl":"https://github.com/HappyLiang12/aria-conductor"}}'
$ curl -s -o /tmp/tp1-closed.json -w "HTTP %{http_code}\n" \
    -X POST http://localhost:8080/api/v1/knowledge/d0000001-0000-0000-0000-000000000001/instantiate-workflow \
    -H 'Content-Type: application/json' -d "$POST_BODY"
HTTP 400

$ cat /tmp/tp1-closed.json
This template hands off to GitHub, but no GitHub credential is configured. Store a GITHUB_TOKEN in the git tool pack (Configure -> Skills & Tools), or set the GITHUB_TOKEN environment variable.
```

PASS: HTTP 400, and the body names `GITHUB_TOKEN`. `repoUrl` was passed explicitly so the
pre-existing R8-F1 `opencode.repo-url` branch could not mask this result.

Side-effect check — the chain count was still 1 afterwards, at `09:20:31.577Z`, well after the
07:15:07 baseline row:

```
$ curl -s -X POST http://localhost:8080/api/v1/dev/sql/execute \
    -H 'Content-Type: application/json' -d '{"sql":"SELECT COUNT(*) AS n FROM workflow_chains"}'
rows: [{"N":1}]
```

This confirms the refusal happens before `workflowService.createAndStart`, as Task 3 intends.

## Step 3: store a credential through the tool-pack API

```
$ curl -s -X POST http://localhost:8080/api/v1/packs/pack-git-0001/credentials \
    -H 'Content-Type: application/json' \
    -d '{"key":"GITHUB_TOKEN","value":"ghp_verification_placeholder"}'
{"key":"GITHUB_TOKEN","status":"stored"}
```

PASS. The brief predicted `{"status":"stored","key":"GITHUB_TOKEN"}`; the key order differs because
`Map.of` does not guarantee iteration order. The content is equivalent and JSON object ordering is
insignificant.

The row landed as pack-global (`agent_id` NULL) with a Base64 `enc_value`:

```
$ curl -s -X POST http://localhost:8080/api/v1/dev/sql/execute \
    -H 'Content-Type: application/json' \
    -d '{"sql":"SELECT id, pack_id, agent_id, cred_key, enc_value FROM pack_credentials"}'
rows: [{"ID":"ab64a22a-1b4a-4199-a1bd-b79fcf3da252","PACK_ID":"pack-git-0001","AGENT_ID":null,
        "CRED_KEY":"GITHUB_TOKEN","ENC_VALUE":"Z2hwX3ZlcmlmaWNhdGlvbl9wbGFjZWhvbGRlcg=="}]
```

`Z2hwX3ZlcmlmaWNhdGlvbl9wbGFjZWhvbGRlcg==` decodes to `ghp_verification_placeholder`. Dev-mode
Base64 is not encryption, which is the documented fallback when `PACK_CREDENTIAL_KEY` is unset.

## Step 4: restart, then the gate opens (partial verification)

`GitBranchConfig.gitBranchService` is a singleton `@Bean`, so the token is resolved once at context
startup and a runtime credential write cannot affect the already-constructed bean. A restart is
required, and was performed. No credential-delete route exists yet, and the precondition above was
re-confirmed before this step.

```
$ pwsh -NoProfile -File scripts/stop.ps1   # stopped backend (PID 23152), frontend (PID 9516), OpenSandbox
$ pwsh -NoProfile -File scripts/start.ps1 -NonInteractive
  Backend    : http://localhost:8080                    [OK]
All services healthy.
```

Evidence that the credential now resolves, as a negative observation on the same startup sequence
that produced the warning in Step 5:

```
$ grep -n "GitHub credential\|GitBranchService\|deprecated GH_TOKEN" .run/backend.log | tail -10
(no output)
```

The `No GitHub credential resolved` warning did not appear, so the no-op bean was not selected.

The Step 2 request then returned HTTP 200 and created a chain:

```
HTTP 200
{"id":"15353770-423e-4be7-a6df-379f8504ace8","name":"development-workflow-instance","status":"RUNNING",
 "currentStepIndex":0,"totalSteps":3,
 "steps":[{"index":0,"agentId":"ba000000-0000-0000-0000-000000000001","status":"RUNNING",
           "runId":"e85503f4-e5b5-4a3e-b042-1cf567c2934a"}, ...],
 "createdAt":"2026-09-13T09:22:49.173309700Z", ...}
```

PASS on the point under test: the `GITHUB_TOKEN` refusal is gone, and the request proceeds into a
real chain with a real BA run. This is the behaviour change Task 3 delivers.

### Unpredicted result: the chain was killed by a startup race, not cancelled

The cancel was refused because the chain had already reached a terminal state:

```
$ curl -s -X POST http://localhost:8080/api/v1/workflows/15353770-423e-4be7-a6df-379f8504ace8/cancel
{"status":400,"error":"Bad Request",
 "message":"Cannot cancel workflow in status FAILED; must be RUNNING, PENDING or WAITING_APPROVAL"}
HTTP 400
```

The cause is unrelated to credentials. The timestamps below are observed; the mechanism that links
them, and the identity of the run that was reclaimed, are `INFERRED` from the pointers given. Spring
Boot publishes `ApplicationReadyEvent` only after all `ApplicationRunner`/`CommandLineRunner` beans
have finished, and `AriaDefaultAgentInitializer` — `@Order(Ordered.HIGHEST_PRECEDENCE)`, an
`ApplicationRunner` whose `run` method ends by logging `ADK instance for Aria is ready` after the
pre-warm — blocks the `main` thread for ~18 seconds
(`agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/init/AriaDefaultAgentInitializer.java:45-46,263,409`,
`INFERRED`; the ~18 s gap itself is observed below: `Started ActApplication` at 20.281 s, Aria ADK
ready at 17:22:51.739). `scripts/start.ps1` reports READY as soon as Tomcat answers on 8080, so
there is a window in which the API accepts work but `recoverOrphanedRuns()` has not yet run. The
request landed inside that window:

```
2026-09-13T17:22:33.022+08:00  INFO 18408 --- [aria-conductor] [           main] io.aria.conductor.ActApplication         : Started ActApplication in 20.281 seconds (process running for 20.977)
2026-09-13T17:22:51.739+08:00  INFO 18408 --- [aria-conductor] [           main] i.a.c.a.i.AriaDefaultAgentInitializer    : ADK instance for Aria is ready (health check passed)
2026-09-13T17:22:51.751+08:00  INFO 18408 --- [aria-conductor] [           main] i.a.c.execution.engine.AgentLoopEngine   : Recovering 1 orphaned run(s) left by backend restart
2026-09-13T17:22:51.800+08:00  INFO 18408 --- [aria-conductor] [           main] i.a.c.e.listener.WorkflowAutoChainer     : Workflow auto-chain triggered: chain=15353770-423e-4be7-a6df-379f8504ace8, step=0, status=FAILED
2026-09-13T17:22:51.806+08:00  INFO 18408 --- [aria-conductor] [           main] i.a.c.agent.service.WorkflowService      : Workflow chain failed: id=15353770-423e-4be7-a6df-379f8504ace8, step=0
```

Observed in the block above: the chain created for the request (`15353770-...`) is logged `FAILED`
at step 0 at 17:22:51.800, 2.5 s after creation, immediately after `Recovering 1 orphaned run(s)`
and before the step could have produced anything.

`INFERRED`, not observed: `recoverOrphanedRuns()` reclaimed run `e85503f4` and marked it `FAILED`
with `Run orphaned by backend restart`. The recovery log line does not name a run id, and no query
of run `e85503f4`'s own row appears in this file (the only runs read here is run `f8852776`'s, in
Step 4's re-run), so the attribution rests on code and timing:

- `recoverOrphanedRuns()` marks every run found in `RUNNING`/`INITIALIZING` as `FAILED`, sets the
  message text, and publishes a `RunCompletedEvent` for each —
  `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:363-370`,
  with the message text at `AgentLoopEngine.java:366`.
- The recovery line reports exactly 1 orphaned run, and the request had just created exactly 1 live
  run, `e85503f4` (step 0 of chain `15353770-...`), per the instantiate response above.
- In production code `recoverOrphanedRuns()` is invoked only from
  `AgentLoopEngine.onApplicationReady()`, itself bound to `ApplicationReadyEvent`
  (`AgentLoopEngine.java:350-353`), so it runs once per context start; a second instantiate after
  the event has fired is unaffected.

This is a pre-existing startup race in `AgentLoopEngine`, independent of TP1, and is reported here
rather than worked around.

**The race killed the database row but not the run.** The run thread continued and finished:

```
2026-09-13T17:24:25.337+08:00  INFO 18408 --- [aria-conductor] [    virtual-162] i.a.c.execution.engine.AgentLoopEngine   : Completing run: runId=e85503f4-e5b5-4a3e-b042-1cf567c2934a, status=COMPLETED, iterations=1, tokens=474
```

leaving the run `COMPLETED` with `totalTokensUsed: 474` and `iterationCount: 1`, and — `INFERRED`
(see below) — still carrying the stale `errorMessage: "Run orphaned by backend restart"`; its chain
`FAILED`.

`status=COMPLETED`, `iterations=1` and `tokens=474` are read directly from the completion line
above, and the chain's `FAILED` status from the auto-chainer line above. The stale `errorMessage` is
`INFERRED`, not observed: no query of run `e85503f4`'s own row appears in this file, so whether the
message was overwritten by completion is unproven here. `AgentLoopEngine.java:1487-1493` writes
`status`, `iterationCount` and `totalTokensUsed` on completion and only *sets* `errorMessage` when
`ctx.getErrors()` is non-empty, so a message written earlier can survive a successful completion —
which is why the stale value is expected on a run that terminated `COMPLETED`.

Real LLM cost was incurred (474 tokens) — stated plainly rather than hidden. No GitHub push
occurred; the BA step only writes `/workspace/spec.md`.

### Step 4 re-run outside the race window

The retry was issued after `ApplicationReadyEvent` had fired, so it was not reclaimed:

```
HTTP 200
CHAIN_ID=284c393f-d6ae-468e-9117-800d77b3448e
--- immediate status ---
"status":"RUNNING"
--- cancel ---
{"id":"284c393f-d6ae-468e-9117-800d77b3448e","status":"CANCELLED","currentStepIndex":0,"totalSteps":3,
 "steps":[{"index":0,"status":"SKIPPED","runId":"f8852776-1789-45f4-8b90-40212f3b3e5f"}, ...],
 "createdAt":"2026-09-13T09:24:13.052948Z","completedAt":"2026-09-13T09:24:14.605338Z", ...}
HTTP 200
```

The documented route `POST /api/v1/workflows/{id}/cancel` (`WorkflowController:44`) worked; the
`cancelWorkflow` helper in `act-dashboard/e2e/fixtures.ts` was not needed. Chain `284c393f` was
cancelled ~1.5 s after creation.

The chain-level cancel left the underlying BA run `RUNNING`, so it was cancelled directly via
`POST /api/v1/runs/{id}/cancel` (`RunController:84`):

```
$ curl -s -X POST http://localhost:8080/api/v1/runs/f8852776-1789-45f4-8b90-40212f3b3e5f/cancel
HTTP 200
$ curl -s http://localhost:8080/api/v1/runs/f8852776-1789-45f4-8b90-40212f3b3e5f
"status":"CANCELLED", "totalTokensUsed":0, "iterationCount":0
```

Zero tokens: the run was terminated before its first LLM call. This asymmetry between chain-level and
run-level cancellation is an observation about the existing cancellation path, not part of TP1.

Step 4 is reported as a **partial verification**: the credential precondition no longer blocks, which
is the whole point of the step, but the chain did not get to run a clean SDD sequence because the
first attempt tripped the startup race and the second was deliberately cancelled.

## Step 5: clear the credential and the gate closes again

There is no credential-delete endpoint until TP2 (spec §8.1). An empty value is the interim clear:
`resolve` returns a blank string and `GitBranchConfig` treats it as absent.

```
$ curl -s -X POST http://localhost:8080/api/v1/packs/pack-git-0001/credentials \
    -H 'Content-Type: application/json' -d '{"key":"GITHUB_TOKEN","value":""}'
{"status":"stored","key":"GITHUB_TOKEN"}

$ curl -s -X POST http://localhost:8080/api/v1/dev/sql/execute \
    -H 'Content-Type: application/json' \
    -d '{"sql":"SELECT id, cred_key, enc_value, updated_at FROM pack_credentials"}'
rows: [{"ID":"ab64a22a-1b4a-4199-a1bd-b79fcf3da252","CRED_KEY":"GITHUB_TOKEN","ENC_VALUE":"",
        "UPDATED_AT":"2026-09-13T09:25:13.422+00:00"}]
```

The row is **updated in place**, not deleted — the same id `ab64a22a-...` with an empty `enc_value`.
Note that `resolve` finds this row and calls `cipher.decrypt("")`, which returns `""` in dev mode;
`isBlank()` then classifies it as absent. A blank row is therefore only *functionally* cleared.

After a restart (`Backend : http://localhost:8080 [OK]`) the no-op bean was selected:

```
2026-09-13T17:26:50.780+08:00  WARN 12440 --- [aria-conductor] [           main] i.a.c.execution.git.GitBranchConfig      : No GitHub credential resolved from the git tool pack (pack-git-0001) or GITHUB_TOKEN: GitBranchService is disabled — Git branch operations will throw GitBranchException when invoked
```

and the Step 2 request was refused again, with the chain count unchanged at 3:

```
HTTP 400
This template hands off to GitHub, but no GitHub credential is configured. Store a GITHUB_TOKEN in the git tool pack (Configure -> Skills & Tools), or set the GITHUB_TOKEN environment variable.

$ ... '{"sql":"SELECT COUNT(*) AS n FROM workflow_chains"}'
rows: [{"N":3}]
```

PASS: the gate closes again, and the refusal again creates no chain.

**A credential row with an empty value remains in the database** (id `ab64a22a-1b4a-4199-a1bd-b79fcf3da252`).
TP2's `DELETE /api/v1/packs/{id}/credentials/{key}` is what removes it properly.

## Explicitly NOT VERIFIED

A full SDD run through to a real GitHub push remains **NOT VERIFIED**. No valid GitHub token exists
in this environment — the only credential used was the placeholder `ghp_verification_placeholder`,
which GitHub would reject. What was proven is the credential precondition and the fail-fast gate:
that a stored pack credential clears the gate and a cleared one restores it. Branch creation,
`getFile`/`putFile`, and the spec-approval handoff were never exercised.

### Scope note: the sandbox environment is a second consumer of the credential

TP1 feeds the credential store into the `GitBranchService` bean only.
`application.yml`'s `sandbox-env.GH_TOKEN` still sources from the process environment alone, and is
never read from the credential store, so an in-sandbox `gh`/`git` step runs without a token even
when this gate is open. This file verifies nothing about in-sandbox credential availability, and
the open gate proven in Step 4 must not be read as in-sandbox GitHub access. The gap is deliberate
and recorded in the design spec §15; TP2's "one resolution point" work owns closing it.

### Scope note: the quoted message text is the pre-fix wording

The HTTP 400 bodies quoted verbatim in Step 2 and Step 5 are captures of the code under
verification (`c62b468`). The branch's final fix round replaced that wording with
`io.aria.conductor.common.git.GitCredentialGuidance.REQUIRED_MESSAGE`, which names the same
`GITHUB_TOKEN` variable and adds the restart requirement. The transcript is left unedited as
observed; only the wording changed, and the message still contains `GITHUB_TOKEN`, which is what
the PASS criteria above assert.

## Environment facts

1. The backend holds an H2 file lock on `agent-control-tower/act-app/data/act_db.mv.db` while it
   runs, so `DevSqlControllerH2ProfilePresenceTest` errors in the unit lane (H2 SQL 90020) whenever
   the stack is up. This verification therefore ran with the stack up and no Java test suite
   concurrently. No Java/Maven test suite was run during this task.
2. `mvn test -pl act-app` does **not** run the `*IntegrationTest` classes — Surefire excludes them
   and Failsafe runs them. The correct command for that lane is
   `mvn verify -pl act-app -Dspring.profiles.active=h2`.

## Final environment state

- Stack left **UP**: backend `http://localhost:8080` HTTP 200, dashboard `http://localhost:5173`
  HTTP 200, OpenSandbox `http://localhost:8090`.
- `pack_credentials`: exactly 1 row — `ab64a22a-1b4a-4199-a1bd-b79fcf3da252`, pack `pack-git-0001`,
  agent NULL, key `GITHUB_TOKEN`, `enc_value` blank.
- `workflow_chains`: 3 rows — `11584a7c-...` WAITING_APPROVAL (pre-existing, untouched),
  `15353770-...` FAILED (Step 4 attempt 1), `284c393f-...` CANCELLED (Step 4 attempt 2).
- `runs`: `e85503f4-...` COMPLETED / 474 tokens / 1 iteration (observed in the completion log line);
  `f8852776-...` CANCELLED / 0 tokens (observed in the run query above). `INFERRED`, not observed —
  no query of run `e85503f4`'s own row appears in this file: `e85503f4-...` still carries the stale
  `errorMessage: "Run orphaned by backend restart"` (`AgentLoopEngine.java:1487-1493`).
- No source file was modified by this task.

## Commands to reproduce

```bash
pwsh -NoProfile -File scripts/stop.ps1
pwsh -NoProfile -File scripts/start.ps1 -NonInteractive

# Step 2 / Step 4 / Step 5 probe
POST_BODY='{"parameters":{"issueRef":"42","issueRepo":"HappyLiang12/aria-conductor","repoUrl":"https://github.com/HappyLiang12/aria-conductor"}}'
curl -s -w "HTTP %{http_code}\n" -X POST \
  http://localhost:8080/api/v1/knowledge/d0000001-0000-0000-0000-000000000001/instantiate-workflow \
  -H 'Content-Type: application/json' -d "$POST_BODY"

# Step 3 / Step 5 credential write
curl -s -X POST http://localhost:8080/api/v1/packs/pack-git-0001/credentials \
  -H 'Content-Type: application/json' -d '{"key":"GITHUB_TOKEN","value":"ghp_verification_placeholder"}'

# cancel
curl -s -X POST http://localhost:8080/api/v1/workflows/<chainId>/cancel
curl -s -X POST http://localhost:8080/api/v1/runs/<runId>/cancel

# database inspection (h2 profile only)
curl -s -X POST http://localhost:8080/api/v1/dev/sql/execute \
  -H 'Content-Type: application/json' -d '{"sql":"SELECT * FROM pack_credentials"}'
```

`.run/backend.log` is a runtime log (not committed); the log lines quoted above are inlined verbatim
so the evidence does not depend on that file.

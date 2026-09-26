# Live four-combination core matrix — Task 20 evidence

Raw captured output of `e2e/agent-core/live-matrix.*` runs on this operator machine (Windows,
git bash, JDK 21.0.11, Maven 3.9.6, Node v24.14.1, qodercli 1.1.61, opencode 1.14.31), plus the
explicit BLOCKED/FAILED records for every combination that did not complete.

Redaction: every block below is verbatim runner output; the runner's own placeholders
(`<USER>`, `<RUN>`) replace operator paths, and the Qoder PAT never appears (the runner reads it
from a file into memory and sends it only as a loopback HTTP body; it is never printed, logged,
passed in argv or written to any artifact).

## 1. Runner and red test (brief Step 2)

`e2e/agent-core/live-matrix.sh`, `e2e/agent-core/live-matrix.ps1` (entry points: fail-closed
prerequisite checks, `--core --mode --model --workspace --repo --evidence --pat-file --paid-opt-in`)
and `e2e/agent-core/live-matrix.mjs` (the shared driver: backend lifecycle through the repository
scripts, real REST seeding, independent verification, evidence capture).

Raw output of `bash e2e/agent-core/live-matrix.sh --core qoder --mode HOST --model efficient
--workspace .tmp-t20 --repo . --evidence …` with no credential available:

```
live-matrix: MISSING PREREQUISITE: qoder runtime credential missing: no readable PAT file at C:/nonexistent/aria-live/.qoder-pat (--pat-file/ARIA_LIVE_PAT_FILE)
live-matrix refused before launching anything (core=qoder mode=HOST)
EXIT=2
```

```
live-matrix: MISSING PREREQUISITE: qoder runtime credential missing: the PAT file at .tmp-t20/empty-pat is empty
live-matrix refused before launching anything (core=qoder mode=HOST)
EXIT=2
```

```
live-matrix: MISSING PREREQUISITE: model-pin refusal: the qoder core is pinned to 'efficient' (requested 'premium-plus'); a paid model needs the explicit opt-in (--paid-opt-in or ARIA_LIVE_PAID_MODEL_OPT_IN=1)
live-matrix refused before launching anything (core=qoder mode=HOST)
EXIT=2
```

```
live-matrix: MISSING PREREQUISITE: opencode model-provider credential missing: no run-owned credential in this environment (DEEPSEEK_API_KEY/LLM_API_KEY/OPENCODE_API_KEY unset and agent-control-tower/.env empty)
live-matrix refused before launching anything (core=opencode mode=HOST)
EXIT=2
```

No launch happened in any of the four: the refusal is emitted by the prerequisite gate, exit code
2 (refused) / 3 (environment blocked). Raw files: `docs/reviews/evidence/2026-09-22-live-matrix/red-*.txt`.

## 2. Matrix table

| Combination | Status | Evidence |
|---|---|---|
| `qoder/HOST` | **FAILED** — real runs dispatched and frozen to `qoder/HOST`, then the host runtime failed to authenticate its control secret; no file written, no approval ask observed | §3 |
| `qoder/SANDBOX` | **BLOCKED (environment)** | §4 |
| `opencode/HOST` | **BLOCKED (environment)** | §4 |
| `opencode/SANDBOX` | **BLOCKED (environment)** | §4 |

## 3. qoder/HOST — real runs (headline acceptance)

Backend started through the repository script (`scripts/start-backend.ps1 -SkipSandbox
-AdkProvider opencode`, Host mode, no container runtime), health `{"status":"UP"}`, the PAT stored
through `PUT /api/v1/adk/providers/qoder/credential` → HTTP 200, agents seeded through
`POST /api/v1/agents` (`adkProvider=qoder`, `executionMode=HOST`, `workspaceMode=WORKTREE|DIRECT`)
and the coding task dispatched through `POST /api/v1/runs`. Verbatim runner output of the last run:

```
core=qoder mode=HOST os=win32 x64 cli=C:\Users\<USER>\.qoder\bin\qodercli\qodercli.exe cliVersion=1.1.61
requested model=efficient (qoder pin efficient); usage evidence is recorded, never asserted as zero cost
qoder runtime credential stored (HTTP 200); the secret is never printed
agent created: id=b2eaaf0d-5afd-4681-aec4-ad74dcd24215 core=qoder mode=HOST workspaceMode=WORKTREE
agent created: id=b62a0328-8b7d-47b1-843b-05bf2a208ad0 core=qoder mode=HOST workspaceMode=DIRECT
run coding-task: id=f0b3508b-cf0a-4478-aab5-ecc806d80ae7 status=PENDING
```

Backend log for that run (`docs/reviews/evidence/2026-09-22-live-matrix/qoder-HOST.txt.backend.log`):

```
2026-09-27T02:38:29.996+08:00  INFO 12344 --- [aria-conductor] [virtual-67] i.a.c.execution.runtime.CoreRunLauncher : Froze run f0b3508b-cf0a-4478-aab5-ecc806d80ae7 to core qoder/HOST
2026-09-27T02:39:02.873+08:00 ERROR 12344 --- [aria-conductor] [virtual-67] i.a.c.execution.engine.AgentLoopEngine : Coordinated core run failed: runId=f0b3508b-cf0a-4478-aab5-ecc806d80ae7
java.lang.IllegalStateException: The runtime endpoint of run f0b3508b-cf0a-4478-aab5-ecc806d80ae7 did not authenticate the run's control secret (no authenticated answer within PT30S (last: ConnectException: null)); the runtime was terminated instead …
	at io.aria.conductor.execution.runtime.host.HostExecutionBackend.authenticationFailure(HostExecutionBackend.java:440)
	at io.aria.conductor.execution.runtime.host.HostExecutionBackend.authenticateRuntimeEndpoint(HostExecutionBackend.java:426)
	at io.aria.conductor.execution.runtime.host.HostExecutionBackend.launch(HostExecutionBackend.java:203)
2026-09-27T02:39:02.875+08:00  INFO 12344 --- [aria-conductor] [virtual-67] i.a.c.execution.engine.AgentLoopEngine : Completing run: runId=f0b3508b-…, status=FAILED, iterations=0, tokens=0
```

Independently observed consequences (runner assertions, not self-report): the run worktree WAS
provisioned (`…/data/workspaces/runs/worktrees/f0b3508b-…/README.md` exists), the probe file was
NOT created, the run never reached `COMPLETED`, no approval ask ever appeared, and no model call
was recorded (`iterations=0, tokens=0`). Criterion verdicts recorded by the runner:
`coding-task` FAIL (all four criteria), `worktree`, `approve-once`, `deny`, `expiry`, `cancel`,
`pause-resume`, `direct`, `no-fallback-sandbox`, `no-fallback-credential` — **NOT VERIFIED** (the
runs of this attempt never got past the runtime launch, so none of those behaviours could be
observed).

The same failure reproduced on every attempt (runs `4af6c972…`, `5018dc57…`, `f0b3508b…`; the
first one failed earlier, at workspace admission, before the runner absolutized the paths).

### 3.1 Configuration gaps the runner had to pin (each is a real finding)

The shipped defaults do not let a qoder/HOST run start under the documented Host path. Four
operator-visible values had to be supplied by the runner (recorded in the runner's
`backend environment pins` line and in the evidence JSON):

1. `aria.cores.qoder.model` defaults to `gpt-4o` (a paid model) — `CoreRuntimeConfiguration.java:201`.
   The runner pins `efficient`.
2. `aria.cores.qoder.bridge-entry` defaults to the cwd-relative `packages/qoder-acp-bridge/dist/main.js`
   (`CoreRuntimeConfiguration.java:199`); `mvn spring-boot:run -pl act-app` runs with cwd=`act-app`,
   where that path does not exist. The runner pins the built bridge's absolute path.
3. `aria.cores.qoder.executable` defaults to the bare name `qoder`
   (`CoreRuntimeConfiguration.java:200`), but the Qoder bridge refuses a `--cli` that is not an
   absolute existing executable path (`packages/qoder-acp-bridge/src/main.ts:229`), so the bridge
   exits and nothing ever listens on the run's loopback endpoint (the `ConnectException` above).
   The runner pins the resolved `qodercli.exe` absolute path.
4. `aria.workspaces.runtime-root` is cwd-relative (`CoreRuntimeConfiguration.java:76`) — pinned to
   an absolute root so the runner can verify the run-owned worktree on disk.

### 3.2 Start-path defect (blocks the documented Host start)

```
$ pwsh -NoProfile -File scripts/start-backend.ps1 -SkipSandbox            # documented Host path
Caused by: java.lang.IllegalStateException: Unsupported ADK provider: configured default 'qoder'
is not one of the registered providers [opencode]; the supported cores are exactly the registered
ones and there is no fallback
[INFO] BUILD FAILURE
```

```
$ bash scripts/start-backend.sh --skip-sandbox                            # POSIX equivalent
Caused by: java.lang.IllegalStateException: Unsupported ADK provider: configured default 'qoder'
is not one of the registered providers [opencode]; the supported cores are exactly the registered
ones and there is no fallback
[INFO] BUILD FAILURE
```

Both scripts pass `--adk.default-provider=qoder` in Host mode, but the only `AdkProvider` bean on
this revision is `OpenCodeAdkProvider` (`providerId()` → `opencode`), and `AdkProviderRegistry`
(`AdkProviderRegistry.java:48-53`) fails closed on a default it does not know. The runner therefore
started the same script with its own explicit `-AdkProvider opencode` parameter. No application
code was changed.

### 3.3 Environment note

A leftover Task 19 harness (`io.aria.conductor.app.e2e.CoreE2eApplication`, launched from
`%TEMP%/t19h`) was still holding port 8080 (PID 27788) and answered the first credential `PUT`
with 401; it was stopped (with its process tree) before the real backend could bind.
A stale vite from an earlier round still listens on 5173 and was left untouched.

## 4. BLOCKED combinations

`qoder/SANDBOX` (real PAT present, so the credential gate passed):

```
live-matrix: MISSING PREREQUISITE: OpenSandbox server not reachable at http://127.0.0.1:8090 (no 2xx /health response)
live-matrix: MISSING PREREQUISITE: no sandbox image present in the local docker/podman image stores
live-matrix BLOCKED by the environment before launching anything (core=qoder mode=SANDBOX)
EXIT=3
```

`opencode/HOST` and `opencode/SANDBOX` (identical refusal; the SANDBOX run stops at the credential
gate before the sandbox gate):

```
live-matrix: MISSING PREREQUISITE: opencode model-provider credential missing: no run-owned credential in this environment (DEEPSEEK_API_KEY/LLM_API_KEY/OPENCODE_API_KEY unset and agent-control-tower/.env empty)
live-matrix refused before launching anything (core=opencode mode=HOST)
EXIT=2
```

`docker` is installed but its engine is not running; `podman 5.8.3` is installed with no local
sandbox image; nothing listens on 8090. No SANDBOX criterion was evaluated — every SANDBOX
criterion is **NOT VERIFIED**.

## 5. Regression lanes (brief Step 3)

| Lane | Verbatim result | Status |
|---|---|---|
| `mvn -o -f agent-control-tower/pom.xml test -Dspring.profiles.active=h2` | `BUILD SUCCESS`, `Total time: 07:22 min`, module summary lines `133`, `259`, `1080`, `268 (4 skipped)`, `79`, `338 (4 skipped)`, `161`, `61` — all `Failures: 0, Errors: 0`, 8 skipped in total | PASS |
| `pnpm --dir agent-control-tower/act-dashboard test` | `Test Files 1 failed | 52 passed (53)`, `Duration 103.69s`; the three failures are in `src/components/__tests__/AriaPanel.test.tsx > AriaPanel slash-command skill handling` (`keyboard selection clears…`, `retry re-sends the original message…`, `mouse selection clears the input…`) | FAIL (3 tests, pre-existing component lane) |
| `pnpm --dir agent-control-tower/act-dashboard build` | `✓ built in 2.96s` (`FRONTEND_BUILD_EXIT=0`) | PASS |
| `pnpm --dir packages/mcp-server test` | `Test Files 20 passed (20)`, `Tests 156 passed (156)`, `Duration 8.53s` | PASS |
| `mvn -o -f agent-control-tower/pom.xml verify -Dskip.unit.tests=true -Dspring.profiles.active=h2` | started, still running when this report was written (log: `t20-integration-tier.log`) | **NOT VERIFIED** |
| Harness Playwright lane (Task 19 recipe: harness zip → boot → ported specs) | not started in this round | **NOT VERIFIED** |

## 6. Not verified (explicit)

- Every SANDBOX criterion for both cores (no OpenSandbox server, no sandbox image).
- Every `opencode/*` criterion (no run-owned model-provider credential).
- Every qoder/HOST behavioural criterion beyond "dispatch, admission, workspace provisioning and
  the failed runtime launch": approval round trip (approve-once re-ask, deny, expiry), cancel,
  pause/resume, Direct workspace, the no-fallback cases, and the coding task's file bytes.
- The integration tier and the Playwright harness lane (§5).
- The model actually used by a run (`observedModels: []` — no prompt call was recorded).
- Usage/cost: no usage evidence was produced (iterations=0, tokens=0). Nothing in this document
  asserts zero cost; the runs that failed made no recorded model call, and the PAT used by these
  runs must be rotated by the operator now that the live matrix has run.

The matrix verdict for Task 20 is therefore **not a pass**: `qoder/HOST` failed in the live
environment, three combinations are environment-blocked, two regression lanes were not evaluated.

# Qoder slice C — live E2E evidence (S1–S12 + regression lanes)

Slice C is the governed-surface acceptance slice of the qoder provider: approval intake, the ACP
companion record, decision dispatch and delivery, worker/operator authorization, and the review surface,
all exercised against a **real Qoder CLI on the zero-credit `efficient` model** (mandatory local E2E, plan
constraint `docs/superpowers/plans/2026-09-17-qoder-cli-provider.md:16`).

- **Authority for the verdicts in this file:** the final tree — `06caaf0` plus `39583b1`, `de9e41f`,
  `da99c9f`, `b782c20`, `f03211c`, `33e1a25`, `6b48899` (the fix wave), `6436d71` (UI pins), `7c8016a`
  (the cross-lane fixes) and `173d210` (a test-only assertion relaxation). The authoritative executions
  are **E14** (the full matrix, `harness-console14.log`, `SUMMARY.run13.md`, all six regression lanes
  green), **E15** (`SUMMARY.run14.md`, S5 and S9), **E16** (`SUMMARY.run15.md`, S10) and **E19**
  (`SUMMARY.run18.md`, S4), which together carry every scenario, lane and scan on that tree. E11–E13 and
  the earlier executions are disclosure only. The production code of `173d210` is identical to
  `7c8016a`; the only difference is the S4 spec's reason assertion, relaxed after E16 showed it was
  over-specified (see the S4 row).
- Multi-pass retries inside one Playwright step are disclosed per scenario; a scenario counts PASS only on
  a green final attempt.
- Every claim cites a committed artifact (log) or a runnable command with its captured output. Anything
  not directly observed is marked `INFERRED` with a pointer, and any criterion not evaluated is reported
  as NOT VERIFIED — never upgraded (`AGENTS.md`, Evidence Discipline).

## Environment and reproduction

```bash
export BACKEND_PORT=8097 SERVER_PORT=8097 VITE_PORT=5273 ARIA_MCP_PORT=8097
export OPENSANDBOX_PORT=8090 APPROVALS_TIMEOUT_MS=120000 CONTAINER_RUNTIME=podman
export QODER_MODEL=efficient QODER_E2E_MODEL=efficient
export API_URL=http://localhost:8097 BASE_URL=http://localhost:5273 OPENSANDBOX_URL=http://localhost:8090
export EVIDENCE_DIR=D:/project/aria-conductor/e2e/qoder/slice-c/live
# operator-local, never echoed, never committed:
export QODER_E2E_PAT="$(cat /c/Users/User/.qoder/qoder-pat.txt)"
export PACK_CREDENTIAL_KEY="$(cat /c/Users/User/c6-run/pack-key.txt)"

pwsh -NoProfile -File scripts/start.ps1 -Provider qoder   # token-auth mode, mints .run/mcp-token
bash e2e/qoder/slice-c/run-all.sh --rebuild-image         # full matrix
```

`PACK_CREDENTIAL_KEY` **must** be in the harness environment: S10 restarts the stack inside the harness,
and a restart without the key makes the credential API answer `503 KEY_NOT_CONFIGURED`, which fails S11
and S12 in their pre-body guard (observed in E9, fixed in the E10/E11/E12 launchers). The sandbox image is
rebuilt in-flight because `Ensure-QoderSandboxImage` never rebuilds an existing tag and the bridge `dist/`
is not committed. The in-file `# git:` header of every step log is authoritative; `<id>.run<N>.log`
numbering is the harness's next-free naming and can lag the console number.

Two environment facts matter when reading the failures below:
1. **The podman/WSL machine degrades intermittently.** When it does, the docker-compat archive PUT that
   OpenSandbox uses to inject execd returns `500 … "passing bulk input to subprocess: write |1: broken
   pipe"` and every sandbox creation dies with `SANDBOX_UNAVAILABLE`; the repair is
   `wsl --shutdown` + `podman machine start`, verified by
   `MSYS_NO_PATHCONV=1 podman machine ssh < e2e/qoder/slice-c/live/probe-opensandbox-archive.sh`
   (two `HTTP 200` lines). A healthy probe is a point-in-time fact, not a durable one.
2. **The dev database must be fresh enough for the UI budget.** With a 19.6 MB database holding 175 agents
   and 73 pending approvals the overview page exceeded the 5 s expect budget of an unrelated pre-existing
   spec; the same spec passes in 2 s on a fresh database (`pw-triage-e11.run3.log`). The dirty database
   from this series is parked as `act_db.mv.db.bak-e11dirty-1789758000`.
3. **The archive failure recurs, and the store was the strongest lead.** The E14–E18 window produced five
   separate degradations (E14 ten occurrences, E15 two, E17 two, E18 two, plus one observed directly by
   the probe at 06:07 returning `HTTP 500` twice). Four hypotheses were tested rather than assumed:
   sandbox-container accumulation (ten containers — refuted), VM memory (about 8 GB — refuted), VM disk
   (1% used, 950 GB free — refuted) and store accumulation (`podman system df`: 175 images, 5.24 GB, 99%
   reclaimable, sixteen dangling — supported). `podman system prune -f` then reclaimed **43.42 GB** of
   build cache and dangling layers produced by the night's repeated image rebuilds, after which the probe
   recovered and E19's S4 passed on the first attempt. The causal link remains a hypothesis, not a proven
   root cause. A lighter repair than the full VM reset was also verified:
   `podman compose up -d --force-recreate opensandbox-server` alone restores the endpoint in about thirty
   seconds, pointing at the long-lived OpenSandbox server's podman-client state as a second contributor.

## Harness executions (disclosure)

| Exec | Console | Summary | Tree | Verdict | Disposition |
|------|---------|---------|------|---------|-------------|
| E1 | `harness-console.log` | — (aborted) | pre-fix | n/a | Killed mid-S8 (R51); logs `.run1.log` kept as the pre-fix record. |
| E2 | `harness-console2.log` | `SUMMARY.run1.md` | `fd67e2c` | 13 pass / 8 FAIL | Discovery: seed-config type defect, strict-mode badge match, dispatch 409 races. |
| E3 | `harness-console3.log` | `SUMMARY.run2.md` | `0a18d96` | 17 pass / 4 FAIL | Discovery (R67): S3 locator defect, S11 platform-MCP read gap, regression-playwright fix wave. |
| E4 | `harness-console4.log` | `SUMMARY.run3.md` | `a4ed69a` | see its matrix | The previous authoritative run; superseded by E11/E12 on the fixed tree. |
| E5 | `harness-console5.log` | `SUMMARY.run4.md` | `8f5ed4d` | S4/S5/S9 PASS; S10/S11/S12 FAIL | Targeted `S4,S5,S9-S12`. |
| E6 | `harness-console6.log` | `SUMMARY.run5.md` | `6c70d1a` | S10 PASS; S11/S12 FAIL | Discovery (harness deadlock class). |
| E7 | `harness-console7.log` | `SUMMARY.run6.md` | `6c70d1a` | S10/S11/S12 FAIL | Aborted; the machine wedged and the operator repaired it. |
| E8 | `harness-console8.log` | `SUMMARY.run7.md` | `6c70d1a` | S10/S12 PASS; S11 FAIL | S11's failure is the R81 product defect (grant minted under the ACP-qualified name). |
| gate | `e9-gate-s11.console.log` | `SUMMARY.run8.md` | `06caaf0` | S11 PASS | Operator pre-gate after the R81 fix. |
| E9 | `harness-console9.log` | — (interrupted) | `06caaf0` | mixed | The host restarted mid-run; the Maven lane was interrupted and the console has no final EXIT. |
| E10 offline | `e10-offline.console.log` | — | `06caaf0` | 4 lanes PASS | `mvn clean test` 6:03 EXIT 0, `mvn verify` 9:21 EXIT 0, vitest 54 files EXIT 0, build EXIT 0. |
| E10 live | `e10-live.console.log` | `SUMMARY.run9.md` | `06caaf0` | S4/S5/S9/S10/S12 PASS; S11 FAIL; playwright FAIL | S11 never evaluated (sandbox creation died in the wedged VM); playwright triaged separately. |
| **E11** | `harness-console11.log` | `SUMMARY.run10.md` | **`6b48899`** | 16 PASS / 5 FAIL | **Full matrix on the fix wave.** S11 PASS; the five failures are classified below. |
| **E12** | `harness-console12.log` | `SUMMARY.run11.md` | **`6b48899`** | 4 PASS / 1 FAIL | Re-run of exactly E11's failures on the repaired VM and a fresh database. |
| E13 | `harness-console13.log` | `SUMMARY.run12.md` | `6b48899` | playwright lane re-run | Clean (`221 passed`); tests whether the in-lane interference of two workflow specs is stable — it did not recur. |
| **E14** | `harness-console14.log` | `SUMMARY.run13.md` | **`7c8016a`** | 16 PASS / 4 FAIL | **The full matrix on the final tree**: S1–S3, S6–S8, S11, S12 PASS **and all six regression lanes PASS** (the first clean lane run of the series); S4/S5/S9/S10 FAIL all at sandbox creation (`SANDBOX_EXECD_DISTRIBUTION_FAILED`, ten occurrences) while the VM was degraded. |
| **E15** | `harness-console15.log` | `SUMMARY.run14.md` | `7c8016a` | 2 PASS / 2 FAIL | `--only S4,S5,S9,S10` after a VM reset: **S5 and S9 PASS** (S9 is the live exercise of the request-changes stop and the slot hand-over); S4/S10 hit the archive class again. |
| **E16** | `harness-console16.log` | `SUMMARY.run15.md` | `7c8016a` | 1 PASS / 1 FAIL | `--only S4,S10`: **S10 PASS** (the restart-interrupt scenario, carrying `PACK_CREDENTIAL_KEY`); S4 failed on an over-specified spec assertion, not on the system (see the S4 row). |
| E17 | `harness-console17.log` | — | `173d210` | FAIL | `--only S4` after the spec fix: the archive endpoint degraded again within ten minutes (both runs died at sandbox creation). |
| E18 | `harness-console18.log` | — | `173d210` | FAIL | `--only S4` after a container-level repair (recreating `aria-opensandbox`): the endpoint degraded again within minutes. |
| **E19** | `harness-console19.log` | `SUMMARY.run18.md` | `173d210` | **PASS** | `--only S4` after `podman system prune -f` reclaimed **43.42 GB** of build cache and dangling layers: **S4 PASS** via the deadline path (`EXPIRED`, `deliveredState=CANCELLED`, late decide `409 EXPIRED`, file absent, run `COMPLETED`). |

## Scenario matrix (C0.8) — final verdicts

Each row cites the log that carries its green verdict on the fix-wave tree.

| # | Scenario | Verdict | Evidence |
|---|----------|---------|----------|
| — | preflight: health + credential + zero-credit pin | PASS | `preflight.run15.log` (E14) |
| — | rebuild-image: pinned qoder sandbox image | PASS | `rebuild-image.run7.log` (E14) |
| S1 | ordinary run on the pinned zero-credit model | PASS | `S1.run7.log` (E14) |
| S2 | write → ask → approve once → executes; second write asks again | PASS | `S2.run7.log` (E14) |
| S3 | deny → no side effect | PASS | `S3.run7.log` (E14) |
| S4 | expiry → reject delivered, typed 409 on a late decide, no stale allow | PASS | `S4.run14.log` (E19): ask `dfa43c4e` PENDING, then `EXPIRED` with `deliveryState=CANCELLED` and reason `expired before decision`, late decide `409 EXPIRED`, the file absent inside the live container, run `COMPLETED`. E16 showed the spec's original single-string reason assertion was over-specified (the run-end sweep is an equally legitimate expiry path) and it was relaxed in `173d210` with the rationale documented; every other assertion stayed strict |
| S5 | cancel during a pending permission (sandbox survival) | PASS | `S5.run11.log` (E15) |
| S6 | credential set/mask/remove/test | PASS | `S6.run7.log` (E14) |
| S7 | worker self-approval denied | PASS | `S7.run7.log` (E14) — the live-worker-token half remains NOT VERIFIED, see deltas |
| S8 | one-use write grant | PASS | `S8.run7.log` (E14) — the worker one-use replay half remains NOT VERIFIED, see deltas |
| S9 | Kanban-dispatched run; card moves; decision does not mark DONE | PASS | `S9.run11.log` (E15). Also the live exercise of the request-changes stop and the agent-slot hand-over |
| S10 | backend restart interrupts a pending ask | PASS | `S10.run15.log` (E16; carries `PACK_CREDENTIAL_KEY` across the in-harness restart) |
| S11 | platform-MCP read auto-allowed; write gated; executions run-correlated | PASS | `S11.run13.log` (E14) — with R81 and the whole approval fix wave in place |
| S12 | governance regression UI smoke | PASS | `S12.run12.log` (E14) |

## Regression lanes

| Lane | Command | Log | Verdict |
|------|---------|-----|---------|
| mvn-test | `mvn clean test -Dspring.profiles.active=h2` | `regression-mvn-test.run8.log` | **PASS** (E14) |
| mvn-verify | `mvn verify` | `regression-mvn-verify.run8.log` | **PASS** (E14). E11's occurrence failed in `act-mcp` Failsafe on `McpNoneModeIdentityIntegrationTest` with a Mockito `WrongTypeOfReturnValue: Boolean cannot be returned by getToken()` at `:88` — refuted as a regression: the wave changed exactly one file under `act-mcp/` (a string), `McpProperties` has no `getToken()`, and `mvn verify -pl act-mcp` alone is BUILD SUCCESS with that class 4/0 (`e11-postverify-act-mcp.run1.log`). |
| vitest | `pnpm test` | `regression-vitest.run6.log` | **PASS** (E14) |
| build | `pnpm build` | `regression-build.run6.log` | **PASS** (E14) |
| playwright | `npx playwright test` (QODER_E2E* stripped) | `regression-playwright.run8.log` | **PASS** (E14) — the first fully clean lane of the series. E11's hard failure (`overview-dashboard.spec.ts:28:3`) was dirty-database slowness (2.0 s on a fresh database, `pw-triage-e11.run3.log`), E12's two workflow-spec failures pass standalone 27/27 in 31.9 s (`pw-triage-e12.run1.log`, in-lane interference), and neither recurred here. |
| container-runtime | `pwsh … container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` | `regression-container-runtime.run6.log` | **PASS** (E14) |
| leak-scan | count-only PAT grep over every evidence file | `leak-scan.run18.log` | **PASS** — 0 hits / 330 files (E19) |

## The R81 defect and the review-driven fix wave

R81 was the one product defect that only a real run could expose: the approval side minted the one-use
worker write grant under the ACP-qualified name (`mcp__aria__store_knowledge`) while the enforcement seam
consumes it under the Spring AI `@Tool` name (`store_knowledge`), so every approved worker write was
denied `GRANT_REQUIRED` (live proof in E8's backend log: ask `f6867117` approved at 20:19:11, denial 61 ms
later). Fixed in `06caaf0`: `AcpPermissionCoordinator.grantBindingForDecision` returns a `GrantBinding`
(runtime tool name + frozen digest) so mint and consume share one key space; the focused S11 gate
(`S11.run9.log`, `SUMMARY.run8.md`) proved it live, and E11's S11 (`S11.run12.log`) re-confirmed it inside
the full matrix.

The integrated branch review then produced one Critical and eight Important findings; all were
independently verified before any fix (the Java-side verification is summarised in ledger sections R89/R91,
the bridge-side in R90) and all were fixed root-cause by the seven commits above, each under its own
scoped review:

- **`39583b1` (G1b)** — the provider now refuses a non-token MCP configuration (the token-auth premise was
  enforced only by the launcher before), honours a cancel that lands during sandbox preparation, stamps a
  one-way credential hash and re-validates it before reusing a sandbox, and derives its own deadline from
  the host-granted one.
- **`de9e41f` (G1a)** — the bridge's outbound redaction set now includes the runtime PAT (every
  CLI-supplied republished field passes `redact`, with a class-closure test over 15 carriers), it enforces
  the permission deadline locally when the host is gone, and it terminates the CLI's process group.
- **`da99c9f` (G2a)** — one approval authorizes at most one execution (the dequeue and the retry probe now
  share the per-key critical section, so a retry can never mint a second authorization after a consume),
  the consume path polls past lapsed entries, and approving a truncated ask is refused with a typed
  `UNDECIDABLE_ASK`.
- **`b782c20` (G3a)** — `request-changes` stops the linked run before re-dispatching, so the pending ask is
  expired and the provider's per-agent slot is released.
- **`f03211c` (G4)** — the one-run-per-agent guard is terminal-aware (it takes the slot over when the
  previous run is already terminal) so the re-dispatched run actually starts; the pending-abort record is
  written atomically with the registration; an unknown MCP configuration is refused too.
- **`33e1a25` (G5)** — the truncated-ask affordance is closed on every approval surface (the undecidable
  predicate lives once in `utils/acpAsk.ts` and fails closed like the backend).
- **`6b48899` (G6)** — a superseded run never tears down the sandbox a successor already owns, and the
  hand-over/busy pins are parameterized over the whole status sets.

## Not verified / deltas (never upgraded)

- **S7's live-worker-token half** and **S8's worker one-use replay half** are NOT VERIFIED at E2E level: a
  live worker bearer is not obtainable host-side. The in-repo evidence is
  `McpWorkerEndpointIntegrationTest`, `WorkerGovernanceAspectTest` and the `WriteGrantService` units, and
  the specs print that attribution in-line.
- **F8's real process-group kill** is NOT VERIFIED on this Windows host: the POSIX-only descendant test
  skips here; the group-first *decision* is pinned cross-platform. The sandbox lane is where it would be
  observed, and no scenario in this series exercises a durable background descendant.
- **The `pause` stop path (task 72, pre-existing).** Moving a card out of IN_PROGRESS pauses the linked
  run, but a pause only flips the row — the provider task keeps working, holds the per-agent slot, and
  PAUSED is not terminal, so a later pickup for that agent hits the typed busy error. Found by the G4
  review as the same family as F9 but not a regression of it; needs its own decision (a cancel-like stop,
  or an engine that honours pause in its task poll).
- **The card-link lifecycle residual (G4's keep-and-state).** `request-changes` keeps the stop inside the
  transition transaction; splitting it was rejected because an early commit lets the async
  `RunKanbanAutoCreator` mirror settle the still-linked REVIEW card to CANCELLED.
- **The superseded predecessor's session** can outlive its run inside the shared sandbox until the sandbox
  dies (bounded by the 30-minute platform TTL and the next-launch rebuilds); it is the deliberate cost of
  never tearing down a sandbox a successor owns.
- **The denied-consume-on-expired-grant corner** (G2a review Corner B) is fail-closed but is an
  availability regression for a never-executed delivery, with a now-truthful refusal message.
- **`regression-playwright` is not a fully clean lane in this environment**: two workflow specs interfere
  with each other in-lane while passing standalone (numbers above), and the lane is load-sensitive on this
  host.
- **The overview page's latency grows with the accumulated dev data** (175 agents / 73 approvals exceeded
  a 5 s expect budget; 2 s on a fresh database). A pre-existing UI characteristic, recorded as an
  observation with timings, not a slice-C defect.
- **Out-of-scope carries from earlier slices** remain: the REST control plane is unauthenticated in
  local-dev by design; circuit-breaker flake note; review-package diff truncation.

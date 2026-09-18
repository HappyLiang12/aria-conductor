# Qoder slice C — acceptance sweep against the approved design

Maps design Section 10 items 1–12 (`docs/superpowers/specs/2026-09-17-qoder-cli-agent-core-design.md`) to
the executed evidence. Per `AGENTS.md`, an item is PASS only when every criterion was evaluated from the
cited artifact; anything not evaluated says NOT VERIFIED and never gets upgraded.

The authoritative live runs for slice C are on the final tree (`173d210`, whose production code is
identical to `7c8016a`): execution **E14** (the full matrix, `harness-console14.log`, `SUMMARY.run13.md`,
with all six regression lanes green), **E15** (`SUMMARY.run14.md`, S5 and S9), **E16** (`SUMMARY.run15.md`,
S10) and **E19** (`SUMMARY.run18.md`, S4). Together they carry every scenario, lane and scan on that tree.
The run-by-run disclosure, including every environment-caused failure and its classification, lives in
`docs/reviews/2026-09-17-qoder-slice-c-evidence.md`; the SDD ledger
(`.superpowers/sdd/2026-09-17-qoder-cli-provider/progress.md`) carries the rulings R1–R120.

## Item-by-item

### 1. Old providers retain behavior after lifecycle extraction and timeout-source changes

- Evidence: `regression-mvn-test.run7.log` and `regression-mvn-verify.run7.log` (the backend suites,
  including the opencode and langchain provider tests), `regression-playwright.run5/6.log` (pre-existing
  Playwright suites with `QODER_E2E*` stripped), `S12.run11.log` (other-provider smoke inside the matrix).
- Verdict: **PASS**, with the playwright lane caveat stated in item 11: the two workflow specs that fail
  in-lane (`workflow-lifecycle-e2e`, `workflow-state-machine-e2e`) are unrelated to any provider and pass
  standalone 27/27 (`pw-triage-e12.run1.log`).

### 2. PAT config round-trips masked responses; rejects absent encryption keys; never leaks

- Evidence: `S6.run6.log` (set/mask/remove/test through UI + API), `leak-scan.run11.log` (count-only scan
  over 267 evidence files, 0 hits), the credential unit lanes inside the Maven runs
  (`RuntimeCredentialServiceTest`, `QoderCredentialControllerTest`, `PackCredentialCipherTest`), and — new
  in this wave — the bridge redaction closure: `bridge/src/server.ts` now includes the runtime PAT in the
  outbound redaction set for every CLI-supplied field, pinned by the class-closure test over 15 carriers
  (`bridge/test/server.test.ts`, GREEN 72 passed | 2 skipped).
- Verdict: **PASS**. PAT hygiene in the harness is by construction (`QODER_E2E_PAT` piped via stdin,
  `set -x` never enabled, count-only leak scan) — `e2e/qoder/lib/common.sh:14`,
  `e2e/qoder/lib/credential.sh:111-134`.

### 3. Linux sandbox boots a pinned CLI; config isolation; host FS/runtime socket inaccessible

- Evidence: `e2e/qoder/slice-a/01-boot.md` (pinned `qodercli` 1.1.41 with sha256, boot inside
  OpenSandbox), `02-isolation.md` (user/project config cannot inject plugins/MCP or change the governed
  permission mode; host FS and the runtime socket are inaccessible), `e2e/qoder/slice-b/01-image.md`,
  `rebuild-image.run6.log` (E11 rebuilds the pinned image from the committed Dockerfile), and the
  `QoderImageBootE2ETest` / `QoderConfigIsolationE2ETest` lanes inside the Maven runs.
- Verdict: **PASS**.

### 4. Task-level approval before CLI execution; cancel leaves nothing behind

- Evidence: `S1.run7.log` (ordinary run through the task-approval config path), `S2.run7.log` (the
  tool-level ask precedes the write), `S5.run11.log` (cancel: run `CANCELLED`, ask `EXPIRED` with
  `run ended before decision`, graceful stop proven by the absence of `qodercli` in the container, the
  sandbox still present → the kill fallback was not needed), `e2e/qoder/slice-a/04-permissions.md` and
  `05-wait-renewal.md`, plus the wave's provider fix that honours a cancel landing during sandbox
  preparation (`QoderAdkProvider`, `executeTask_abortDuringSandboxPreparation_…`).
- Verdict: **PASS**.

### 5. Ordinary and kanban runs surface asks; allow-once executes exactly once; deny prevents the side effect

- Evidence: `S2.run7.log` (the first write lands after `proceed_once`; the second write raises a new ask),
  `S3.run7.log` (denial → the file is absent, the run is not cancelled), `S9.run11.log` (kanban-dispatched
  run shows the live ask; the decision does not mark the card DONE). The wave strengthened the invariant
  underneath: one approval now authorizes at most one execution (the dequeue and the retry probe share the
  per-key critical section), `request-changes` stops the linked run before re-dispatching, and the
  re-dispatched run takes over the agent slot once the previous run is terminal.
- Verdict: **PASS**.

### 6. Authenticated platform MCP read automatic; write stays gated; audit shows the allowed execution with run correlation

- Evidence: `S11.run12.log` (E11) — the read executes with no ask, the write stays unexecuted until the
  operator approves, the approved write produces exactly one knowledge item, and both executions appear as
  run-correlated `TOOL_RESULT` entries in the run progress stream; `e2e/qoder/slice-a/03-mcp-auth.md`
  (authenticated MCP round-trip with real headers); the coordinator's fail-closed auto-allow table
  (`AcpPermissionCoordinatorTest`).
- Verdict: **PASS** for the read/write/audit criteria, with one criterion NOT VERIFIED: the spec's
  `tool_calls` table row. No writer exists on the qoder/MCP path (verified in code:
  `AgentLoopEngine` writes those rows only for the legacy tool loop), so the executing record is the run
  progress stream asserted above; the spec itself documents this shape, and S11 prints the attribution
  in-line. The wave's R81 fix is what makes the write half observable at all.

### 7. Bypass attempts cannot; unknown classifications do not allow

- Evidence: `S7.run6.log` (an unknown worker-shaped bearer is rejected `401` at the transport and the ask
  is unchanged), `S8.run6.log` (a forged worker-shaped bearer is rejected `401` with no side effect; the
  operator replay path is not deduplicated by design), the `WorkerGovernanceAspectTest`,
  `ToolPolicyRegistryTest`, `McpWorkerEndpointIntegrationTest`, `WriteGrantServiceTest` and
  `RunScopedCredentialServiceTest` lanes. The wave closed the bypass the review found: the provider now
  refuses to run at all when the platform MCP is enabled in a non-token auth mode, so a header-less
  sandbox can no longer resolve as operator.
- Verdict: **PASS** with the live-worker-token half of S7 NOT VERIFIED at E2E level (no host-side worker
  bearer; the integration tests cited above cover that seam — see the deltas).

### 8. Concurrent approve/deny/expire/cancel has one terminal result; retries do not duplicate; late approvals rejected

- Evidence: `S4.run14.log` (ask `EXPIRED` with `deliveryState=CANCELLED`, a late decide answers a typed
  `409 EXPIRED`, the file is absent afterwards), `S5.run11.log` (cancel wins over a pending ask), and the
  unit lanes `ApprovalGateConcurrencyTest`, `AcpPermissionCoordinatorTest` (ALREADY_RESOLVED, delivery
  race), `ApprovalDecisionServiceTest`, `ApprovalExpiryCheckerTest`. The wave's G2a fix is the
  load-bearing one here: a delivery retry can no longer accumulate a second consumable grant, and an
  approval whose grant was consumed refuses a retry with a typed rejection.
- Verdict: **PASS**.

### 9. Approval wait is visible, obeys the hard deadline, renews sandbox TTL, stays cancellable; restart interrupts rather than replays

- Evidence: `e2e/qoder/slice-a/05-wait-renewal.md` (long wait + renewal), `S4.run14.log` (hard deadline with
  `APPROVALS_TIMEOUT_MS=120000`), `S5.run11.log` (cancellable during the wait), `S10.run15.log` (backend
  restart mid-ask → the startup sweep marks the run FAILED with "Run orphaned by backend restart", the ask
  expires with no replay), `ApprovalExpiryCheckerTest` (ACP branch), `QoderWaitRenewalE2ETest`. The wave
  added the bridge-side half: when the host is gone the bridge itself enforces the permission deadline and
  releases the CLI.
- Verdict: **PASS**.

### 10. ACP final result, errors, unknown usage, bounded replay, unsupported shapes represented honestly; no zero-token inference presented as zero cost

- Evidence: `S1.run7.log` (`reported usage (no zero-cost guarantee): totalTokensUsed=0`, the
  `qoder.model=efficient` progress event, the probe answering `billable=false`), the
  `QoderAdkProviderTest` / `QoderBridgeClientTest` / `QoderProgressPumpTest` lanes, the bridge unit suite
  re-run in this wave (72 passed | 2 skipped, GREEN log in the SDD workspace), and the spike report
  `docs/reviews/2026-09-17-qoder-cli-acp-spike.md`.
- Verdict: **PASS**.

### 11. Browser golden path + deny/expire/cancel/double-submit in the existing Review surfaces; no other-provider regression

- Evidence: `S12.run12.log` (governance regression including the ACP outcome strip derived from server
  truth, console/network clean on touched routes), the live `S2.run7` / `S3.run7` / `S4.run14` / `S5.run11`
  paths, the new dashboard pins for the truncated-ask affordance (`ReviewQueue.acp.test.tsx`,
  `OpsPage.acp.test.tsx`, `ReviewPanels.acp.test.tsx`), and the `regression-playwright` lane.
- Verdict: **PASS**, and the lane itself is **clean**: E14's run is `regression-playwright.run8.log`, the
  first fully clean lane of the series (E13's run7 was also clean at `0 failed / 2 flaky / 35 skipped /
  221 passed`). The earlier occurrences were classified away from the code first: E11's
  `overview-dashboard.spec.ts:28:3` was dirty-database slowness (2.0 s on a fresh database) and E12's two
  workflow-spec failures pass standalone 27/27 in 31.9 s, i.e. in-lane interference. Double-submit is
  covered by the decision-dispatch race tests (item 8) plus the C5 outcome strip rather than a separate
  browser scenario.

### 12. Tests distinguish absent credentials (explicit skip) from configured-but-broken (failure)

- Evidence: `agent-control-tower/act-dashboard/e2e/qoder-e2e-lib.ts` (the PAT gate skips with a message
  naming the runtime credential; the zero-credit guard fails closed unless `efficient|lite` or
  `QODER_E2E_ALLOW_PAID=1`), the harness preflight (`preflight.run13.log` — a configured-but-broken
  credential fails, it does not skip; the E9 `503 KEY_NOT_CONFIGURED` occurrence is the evidence that the
  guard bites), and the playwright lane, which runs with `QODER_E2E*` stripped so the qoder specs skip
  rather than fail.
- Verdict: **PASS**.

## Remaining risks and deferred items (plan Step 2)

- Deferred to later slices (D/E): hooks, session resume, accounting fidelity, the default-provider flip.
- **The `pause` stop path (task 72, pre-existing, not fixed here).** A card moved out of IN_PROGRESS
  pauses its linked run, but a pause only flips the row: the provider task keeps working, holds the
  per-agent slot, and PAUSED is not terminal, so a later pickup for that agent hits the typed busy error.
- **The card-link lifecycle residual** from the request-changes fix: the stop stays inside the transition
  transaction because an early commit lets the async kanban mirror settle the still-linked card.
- **A superseded run's session** can outlive its run inside the shared sandbox until the sandbox dies
  (bounded by the platform TTL and the next-launch rebuilds).
- **The denied-consume-on-expired-grant corner** is fail-closed but is an availability regression for a
  never-executed delivery.
- Environment fragilities with evidence, not code defects: the podman/WSL machine degrades intermittently
  (repair and probe documented in the evidence doc), the dev database must be fresh enough for the UI
  budget, and the playwright lane is load-sensitive and carries pre-existing in-lane interference between
  two workflow specs.
- Carried observations: `CircuitBreakerTest` flake note; the REST control plane is unauthenticated by
  design in local-dev (observation, not a slice-C defect); review-package diff truncation; the
  fast-fail triage note (R66).

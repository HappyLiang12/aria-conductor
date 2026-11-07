# Spec: Cancel Workflow Must Cancel In-Flight Run

**Issue:** #56 — cancelWorkflow does not cancel the in-flight run (orphaned run keeps executing)  
**Severity:** P3 (resource leak — orphaned run consumes LLM/compute after chain is cancelled)  
**Found by:** Concurrency E2E effort (ledger: e2e-concurrency-report.md, item O3)

---

## Problem Statement

`POST /api/v1/workflows/{id}/cancel` sets the `WorkflowChain` status to `CANCELLED` and marks the current `WorkflowStep` as `SKIPPED`, but the underlying `Run` associated with that step keeps executing until it ends on its own. This orphaned run continues consuming LLM tokens and compute resources despite the operator having explicitly cancelled the workflow.

**Current behavior (buggy):**
1. `WorkflowService.cancelWorkflow(chainId)` loads the chain, validates it is RUNNING/PENDING/WAITING_APPROVAL
2. Marks `steps[currentStepIndex].status = SKIPPED`
3. Sets `chain.status = CANCELLED` and `chain.completedAt = now()`
4. Publishes `WorkflowCancelledEvent`
5. **Does NOT read `step.runId` or call `RunService.cancelRun()`**
6. The run continues executing on its virtual thread in `AgentLoopEngine`

**Evidence:** `e2e/api/workflow-state-concurrency.api.spec.ts` test 3 — cancel race test shows the cancel guard holds but the underlying run is not cancelled.

**Root cause location:** `WorkflowService.cancelWorkflow()` at `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/WorkflowService.java:214-241` — the method never looks up or cancels the in-flight run.

---

## Proposed Solution

Modify `WorkflowService.cancelWorkflow()` to cancel the in-flight run (if any) when cancelling a workflow chain. The fix leverages the existing `RunService.cancelRun()` method which already handles:
- Verified stop through `RunRuntimeControlPort` (blocks up to 30s for runtime ACK)
- Atomic conditional write via `RunCancellationWriter` (only writes if run is still cancelable)
- Publishing `RunCompletedEvent` with status `CANCELLED`

**Implementation approach:**

1. **In `cancelWorkflow()` after marking the step SKIPPED:**
   - Read the current step's `runId` (if present)
   - If `runId != null`, call `runService.cancelRun(runId)` to stop the in-flight execution
   - Handle the case where the run already completed (catch `InvalidStateTransitionException` or `ResourceNotFoundException`)

2. **Guard against `WorkflowAutoChainer` conflict:**
   - When `cancelWorkflow()` cancels the run, `RunCompletedEvent(CANCELLED)` fires
   - `WorkflowAutoChainer.onRunCompleted()` will call `findChainByRunId()` which only searches RUNNING/PENDING/WAITING_APPROVAL chains
   - Since the chain is already CANCELLED, `findChainByRunId()` returns null and the auto-chainer is a no-op — **no conflict**

3. **Transaction boundary:**
   - `cancelWorkflow()` is `@Transactional`
   - `RunService.cancelRun()` uses `@Transactional(propagation = Propagation.NOT_SUPPORTED)` — it suspends the outer transaction
   - This is correct: the verified stop may block, and the atomic write must not be rolled back if the outer tx fails

**Files to modify:**
- `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/WorkflowService.java`
  - Inject `RunService` dependency (if not already present)
  - In `cancelWorkflow()`: after line 229 (marking step SKIPPED), add run cancellation logic

**Pseudocode:**
```java
@Transactional
public WorkflowResponse cancelWorkflow(UUID chainId) {
    WorkflowChain chain = workflowChainRepository.findById(chainId)
            .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));

    if (chain.getStatus() != WorkflowChain.Status.RUNNING
            && chain.getStatus() != WorkflowChain.Status.PENDING
            && chain.getStatus() != WorkflowChain.Status.WAITING_APPROVAL) {
        throw new IllegalArgumentException(
                "Cannot cancel workflow in status " + chain.getStatus()
                        + "; must be RUNNING, PENDING or WAITING_APPROVAL");
    }

    List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
    WorkflowStep currentStep = null;
    if (chain.getCurrentStepIndex() < steps.size()) {
        currentStep = steps.get(chain.getCurrentStepIndex());
        currentStep.setStatus(WorkflowStep.Status.SKIPPED);
    }

    chain.setStatus(WorkflowChain.Status.CANCELLED);
    chain.setCompletedAt(Instant.now());
    chain.setStepsJson(serializeSteps(steps));
    workflowChainRepository.save(chain);

    // Cancel the in-flight run (if any)
    if (currentStep != null && currentStep.getRunId() != null) {
        try {
            runService.cancelRun(currentStep.getRunId());
        } catch (InvalidStateTransitionException | ResourceNotFoundException e) {
            // Run already completed or not found — log and continue
            log.debug("Could not cancel run {} for chain {}: {}",
                    currentStep.getRunId(), chainId, e.getMessage());
        }
    }

    log.info("Workflow chain cancelled: id={}, name={}", chain.getId(), chain.getName());
    eventPublisher.publishEvent(new WorkflowCancelledEvent(this, chain.getId(), chain.getName()));

    return toResponse(chain);
}
```

---

## Acceptance Criteria

1. **AC-1: Cancelling a chain cancels its in-flight run**
   - Given a workflow chain in RUNNING status with an active run
   - When `POST /api/v1/workflows/{id}/cancel` is called
   - Then the chain status becomes CANCELLED
   - And the current step status becomes SKIPPED
   - And the run status becomes CANCELLED (verified via `GET /api/v1/runs/{runId}`)
   - And the run stops executing within 30 seconds (verified stop timeout)

2. **AC-2: Cancelling a chain without an active run succeeds**
   - Given a workflow chain in PENDING status (no run started yet)
   - When `POST /api/v1/workflows/{id}/cancel` is called
   - Then the chain status becomes CANCELLED
   - And no error is thrown

3. **AC-3: Cancelling a chain when the run already completed succeeds**
   - Given a workflow chain where the run completed between the cancel request and the run cancellation
   - When `cancelWorkflow()` attempts to cancel the run
   - Then the chain is still marked CANCELLED
   - And the `InvalidStateTransitionException` from the run cancel is caught and logged
   - And the API returns 200 with the cancelled chain

4. **AC-4: No orphaned runs after cancel**
   - Given a workflow chain with a long-running step (e.g., maxIterations=50)
   - When the chain is cancelled
   - Then the run does not continue executing (no further LLM calls, no iteration progress)
   - Verified by: `Run.iterationCount` does not increase after cancel

5. **AC-5: E2E test coverage**
   - The existing E2E test `workflow-state-concurrency.api.spec.ts` test 3 is extended to verify that after cancelling a chain, the associated run is also CANCELLED (not just the chain)
   - Add assertion: `GET /api/v1/runs/{runId}` returns status CANCELLED within 5 seconds of the cancel response

6. **AC-6: Unit test coverage**
   - `WorkflowServiceTest` includes a test case: `cancelWorkflow_withActiveRun_cancelsRun`
   - Mock `RunService.cancelRun()` and verify it is called with the step's runId
   - Test case: `cancelWorkflow_runAlreadyCompleted_succeeds` — mock `RunService` to throw `InvalidStateTransitionException`, verify chain is still CANCELLED

---

## Error Handling

| Scenario | Handling |
|----------|----------|
| Run already completed (CANCELLED/COMPLETED/FAILED/ABORTED) | `RunService.cancelRun()` throws `InvalidStateTransitionException` → caught and logged at DEBUG level; chain cancel succeeds |
| Run not found (race: run deleted between chain load and cancel) | `RunService.cancelRun()` throws `ResourceNotFoundException` → caught and logged at DEBUG level; chain cancel succeeds |
| Verified stop timeout (runtime does not ACK within 30s) | `RunService.cancelRun()` blocks up to 30s via `RunRuntimeControlPort.cancel()` → if runtime ACKs, atomic write proceeds; if runtime vanishes, falls through to recorded transition; if runtime refuses, exception propagates and chain cancel fails with 500 (operator can retry) |
| Chain in terminal state (COMPLETED/FAILED/CANCELLED) | Existing guard throws `IllegalArgumentException` → 400 response, no run cancel attempted |
| Current step has no runId (PENDING chain, run not started) | Null check skips run cancellation; chain cancel succeeds |
| Transaction rollback during run cancel | `RunService.cancelRun()` uses `NOT_SUPPORTED` propagation → runs outside outer tx; its own atomic write is independent; chain cancel tx can rollback without affecting run cancel |

---

## Questions

_None — the issue is unambiguous and the existing `RunService.cancelRun()` API provides all necessary functionality._

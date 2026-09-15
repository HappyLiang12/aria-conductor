# =============================================================================
# Kanban pickup end-to-end scenario (NON-BLOCKING / manual).
#
# Purpose: prove the pickup-eligibility behaviour on a real stack - a real LLM
# behind a real sandbox - rather than only in unit/integration tests. Two
# scenarios run against the live backend:
#
#   1. create a TODO card, move it into IN_PROGRESS through the transition API
#      and observe that the move is ACCEPTED (HTTP 200), that the card holds a
#      run, that the run reaches a terminal state, and that the card ends in
#      REVIEW having reported non-zero tokens. Before this branch the same
#      gesture bounced with the empty-pool message, left the card in TODO and
#      answered 200 with "No healthy agent available for kanban pickup" on
#      lastError.
#   2. park a card out of IN_PROGRESS while its run is genuinely RUNNING and
#      observe that the run leaves RUNNING, that the card's run link is dropped,
#      and that the settled run never drags the parked card back. The settled run
#      status is reported as observed: an already-in-flight provider task can
#      still finish and complete the run after the pause.
#
# Intentionally kept OUT of the blocking CI gate: it needs a live stack, a real
# LLM key and a container runtime, and its duration depends on the model and on
# the sandbox. The deterministic guarantees live in the Java tests.
#
# Prerequisites:
#   pwsh -NoProfile -File scripts/start.ps1 -Mode local -NonInteractive
#   (backend :8080, OpenSandbox :8090, .env populated with a working LLM key)
#
# On the pinned agent: the picker falls back to its first eligible agent when the
# pin matches nothing in the eligible pool, so scenario 1 asserts that the pin was
# honoured. Before this branch the pool filter excluded the seeded role agents
# (model='mock') and the pin could not be honoured at all.
#
# Usage:
#   pwsh -NoProfile -File e2e/kanban-pickup-e2e.ps1
#   pwsh -NoProfile -File e2e/kanban-pickup-e2e.ps1 -TimeoutMinutes 30 -PollSeconds 5
#   pwsh -NoProfile -File e2e/kanban-pickup-e2e.ps1 -AgentTemplateId ''   # backend auto-assigns
#
# Exit code 0 = every assertion held. Exit code 1 = an assertion failed, or an
# observation was inconclusive, and the last observed card/run state is printed.
# =============================================================================
param(
    [string]$ApiUrl = "http://localhost:8080",
    # An existing agent must take the card - this script never creates an agent.
    # The default pins the seeded SDD BA agent, a worker with no explicit
    # adkProvider, so the run goes through the default (opencode) provider and
    # really uses the sandbox. Pass '' to let the backend's picker choose.
    [string]$AgentTemplateId = "SDD BA Agent",
    # How long scenario 1 waits for the run to reach a terminal state.
    [int]$TimeoutMinutes = 20,
    # How long scenario 2 waits to observe its run RUNNING before parking.
    [int]$LiveWaitSeconds = 240,
    # How long scenario 2 keeps watching the parked run after the park, so the
    # reported state is the settled one rather than a single early snapshot.
    [int]$SettleSeconds = 20,
    [int]$PollSeconds = 10
)

$ErrorActionPreference = 'Stop'

# ── HTTP helpers ─────────────────────────────────────────────────────────────
function ApiGet($path) { return Invoke-RestMethod -Method Get -Uri "$ApiUrl$path" }

# ApiPost as in natural-flow-harness.ps1, except that the status code is returned
# as data: this scenario makes assertions about the code, so a 4xx/5xx is an
# observation to report rather than an exception that aborts the run.
function ApiPost($path, $body) {
    $response = Invoke-WebRequest -Method Post -Uri "$ApiUrl$path" -ContentType 'application/json' `
        -Body ($body | ConvertTo-Json -Depth 8) -SkipHttpErrorCheck
    $parsed = $null
    if ($response.Content) { try { $parsed = $response.Content | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $parsed; Raw = $response.Content }
}

# ── Reporting helpers ────────────────────────────────────────────────────────
$script:StateLog = @()

function Show([string]$line) {
    Write-Output $line
    $script:StateLog += $line
}

function Snapshot([string]$line) {
    Write-Output "    $line"
    $script:StateLog += $line
}

function Fail([string]$assertion, [string]$observed) {
    Write-Output ""
    Write-Output "FAILURE: $assertion"
    Write-Output "Observed instead: $observed"
    Write-Output "Last observed state:"
    foreach ($line in ($script:StateLog | Select-Object -Last 12)) { Write-Output "  | $line" }
    exit 1
}

function AssertEqual($actual, $expected, [string]$what) {
    if ("$actual" -ne "$expected") { Fail "$what should be '$expected'" "it was '$actual'" }
}

function AssertTrue($condition, [string]$what, [string]$observed) {
    if (-not $condition) { Fail $what $observed }
}

# A transport error (backend restarted underneath the scenario, run id no longer
# resolvable) must still report the last state rather than only a stack trace.
trap {
    Write-Output ""
    Write-Output "FAILURE: unexpected error - $($_.Exception.Message)"
    Write-Output "Last observed state:"
    foreach ($line in ($script:StateLog | Select-Object -Last 12)) { Write-Output "  | $line" }
    exit 1
}

function NewCardBody([string]$title) {
    $body = @{
        title       = $title
        status      = 'TODO'
        description = 'Automated pickup scenario - briefly report what you did and how many steps it took.'
    }
    if ($AgentTemplateId) { $body['agentTemplateId'] = $AgentTemplateId }
    return $body
}

function CardSnapshot($card) {
    return ("card={0} assignee='{1}' agent={2} run={3} lastError={4}" -f `
            $card.status, $card.assignee, $card.linkedAgentId, $card.linkedRunId, $card.lastError)
}

function RunSnapshot($run) {
    return ("run={0} iterations={1} tokens={2}" -f $run.status, $run.iterationCount, $run.totalTokensUsed)
}

# ── Preflight ────────────────────────────────────────────────────────────────
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Write-Output "== Kanban pickup E2E: real LLM + real sandbox =="
Write-Output "   api=$ApiUrl pinnedAgent='$AgentTemplateId' runTimeout=${TimeoutMinutes}min liveWait=${LiveWaitSeconds}s"
try {
    $null = Invoke-RestMethod -Method Get -Uri "$ApiUrl/api/v1/agents" -TimeoutSec 15
} catch {
    Write-Output "FAILURE: the backend is not reachable at $ApiUrl ($($_.Exception.Message))"
    Write-Output "Start the stack first: pwsh -NoProfile -File scripts/start.ps1 -Mode local -NonInteractive"
    exit 1
}

# =============================================================================
# Scenario 1: create -> accepted IN_PROGRESS move -> terminal run -> REVIEW
# =============================================================================
Write-Output ""
Show "== Scenario 1: TODO -> IN_PROGRESS -> REVIEW =="

$create = ApiPost "/api/v1/kanban/items" (NewCardBody "E2E pickup proof $timestamp")
AssertTrue ($create.StatusCode -eq 201) "creating a TODO card should answer 201" `
    "it answered $($create.StatusCode): $($create.Raw)"
$id = $create.Body.id
AssertEqual $create.Body.status 'TODO' "the create response's card status"
Write-Output "   created card $id"

# Creation is a dispatch intent, so the after-commit auto-dispatch listener may
# already have picked the card up by the time the response is read: observe, do
# not assume.
$card = ApiGet "/api/v1/kanban/items/$id"
$statusBeforeMove = $card.status
Snapshot "after create: $(CardSnapshot $card)"

$move = ApiPost "/api/v1/kanban/items/$id/transition" @{ status = 'IN_PROGRESS' }
AssertTrue ($move.StatusCode -eq 200) `
    "the IN_PROGRESS transition should be accepted (HTTP 200), not bounced" `
    "it answered $($move.StatusCode): $($move.Raw)"

$card = ApiGet "/api/v1/kanban/items/$id"
Snapshot "after IN_PROGRESS move: $(CardSnapshot $card)"
AssertEqual $card.status 'IN_PROGRESS' "the card's status after an accepted IN_PROGRESS move"
AssertTrue (-not [string]::IsNullOrWhiteSpace($card.linkedRunId)) `
    "the card moved to IN_PROGRESS should carry a run" `
    "$(CardSnapshot $card)"
AssertTrue ([string]::IsNullOrWhiteSpace($card.lastError)) `
    "an accepted move should leave no lastError on the card" `
    "lastError='$($card.lastError)'"
if ($AgentTemplateId) {
    # The picker silently falls back to its first eligible agent when the pin
    # matches nothing in the eligible pool, so this assertion is what makes the
    # pin binding rather than decorative.
    AssertTrue ($card.assignee -eq $AgentTemplateId) `
        "the pinned agent '$AgentTemplateId' should be eligible and take the card" `
        "the card was assigned to '$($card.assignee)' instead - the picker fell back to its first eligible agent"
}
if ($statusBeforeMove -eq 'IN_PROGRESS') {
    Write-Output "   note: the card was already IN_PROGRESS before the move, so the accepted 200 is the"
    Write-Output "         same-status no-op. The pickup itself was the after-commit auto-dispatch, which"
    Write-Output "         runs the same eligibility authority and created the run seen above."
} else {
    Write-Output "   note: the move itself performed the pickup (the card was $statusBeforeMove before it)."
}
$runId = $card.linkedRunId

Write-Output ""
Show "== Polling run $runId for a terminal state (<= $TimeoutMinutes min) =="
$run = $null
$card = $null
$terminal = $false
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while ((Get-Date) -lt $deadline) {
    $card = ApiGet "/api/v1/kanban/items/$id"
    $run = ApiGet "/api/v1/runs/$runId"
    Snapshot "$(CardSnapshot $card) | $(RunSnapshot $run) | error=$($run.errorMessage)"
    if ($run.status -in @('COMPLETED', 'FAILED', 'CANCELLED', 'ABORTED')) { $terminal = $true; break }
    Start-Sleep -Seconds $PollSeconds
}
AssertTrue $terminal "the run should reach a terminal state within $TimeoutMinutes minutes" `
    "$(RunSnapshot $run) after the timeout"

# The terminal run ends the loop; the card move to REVIEW is done by an
# after-commit listener, so give it a bounded moment before judging it.
$cardDeadline = (Get-Date).AddSeconds(60)
while ($card.status -ne 'REVIEW' -and (Get-Date) -lt $cardDeadline) {
    Start-Sleep -Seconds 2
    $card = ApiGet "/api/v1/kanban/items/$id"
}
Snapshot "final: $(CardSnapshot $card) | $(RunSnapshot $run)"
AssertEqual $card.status 'REVIEW' "the card's status after its run ended ($($run.status))"
AssertTrue ($run.totalTokensUsed -gt 0) `
    "the run should report non-zero tokens (a real LLM call, not a bounce)" `
    "tokens=$($run.totalTokensUsed) status=$($run.status) error='$($run.errorMessage)'"

Write-Output ""
Write-Output "PASS (scenario 1): card $id ended in REVIEW; run $runId $($run.status) with $($run.totalTokensUsed) tokens over $($run.iterationCount) iteration(s)."

# =============================================================================
# Scenario 2: park a card whose run is RUNNING
# =============================================================================
Write-Output ""
Show "== Scenario 2: park a live card out of IN_PROGRESS =="

$create2 = ApiPost "/api/v1/kanban/items" (NewCardBody "E2E park proof $timestamp")
AssertTrue ($create2.StatusCode -eq 201) "creating the second TODO card should answer 201" `
    "it answered $($create2.StatusCode): $($create2.Raw)"
$id2 = $create2.Body.id
Write-Output "   created card $id2"

$card2 = $null
$run2 = $null
$live = $false
$deadline = (Get-Date).AddSeconds($LiveWaitSeconds)
while ((Get-Date) -lt $deadline) {
    $card2 = ApiGet "/api/v1/kanban/items/$id2"
    if (-not [string]::IsNullOrWhiteSpace($card2.linkedRunId)) {
        $run2 = ApiGet "/api/v1/runs/$($card2.linkedRunId)"
    }
    $runText = if ($run2) { RunSnapshot $run2 } else { 'run=- (no run linked yet)' }
    Snapshot "waiting for a RUNNING run: $(CardSnapshot $card2) | $runText"
    if ($run2 -and $run2.status -eq 'RUNNING') { $live = $true; break }
    # If the run finishes before RUNNING is observed, parking it proves nothing.
    if ($run2 -and $run2.status -in @('COMPLETED', 'FAILED', 'CANCELLED', 'ABORTED')) { break }
    if ($card2.status -eq 'REVIEW' -or $card2.status -eq 'CANCELLED') { break }
    Start-Sleep -Seconds 5
}
if (-not $live) {
    $runText = if ($run2) { "$(RunSnapshot $run2)" } else { 'no run was linked' }
    Fail "scenario 2 needs a card whose run is observed RUNNING before it is parked" `
        "$(CardSnapshot $card2) | $runText after ${LiveWaitSeconds}s - parking it would prove nothing about stopping a live run"
}
$runId2 = $card2.linkedRunId

$park = ApiPost "/api/v1/kanban/items/$id2/transition" @{ status = 'TODO' }
AssertTrue ($park.StatusCode -eq 200) "parking a live card to TODO should answer 200" `
    "it answered $($park.StatusCode): $($park.Raw)"

# stopLinkedRun pauses a RUNNING run synchronously, so allow only a short window
# for the stop to be visible before judging it. The status observed here is the
# park's own effect and is what gets asserted.
$parkDeadline = (Get-Date).AddSeconds(30)
$card2 = ApiGet "/api/v1/kanban/items/$id2"
$run2 = ApiGet "/api/v1/runs/$runId2"
while ($run2.status -eq 'RUNNING' -and (Get-Date) -lt $parkDeadline) {
    Start-Sleep -Seconds 2
    $card2 = ApiGet "/api/v1/kanban/items/$id2"
    $run2 = ApiGet "/api/v1/runs/$runId2"
}
Snapshot "right after park: $(CardSnapshot $card2) | $(RunSnapshot $run2)"
AssertTrue ($run2.status -ne 'RUNNING') `
    "parking the card should leave its run no longer RUNNING" `
    "the run was still RUNNING 30s after the park"
AssertTrue ($run2.status -in @('PAUSED', 'CANCELLED')) `
    "parking a RUNNING run should stop it (PAUSED, or CANCELLED for a run that had not started)" `
    "the run was $($run2.status) - it left RUNNING for a reason this scenario cannot attribute to the park"
$parkedStatus = $run2.status

# Pausing writes the run row; a provider call already in flight can still drive
# the run on to a terminal state afterwards (the opencode task-execution path is
# a single blocking call, so there is no iteration boundary at which the pause is
# noticed). Watch the run settle and report what it actually settled to.
$settleDeadline = (Get-Date).AddSeconds($SettleSeconds)
$settled = $run2
while ((Get-Date) -lt $settleDeadline) {
    Start-Sleep -Seconds 5
    $settled = ApiGet "/api/v1/runs/$runId2"
}
$card2 = ApiGet "/api/v1/kanban/items/$id2"
Snapshot "settled after ${SettleSeconds}s: $(CardSnapshot $card2) | $(RunSnapshot $settled)"
if ($settled.status -ne $parkedStatus) {
    Write-Output "   note: the run moved on from $parkedStatus to $($settled.status) after the park - an"
    Write-Output "         already-in-flight provider task still finished and completed it."
}
AssertEqual $card2.status 'TODO' "the card's status after being parked"
AssertTrue ([string]::IsNullOrWhiteSpace($card2.linkedRunId)) `
    "parking should drop the card's run link (the link is what drags a parked card back)" `
    "linkedRunId was still '$($card2.linkedRunId)'"

Write-Output ""
Write-Output "PASS (scenario 2): parking left run $runId2 $parkedStatus (observed RUNNING before the park, settled $($settled.status)) and card $id2 in TODO with no run link."

# =============================================================================
# Summary - the cards and runs are left in place for the operator to inspect.
# =============================================================================
Write-Output ""
Write-Output "== Summary =="
Write-Output "   scenario 1: card $id is $($card.status); run $runId is $($run.status) with $($run.totalTokensUsed) tokens."
Write-Output "   scenario 2: card $id2 is $($card2.status); run $runId2 was parked from RUNNING to $parkedStatus, settled $($settled.status)."
Write-Output "   Both cards and runs are left as they are: they are the evidence."
exit 0

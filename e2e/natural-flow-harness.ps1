# =============================================================================
# Natural-flow harness verification (NON-BLOCKING / manual / nightly).
#
# Purpose: verify that under the "weak-model-safe" harness profile, a weak model
# (deepseek-v4-flash) driving the governed dev workflow RELIABLY REACHES the
# git_push / git_create_pr human-approval gate. Reaching the gate is success —
# a human may then approve OR deny; both count. This exercises the real LLM +
# ADK + GitHub and is therefore intentionally kept OUT of the blocking CI gate
# (the deterministic guarantees live in harness-governance.spec.ts and the Java
# unit/integration tests).
#
# Prerequisites (start these first, e.g. via e2e/run-backend-e2e.ps1):
#   - Backend up on http://localhost:8080 (h2 profile, V37 harness profiles seeded)
#   - LangChain ADK runtime up (remote mode) for the worker model
#   - .env populated with the DeepSeek LLM provider + GITHUB_TOKEN
#
# Usage:
#   pwsh e2e/natural-flow-harness.ps1 -RepoUrl "https://github.com/<owner>/<repo>.git" `
#        [-Model deepseek-v4-flash] [-Provider deepseek] [-TimeoutMinutes 15]
#
# Exit code 0 = push/PR gate reached (success); 1 = gate not reached in time.
# =============================================================================
param(
    [string]$RepoUrl = $env:E2E_REPO_URL,
    [string]$Model = "deepseek-v4-flash",
    [string]$Provider = "deepseek",
    [int]$TimeoutMinutes = 15,
    [string]$ApiUrl = "http://localhost:8080"
)

$ErrorActionPreference = 'Stop'
if (-not $RepoUrl) { Write-Error "Set -RepoUrl or E2E_REPO_URL to a repository the worker may clone/push."; exit 1 }

function ApiPost($path, $body) {
    return Invoke-RestMethod -Method Post -Uri "$ApiUrl$path" -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 8)
}
function ApiGet($path) { return Invoke-RestMethod -Method Get -Uri "$ApiUrl$path" }

Write-Output "== Natural-flow harness: creating a weak-model-safe dev worker =="
# role 'dev' auto-adopts the weak-model-safe profile in AgentService.createAgent.
$agent = ApiPost "/api/v1/agents" @{
    name      = "nf-dev-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    role      = "dev"
    agentType = "NATIVE"
    model     = $Model
    provider  = $Provider
}
Write-Output "  agent=$($agent.id) model=$Model provider=$Provider"

# Confirm the effective tool set is hardened (shell_exec removed, git pack present).
$tools = (ApiGet "/api/v1/agents/$($agent.id)/tools") | ForEach-Object { $_.name }
Write-Output "  tools: $($tools -join ', ')"
if ($tools -contains 'shell_exec') { Write-Warning "shell_exec is still present — weak-model-safe not applied?" }

$prompt = "Clone $RepoUrl, create a new branch, make a small documentation improvement to the README, " +
          "commit it, push the branch, and open a pull request. Use the governed git tools only."
Write-Output "== Starting run =="
$run = ApiPost "/api/v1/runs" @{ agentId = $agent.id; promptSeed = $prompt }
Write-Output "  run=$($run.id)"

Write-Output "== Polling for a git_push / git_create_pr approval gate (<= $TimeoutMinutes min) =="
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while ((Get-Date) -lt $deadline) {
    $pending = ApiGet "/api/v1/approvals?status=PENDING"
    $gate = $pending | Where-Object { $_.runId -eq $run.id -and ($_.toolName -eq 'git_push' -or $_.toolName -eq 'git_create_pr') } | Select-Object -First 1
    if ($gate) {
        Write-Output "SUCCESS: reached the '$($gate.toolName)' approval gate (approvalId=$($gate.id))."
        Write-Output "  reason: $($gate.reason)"
        Write-Output "  Denying to leave the remote untouched (gate reached is the success criterion)."
        try { ApiPost "/api/v1/approvals/$($gate.id)/reject" @{ reason = "natural-flow harness: gate reached, denying" } | Out-Null } catch {}
        exit 0
    }
    $r = ApiGet "/api/v1/runs/$($run.id)"
    Write-Output ("  status={0} iterations={1} tokens={2}" -f $r.status, $r.iterationCount, $r.totalTokensUsed)
    if ($r.status -in @('COMPLETED','FAILED','CANCELLED')) { Write-Warning "Run ended ($($r.status)) before reaching the push/PR gate."; break }
    Start-Sleep -Seconds 10
}
Write-Error "FAILURE: push/PR approval gate not reached within $TimeoutMinutes minutes."
exit 1

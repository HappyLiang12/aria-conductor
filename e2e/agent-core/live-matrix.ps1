#!/usr/bin/env pwsh
# Live four-combination matrix runner (Task 20) — PowerShell entry point.
#
#   pwsh -NoProfile -File e2e/agent-core/live-matrix.ps1 --core qoder --mode HOST --model efficient `
#     --workspace <dir> --repo <git repository> --evidence <file> [--pat-file <file>] `
#     [--paid-opt-in] [--scenarios a,b] [--timeout-ms n] [--expiry-budget-ms n] \n#     [--backend-task-deadline-minutes n] [--base-url url] [--no-backend]
#
# Fails closed BEFORE launching anything: every required prerequisite (credential, CLI binary,
# container image / OpenSandbox server, base repository, toolchain) is checked here and a missing
# one is a named refusal, never a skip. The Qoder core is pinned to `efficient` unless the
# operator passes --paid-opt-in (or ARIA_LIVE_PAID_MODEL_OPT_IN=1).
#
# The Qoder PAT is read from a file, never printed, never passed in argv: this script only checks
# that the file exists and is non-empty, and the Node driver reads it into memory.
param(
    [string]$core, [string]$mode, [string]$model, [string]$workspace, [string]$repo,
    [string]$evidence, [string]$patFile, [switch]$paidOptIn,
    [string]$scenarios, [string]$timeoutMs, [string]$expiryBudgetMs, [string]$backendTaskDeadlineMinutes, [string]$baseUrl,
    [switch]$noBackend, [switch]$keepBackend
)
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "../..") | ForEach-Object { $_.Path }
$QoderPinnedModel = "efficient"
$SandboxEndpoint = if ($env:ARIA_LIVE_SANDBOX_URL) { $env:ARIA_LIVE_SANDBOX_URL } else { "http://127.0.0.1:8090" }
$paid = $paidOptIn -or $env:ARIA_LIVE_PAID_MODEL_OPT_IN -eq "1"

function Refuse([string]$reason) {
    Write-Host "live-matrix: MISSING PREREQUISITE: $reason" -ForegroundColor Red
    Write-Host "live-matrix refused before launching anything (core=$core mode=$mode)" -ForegroundColor Red
    exit 2
}
function Blocked([string[]]$reasons) {
    foreach ($reason in $reasons) { Write-Host "live-matrix: MISSING PREREQUISITE: $reason" -ForegroundColor Red }
    Write-Host "live-matrix BLOCKED by the environment before launching anything (core=$core mode=$mode)" -ForegroundColor Red
    exit 3
}

if (-not $core) { Refuse "--core is required" }
if (-not $mode) { Refuse "--mode is required" }
if (-not $model) { $model = $QoderPinnedModel }
if (-not $workspace) { Refuse "--workspace (the Direct-mode working directory) is required" }
if (-not $repo) { Refuse "--repo (the operator-admitted git repository) is required" }
if (-not $evidence) { Refuse "--evidence (the raw output destination) is required" }
$mode = $mode.ToUpperInvariant()
if ($core -notin @("qoder", "opencode")) { Refuse "--core must be qoder or opencode (got $core)" }
if ($mode -notin @("HOST", "SANDBOX")) { Refuse "--mode must be HOST or SANDBOX (got $mode)" }
if ($core -eq "qoder" -and $model -ne $QoderPinnedModel -and -not $paid) {
    Refuse "model-pin refusal: the qoder core is pinned to '$QoderPinnedModel' (requested '$model'); a paid model needs the explicit opt-in (--paid-opt-in or ARIA_LIVE_PAID_MODEL_OPT_IN=1)"
}

$passthru = @("--model", $model)
if ($paidOptIn) { $passthru += "--paid-opt-in" }
if ($scenarios) { $passthru += @("--scenarios", $scenarios) }
if ($timeoutMs) { $passthru += @("--timeout-ms", $timeoutMs) }
if ($expiryBudgetMs) { $passthru += @("--expiry-budget-ms", $expiryBudgetMs) }
if ($backendTaskDeadlineMinutes) { $passthru += @("--backend-task-deadline-minutes", $backendTaskDeadlineMinutes) }
if ($baseUrl) { $passthru += @("--base-url", $baseUrl) }
if ($noBackend) { $passthru += "--no-backend" }
if ($keepBackend) { $passthru += "--keep-backend" }

# ── credential prerequisite ──
if ($core -eq "qoder") {
    $patFile = if ($patFile) { $patFile }
        elseif ($env:ARIA_LIVE_PAT_FILE) { $env:ARIA_LIVE_PAT_FILE }
        elseif ($env:ARIA_PROBE_PAT_FILE) { $env:ARIA_PROBE_PAT_FILE }
        else { Join-Path $env:USERPROFILE "AppData/Local/aria-conductor-secrets/.qoder-pat" }
    if (-not (Test-Path -LiteralPath $patFile -PathType Leaf)) {
        Refuse "qoder runtime credential missing: no readable PAT file at $patFile (--pat-file/ARIA_LIVE_PAT_FILE)"
    }
    if ((Get-Item -LiteralPath $patFile).Length -eq 0) {
        Refuse "qoder runtime credential missing: the PAT file at $patFile is empty"
    }
    $passthru += @("--pat-file", $patFile)
} else {
    $envFile = Join-Path $RepoRoot "agent-control-tower/.env"
    $repoEnv = (Test-Path $envFile) -and ((Get-Item $envFile).Length -gt 0)
    $providerKey = $env:DEEPSEEK_API_KEY -or $env:LLM_API_KEY -or $env:OPENCODE_API_KEY
    if (-not $providerKey -and -not $repoEnv) {
        Refuse "opencode model-provider credential missing: no run-owned credential in this environment (DEEPSEEK_API_KEY/LLM_API_KEY/OPENCODE_API_KEY unset and agent-control-tower/.env empty)"
    }
}

# ── CLI / binary prerequisite ──
if ($core -eq "qoder") {
    $qoderCli = if ($env:ARIA_LIVE_QODER_CLI) { $env:ARIA_LIVE_QODER_CLI } elseif ($env:ARIA_PROBE_QODER_CLI) { $env:ARIA_PROBE_QODER_CLI } else { $null }
    if (-not $qoderCli) {
        $candidate = Join-Path $env:USERPROFILE ".qoder/bin/qodercli/qodercli.exe"
        if (Test-Path $candidate) { $qoderCli = $candidate }
    }
    if (-not $qoderCli -or -not (Test-Path $qoderCli)) {
        Refuse "qoder CLI binary missing: no qodercli executable found (ARIA_LIVE_QODER_CLI or the operator install path)"
    }
} elseif (-not (Get-Command opencode -ErrorAction SilentlyContinue)) {
    Refuse "opencode binary missing: no opencode executable on PATH (ARIA_LIVE_OPENCODE)"
}

# ── SANDBOX prerequisite: container runtime + OpenSandbox server + image ──
if ($mode -eq "SANDBOX") {
    $unmet = @()
    $reachable = $false
    try {
        $response = Invoke-WebRequest -Uri "$SandboxEndpoint/health" -TimeoutSec 10 -UseBasicParsing
        $reachable = $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch { $reachable = $false }
    if (-not $reachable) { $unmet += "OpenSandbox server not reachable at $SandboxEndpoint (no 2xx /health response)" }
    $images = @()
    foreach ($rt in @("docker", "podman")) {
        if (Get-Command $rt -ErrorAction SilentlyContinue) {
            $images += @(& $rt images --format "{{.Repository}}:{{.Tag}}" 2>$null | Where-Object { $_ -match "sandbox" })
        }
    }
    if ($images.Count -eq 0) { $unmet += "no sandbox image present in the local docker/podman image stores" }
    if ($unmet.Count -gt 0) { Blocked $unmet }
}

# ── base repository / workspace prerequisite ──
if (-not (Test-Path -LiteralPath $repo -PathType Container)) { Refuse "--repo does not exist: $repo" }
git -C $repo rev-parse --git-dir *> $null
if ($LASTEXITCODE -ne 0) { Refuse "--repo is not a git repository: $repo" }
if (-not (Test-Path -LiteralPath $workspace -PathType Container)) { Refuse "--workspace does not exist: $workspace" }

# ── toolchain prerequisite ──
foreach ($command in @("node", "java", "mvn")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { Refuse "$command is not on PATH" }
}

& node (Join-Path $ScriptDir "live-matrix.mjs") --core $core --mode $mode `
    --workspace $workspace --repo $repo --evidence $evidence @passthru
exit $LASTEXITCODE

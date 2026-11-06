# =============================================================================
# run-e2e-evidence.ps1  —  Skill/Knowledge/Workflow concurrency E2E evidence run
# =============================================================================
# Orchestrates the API + UI concurrency E2E tiers against a running stack and
# collects all evidence (Playwright JSON/log/traces, load metrics JSON, an
# actuator baseline snapshot) into a timestamped e2e-evidence/<ts>/ folder, then
# writes a run-summary.md. Assumes the backend + frontend are already running
# (see AGENTS.md); real-LLM tiers auto-skip unless LLM_PROVIDER_API_KEY is set.
#
# Usage:
#   ./scripts/run-e2e-evidence.ps1 -BackendPort 18080 -FrontendPort 5173
# =============================================================================
param(
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 5173
)
$ErrorActionPreference = 'Stop'

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$Dashboard  = Join-Path $RepoRoot 'agent-control-tower/act-dashboard'
$Backend    = "http://localhost:$BackendPort"
$Stamp      = Get-Date -Format 'yyyyMMdd-HHmmss'
$Evidence   = Join-Path $RepoRoot "e2e-evidence/$Stamp"
New-Item -ItemType Directory -Force -Path $Evidence | Out-Null

$env:API_URL      = $Backend
$env:BASE_URL     = "http://localhost:$FrontendPort"
$env:EVIDENCE_DIR = $Evidence
$env:CI           = '1'   # headless Playwright

Write-Host "Evidence dir: $Evidence" -ForegroundColor Cyan

# --- 1. Actuator baseline snapshot (pool + threads; best-effort) -------------
$metricNames = @('hikaricp.connections.active','hikaricp.connections.pending',
                 'hikaricp.connections.idle','jvm.threads.live','tomcat.threads.busy')
$baseline = @{ timestamp = (Get-Date).ToString('o'); backend = $Backend; metrics = @{} }
try { $baseline.health = (Invoke-RestMethod "$Backend/actuator/health" -TimeoutSec 5).status } catch { $baseline.health = 'UNREACHABLE' }
foreach ($m in $metricNames) {
    try { $baseline.metrics[$m] = (Invoke-RestMethod "$Backend/actuator/metrics/$m" -TimeoutSec 5).measurements[0].value }
    catch { $baseline.metrics[$m] = $null }
}
$baseline | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $Evidence 'actuator-baseline.json')

Push-Location $Dashboard
try {
    # --- 2. API concurrency + load tier -------------------------------------
    $env:PW_JSON_OUT = Join-Path $Evidence 'api-results.json'
    Write-Host 'Running API concurrency/load tier...' -ForegroundColor Cyan
    npx playwright test --project=api --reporter=list 2>&1 |
        Tee-Object -FilePath (Join-Path $Evidence 'api-run.log')
    $apiExit = $LASTEXITCODE

    # --- 3. UI concurrent-collaboration tier --------------------------------
    Write-Host 'Running UI concurrent-collaboration tier...' -ForegroundColor Cyan
    npx playwright test concurrent-collaboration-ui --project=chromium --reporter=list 2>&1 |
        Tee-Object -FilePath (Join-Path $Evidence 'ui-run.log')
    $uiExit = $LASTEXITCODE
}
finally {
    Pop-Location
}

# --- 4. Summarize -----------------------------------------------------------
$summary = @("# E2E Evidence Run $Stamp","","- Backend: $Backend","- Evidence: $Evidence","")
$jsonPath = Join-Path $Evidence 'api-results.json'
if (Test-Path $jsonPath) {
    try {
        $r = Get-Content $jsonPath -Raw | ConvertFrom-Json
        $st = $r.stats
        $summary += "## API tier"
        $summary += "- expected: $($st.expected)  unexpected: $($st.unexpected)  skipped: $($st.skipped)  flaky: $($st.flaky)"
    } catch { $summary += "## API tier (could not parse api-results.json)" }
}
$summary += ""
$summary += "## Exit codes"
$summary += "- API project: $apiExit"
$summary += "- UI project:  $uiExit"
$summary += ""
$summary += "## Load metrics (JSON in this folder)"
foreach ($f in 'metrics-read-burst.json','metrics-mixed-load.json','metrics-saturation.json') {
    if (Test-Path (Join-Path $Evidence $f)) { $summary += "- $f" }
}
$summary -join "`n" | Set-Content (Join-Path $Evidence 'run-summary.md')

Write-Host "`nDone. API exit=$apiExit UI exit=$uiExit" -ForegroundColor Green
Write-Host "Summary: $(Join-Path $Evidence 'run-summary.md')" -ForegroundColor Green
if ($apiExit -ne 0 -or $uiExit -ne 0) { exit 1 } else { exit 0 }

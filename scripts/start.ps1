#!/usr/bin/env pwsh
<#
.SYNOPSIS
One-click local-dev startup for Aria Conductor.

Defaults to the opencode provider on podman in the local-dev topology (backend and
frontend on the host, OpenSandbox in a container) - the only topology in which the
opencode provider works. Use -Mode compose for the legacy full-stack compose stack,
which is langchain-only.

.PARAMETER Mode
local (default) or compose.

.PARAMETER DryRun
Run the environment checks, print the mode block, then exit. Nothing is mutated:
no containers are started, no ports are freed, no processes are launched and no
`.run/` state is written.
#>
param(
    [ValidateSet('local', 'compose')][string]$Mode = 'local',
    [switch]$DryRun,
    [switch]$NonInteractive,
    # Test seam: defaults to the repository root.
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
. (Join-Path $PSScriptRoot "lib/env-setup.ps1")
. (Join-Path $PSScriptRoot "lib/preflight.ps1")

$RunDir = Join-Path $ProjectRoot ".run"

$provider = if ($Mode -eq 'local') { 'opencode' } else { 'langchain' }
$topology = if ($Mode -eq 'local') { 'local-dev (backend + frontend on host)' } else { 'full-stack compose (backend in a container)' }

function Write-Phase([int]$Number, [string]$Text) {
    Write-Host ("[{0}/8] {1}" -f $Number, $Text) -ForegroundColor Cyan
}

function Get-EnvValue([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

function Write-ModeSummary([string]$Topology, [string]$Provider, [string]$RuntimeLine,
                          [string]$SandboxLine, [hashtable]$Checks) {
    Write-Host ""
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Aria Conductor - READY" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Topology : $Topology"
    Write-Host "  Provider : $Provider"
    Write-Host "  Runtime  : $RuntimeLine"
    if ($SandboxLine) { Write-Host "  Sandbox  : $SandboxLine" }
    Write-Host "  Database : h2 (file)"
    Write-Host "---------------------------------------------------------"
    foreach ($k in $Checks.Keys) {
        Write-Host ("  {0,-10}: {1,-40} [{2}]" -f $k, $Checks[$k].Url, $Checks[$k].Result)
    }
    Write-Host "  Swagger  : http://localhost:8080/swagger-ui.html"
    Write-Host "---------------------------------------------------------"
    Write-Host "  Logs : .run\backend.log   .run\frontend.log"
    Write-Host "  Stop : pwsh -File scripts\stop.ps1"
    Write-Host "=========================================================" -ForegroundColor Green
}

# ── Phase 1: environment check ───────────────────────────────────────────────
Write-Host "Aria Conductor - one-click start ($Mode)" -ForegroundColor Cyan
Write-Phase 1 "Checking environment"

Load-DotEnv $ProjectRoot

# This flow targets podman by default: the opencode sandbox needs a runtime whose socket
# the OpenSandbox server can mount, and Docker Desktop is commonly installed alongside
# podman, where auto-detection would prefer docker. Pin podman when it is usable and only
# fall back to docker with a loud warning. Whatever is chosen here is what Phase 2 records
# in .env, so the next run agrees with this one.
if (-not $env:CONTAINER_RUNTIME) {
    if (Get-Command podman -ErrorAction SilentlyContinue) {
        & podman info *> $null
        if ($LASTEXITCODE -eq 0) { $env:CONTAINER_RUNTIME = 'podman' }
    }
    if (-not $env:CONTAINER_RUNTIME) {
        Write-Host "      podman is not usable - falling back to docker" -ForegroundColor Yellow
        $env:CONTAINER_RUNTIME = 'docker'
    }
}

$runtimeInfo = Resolve-ContainerRuntime
$runtime = $runtimeInfo.Runtime
if (-not $runtime) { throw "No container runtime available. Install podman (or docker) and retry." }
if ($runtime -eq 'podman') {
    if (Start-PodmanMachineIfNeeded) { Write-Host "      podman machine started" -ForegroundColor DarkGray }
}
foreach ($tool in @('java', 'node', 'pnpm')) {
    if (-not (Test-ToolchainCommand $tool)) { throw "$tool not found on PATH. Install it and retry." }
}
$mavenShimDir = Initialize-MavenShim -ProjectRoot (Join-Path $ProjectRoot 'agent-control-tower') -RunDir $RunDir
if ($mavenShimDir) {
    $env:PATH = "$mavenShimDir;$env:PATH"
    Write-Host "      mvn absent - using the repo wrapper (shim in .run/bin)" -ForegroundColor Yellow
}
if (-not (Test-ToolchainCommand 'mvn')) {
    throw "Maven not found and the wrapper bootstrap is unavailable. Install Maven 3.9+, or open a NEW terminal (a PATH change does not affect shells that are already open)."
}
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker info *> $null
    if ($LASTEXITCODE -eq 0 -and $runtime -eq 'podman') {
        Write-Host "      NOTE: Docker Desktop is also running. 'docker compose' targets Docker, not podman." -ForegroundColor Yellow
    }
}

# ── Phase 2: env guidance ────────────────────────────────────────────────────
Write-Phase 2 "Checking .env"
$socket = Get-SandboxSocketPath
if (-not $socket) {
    if ($runtime -eq 'podman') { throw "Could not read the podman socket path (podman info). Is the machine running?" }
    $socket = '/var/run/docker.sock'
}
$envResult = Ensure-EnvFile -ProjectRoot $ProjectRoot -SandboxSocket $socket `
    -ContainerRuntime $runtime -NonInteractive:$NonInteractive -ApiKey $env:LLM_API_KEY
if ($envResult.Created) {
    Write-Host "      created .env" -ForegroundColor DarkGray
} elseif ($envResult.AddedKeys.Count -gt 0) {
    Write-Host "      added to .env: $($envResult.AddedKeys -join ', ')" -ForegroundColor DarkGray
}
$llmKey = $null
foreach ($line in (Get-Content (Join-Path $ProjectRoot '.env'))) {
    if ($line.Trim() -match '^LLM_API_KEY=(.*)$') { $llmKey = $Matches[1] }
}
if (-not (Test-LlmKeyValid $llmKey)) {
    throw "LLM_API_KEY is missing or still a placeholder in .env. Set a real key and retry."
}

# ── Compose mode: the runtime owns the whole stack ───────────────────────────
if ($Mode -eq 'compose') {
    Write-Phase 3 "Starting the full-stack compose stack"
    Push-Location $ProjectRoot
    try {
        & $runtime compose up -d --build
        if ($LASTEXITCODE -ne 0) { throw "compose up failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    $composeBackendPort = Get-EnvValue 'BACKEND_PORT' '8080'
    $composeDashboardPort = Get-EnvValue 'FRONTEND_PORT' '3000'
    Write-Phase 7 "Reporting"
    Write-Host ""
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Aria Conductor - COMPOSE STACK STARTING" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Topology : $topology"
    Write-Host "  Provider : $provider"
    Write-Host "  Runtime  : $runtime ($($runtimeInfo.Mode))"
    Write-Host "  Dashboard: http://localhost:$composeDashboardPort"
    Write-Host "  Backend  : http://localhost:$composeBackendPort"
    Write-Host "---------------------------------------------------------"
    Write-Host "  NOTE: the opencode provider is NOT usable in this topology - the" -ForegroundColor Yellow
    Write-Host "  containerized backend cannot reach the sandbox endpoints. This stack" -ForegroundColor Yellow
    Write-Host "  runs the langchain provider. Run without -Mode for opencode." -ForegroundColor Yellow
    Write-Host "---------------------------------------------------------"
    Write-Host "  Logs : $runtime compose logs -f"
    Write-Host "  Stop : $runtime compose down"
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Phase 8 "Done"
    exit 0
}

# Ports feed both the summary and the port pre-check.
$backendPort = [int](Get-EnvValue 'BACKEND_PORT' '8080')
$frontendPort = [int](Get-EnvValue 'VITE_PORT' '5173')
$sandboxPort = [int](Get-EnvValue 'OPENSANDBOX_PORT' '8090')

# ── Dry run: stop before anything is mutated ─────────────────────────────────
if ($DryRun) {
    Write-ModeSummary -Topology $topology -Provider $provider `
        -RuntimeLine "$runtime ($($runtimeInfo.Mode))" `
        -SandboxLine "aria-opensandbox  http://localhost:$sandboxPort" -Checks @{}
    Write-Host ""
    Write-Host "-DryRun: environment OK, nothing started." -ForegroundColor Yellow
    exit 0
}

# ── Phase 3: resource preparation ────────────────────────────────────────────
Write-Phase 3 "Preparing container resources"
Ensure-OpencodeSandboxImage -Runtime $runtime -ProjectRoot $ProjectRoot | Out-Null
Ensure-OpenSandboxServer -Runtime $runtime -ProjectRoot $ProjectRoot | Out-Null

# ── Phase 4: port pre-check ──────────────────────────────────────────────────
Write-Phase 4 "Checking ports"
foreach ($port in @($backendPort, $sandboxPort, $frontendPort)) {
    $holder = Get-PortHolder -Port $port
    if ($holder) {
        Write-Host "      port $port is held by $($holder.Name) (PID $($holder.Pid))" -ForegroundColor Yellow
        if ($NonInteractive) { throw "Port $port is in use. Stop PID $($holder.Pid) ($($holder.Name)) and retry." }
        $answer = Read-Host "      Stop PID $($holder.Pid) ($($holder.Name))? [y/N]"
        if ($answer -notmatch '^(?i)y') { throw "Aborted: port $port is still in use." }
        & taskkill /PID $holder.Pid /T /F | Out-Null
    }
}

# ── Phase 5: start ───────────────────────────────────────────────────────────
Write-Phase 5 "Starting services"
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
$env:VITE_BACKEND_PORT = "$backendPort"

$backend = Start-Process pwsh -ArgumentList @(
        '-NoProfile', '-File', (Join-Path $PSScriptRoot 'start-backend.ps1'),
        '-AdkProvider', 'opencode'
    ) -NoNewWindow -PassThru `
    -RedirectStandardOutput (Join-Path $RunDir 'backend.log') `
    -RedirectStandardError (Join-Path $RunDir 'backend.err.log')
Set-Content -Path (Join-Path $RunDir 'backend.pid') -Value $backend.Id

$frontend = Start-Process pwsh -ArgumentList @(
        '-NoProfile', '-File', (Join-Path $PSScriptRoot 'start-frontend.ps1')
    ) -NoNewWindow -PassThru `
    -RedirectStandardOutput (Join-Path $RunDir 'frontend.log') `
    -RedirectStandardError (Join-Path $RunDir 'frontend.err.log')
Set-Content -Path (Join-Path $RunDir 'frontend.pid') -Value $frontend.Id

# ── Phase 6: health verification ─────────────────────────────────────────────
Write-Phase 6 "Waiting for health"
$checks = [ordered]@{}
$backendOk = Wait-HttpHealthy -Url "http://localhost:$backendPort/actuator/health" `
    -TimeoutSeconds 300 -ExpectBodyMatch '"status"\s*:\s*"UP"'
$dashboardOk = Wait-HttpHealthy -Url "http://localhost:$frontendPort" -TimeoutSeconds 120
$sandboxOk = Wait-HttpHealthy -Url "http://localhost:$sandboxPort/health" -TimeoutSeconds 120
$checks['Dashboard'] = @{ Url = "http://localhost:$frontendPort"; Result = $(if ($dashboardOk) { 'OK' } else { 'FAIL' }) }
$checks['Backend'] = @{ Url = "http://localhost:$backendPort"; Result = $(if ($backendOk) { 'OK' } else { 'FAIL' }) }
$checks['Sandbox'] = @{ Url = "http://localhost:$sandboxPort"; Result = $(if ($sandboxOk) { 'OK' } else { 'FAIL' }) }
$allOk = $backendOk -and $dashboardOk -and $sandboxOk

# ── Phase 7: mode confirmation ───────────────────────────────────────────────
Write-Phase 7 "Reporting"
Write-ModeSummary -Topology $topology -Provider $provider `
    -RuntimeLine "$runtime ($($runtimeInfo.Mode))" `
    -SandboxLine "aria-opensandbox  http://localhost:$sandboxPort" -Checks $checks
if ($allOk) {
    Start-Process "http://localhost:$frontendPort" | Out-Null
}

# ── Phase 8: exit ────────────────────────────────────────────────────────────
Write-Phase 8 "Done"
if (-not $allOk) {
    Write-Host "One or more services did not become healthy. Tail of the backend log:" -ForegroundColor Red
    Get-Content (Join-Path $RunDir 'backend.log') -Tail 25 -ErrorAction SilentlyContinue
    Write-Host "Retry after fixing, or stop everything with: pwsh -File scripts\stop.ps1" -ForegroundColor Yellow
    exit 1
}
Write-Host "All services healthy." -ForegroundColor Green
exit 0

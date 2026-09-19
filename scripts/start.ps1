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

.PARAMETER Provider
opencode (default) or qoder. `-Provider qoder` is an explicit opt-in: it selects the
qoder provider and its sandbox image and pins MCP token auth (`ARIA_MCP_AUTH_MODE=token`)
with a locally generated token file, because a qoder sandbox can reach the backend's
operator APIs (design Section 6.2). An explicit override to any other auth mode is
refused. The qoder provider needs the local-dev topology, so compose mode rejects it.

.PARAMETER DryRun
Run the environment checks, print the mode block, then exit. No services are started,
no containers and no ports are touched, and no `.run/` state is written. The one
exception is that the environment check may start a stopped podman machine - that is
the repair this script exists to make, and without it the sandbox socket cannot be read.
#>
param(
    [ValidateSet('local', 'compose')][string]$Mode = 'local',
    [ValidateSet('opencode', 'qoder')][string]$Provider = 'opencode',
    [switch]$DryRun,
    [switch]$NonInteractive,
    # Test seam: defaults to the repository root.
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

# -Provider qoder is valid only in the local-dev topology: the compose backend runs in a
# container, where the OpenSandbox endpoints are unreachable, so an explicit qoder request
# must fail loudly instead of being silently served by langchain.
if ($Provider -eq 'qoder' -and $Mode -ne 'local') {
    throw "-Provider qoder requires the local-dev topology. -Mode compose is langchain-only (its backend runs in a container and cannot reach the OpenSandbox endpoints). Run: pwsh -File scripts\start.ps1 -Provider qoder"
}

. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
. (Join-Path $PSScriptRoot "lib/env-setup.ps1")
. (Join-Path $PSScriptRoot "lib/preflight.ps1")

$RunDir = Join-Path $ProjectRoot ".run"

# Effective provider: the local-dev topology honours -Provider (opencode by default, qoder
# as an explicit opt-in); compose is langchain-only. This must NOT be assigned back into
# $Provider: PowerShell variables are case-insensitive, so that would trip the parameter's
# ValidateSet (compose would die with 'langchain is not a valid value for Provider'). The id
# is lowercased here because the ValidateSet match is case-insensitive too: -Provider Qoder
# is accepted, and without normalization its raw casing would leak into the summary and into
# the backend's case-sensitive --adk.default-provider lookup.
$providerId = if ($Mode -eq 'local') { $Provider.ToLowerInvariant() } else { 'langchain' }
$topology = if ($Mode -eq 'local') { 'local-dev (backend + frontend on host)' } else { 'full-stack compose (backend in a container)' }
# Compose runs three phases (environment, stack bring-up, report); the local-dev flow runs eight.
$phaseTotal = if ($Mode -eq 'compose') { 3 } else { 8 }

# Qoder mode assets. The image tag is authoritative in act-app/src/main/resources/application.yml
# (`qoder.image`) and in Ensure-QoderSandboxImage's default; keep the three in step. The token
# file holds the locally generated MCP bearer (never printed) that the backend and its sandboxes
# share - the qoder refusal below is what keeps that endpoint authenticated (design Section 6.2).
$qoderSandboxImage = 'aria-conductor/qoder-sandbox:0.1'
$qoderTokenFile = Join-Path $RunDir 'mcp-token'
# Qoder-only summary lines: the default (opencode) mode block stays unchanged.
$summaryImage = if ($providerId -eq 'qoder') { $qoderSandboxImage } else { '' }
$summaryMcpAuth = if ($providerId -eq 'qoder') { "token (token file: $qoderTokenFile)" } else { '' }

function Write-Phase([int]$Number, [int]$Total, [string]$Text) {
    Write-Host ("[{0}/{1}] {2}" -f $Number, $Total, $Text) -ForegroundColor Cyan
}

function Get-EnvValue([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

# 256 bits of CSPRNG output as lowercase hex. Used for the qoder mode MCP bearer;
# McpTokenFilter refuses an empty token, so it must never be blank.
function New-McpToken {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Write-ModeSummary([string]$Topology, [string]$Provider, [string]$RuntimeLine,
                          [string]$OpenSandboxUrl, [hashtable]$Checks,
                          [string]$SandboxImage, [string]$McpAuthLine) {
    Write-Host ""
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Aria Conductor - READY" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Topology : $Topology"
    Write-Host "  Provider : $Provider"
    # Empty for the default provider: its mode block is deliberately unchanged.
    if ($SandboxImage) { Write-Host "  Image    : $SandboxImage" }
    if ($McpAuthLine) { Write-Host "  MCP auth : $McpAuthLine" }
    Write-Host "  Runtime  : $RuntimeLine"
    if ($OpenSandboxUrl) { Write-Host "  OpenSandbox: $OpenSandboxUrl" }
    Write-Host "  Database : h2 (file)"
    Write-Host "---------------------------------------------------------"
    foreach ($k in $Checks.Keys) {
        Write-Host ("  {0,-11}: {1,-40} [{2}]" -f $k, $Checks[$k].Url, $Checks[$k].Result)
    }
    Write-Host "  Swagger  : http://localhost:8080/swagger-ui.html"
    Write-Host "---------------------------------------------------------"
    Write-Host "  Logs : .run\backend.log   .run\frontend.log"
    Write-Host "  Stop : pwsh -File scripts\stop.ps1"
    Write-Host "=========================================================" -ForegroundColor Green
}

# ── Phase 1: environment check ───────────────────────────────────────────────
Write-Host "Aria Conductor - one-click start ($Mode)" -ForegroundColor Cyan
Write-Phase 1 $phaseTotal "Checking environment"

Load-DotEnv $ProjectRoot

# ── Qoder provider: pin MCP token auth, reject an unauthenticated override ───
# A qoder sandbox shares the host network with the backend, so the local default
# (aria.mcp.auth-mode=none, an open operator API) is a blocked configuration for it
# (design Section 6.2): qoder mode pins token auth and mints a local token file in phase 5.
# An explicit override to any other mode is refused rather than silently degraded. The
# default opencode path is untouched - no pinning, no token, no extra files.
if ($providerId -eq 'qoder') {
    $explicitAuthMode = [Environment]::GetEnvironmentVariable('ARIA_MCP_AUTH_MODE')
    if ($explicitAuthMode -and $explicitAuthMode.Trim().ToLowerInvariant() -ne 'token') {
        throw ("Refusing to start the qoder provider with ARIA_MCP_AUTH_MODE='$explicitAuthMode': qoder " +
               "sandboxes can reach the backend's operator APIs, so an unauthenticated MCP endpoint is a " +
               "blocked configuration (design Section 6.2). Remove the override from .env (or set it to " +
               "'token') and retry.")
    }
    $env:ARIA_MCP_AUTH_MODE = 'token'
    if ($DryRun) {
        Write-Host "      would pin MCP token auth (token file: $qoderTokenFile)" -ForegroundColor DarkGray
    }
}

# This flow targets podman by default: the opencode sandbox needs a runtime whose socket
# the OpenSandbox server can mount, and Docker Desktop is commonly installed alongside
# podman, where auto-detection would prefer docker. Pin podman when its CLI is present and
# only fall back to docker with a loud warning. Whatever is chosen here is what Phase 2
# records in .env, so the next run agrees with this one.
if (-not $env:CONTAINER_RUNTIME) {
    if (Get-Command podman -ErrorAction SilentlyContinue) {
        $env:CONTAINER_RUNTIME = 'podman'
    } else {
        Write-Host "      podman is not installed - falling back to docker" -ForegroundColor Yellow
        $env:CONTAINER_RUNTIME = 'docker'
    }
}

# Repair the machine *before* anything probes `podman info`: a stopped machine makes that
# probe fail, and a failed probe is what used to send this flow to docker, which then got
# written into .env and stuck. The CLI being present is what makes podman the right choice
# here - the machine is merely asleep. This is the only call site; it covers the
# auto-detected and the .env-pinned podman paths, and no-ops when podman is absent.
if ($env:CONTAINER_RUNTIME -eq 'podman') {
    if (Start-PodmanMachineIfNeeded) { Write-Host "      podman machine started" -ForegroundColor DarkGray }
}

$runtimeInfo = Resolve-ContainerRuntime
$runtime = $runtimeInfo.Runtime
if (-not $runtime) { throw "No container runtime available. Install podman (or docker) and retry." }
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
# Only local-dev numbers this as a phase of its own: compose's second phase is the stack
# bring-up, so there the .env check is part of the environment phase.
if ($Mode -eq 'local') { Write-Phase 2 $phaseTotal "Checking .env" }
# Only the podman path can read a podman socket: Get-SandboxSocketPath shells out to
# `podman info`, so asking it on the docker fallback would pin a podman socket in .env next
# to CONTAINER_RUNTIME=docker. Docker's own socket lives at a fixed path.
if ($runtime -eq 'podman') {
    $socket = Get-SandboxSocketPath
    if (-not $socket) { throw "Could not read the podman socket path (podman info). Is the machine running?" }
} else {
    $socket = '/var/run/docker.sock'
}
$envPath = Join-Path $ProjectRoot '.env'
if ($DryRun) {
    # -DryRun promises "Nothing is mutated", so it must not call Ensure-EnvFile: on a fresh
    # checkout that would create .env. Get-EnvPlan is the same decision, read-only.
    $plan = Get-EnvPlan -ProjectRoot $ProjectRoot -SandboxSocket $socket `
        -ContainerRuntime $runtime -ApiKey $env:LLM_API_KEY
    if (-not $plan.Exists) {
        Write-Host "      would create .env" -ForegroundColor DarkGray
    } elseif ($plan.Missing.Count -gt 0) {
        Write-Host "      would add to .env: $($plan.Missing -join ', ')" -ForegroundColor DarkGray
    }
} else {
    $envResult = Ensure-EnvFile -ProjectRoot $ProjectRoot -SandboxSocket $socket `
        -ContainerRuntime $runtime -NonInteractive:$NonInteractive -ApiKey $env:LLM_API_KEY
    if ($envResult.Created) {
        Write-Host "      created .env" -ForegroundColor DarkGray
    } elseif ($envResult.AddedKeys.Count -gt 0) {
        Write-Host "      added to .env: $($envResult.AddedKeys -join ', ')" -ForegroundColor DarkGray
    }
}
# A run with no .env at all (only reachable under -DryRun) has nothing to validate; -DryRun
# has to succeed on a fresh checkout instead of failing a check it could never pass.
if (Test-Path $envPath) {
    $llmKey = $null
    foreach ($line in (Get-Content $envPath)) {
        if ($line.Trim() -match '^LLM_API_KEY=(.*)$') { $llmKey = $Matches[1] }
    }
    if (-not (Test-LlmKeyValid $llmKey)) {
        throw "LLM_API_KEY is missing or still a placeholder in .env. Set a real key and retry."
    }
}

# ── Compose mode: the runtime owns the whole stack ───────────────────────────
if ($Mode -eq 'compose') {
    if ($DryRun) {
        Write-Host ""
        Write-Host "-DryRun: environment OK, nothing started (compose mode would run: $runtime compose up -d --build)." -ForegroundColor Yellow
        exit 0
    }
    Write-Phase 2 $phaseTotal "Starting the full-stack compose stack"
    Push-Location $ProjectRoot
    try {
        & $runtime compose up -d --build
        if ($LASTEXITCODE -ne 0) { throw "compose up failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    $composeBackendPort = Get-EnvValue 'BACKEND_PORT' '8080'
    $composeDashboardPort = Get-EnvValue 'FRONTEND_PORT' '3000'
    Write-Phase 3 $phaseTotal "Reporting"
    Write-Host ""
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Aria Conductor - COMPOSE STACK STARTING" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Topology : $topology"
    Write-Host "  Provider : $providerId"
    Write-Host "  Runtime  : $runtime ($($runtimeInfo.Mode))"
    Write-Host "  Dashboard: http://localhost:$composeDashboardPort"
    Write-Host "  Backend  : http://localhost:$composeBackendPort"
    Write-Host "---------------------------------------------------------"
    Write-Host "  NOTE: the opencode provider is NOT usable in this topology - the" -ForegroundColor Yellow
    Write-Host "  containerized backend cannot reach the OpenSandbox endpoints. This" -ForegroundColor Yellow
    Write-Host "  stack runs the langchain provider. Run without -Mode for opencode." -ForegroundColor Yellow
    Write-Host "---------------------------------------------------------"
    Write-Host "  Logs : $runtime compose logs -f"
    Write-Host "  Stop : $runtime compose down"
    Write-Host "=========================================================" -ForegroundColor Green
    exit 0
}

# Ports feed both the summary and the port pre-check.
$backendPort = [int](Get-EnvValue 'BACKEND_PORT' '8080')
$frontendPort = [int](Get-EnvValue 'VITE_PORT' '5173')
$sandboxPort = [int](Get-EnvValue 'OPENSANDBOX_PORT' '8090')

# ── Dry run: stop before anything is mutated ─────────────────────────────────
if ($DryRun) {
    Write-ModeSummary -Topology $topology -Provider $providerId `
        -RuntimeLine "$runtime ($($runtimeInfo.Mode))" `
        -OpenSandboxUrl "http://localhost:$sandboxPort" -Checks @{} `
        -SandboxImage $summaryImage -McpAuthLine $summaryMcpAuth
    Write-Host ""
    Write-Host "-DryRun: environment OK, nothing started." -ForegroundColor Yellow
    exit 0
}

# ── Phase 3: resource preparation ────────────────────────────────────────────
Write-Phase 3 $phaseTotal "Preparing container resources"
if ($providerId -eq 'qoder') {
    Ensure-QoderSandboxImage -Runtime $runtime -ProjectRoot $ProjectRoot -Tag $qoderSandboxImage | Out-Null
} else {
    Ensure-OpencodeSandboxImage -Runtime $runtime -ProjectRoot $ProjectRoot | Out-Null
}
Ensure-OpenSandboxServer -Runtime $runtime -ProjectRoot $ProjectRoot | Out-Null

# ── Phase 4: port pre-check ──────────────────────────────────────────────────
Write-Phase 4 $phaseTotal "Checking ports"
# Only the ports this script starts on the host. The OpenSandbox port is deliberately excluded:
# `aria-opensandbox` publishes 127.0.0.1:${OPENSANDBOX_PORT:-8090} and Ensure-OpenSandboxServer
# (phase 3) owns it. On any restart where the server is already up that helper no-ops, so
# checking the port here would find our own container's port forward and ask the user to
# kill the OpenSandbox server this launcher just ensured.
foreach ($port in @($backendPort, $frontendPort)) {
    $holder = Get-PortHolder -Port $port
    if ($holder) {
        Write-Host "      port $port is held by $($holder.Name) (PID $($holder.Pid))" -ForegroundColor Yellow
        if ($NonInteractive) { throw "Port $port is in use. Stop PID $($holder.Pid) ($($holder.Name)) and retry." }
        $answer = [string](Read-Host "      Stop PID $($holder.Pid) ($($holder.Name))? [y/N]")
        # The [string] cast matters: with stdin not connected Read-Host returns AutomationNull
        # and `AutomationNull -notmatch '^(?i)y'` is not $true at all, so the guard used to
        # fall through to taskkill with no consent at all. No answer means no.
        if ([string]::IsNullOrWhiteSpace($answer) -or $answer -notmatch '^(?i)y') {
            throw "Aborted: port $port is still in use."
        }
        & taskkill /PID $holder.Pid /T /F | Out-Null
    }
}

# ── Phase 5: start ───────────────────────────────────────────────────────────
Write-Phase 5 $phaseTotal "Starting services"
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
if ($providerId -eq 'qoder') {
    # Minted here rather than during the checks above, so a dry run and a run aborted before
    # this phase leave no secret on disk. ARIA_MCP_TOKEN is inherited by start-backend.ps1
    # (Start-Process passes this process's environment), which hands it to Spring Boot; the
    # file is the local copy the operator and the E2E harness can read.
    if ([string]::IsNullOrWhiteSpace($env:ARIA_MCP_TOKEN)) {
        $env:ARIA_MCP_TOKEN = New-McpToken
    }
    Set-Content -Path $qoderTokenFile -Value $env:ARIA_MCP_TOKEN -NoNewline
    # Tighten the file to the current user, the PowerShell twin of the bash launcher's
    # chmod 600: drop the inherited ACEs and grant only this user. Read+write, not read
    # alone, because the next qoder run rewrites the file right here and a read-only DACL
    # would fail that Set-Content with access denied. Nothing in this repository reads the
    # file back (the backend receives the token through ARIA_MCP_TOKEN, inherited from this
    # process), and .run removal keeps working (delete-child comes from the parent entry).
    # Best effort like the bash chmod: a failure here must not stop the launcher.
    if ($IsWindows) {
        icacls $qoderTokenFile /inheritance:r /grant:r "$([System.Security.Principal.WindowsIdentity]::GetCurrent().Name):(R,W)" | Out-Null
    }
    Write-Host "      MCP auth token written to $qoderTokenFile" -ForegroundColor DarkGray
}
$env:VITE_BACKEND_PORT = "$backendPort"

$backend = Start-Process pwsh -ArgumentList @(
        '-NoProfile', '-File', (Join-Path $PSScriptRoot 'start-backend.ps1'),
        '-AdkProvider', $providerId
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
Write-Phase 6 $phaseTotal "Waiting for health"
$checks = [ordered]@{}
# 900s, not 300s: start-backend.ps1 runs `mvn install -DskipTests` on a cold checkout before
# Spring Boot can even start, and that build has to fit inside this budget.
$backendOk = Wait-HttpHealthy -Url "http://localhost:$backendPort/actuator/health" `
    -TimeoutSeconds 900 -ExpectBodyMatch '"status"\s*:\s*"UP"'
$dashboardOk = Wait-HttpHealthy -Url "http://localhost:$frontendPort" -TimeoutSeconds 120
$sandboxOk = Wait-HttpHealthy -Url "http://localhost:$sandboxPort/health" -TimeoutSeconds 120
$checks['Dashboard'] = @{ Url = "http://localhost:$frontendPort"; Result = $(if ($dashboardOk) { 'OK' } else { 'FAIL' }) }
$checks['Backend'] = @{ Url = "http://localhost:$backendPort"; Result = $(if ($backendOk) { 'OK' } else { 'FAIL' }) }
$checks['OpenSandbox'] = @{ Url = "http://localhost:$sandboxPort"; Result = $(if ($sandboxOk) { 'OK' } else { 'FAIL' }) }
$allOk = $backendOk -and $dashboardOk -and $sandboxOk

# ── Phase 7: mode confirmation ───────────────────────────────────────────────
Write-Phase 7 $phaseTotal "Reporting"
Write-ModeSummary -Topology $topology -Provider $providerId `
    -RuntimeLine "$runtime ($($runtimeInfo.Mode))" `
    -OpenSandboxUrl "http://localhost:$sandboxPort" -Checks $checks `
    -SandboxImage $summaryImage -McpAuthLine $summaryMcpAuth
if ($allOk) {
    Start-Process "http://localhost:$frontendPort" | Out-Null
}

# ── Phase 8: exit ────────────────────────────────────────────────────────────
Write-Phase 8 $phaseTotal "Done"
if (-not $allOk) {
    Write-Host "One or more services did not become healthy. Tail of the backend log:" -ForegroundColor Red
    Get-Content (Join-Path $RunDir 'backend.log') -Tail 25 -ErrorAction SilentlyContinue
    Write-Host "Retry after fixing, or stop everything with: pwsh -File scripts\stop.ps1" -ForegroundColor Yellow
    exit 1
}
Write-Host "All services healthy." -ForegroundColor Green
exit 0

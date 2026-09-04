#!/usr/bin/env pwsh
param(
    [string]$Profile = "h2",
    [switch]$SkipBuild,
    [string]$AdkProvider = "opencode",
    [switch]$SkipSandbox
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"

# Shared container-runtime helpers (Load-DotEnv, Resolve-ContainerRuntime)
. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
Load-DotEnv $ProjectRoot

# Prerequisites check
function Test-Command($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Error "$cmd is not installed. $hint"
        exit 1
    }
}

Test-Command "java" "Install JDK 21: https://adoptium.net/"
Test-Command "mvn" "Install Maven 3.9+: https://maven.apache.org/"

# ── Preflight: report toolchain status (non-fatal warnings only) ──
Write-Host "Preflight:" -ForegroundColor Cyan

$javaVersion = (java -version 2>&1 | Select-Object -First 1)
Write-Host "  Java  : $javaVersion"
if ($javaVersion -notmatch '"21') {
    Write-Warning "JDK 21 is recommended (found: $javaVersion)."
}

$mvnVersion = (mvn -version 2>&1 | Select-Object -First 1)
Write-Host "  Maven : $mvnVersion"

# Container runtime status (required only for -AdkProvider opencode)
foreach ($rt in @("docker", "podman")) {
    if (Get-Command $rt -ErrorAction SilentlyContinue) {
        & $rt info *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  $rt : running"
        } else {
            Write-Warning "$rt is installed but not running. Required only for -AdkProvider opencode."
        }
    } else {
        Write-Host "  $rt : not installed" -ForegroundColor DarkGray
    }
}

$runtimeInfo = $null
$runtimeError = $null
try {
    $runtimeInfo = Resolve-ContainerRuntime
} catch {
    $runtimeError = $_.Exception.Message
}
if ($runtimeError) {
    Write-Warning "Container runtime: $runtimeError"
} elseif ($runtimeInfo.Runtime) {
    $reason = if ($runtimeInfo.Mode -eq "explicit") { "explicit: CONTAINER_RUNTIME" } else { "auto-detected" }
    Write-Host "  Container runtime: $($runtimeInfo.Runtime) ($reason)"
} else {
    Write-Warning "No container runtime available. Required only for -AdkProvider opencode."
}

if (-not $env:DEEPSEEK_API_KEY) {
    Write-Warning "DEEPSEEK_API_KEY is not set; real LLM calls will fail."
} else {
    Write-Host "  DeepSeek API key: set"
}

if (-not $env:GH_TOKEN) {
    Write-Warning "GH_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox."
}

# Windows: prefer the `py` launcher so the ADK subprocess can find a Python runtime.
$env:ADK_PYTHON = "py"

# ── OpenSandbox server (required for opencode provider) ──
if ($AdkProvider -eq "opencode" -and -not $SkipSandbox) {
    Write-Host "Checking OpenSandbox server..." -ForegroundColor Cyan
    if ($runtimeError) {
        Write-Error "Container runtime unavailable: $runtimeError"
        exit 1
    }
    $rt = $runtimeInfo.Runtime
    if (-not $rt) {
        Write-Error "Neither docker nor podman is available. The opencode provider requires a container runtime for the OpenSandbox server. Install Docker or podman, or use -SkipSandbox / -AdkProvider langchain."
        exit 1
    }

    $sandboxRunning = & $rt ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>$null
    if (-not $sandboxRunning) {
        Write-Host "Starting OpenSandbox server ($rt compose)..." -ForegroundColor Yellow
        Push-Location $ProjectRoot
        & $rt compose up -d opensandbox-server
        if ($LASTEXITCODE -ne 0) {
            if ($rt -eq "podman") {
                Write-Host "podman hint: verify the socket is enabled (podman machine ssh 'systemctl --user is-active podman.socket') and SANDBOX_SOCKET in .env matches its VM path." -ForegroundColor Yellow
            }
            Write-Error "Failed to start OpenSandbox server"
            Pop-Location; exit 1
        }
        Pop-Location
        Start-Sleep -Seconds 3
    }

    # Default OpenSandbox URL for local dev (host port 8090)
    if (-not $env:OPENCODE_SANDBOX_SERVER_URL) {
        $env:OPENCODE_SANDBOX_SERVER_URL = "http://localhost:8090"
    }
}

# ── LLM credentials (injected into sandbox env for opencode provider) ──
if (-not $env:DEEPSEEK_API_KEY -and $env:LLM_API_KEY) {
    $env:DEEPSEEK_API_KEY = $env:LLM_API_KEY
}

Write-Host "Starting Aria Conductor backend..." -ForegroundColor Cyan
Write-Host "  Profile: $Profile"
Write-Host "  ADK Provider: $AdkProvider"
Write-Host "  Port: 8080"
if ($AdkProvider -eq "opencode") {
    Write-Host "  OpenSandbox: $($env:OPENCODE_SANDBOX_SERVER_URL)" -ForegroundColor DarkGray
}

Set-Location $BackendDir

if (-not $SkipBuild) {
    Write-Host "Installing backend modules (mvn install -DskipTests -q)..." -ForegroundColor Yellow
    mvn install -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "mvn install failed"; exit 1 }
}

Write-Host "Launching Spring Boot..." -ForegroundColor Green
# R-F6 mitigation: JDK 21 HttpClient HTTP/1.1 idle-connection keep-alive tuning.
# The default keepalive.timeout (1200s = 20min) is below the 15-31min opencode task window,
# so idle connections get dropped mid-task; raising it (plus a larger connection pool) reduces
# those drops. NOTE: this cannot fix opencode serve's own timeout on the sandbox side.
mvn spring-boot:run -pl act-app "-Dspring-boot.run.profiles=$Profile" "-Dspring-boot.run.jvmArguments=--enable-preview -Djdk.httpclient.keepalive.timeout=3600 -Djdk.httpclient.connectionPoolSize=8" "-Dspring-boot.run.arguments=--adk.default-provider=$AdkProvider"

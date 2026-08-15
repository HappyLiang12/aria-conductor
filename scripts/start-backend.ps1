#!/usr/bin/env pwsh
param(
    [string]$Profile = "h2",
    [switch]$SkipBuild,
    [string]$AdkProvider = "langchain",
    [switch]$SkipSandbox
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"

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

if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Docker: running"
    } else {
        Write-Warning "Docker Desktop is not running. Required only for -AdkProvider opencode."
    }
} else {
    Write-Warning "Docker is not installed. Required only for -AdkProvider opencode."
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
    try {
        $null = docker ps 2>&1
    } catch {
        Write-Error "Docker is not running. Start Docker Desktop first, or use -SkipSandbox."
        exit 1
    }

    $sandboxRunning = docker ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>$null
    if (-not $sandboxRunning) {
        Write-Host "Starting OpenSandbox server (docker compose)..." -ForegroundColor Yellow
        Push-Location $ProjectRoot
        docker compose up -d opensandbox-server
        if ($LASTEXITCODE -ne 0) { Write-Error "Failed to start OpenSandbox server"; Pop-Location; exit 1 }
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

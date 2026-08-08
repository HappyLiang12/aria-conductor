#!/usr/bin/env pwsh
param(
    [string]$Profile = "h2",
    [switch]$SkipBuild,
    [string]$AdkProvider = "opencode",
    [switch]$SkipSandbox
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
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

$jarPath = "act-app\target\act-app-0.1.0-SNAPSHOT.jar"
if (-not $SkipBuild -and -not (Test-Path $jarPath)) {
    Write-Host "Building..." -ForegroundColor Yellow
    mvn clean install -DskipTests -B
    if ($LASTEXITCODE -ne 0) { Write-Error "Build failed"; exit 1 }
}

Write-Host "Launching Spring Boot..." -ForegroundColor Green
mvn spring-boot:run -pl act-app "-Dspring-boot.run.profiles=$Profile" "-Dspring-boot.run.jvmArguments=--enable-preview"

#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Aria Conductor - Quick Start" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

$ProjectRoot = Split-Path -Parent $PSScriptRoot

# Shared container-runtime helpers (Load-DotEnv, Resolve-ContainerRuntime)
. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
Load-DotEnv $ProjectRoot

# Resolve container runtime (docker | podman; strict when CONTAINER_RUNTIME is set)
$rt = $null
$rtLabel = ""
try {
    $runtimeInfo = Resolve-ContainerRuntime
    $rt = $runtimeInfo.Runtime
    $rtLabel = if ($runtimeInfo.Mode -eq "explicit") { "explicit: CONTAINER_RUNTIME" } else { "auto-detected" }
} catch {
    Write-Error $_.Exception.Message
    # exit 1 is defensive: Write-Error throws under EAP=Stop (message still displays, exit code still 1)
    exit 1
}

if ($rt) {
    Write-Host "Container runtime: $rt ($rtLabel). Starting with Compose..." -ForegroundColor Green
    Write-Host ""

    Set-Location $ProjectRoot

    if (-not (Test-Path ".env")) {
        if (Test-Path ".env.example") {
            Write-Host "Creating .env from .env.example..." -ForegroundColor Yellow
            Copy-Item ".env.example" ".env"
            Write-Host ""
            Write-Host "IMPORTANT: Edit .env and set your LLM_API_KEY before continuing." -ForegroundColor Red
            Write-Host "  notepad .env"
            Write-Host ""
            Read-Host "Press Enter after setting your API key (or Ctrl+C to abort)"
        }
    }

    Write-Host "Building and starting services..." -ForegroundColor Yellow
    Write-Host "  (includes OpenSandbox server for opencode agent runtime)" -ForegroundColor DarkGray
    & $rt compose up -d --build
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Compose failed to start services (exit $LASTEXITCODE). Check $rt logs."
        exit 1
    }

    Write-Host ""
    Write-Host "Services:" -ForegroundColor Green
    Write-Host "  Dashboard:       http://localhost:3000" -ForegroundColor White
    Write-Host "  Backend:         http://localhost:8080" -ForegroundColor White
    Write-Host "  ADK:             http://localhost:9300" -ForegroundColor White
    Write-Host "  OpenSandbox:     http://localhost:8090" -ForegroundColor White
    Write-Host "  Swagger:         http://localhost:8080/swagger-ui.html" -ForegroundColor White
    Write-Host ""
    Write-Host "ADK default: opencode (sandbox; needs the OpenSandbox server started above). Fallback langchain: set ADK_DEFAULT_PROVIDER=langchain in .env where supported (the containerized compose backend cannot reach the sandbox - see README topology note)." -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Commands:" -ForegroundColor Cyan
    Write-Host "  $rt compose ps              # Check status"
    Write-Host "  $rt compose logs -f         # View logs"
    Write-Host "  $rt compose down            # Stop"
} else {
    Write-Host "Neither docker nor podman detected. Falling back to local development mode." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "NOTE: OpenCode sandbox mode requires Docker or podman for the OpenSandbox server." -ForegroundColor Red
    Write-Host "      Without a container runtime, only langchain ADK provider is available." -ForegroundColor Red
    Write-Host ""

    # Check prerequisites
    $missing = @()
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { $missing += "java" }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { $missing += "maven" }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { $missing += "node" }
    if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { $missing += "pnpm" }

    if ($missing.Count -gt 0) {
        Write-Error "Missing prerequisites: $($missing -join ', ')"
        Write-Host "Install them and try again, or install Docker or podman for the easiest setup."
        exit 1
    }

    Write-Host "All prerequisites found. Starting services..." -ForegroundColor Green
    Write-Host ""

    # No-container-runtime path: default ADK provider is langchain (opencode requires docker/podman)
    $adkProvider = "langchain"
    Write-Host "Using ADK provider: $adkProvider (container runtime required for opencode)" -ForegroundColor Yellow

    Write-Host "Starting backend..." -ForegroundColor Yellow
    Start-Process pwsh -ArgumentList "-NoProfile", "-File", "$PSScriptRoot\start-backend.ps1", "-AdkProvider", $adkProvider, "-SkipSandbox" -NoNewWindow

    Start-Sleep -Seconds 5

    Write-Host "Starting frontend..." -ForegroundColor Yellow
    Start-Process pwsh -ArgumentList "-NoProfile", "-File", "$PSScriptRoot\start-frontend.ps1" -NoNewWindow

    Write-Host ""
    Write-Host "Services:" -ForegroundColor Green
    Write-Host "  Dashboard:  http://localhost:5173" -ForegroundColor White
    Write-Host "  Backend:    http://localhost:8080" -ForegroundColor White
    Write-Host ""
    Write-Host "To use opencode provider, install Docker or podman and re-run this script." -ForegroundColor Cyan
}

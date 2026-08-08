#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Aria Conductor - Quick Start" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# Check Docker
$dockerAvailable = $false
try {
    $null = Get-Command docker -ErrorAction Stop
    $null = docker compose version 2>&1
    $dockerAvailable = $true
} catch {}

if ($dockerAvailable) {
    Write-Host "Docker detected. Starting with Docker Compose..." -ForegroundColor Green
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
    docker compose up -d --build

    Write-Host ""
    Write-Host "Services:" -ForegroundColor Green
    Write-Host "  Dashboard:       http://localhost:3000" -ForegroundColor White
    Write-Host "  Backend:         http://localhost:8080" -ForegroundColor White
    Write-Host "  ADK:             http://localhost:9300" -ForegroundColor White
    Write-Host "  OpenSandbox:     http://localhost:8090" -ForegroundColor White
    Write-Host "  Swagger:         http://localhost:8080/swagger-ui.html" -ForegroundColor White
    Write-Host ""
    Write-Host "ADK provider: langchain (default); use opencode with sandbox by setting -AdkProvider opencode" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Commands:" -ForegroundColor Cyan
    Write-Host "  docker compose ps              # Check status"
    Write-Host "  docker compose logs -f         # View logs"
    Write-Host "  docker compose down            # Stop"
} else {
    Write-Host "Docker not found. Falling back to local development mode." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "NOTE: OpenCode sandbox mode requires Docker for the OpenSandbox server." -ForegroundColor Red
    Write-Host "      Without Docker, only langchain ADK provider is available." -ForegroundColor Red
    Write-Host ""

    # Check prerequisites
    $missing = @()
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { $missing += "java" }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { $missing += "maven" }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { $missing += "node" }
    if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { $missing += "pnpm" }

    if ($missing.Count -gt 0) {
        Write-Error "Missing prerequisites: $($missing -join ', ')"
        Write-Host "Install them and try again, or install Docker for the easiest setup."
        exit 1
    }

    Write-Host "All prerequisites found. Starting services..." -ForegroundColor Green
    Write-Host ""

    # No-Docker path: default ADK provider is langchain (opencode requires Docker)
    $adkProvider = "langchain"
    Write-Host "Using ADK provider: $adkProvider (Docker required for opencode)" -ForegroundColor Yellow

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
    Write-Host "To use opencode provider, install Docker and re-run this script." -ForegroundColor Cyan
}

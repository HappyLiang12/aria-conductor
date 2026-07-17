#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$FrontendDir = Join-Path $ProjectRoot "agent-control-tower\act-dashboard"

function Test-Command($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Error "$cmd is not installed. $hint"
        exit 1
    }
}

Test-Command "node" "Install Node.js 20+: https://nodejs.org/"
Test-Command "pnpm" "Install pnpm: npm install -g pnpm"

Write-Host "Starting Aria Conductor frontend..." -ForegroundColor Cyan
Set-Location $FrontendDir

if (-not (Test-Path "node_modules")) {
    Write-Host "Installing dependencies..." -ForegroundColor Yellow
    pnpm install --frozen-lockfile
}

Write-Host "  Dev server: http://localhost:5173" -ForegroundColor Green
pnpm dev
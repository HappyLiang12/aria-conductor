#!/usr/bin/env pwsh
<#
.SYNOPSIS
Stops the services started by scripts/start.ps1.

.PARAMETER All
Also stops the sandbox containers and the podman machine.
#>
param(
    [switch]$All,
    # Test seams.
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$SkipContainers
)

$ErrorActionPreference = "Continue"
$RunDir = Join-Path $ProjectRoot ".run"

Write-Host "Stopping Aria Conductor services..." -ForegroundColor Cyan

foreach ($name in @('backend', 'frontend')) {
    $pidFile = Join-Path $RunDir "$name.pid"
    if (-not (Test-Path $pidFile)) { continue }
    $processId = (Get-Content $pidFile -Raw).Trim()
    if ($processId -match '^\d+$') {
        & taskkill /PID $processId /T /F *> $null
        Write-Host "  stopped $name (PID $processId)" -ForegroundColor DarkGray
    }
}

if (-not $SkipContainers) {
    Push-Location $ProjectRoot
    try {
        & podman compose stop opensandbox-server *> $null
        Write-Host "  stopped aria-opensandbox" -ForegroundColor DarkGray
        if ($All) {
            $sandboxes = & podman ps --filter 'name=sandbox-' --format '{{.Names}}' 2>$null
            foreach ($s in $sandboxes) { & podman stop $s *> $null }
            & podman machine stop *> $null
            Write-Host "  stopped sandbox containers and the podman machine" -ForegroundColor DarkGray
        }
    } finally {
        Pop-Location
    }
}

Remove-Item $RunDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "Done." -ForegroundColor Green
exit 0

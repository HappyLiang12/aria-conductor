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
    if ($processId -notmatch '^\d+$') { continue }

    $proc = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host "  skipped $name (PID $processId is no longer running)" -ForegroundColor DarkGray
        continue
    }
    # The pid file only records a number, and PIDs get recycled, so a stale file can point at
    # an unrelated process. start.ps1 launches these children with pwsh, so anything else is
    # not ours and must not be killed.
    if ($proc.ProcessName -notin @('pwsh', 'powershell')) {
        Write-Host "  skipping $name (PID $processId is not one of our processes)" -ForegroundColor Yellow
        continue
    }

    & taskkill /PID $processId /T /F *> $null
    if ($LASTEXITCODE -eq 0 -or -not (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
        Write-Host "  stopped $name (PID $processId)" -ForegroundColor DarkGray
    } else {
        Write-Host "  FAILED to stop $name (PID $processId, taskkill exit $LASTEXITCODE)" -ForegroundColor Yellow
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

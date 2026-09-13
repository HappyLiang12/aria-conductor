#!/usr/bin/env pwsh
<#
.SYNOPSIS
Stops the services started by scripts/start.ps1.

.PARAMETER All
Also stops the sandbox containers and, when the resolved runtime is podman, the machine.
#>
param(
    [switch]$All,
    # Test seams.
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$SkipContainers
)

$ErrorActionPreference = "Continue"
$RunDir = Join-Path $ProjectRoot ".run"

# Shared container-runtime helpers (Load-DotEnv, Resolve-ContainerRuntime)
. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")

Write-Host "Stopping Aria Conductor services..." -ForegroundColor Cyan

# Set when a recorded pid resisted taskkill: .run/ must then survive so the retry can find it.
$killFailed = $false

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
        $killFailed = $true
        Write-Host "  FAILED to stop $name (PID $processId, taskkill exit $LASTEXITCODE)" -ForegroundColor Yellow
    }
}

if (-not $SkipContainers) {
    # Resolved exactly as start.ps1 phase 1 resolves it - .env first, then podman when its CLI
    # is present, docker otherwise. Any other rule here would target a different engine than
    # the one that started the sandbox, and the stop would silently miss it.
    Load-DotEnv $ProjectRoot
    if (-not $env:CONTAINER_RUNTIME) {
        if (Get-Command podman -ErrorAction SilentlyContinue) {
            $env:CONTAINER_RUNTIME = 'podman'
        } else {
            $env:CONTAINER_RUNTIME = 'docker'
        }
    }
    $runtime = $null
    try {
        $runtime = (Resolve-ContainerRuntime).Runtime
    } catch {
        # Stopping must not abort on this: the recorded pids still get terminated below.
        Write-Host "  WARNING: could not resolve the container runtime: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "  the OpenSandbox server was NOT stopped" -ForegroundColor Yellow
    }

    if ($runtime) {
        Push-Location $ProjectRoot
        try {
            & $runtime compose stop opensandbox-server *> $null
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  stopped aria-opensandbox" -ForegroundColor DarkGray
            } else {
                Write-Host "  WARNING: $runtime compose stop opensandbox-server failed (exit $LASTEXITCODE)" -ForegroundColor Yellow
            }
            if ($All) {
                $sandboxes = & $runtime ps --filter 'name=sandbox-' --format '{{.Names}}' 2>$null
                if ($LASTEXITCODE -ne 0) {
                    Write-Host "  WARNING: $runtime ps failed (exit $LASTEXITCODE) - sandbox containers were NOT stopped" -ForegroundColor Yellow
                }
                foreach ($s in $sandboxes) {
                    & $runtime stop $s *> $null
                    if ($LASTEXITCODE -eq 0) {
                        Write-Host "  stopped sandbox container $s" -ForegroundColor DarkGray
                    } else {
                        Write-Host "  WARNING: could not stop sandbox container $s (exit $LASTEXITCODE)" -ForegroundColor Yellow
                    }
                }
                if ($runtime -eq 'podman') {
                    & podman machine stop *> $null
                    if ($LASTEXITCODE -eq 0) {
                        Write-Host "  stopped the podman machine" -ForegroundColor DarkGray
                    } else {
                        Write-Host "  WARNING: podman machine stop failed (exit $LASTEXITCODE)" -ForegroundColor Yellow
                    }
                }
            }
        } finally {
            Pop-Location
        }
    }
}

# A pid that survived taskkill is still recorded here, so the retry needs these files.
if ($killFailed) {
    Write-Host "  keeping .run/ - a recorded process could not be stopped, retry this script" -ForegroundColor Yellow
} else {
    Remove-Item $RunDir -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "Done." -ForegroundColor Green
exit 0

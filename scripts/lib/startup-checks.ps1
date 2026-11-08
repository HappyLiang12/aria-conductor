#!/usr/bin/env pwsh
# Pure startup checks shared by Aria Conductor's start scripts.
# No side effects: safe to dot-source and self-test without starting a stack.
# Dot-source from another script: . "$PSScriptRoot/startup-checks.ps1"

<#
.SYNOPSIS
Resolves the run-owned workspace roots (F30).

The defaults live OUTSIDE the checkout, so a bare `git` in a run's scratch directory
cannot resolve to the operator repository and result clones never land inside it:
  base = $LOCALAPPDATA\AriaConductor\workspaces   (Windows, LOCALAPPDATA set)
       = $HOME/.aria-conductor/workspaces         (otherwise; pwsh resolves $HOME on
                                                   Windows and POSIX)
  runtime root = <base>/runs, result root = <base>/results

An operator-provided value (explicit parameter, or ARIA_WORKSPACES_RUNTIME_ROOT /
ARIA_WORKSPACES_RESULT_ROOT in the environment) always wins. Returns
@{ Base; RuntimeRoot; ResultRoot }.
#>
function Resolve-WorkspaceRoots {
    param(
        [string]$RuntimeRoot = $env:ARIA_WORKSPACES_RUNTIME_ROOT,
        [string]$ResultRoot = $env:ARIA_WORKSPACES_RESULT_ROOT,
        [string]$LocalAppData = $env:LOCALAPPDATA,
        [string]$UserHome = $HOME
    )

    $base = $null
    if ($LocalAppData) {
        $base = Join-Path $LocalAppData "AriaConductor/workspaces"
    } elseif ($UserHome) {
        $base = Join-Path $UserHome ".aria-conductor/workspaces"
    }

    if (-not $base -and (-not $RuntimeRoot -or -not $ResultRoot)) {
        throw "Cannot resolve the default workspace base: set LOCALAPPDATA or HOME, or set ARIA_WORKSPACES_RUNTIME_ROOT and ARIA_WORKSPACES_RESULT_ROOT explicitly."
    }

    [pscustomobject]@{
        Base        = $base
        RuntimeRoot = if ($RuntimeRoot) { $RuntimeRoot } elseif ($base) { Join-Path $base "runs" } else { $null }
        ResultRoot  = if ($ResultRoot) { $ResultRoot } elseif ($base) { Join-Path $base "results" } else { $null }
    }
}

<#
.SYNOPSIS
Returns a reason string when packages/qoder-acp-bridge/dist is missing or stale (F31),
or $null when dist/main.js exists and nothing that feeds it is newer.

Stale means any file under src/ (recursively), or package.json, tsconfig.json or
pnpm-lock.yaml when present, has a newer LastWriteTimeUtc than dist/main.js. A bridge
directory without a src/ tree is not an error (the check stays quiet the moment dist
exists). The caller decides whether to rebuild or to fail with a actionable message.
#>
function Get-BridgeDistProblem {
    param([Parameter(Mandatory)][string]$BridgeDir)

    $distMain = Join-Path $BridgeDir "dist/main.js"
    if (-not (Test-Path -LiteralPath $distMain -PathType Leaf)) {
        return "dist/main.js is missing under '$BridgeDir'"
    }
    $distTime = (Get-Item -LiteralPath $distMain).LastWriteTimeUtc

    $newer = @()
    $srcDir = Join-Path $BridgeDir "src"
    if (Test-Path -LiteralPath $srcDir -PathType Container) {
        foreach ($file in Get-ChildItem -LiteralPath $srcDir -Recurse -File -Force -ErrorAction SilentlyContinue) {
            if ($file.LastWriteTimeUtc -gt $distTime) { $newer += $file }
        }
    }
    foreach ($name in @("package.json", "tsconfig.json", "pnpm-lock.yaml")) {
        $path = Join-Path $BridgeDir $name
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $item = Get-Item -LiteralPath $path -Force
            if ($item.LastWriteTimeUtc -gt $distTime) { $newer += $item }
        }
    }

    if ($newer.Count -gt 0) {
        $prefix = $BridgeDir.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) +
            [System.IO.Path]::DirectorySeparatorChar
        $names = $newer | ForEach-Object {
            $full = $_.FullName
            if ($full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                $full.Substring($prefix.Length)
            } else {
                $_.Name
            }
        }
        return "dist/main.js is older than: $($names -join ', ')"
    }

    return $null
}

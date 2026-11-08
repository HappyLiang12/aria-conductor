#!/usr/bin/env pwsh
# Pure startup checks shared by Aria Conductor's start scripts.
# No side effects: safe to dot-source and self-test without starting a stack.
# Dot-source from another script: . "$PSScriptRoot/startup-checks.ps1"

# Resolved when this file is loaded (dot-sourced), never from the caller's cwd: the
# bridge freshness helper lives next to this file even when a test runs from a temp dir.
$BridgeFreshnessHelperPath = Join-Path $PSScriptRoot "bridge-freshness.mjs"

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
or $null when dist/main.js exists and matches its build inputs.

Freshness is decided by scripts/lib/bridge-freshness.mjs when node is available: it
hashes src/** plus package.json, tsconfig.json and pnpm-lock.yaml with SHA-256 and
compares the result with dist/.build-inputs (written by the start scripts after a
rebuild). A missing dist is a problem. A manifest whose hash differs is a problem that
names the changed input(s). A missing or unusable manifest is the documented legacy
state and falls back to mtime: any input newer than dist/main.js is stale. Without
node (or without the helper) the same mtime comparison is used.

The caller decides whether to rebuild or to fail with an actionable message.
#>
function Get-BridgeDistProblem {
    param(
        [Parameter(Mandatory)][string]$BridgeDir,
        # Tests inject a bogus command to exercise the documented mtime fallback.
        [string]$NodeCommand = "node"
    )

    $distMain = Join-Path $BridgeDir "dist/main.js"
    if (-not (Test-Path -LiteralPath $distMain -PathType Leaf)) {
        return "dist/main.js is missing under '$BridgeDir'"
    }

    # 0 = fresh, 1 = problem (reason on stdout), 2 = usage/IO error. Anything the helper
    # cannot answer keeps the start path working through the legacy mtime comparison.
    if ($NodeCommand -and (Test-Path -LiteralPath $BridgeFreshnessHelperPath -PathType Leaf) -and
        (Get-Command $NodeCommand -ErrorAction SilentlyContinue)) {
        $reason = & $NodeCommand $BridgeFreshnessHelperPath "check" $BridgeDir 2>$null
        $nodeExit = $LASTEXITCODE
        if ($nodeExit -eq 0) {
            return $null
        }
        if ($nodeExit -eq 1) {
            $text = ($reason | Out-String).Trim()
            if ($text) { return $text }
            return "the bridge build inputs do not match dist/.build-inputs"
        }
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

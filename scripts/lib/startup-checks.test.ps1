#!/usr/bin/env pwsh
# Plain pwsh self-test for scripts/lib/startup-checks.ps1 (this repo has no Pester).
# Every assertion throws on failure and prints a PASS line on success.
# Run: pwsh -NoProfile -File scripts/lib/startup-checks.test.ps1

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "startup-checks.ps1")

$script:passes = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:passes++
    Write-Host "PASS: $Message"
}

function Normalize-Path {
    param([string]$Path)
    if (-not $Path) { return "" }
    $full = [System.IO.Path]::GetFullPath($Path)
    return $full.Replace(
        [System.IO.Path]::AltDirectorySeparatorChar,
        [System.IO.Path]::DirectorySeparatorChar).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
}

function Assert-PathEqual {
    param([string]$Expected, [string]$Actual, [string]$Message)
    $e = Normalize-Path $Expected
    $a = Normalize-Path $Actual
    Assert-True ($e -eq $a) "$Message (expected '$e', got '$a')"
}

function Test-PathUnder {
    param([string]$Path, [string]$Root)
    $p = Normalize-Path $Path
    $r = Normalize-Path $Root
    if (-not $p -or -not $r) { return $false }
    return $p.StartsWith($r + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)
}

function New-BridgeFixture {
    param([string]$Name)
    $dir = Join-Path $tempRoot $Name
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    return $dir
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("aria-startup-checks-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    # ── F30: default workspace roots live outside the checkout ──
    $fakeLocalAppData = Join-Path $tempRoot "localappdata"
    $fakeHome = Join-Path $tempRoot "home"
    New-Item -ItemType Directory -Path $fakeLocalAppData, $fakeHome -Force | Out-Null

    $defaults = Resolve-WorkspaceRoots -LocalAppData $fakeLocalAppData -UserHome $fakeHome -RuntimeRoot "" -ResultRoot ""
    Assert-PathEqual (Join-Path $fakeLocalAppData "AriaConductor/workspaces") $defaults.Base `
        "default base is LOCALAPPDATA\AriaConductor\workspaces"
    Assert-PathEqual (Join-Path $defaults.Base "runs") $defaults.RuntimeRoot `
        "default runtime root is <base>/runs"
    Assert-PathEqual (Join-Path $defaults.Base "results") $defaults.ResultRoot `
        "default result root is <base>/results"
    Assert-True (-not (Test-PathUnder $defaults.Base $repoRoot)) `
        "default base is outside the project root ($repoRoot)"
    Assert-True (-not (Test-PathUnder $defaults.RuntimeRoot $repoRoot)) `
        "default runtime root is outside the project root"
    Assert-True (-not (Test-PathUnder $defaults.ResultRoot $repoRoot)) `
        "default result root is outside the project root"

    # ── F30: operator-provided environment values win ──
    $operatorRuntime = Join-Path $tempRoot "operator/runs"
    $operatorResult = Join-Path $tempRoot "operator/results"
    $previousRuntime = $env:ARIA_WORKSPACES_RUNTIME_ROOT
    $previousResult = $env:ARIA_WORKSPACES_RESULT_ROOT
    $env:ARIA_WORKSPACES_RUNTIME_ROOT = $operatorRuntime
    $env:ARIA_WORKSPACES_RESULT_ROOT = $operatorResult
    try {
        $fromEnv = Resolve-WorkspaceRoots -LocalAppData $fakeLocalAppData -UserHome $fakeHome
        Assert-PathEqual $operatorRuntime $fromEnv.RuntimeRoot `
            "operator ARIA_WORKSPACES_RUNTIME_ROOT wins"
        Assert-PathEqual $operatorResult $fromEnv.ResultRoot `
            "operator ARIA_WORKSPACES_RESULT_ROOT wins"
    } finally {
        if ($null -eq $previousRuntime) {
            Remove-Item Env:ARIA_WORKSPACES_RUNTIME_ROOT -ErrorAction SilentlyContinue
        } else {
            $env:ARIA_WORKSPACES_RUNTIME_ROOT = $previousRuntime
        }
        if ($null -eq $previousResult) {
            Remove-Item Env:ARIA_WORKSPACES_RESULT_ROOT -ErrorAction SilentlyContinue
        } else {
            $env:ARIA_WORKSPACES_RESULT_ROOT = $previousResult
        }
    }

    $explicitRuntime = Join-Path $tempRoot "explicit/runs"
    $mixed = Resolve-WorkspaceRoots -RuntimeRoot $explicitRuntime -ResultRoot "" `
        -LocalAppData $fakeLocalAppData -UserHome $fakeHome
    Assert-PathEqual $explicitRuntime $mixed.RuntimeRoot `
        "explicit runtime root wins over the default"
    Assert-PathEqual (Join-Path $mixed.Base "results") $mixed.ResultRoot `
        "unset result root still falls back to <base>/results"

    # ── F30: empty LOCALAPPDATA falls back to HOME ──
    $fallback = Resolve-WorkspaceRoots -LocalAppData "" -UserHome $fakeHome -RuntimeRoot "" -ResultRoot ""
    Assert-PathEqual (Join-Path $fakeHome ".aria-conductor/workspaces") $fallback.Base `
        "empty LOCALAPPDATA falls back to HOME/.aria-conductor/workspaces"
    Assert-PathEqual (Join-Path $fakeHome ".aria-conductor/workspaces/runs") $fallback.RuntimeRoot `
        "HOME fallback runtime root is <base>/runs"
    Assert-PathEqual (Join-Path $fakeHome ".aria-conductor/workspaces/results") $fallback.ResultRoot `
        "HOME fallback result root is <base>/results"

    # ── F31: bridge dist freshness ──
    $now = [DateTime]::UtcNow

    $missing = New-BridgeFixture "bridge-missing"
    New-Item -ItemType Directory -Path (Join-Path $missing "src") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $missing "src/main.ts") -Value "export {}"
    $problem = Get-BridgeDistProblem -BridgeDir $missing
    Assert-True ($null -ne $problem) "missing dist/main.js reports a problem"
    Assert-True ($problem -match "dist/main\.js") "missing-dist problem names dist/main.js"

    $fresh = New-BridgeFixture "bridge-fresh"
    New-Item -ItemType Directory -Path (Join-Path $fresh "src"), (Join-Path $fresh "dist") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $fresh "src/main.ts") -Value "export {}"
    Set-Content -LiteralPath (Join-Path $fresh "dist/main.js") -Value "// built"
    (Get-Item -LiteralPath (Join-Path $fresh "dist/main.js")).LastWriteTimeUtc = $now
    (Get-Item -LiteralPath (Join-Path $fresh "src/main.ts")).LastWriteTimeUtc = $now.AddMinutes(-10)
    $problem = Get-BridgeDistProblem -BridgeDir $fresh
    Assert-True ($null -eq $problem) "fresh dist reports no problem (got '$problem')"

    $stale = New-BridgeFixture "bridge-stale"
    New-Item -ItemType Directory -Path (Join-Path $stale "src"), (Join-Path $stale "dist") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $stale "src/main.ts") -Value "export {}"
    Set-Content -LiteralPath (Join-Path $stale "dist/main.js") -Value "// stale build"
    (Get-Item -LiteralPath (Join-Path $stale "dist/main.js")).LastWriteTimeUtc = $now
    (Get-Item -LiteralPath (Join-Path $stale "src/main.ts")).LastWriteTimeUtc = $now.AddMinutes(5)
    $problem = Get-BridgeDistProblem -BridgeDir $stale
    Assert-True ($null -ne $problem) "source newer than dist reports a problem"
    Assert-True ($problem -match "main\.ts") "stale problem names the newer source file"

    $stalePkg = New-BridgeFixture "bridge-stale-package"
    New-Item -ItemType Directory -Path (Join-Path $stalePkg "src"), (Join-Path $stalePkg "dist") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $stalePkg "src/main.ts") -Value "export {}"
    Set-Content -LiteralPath (Join-Path $stalePkg "package.json") -Value "{}"
    Set-Content -LiteralPath (Join-Path $stalePkg "dist/main.js") -Value "// built"
    (Get-Item -LiteralPath (Join-Path $stalePkg "dist/main.js")).LastWriteTimeUtc = $now
    (Get-Item -LiteralPath (Join-Path $stalePkg "src/main.ts")).LastWriteTimeUtc = $now.AddMinutes(-10)
    (Get-Item -LiteralPath (Join-Path $stalePkg "package.json")).LastWriteTimeUtc = $now.AddMinutes(5)
    $problem = Get-BridgeDistProblem -BridgeDir $stalePkg
    Assert-True ($null -ne $problem) "package.json newer than dist reports a problem"

    $noSrc = New-BridgeFixture "bridge-no-src"
    New-Item -ItemType Directory -Path (Join-Path $noSrc "dist") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $noSrc "dist/main.js") -Value "// built"
    $problem = $null
    try {
        $problem = Get-BridgeDistProblem -BridgeDir $noSrc
    } catch {
        throw "FAIL: a bridge dir without src files crashed: $($_.Exception.Message)"
    }
    Assert-True ($null -eq $problem) "bridge dir without src files does not crash and is fresh with dist"

    # ── F31: bridge input manifest (hash freshness beyond mtime) ──
    # The helper contract: exit 0 = fresh, 1 = problem (reason on stdout), 2 = usage
    # or filesystem error. The start scripts rely on those codes, so pin them here.
    $freshnessHelper = Join-Path $repoRoot "scripts/lib/bridge-freshness.mjs"
    Assert-True (Test-Path -LiteralPath $freshnessHelper -PathType Leaf) `
        "bridge freshness helper is committed at scripts/lib/bridge-freshness.mjs"
    Assert-True ($null -ne (Get-Command node -ErrorAction SilentlyContinue)) `
        "node is available to exercise the bridge freshness helper"

    $usage = & node $freshnessHelper 2>&1
    Assert-True ($LASTEXITCODE -eq 2) "no arguments exits 2 (usage error), got $LASTEXITCODE"
    $unknown = & node $freshnessHelper verify $noSrc 2>&1
    Assert-True ($LASTEXITCODE -eq 2) "an unknown command exits 2 (usage error), got $LASTEXITCODE"

    $hashFresh = New-BridgeFixture "bridge-hash-fresh"
    New-Item -ItemType Directory -Path (Join-Path $hashFresh "src"), (Join-Path $hashFresh "dist") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $hashFresh "src/main.ts") -Value "export {}"
    Set-Content -LiteralPath (Join-Path $hashFresh "dist/main.js") -Value "// built"
    $writeOutput = & node $freshnessHelper write $hashFresh 2>&1
    Assert-True ($LASTEXITCODE -eq 0) "writing the manifest exits 0 (got ${LASTEXITCODE}: $writeOutput)"
    Assert-True (Test-Path -LiteralPath (Join-Path $hashFresh "dist/.build-inputs") -PathType Leaf) `
        "the manifest lives under the git-ignored dist/"
    $checkOutput = & node $freshnessHelper check $hashFresh 2>&1
    Assert-True ($LASTEXITCODE -eq 0) "a matching manifest reports fresh (exit 0), got '$checkOutput'"

    # A matching manifest is fresh even when the source mtime is newer than dist/main.js.
    (Get-Item -LiteralPath (Join-Path $hashFresh "src/main.ts")).LastWriteTimeUtc = $now.AddMinutes(10)
    $problem = Get-BridgeDistProblem -BridgeDir $hashFresh
    Assert-True ($null -eq $problem) "a matching manifest is fresh even when mtimes suggest otherwise (got '$problem')"

    # A mismatched manifest is a problem, and it names the changed input, even when the
    # changed file's mtime is older than dist/main.js.
    Set-Content -LiteralPath (Join-Path $hashFresh "src/main.ts") -Value "export const changed = true"
    (Get-Item -LiteralPath (Join-Path $hashFresh "src/main.ts")).LastWriteTimeUtc = $now.AddMinutes(-30)
    $checkOutput = & node $freshnessHelper check $hashFresh 2>&1
    Assert-True ($LASTEXITCODE -eq 1) "a mismatched manifest reports a problem (exit 1), got $LASTEXITCODE"
    Assert-True (($checkOutput | Out-String) -match "src/main\.ts") `
        "the mismatch names the changed state (got '$checkOutput')"
    $problem = Get-BridgeDistProblem -BridgeDir $hashFresh
    Assert-True ($null -ne $problem) "Get-BridgeDistProblem reports a manifest mismatch"
    Assert-True ($problem -match "src/main\.ts") "the manifest problem names the changed file"

    # A missing manifest falls back to the legacy mtime comparison.
    $legacy = New-BridgeFixture "bridge-legacy"
    New-Item -ItemType Directory -Path (Join-Path $legacy "src"), (Join-Path $legacy "dist") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $legacy "src/main.ts") -Value "export {}"
    Set-Content -LiteralPath (Join-Path $legacy "dist/main.js") -Value "// built"
    (Get-Item -LiteralPath (Join-Path $legacy "dist/main.js")).LastWriteTimeUtc = $now
    (Get-Item -LiteralPath (Join-Path $legacy "src/main.ts")).LastWriteTimeUtc = $now.AddMinutes(-30)
    $problem = Get-BridgeDistProblem -BridgeDir $legacy
    Assert-True ($null -eq $problem) "a missing manifest falls back to a fresh mtime result (got '$problem')"
    (Get-Item -LiteralPath (Join-Path $legacy "src/main.ts")).LastWriteTimeUtc = $now.AddMinutes(5)
    $problem = Get-BridgeDistProblem -BridgeDir $legacy
    Assert-True ($null -ne $problem) "a missing manifest still detects a stale mtime"
    Assert-True ($problem -match "main\.ts") "the legacy mtime problem names the newer file"

    # Node absent (or the helper missing) must not break the start path: mtime fallback.
    $problem = Get-BridgeDistProblem -BridgeDir $legacy -NodeCommand "aria-no-such-node"
    Assert-True ($null -ne $problem) "node absent falls back to the mtime comparison"

    # Writing without a dist is a problem, not a partial manifest.
    $noDist = New-BridgeFixture "bridge-write-no-dist"
    New-Item -ItemType Directory -Path (Join-Path $noDist "src") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $noDist "src/main.ts") -Value "export {}"
    $writeOutput = & node $freshnessHelper write $noDist 2>&1
    Assert-True ($LASTEXITCODE -eq 1) "writing a manifest without dist/main.js exits 1, got $LASTEXITCODE"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $noDist "dist/.build-inputs"))) `
        "a failed write leaves no manifest behind"

    Write-Host ""
    Write-Host "PASS: $($script:passes) assertions" -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

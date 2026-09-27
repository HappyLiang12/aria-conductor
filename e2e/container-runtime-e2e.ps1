#!/usr/bin/env pwsh
# E2E scenario tests for container-runtime resolution (scripts/lib/container-runtime.ps1).
# Zero external dependencies: stub docker/podman CLIs are injected via a temp PATH,
# and every scenario runs in a fresh pwsh child process so a real docker/podman
# on the host can never leak into the test.
# Run: pwsh -NoProfile -File e2e/container-runtime-e2e.ps1
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LibPath = Join-Path $ProjectRoot "scripts/lib/container-runtime.ps1"

# Self-heal: remove stale stub dirs from crashed/aborted runs (GUID names make collision-free)
Get-ChildItem ([System.IO.Path]::GetTempPath()) -Directory -Filter "act-crt-*" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

$StubDir = Join-Path ([System.IO.Path]::GetTempPath()) ("act-crt-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $StubDir | Out-Null

$failures = 0
function Assert-True($Name, [bool]$Cond, $Detail) {
    if ($Cond) {
        Write-Host "  PASS: $Name" -ForegroundColor Green
    } else {
        $script:failures++
        Write-Host "  FAIL: $Name ($Detail)" -ForegroundColor Red
    }
}

# Runs one resolution scenario in a fresh pwsh process whose PATH only exposes
# stubs listed in $Stubs ("name" = running stub, "name-dead" = engine-down stub).
function Invoke-Scenario([string]$RuntimeEnv, [string[]]$Stubs) {
    $dir = Join-Path $StubDir ([guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $dir | Out-Null
    foreach ($stub in $Stubs) {
        if ($stub.EndsWith("-dead")) {
            $name = $stub.Substring(0, $stub.Length - 5)
            Set-Content -Path (Join-Path $dir "$name.ps1") -Value 'exit 1'
        } else {
            Set-Content -Path (Join-Path $dir "$stub.ps1") -Value 'param([string]$cmd) if ($cmd -eq "info") { exit 0 } ; exit 1'
        }
    }
    $scenario = @"
`$env:PATH = '$dir'
`$env:CONTAINER_RUNTIME = '$RuntimeEnv'
. '$LibPath'
try {
    `$r = Resolve-ContainerRuntime
    Write-Output ("RESULT runtime={0} mode={1}" -f `$r.Runtime, `$r.Mode)
} catch {
    Write-Output ("RESULT error=" + `$_.Exception.Message)
}
"@
    $file = Join-Path $dir "scenario.ps1"
    Set-Content -Path $file -Value $scenario
    return (pwsh -NoProfile -File $file)
}

# Runs one Ensure-QoderSandboxImage scenario in a fresh pwsh process whose PATH only
# exposes a recording stub CLI named after the runtime under test. The stub answers
# `image inspect` with $ImagePresent and appends every invocation to calls.log, so the
# scenario can assert whether a `build` was issued. Returns @{ Out; Calls }.
#
# The runtime is parameterized because the chosen predicate exists for docker
# compatibility: the docker CLI has no `image exists` (unknown command, exit 1 for every
# tag), so a helper regressed to `image exists` would always build under docker and fail
# the "present -> no build" case. The stub exits 1 for anything but `image inspect`/`build`,
# which is how a real docker CLI answers `image exists`.
function Invoke-QoderImageScenario([bool]$ImagePresent, [string]$Runtime = "podman") {
    $dir = Join-Path $StubDir ([guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $dir | Out-Null
    $inspectRc = if ($ImagePresent) { 0 } else { 1 }
    $stub = @"
Add-Content -LiteralPath '$dir\calls.log' -Value (`$args -join ' ')
if (`$args.Count -ge 2 -and `$args[0] -eq 'image' -and `$args[1] -eq 'inspect') { exit $inspectRc }
if (`$args.Count -ge 1 -and `$args[0] -eq 'build') { exit 0 }
exit 1
"@
    Set-Content -Path (Join-Path $dir "$Runtime.ps1") -Value $stub
    $scenario = @"
`$env:PATH = '$dir'
. '$LibPath'
`$built = Ensure-QoderSandboxImage -Runtime '$Runtime' -ProjectRoot '$ProjectRoot'
Write-Output ("RESULT built={0}" -f `$built)
"@
    $file = Join-Path $dir "scenario.ps1"
    Set-Content -Path $file -Value $scenario
    $out = pwsh -NoProfile -File $file
    $callsFile = Join-Path $dir "calls.log"
    $calls = if (Test-Path $callsFile) { (Get-Content $callsFile -Raw) } else { "" }
    return @{ Out = $out; Calls = $calls }
}

try {
    Write-Host "Container-runtime resolution scenarios:" -ForegroundColor Cyan

    $out = Invoke-Scenario "docker" @("docker")
    Assert-True "explicit docker + docker available" ($out -match "runtime=docker mode=explicit") $out

    $out = Invoke-Scenario "podman" @("podman")
    Assert-True "explicit podman + podman available" ($out -match "runtime=podman mode=explicit") $out

    $out = Invoke-Scenario "docker" @()
    Assert-True "explicit docker + CLI missing -> hard error" ($out -match "error=.*docker is not available") $out

    $out = Invoke-Scenario "podman" @("podman-dead")
    Assert-True "explicit podman + engine not running -> hard error with podman hint" ($out -match "error=.*podman is not available.*podman machine start") $out

    $out = Invoke-Scenario "nerdctl" @("docker")
    Assert-True "explicit invalid value -> hard error" ($out -match "error=.*invalid.*docker.*podman") $out

    $out = Invoke-Scenario "" @("docker", "podman")
    Assert-True "auto + docker running -> docker" ($out -match "runtime=docker mode=auto") $out

    $out = Invoke-Scenario "" @("podman")
    Assert-True "auto + only podman running -> podman" ($out -match "runtime=podman mode=auto") $out

    $out = Invoke-Scenario "" @()
    Assert-True "auto + neither available -> null runtime" ($out -match "runtime= mode=auto") $out

    Write-Host "Qoder sandbox image ensure scenarios:" -ForegroundColor Cyan

    $r = Invoke-QoderImageScenario $true
    Assert-True "qoder image present -> no build" `
        (($r.Out -match "RESULT built=False") -and
        ($r.Calls -match "image inspect aria-conductor/qoder-sandbox:0.1") -and
        ($r.Calls -notmatch "build")) ($r.Out + " | " + $r.Calls)

    $r = Invoke-QoderImageScenario $false
    $qoderContext = Join-Path $ProjectRoot "agent-control-tower/qoder-sandbox"
    Assert-True "qoder image absent -> build invoked from the qoder-sandbox context" `
        (($r.Out -match "RESULT built=True") -and
        ($r.Calls -match "build -t aria-conductor/qoder-sandbox:0.1") -and
        ($r.Calls -match [regex]::Escape($qoderContext))) ($r.Out + " | " + $r.Calls)

    $r = Invoke-QoderImageScenario $true "docker"
    Assert-True "qoder image present (docker) -> no build" `
        (($r.Out -match "RESULT built=False") -and
        ($r.Calls -match "image inspect aria-conductor/qoder-sandbox:0.1") -and
        ($r.Calls -notmatch "build")) ($r.Out + " | " + $r.Calls)

    $r = Invoke-QoderImageScenario $false "docker"
    Assert-True "qoder image absent (docker) -> build invoked from the qoder-sandbox context" `
        (($r.Out -match "RESULT built=True") -and
        ($r.Calls -match "build -t aria-conductor/qoder-sandbox:0.1") -and
        ($r.Calls -match [regex]::Escape($qoderContext))) ($r.Out + " | " + $r.Calls)

    Write-Host "Load-DotEnv scenarios:" -ForegroundColor Cyan

    $dotenvDir = Join-Path $StubDir "dotenv"
    New-Item -ItemType Directory -Path $dotenvDir | Out-Null
    Set-Content -Path (Join-Path $dotenvDir ".env") -Value @"
# comment line
CONTAINER_RUNTIME=podman
SANDBOX_SOCKET=/run/user/1000/podman/podman.sock
INVALID LINE WITHOUT EQUALS
1BAD_NAME=should-be-skipped
"@
    $s1 = @"
. '$LibPath'
`$env:CONTAINER_RUNTIME = "docker"
Load-DotEnv '$dotenvDir'
Write-Output "RESULT runtime=`$env:CONTAINER_RUNTIME socket=`$env:SANDBOX_SOCKET"
Write-Output ("RESULT badname=[" + [Environment]::GetEnvironmentVariable('1BAD_NAME') + "]")
"@
    $f1 = Join-Path $dotenvDir "s1.ps1"
    Set-Content -Path $f1 -Value $s1
    $out = pwsh -NoProfile -File $f1
    Assert-True "Load-DotEnv parses KEY=VALUE, skips comments/invalid names, preserves existing env" `
        (($out -match "runtime=docker socket=/run/user/1000/podman/podman.sock") -and ($out -match "RESULT badname=\[\]")) $out

    $emptyDir = Join-Path $StubDir "noenv"
    New-Item -ItemType Directory -Path $emptyDir | Out-Null
    $s2 = @"
. '$LibPath'
Load-DotEnv '$emptyDir'
Write-Output "RESULT ok"
"@
    $f2 = Join-Path $emptyDir "s2.ps1"
    Set-Content -Path $f2 -Value $s2
    $out = pwsh -NoProfile -File $f2
    Assert-True "Load-DotEnv missing .env is a no-op" ($out -match "RESULT ok") $out

    $crlfDir = Join-Path $StubDir "crlf"
    New-Item -ItemType Directory -Path $crlfDir | Out-Null
    # -NoNewline: the CRLFs below are then the only line terminators in the file.
    Set-Content -Path (Join-Path $crlfDir ".env") -NoNewline `
        -Value "CONTAINER_RUNTIME=podman`r`nSANDBOX_SOCKET=/run/user/1000/podman/podman.sock`r`n"
    $s3 = @"
. '$LibPath'
Remove-Item Env:CONTAINER_RUNTIME -ErrorAction SilentlyContinue
Remove-Item Env:SANDBOX_SOCKET -ErrorAction SilentlyContinue
Load-DotEnv '$crlfDir'
`$raw = Get-Content '$crlfDir\.env' -Raw
`$crlf = [string]([char]13) + [char]10
`$socket = [Environment]::GetEnvironmentVariable('SANDBOX_SOCKET')
Write-Output ("RESULT fixtureCrlf=" + `$raw.Contains(`$crlf))
Write-Output ("RESULT runtime=" + [Environment]::GetEnvironmentVariable('CONTAINER_RUNTIME') + " socket=" + `$socket)
Write-Output ("RESULT hasCr=" + `$socket.Contains([char]13))
"@
    $f3 = Join-Path $crlfDir "s3.ps1"
    Set-Content -Path $f3 -Value $s3
    $out = pwsh -NoProfile -File $f3
    Assert-True "Load-DotEnv strips CRLF line endings" `
        (($out -match "RESULT fixtureCrlf=True") -and
        ($out -match "RESULT runtime=podman socket=/run/user/1000/podman/podman.sock") -and
        ($out -match "RESULT hasCr=False")) $out
} finally {
    Remove-Item $StubDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
if ($failures -gt 0) {
    Write-Host "$failures scenario(s) FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "All scenarios PASSED" -ForegroundColor Green
exit 0

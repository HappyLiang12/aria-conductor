#!/usr/bin/env pwsh
# E2E scenario tests for container-runtime resolution (scripts/lib/container-runtime.ps1).
# Zero external dependencies: stub docker/podman CLIs are injected via a temp PATH,
# and every scenario runs in a fresh pwsh child process so a real docker/podman
# on the host can never leak into the test.
# Run: pwsh -NoProfile -File e2e/container-runtime-e2e.ps1
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LibPath = Join-Path $ProjectRoot "scripts/lib/container-runtime.ps1"

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

Write-Host "Load-DotEnv scenarios:" -ForegroundColor Cyan

$dotenvDir = Join-Path $StubDir "dotenv"
New-Item -ItemType Directory -Path $dotenvDir | Out-Null
Set-Content -Path (Join-Path $dotenvDir ".env") -Value @"
# comment line
CONTAINER_RUNTIME=podman
SANDBOX_SOCKET=/run/user/1000/podman/podman.sock
INVALID LINE WITHOUT EQUALS
"@
$s1 = @"
. '$LibPath'
`$env:CONTAINER_RUNTIME = "docker"
Load-DotEnv '$dotenvDir'
Write-Output "RESULT runtime=`$env:CONTAINER_RUNTIME socket=`$env:SANDBOX_SOCKET"
"@
$f1 = Join-Path $dotenvDir "s1.ps1"
Set-Content -Path $f1 -Value $s1
$out = pwsh -NoProfile -File $f1
Assert-True "Load-DotEnv parses KEY=VALUE, skips comments/invalid, preserves existing env" ($out -match "runtime=docker socket=/run/user/1000/podman/podman.sock") $out

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

Remove-Item $StubDir -Recurse -Force

Write-Host ""
if ($failures -gt 0) {
    Write-Host "$failures scenario(s) FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "All scenarios PASSED" -ForegroundColor Green
exit 0

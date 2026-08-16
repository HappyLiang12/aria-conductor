#!/usr/bin/env pwsh
# Shared container-runtime resolution for Aria Conductor startup scripts.
# Dot-source from another script: . "$PSScriptRoot/container-runtime.ps1"

<#
.SYNOPSIS
Loads KEY=VALUE pairs from the project .env file into the process environment.
Existing environment variables are never overwritten.
#>
function Load-DotEnv {
    param([Parameter(Mandatory)][string]$ProjectRoot)

    $envFile = Join-Path $ProjectRoot ".env"
    if (-not (Test-Path $envFile)) { return }
    foreach ($line in Get-Content $envFile) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
        if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { continue }
        $name = $Matches[1]
        $value = $Matches[2]
        if ($null -eq [Environment]::GetEnvironmentVariable($name)) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

<#
.SYNOPSIS
True when the given runtime CLI exists AND `info` succeeds (engine reachable).
#>
function Test-RuntimeCli {
    param([Parameter(Mandatory)][string]$Runtime)
    if (-not (Get-Command $Runtime -ErrorAction SilentlyContinue)) { return $false }
    try {
        & $Runtime info *> $null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    }
}

<#
.SYNOPSIS
Resolves the container runtime. Returns @{ Runtime; Mode }.
Strict mode (CONTAINER_RUNTIME set): invalid value or unavailable CLI throws.
Auto mode: docker first, then podman; Runtime = $null when neither is usable.
#>
function Resolve-ContainerRuntime {
    $explicit = [string]$env:CONTAINER_RUNTIME
    if ($explicit) {
        $rt = $explicit.ToLowerInvariant()
        if ($rt -ne "docker" -and $rt -ne "podman") {
            throw "CONTAINER_RUNTIME='$explicit' is invalid. Use 'docker' or 'podman'."
        }
        if (-not (Test-RuntimeCli $rt)) {
            if ($rt -eq "podman") {
                if ($IsWindows) {
                    throw "CONTAINER_RUNTIME=podman is set but podman is not available. Install podman, ensure a machine is running ('podman machine start'), then retry."
                }
                throw "CONTAINER_RUNTIME=podman is set but podman is not available. Install podman (or start its service), then retry."
            }
            throw "CONTAINER_RUNTIME=docker is set but docker is not available. Install/start Docker Desktop, or switch CONTAINER_RUNTIME to podman."
        }
        return @{ Runtime = $rt; Mode = "explicit" }
    }
    if (Test-RuntimeCli "docker") { return @{ Runtime = "docker"; Mode = "auto" } }
    if (Test-RuntimeCli "podman") { return @{ Runtime = "podman"; Mode = "auto" } }
    return @{ Runtime = $null; Mode = "auto" }
}

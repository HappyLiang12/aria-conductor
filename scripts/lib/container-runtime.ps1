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

<#
.SYNOPSIS
Reads the engine's own socket path via `podman info`, e.g.
unix:///run/user/1000/podman/podman.sock becomes /run/user/1000/podman/podman.sock.
Returns $null when the engine cannot be queried.
Never hardcode this value: the rootful machine uses a different path.
#>
function Get-SandboxSocketPath {
    if (-not (Get-Command podman -ErrorAction SilentlyContinue)) { return $null }
    try {
        $raw = & podman info --format '{{.Host.RemoteSocket.Path}}' 2>$null
    } catch {
        return $null
    }
    if (-not $raw) { return $null }
    $value = "$raw".Trim()
    if ($value.StartsWith('unix://')) { return $value.Substring('unix://'.Length) }
    return $value
}

<#
.SYNOPSIS
Starts the podman machine when it exists but is not running. Returns $true when a
start was issued, $false when the machine was already running or podman is absent.
#>
function Start-PodmanMachineIfNeeded {
    if (-not (Get-Command podman -ErrorAction SilentlyContinue)) { return $false }

    $list = & podman machine list 2>$null
    if (-not $list) { return $false }
    if ("$list" -notmatch 'Currently running') {
        & podman machine start | Out-Null
        return $true
    }
    return $false
}

<#
.SYNOPSIS
Starts the OpenSandbox server when the aria-opensandbox container is not running.
$Runtime is 'docker' or 'podman'. Returns $true when a start was issued.
#>
function Ensure-OpenSandboxServer {
    param(
        [Parameter(Mandatory)][string]$Runtime,
        [Parameter(Mandatory)][string]$ProjectRoot
    )

    $running = & $Runtime ps --filter 'name=aria-opensandbox' --format '{{.Names}}' 2>$null
    if ("$running" -match 'aria-opensandbox') { return $false }

    Push-Location $ProjectRoot
    try {
        & $Runtime compose up -d opensandbox-server | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to start the OpenSandbox server ($Runtime compose up -d opensandbox-server, exit $LASTEXITCODE)"
        }
    } finally {
        Pop-Location
    }
    return $true
}

<#
.SYNOPSIS
Builds the opencode sandbox image when it is not present in the engine's store.
Returns $true when a build was issued.
#>
function Ensure-OpencodeSandboxImage {
    param(
        [Parameter(Mandatory)][string]$Runtime,
        [Parameter(Mandatory)][string]$ProjectRoot,
        [string]$Tag = 'aria-conductor/opencode-sandbox:1.1'
    )

    & $Runtime image exists $Tag 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { return $false }

    $context = Join-Path $ProjectRoot 'agent-control-tower/opencode-sandbox'
    & $Runtime build -t $Tag $context | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to build $Tag (exit $LASTEXITCODE)" }
    return $true
}

<#
.SYNOPSIS
Builds the qoder sandbox image when it is not present in the engine's store.
Returns $true when a build was issued.

NOTE: the existence predicate is `image inspect`, not `image exists`. `podman image
exists` is a podman subcommand and the docker CLI has no `image exists` at all (it
answers "unknown command", exit 1, for every tag), so the opencode helper above would
always re-build under docker. `image inspect` is a valid predicate for both runtimes
(exit 0 when the image is present, non-zero otherwise). The opencode helper is left
unchanged (out of scope for this task).
#>
function Ensure-QoderSandboxImage {
    param(
        [Parameter(Mandatory)][string]$Runtime,
        [Parameter(Mandatory)][string]$ProjectRoot,
        [string]$Tag = 'aria-conductor/qoder-sandbox:0.1'
    )

    & $Runtime image inspect $Tag *> $null
    if ($LASTEXITCODE -eq 0) { return $false }

    $context = Join-Path $ProjectRoot 'agent-control-tower/qoder-sandbox'
    & $Runtime build -t $Tag $context | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to build $Tag (exit $LASTEXITCODE)" }
    return $true
}

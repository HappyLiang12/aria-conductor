#!/usr/bin/env pwsh
# Pre-flight probes for the one-click startup launcher: toolchain, Maven bootstrap,
# TCP port ownership and HTTP health polling.
# Dot-source from another script: . "$PSScriptRoot/preflight.ps1"

<#
.SYNOPSIS
True when $Name resolves on PATH.
#>
function Test-ToolchainCommand {
    param([Parameter(Mandatory)][string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

<#
.SYNOPSIS
Materializes a mvn shim that boots Maven through the repo's wrapper jar.
Returns the shim directory to prepend to PATH, or $null when the jar is absent.

The shim pins MAVEN_PROJECTBASEDIR and passes -Dmaven.multiModuleProjectDirectory,
exactly as the official mvnw.cmd does; without the system property the wrapper
aborts with "-Dmaven.multiModuleProjectDirectory system property is not set". The
wrapper locates the distribution through .mvn/wrapper/maven-wrapper.properties, so
-ProjectRoot must be the directory that contains .mvn/ (agent-control-tower here).
#>
function Initialize-MavenShim {
    param(
        [Parameter(Mandatory)][string]$ProjectRoot,
        [Parameter(Mandatory)][string]$RunDir
    )

    if (Get-Command mvn -ErrorAction SilentlyContinue) { return $null }

    $jar = Join-Path $ProjectRoot '.mvn/wrapper/maven-wrapper.jar'
    if (-not (Test-Path $jar)) { return $null }

    $binDir = Join-Path $RunDir 'bin'
    New-Item -ItemType Directory -Path $binDir -Force | Out-Null
    $shim = Join-Path $binDir 'mvn.cmd'
    $content = "@echo off`r`nset MAVEN_PROJECTBASEDIR=$ProjectRoot`r`njava -cp `"$jar`" `"-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%`" org.apache.maven.wrapper.MavenWrapperMain %*`r`n"
    Set-Content -Path $shim -Value $content -NoNewline
    return $binDir
}

<#
.SYNOPSIS
Returns @{ Port; Pid; Name } for the process listening on $Port, or $null when
the port is free.
#>
function Get-PortHolder {
    param([Parameter(Mandatory)][int]$Port)

    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $conn) { return $null }

    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    return [pscustomobject]@{
        Port = $Port
        Pid  = $conn.OwningProcess
        Name = if ($proc) { $proc.ProcessName } else { 'unknown' }
    }
}

<#
.SYNOPSIS
Polls $Url until it answers 200 (and matches $ExpectBodyMatch when supplied).
Returns $true on success, $false when $TimeoutSeconds elapses. Never throws.
#>
function Wait-HttpHealthy {
    param(
        [Parameter(Mandatory)][string]$Url,
        [int]$TimeoutSeconds = 180,
        [string]$ExpectBodyMatch
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        # Never let one request outlive the remaining budget, or the caller's bound is not a bound.
        $remaining = [int][Math]::Ceiling(($deadline - (Get-Date)).TotalSeconds)
        $perRequest = [Math]::Max(1, [Math]::Min(5, $remaining))
        try {
            $resp = Invoke-WebRequest -Uri $Url -TimeoutSec $perRequest -UseBasicParsing
            if ($resp.StatusCode -eq 200) {
                # Spring actuator answers with `application/vnd.spring-boot.actuator.v3+json`, a
                # vendor media type PowerShell does not decode, so Content arrives as byte[] and
                # -match would silently never match - the backend would look unhealthy forever.
                $body = if ($resp.Content -is [byte[]]) {
                    [System.Text.Encoding]::UTF8.GetString($resp.Content)
                } else {
                    $resp.Content
                }
                if (-not $ExpectBodyMatch -or $body -match $ExpectBodyMatch) { return $true }
            }
        } catch {
            # Connection refused while the service boots is the expected path.
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

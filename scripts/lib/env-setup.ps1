#!/usr/bin/env pwsh
# .env authoring for the one-click startup launcher.
# Dot-source from another script: . "$PSScriptRoot/env-setup.ps1"

<#
.SYNOPSIS
True when the value looks like a real API key rather than an empty field or a
template placeholder copied from .env.example.
#>
function Test-LlmKeyValid {
    param([string]$Key)

    if ([string]::IsNullOrWhiteSpace($Key)) { return $false }
    if ($Key.Trim().Length -lt 8) { return $false }
    if ($Key -match '(?i)your[-_]?api[-_]?key|placeholder|change[-_]?me|^sk-x+$') { return $false }
    return $true
}

$script:LlmPresets = @{
    deepseek = @{ BaseUrl = 'https://api.deepseek.com'; Model = 'deepseek-v4-flash' }
    openai   = @{ BaseUrl = 'https://api.openai.com/v1'; Model = 'gpt-4o' }
}

<#
.SYNOPSIS
Creates .env on first run, or adds only the keys that are missing.
Returns @{ Created; AddedKeys; Path }.

Existing values are never overwritten. -Preset and -ApiKey are used only when the
file (or the specific key) is missing; omit them to be prompted instead.
#>
function Ensure-EnvFile {
    param(
        [Parameter(Mandatory)][string]$ProjectRoot,
        [Parameter(Mandatory)][string]$SandboxSocket,
        # The runtime the launcher actually resolved. Pinning a value here that the machine
        # cannot use would brick the next run, because Load-DotEnv is first-wins and
        # Resolve-ContainerRuntime runs before this function ever gets a chance to correct it.
        [string]$ContainerRuntime = 'podman',
        [switch]$NonInteractive,
        [ValidateSet('deepseek', 'openai')][string]$Preset = 'deepseek',
        [string]$ApiKey,
        [string]$Model
    )

    $envPath = Join-Path $ProjectRoot '.env'
    $presetDef = $script:LlmPresets[$Preset]

    if (-not $ApiKey -and -not $NonInteractive) {
        $secure = Read-Host -Prompt "LLM API key ($Preset)" -AsSecureString
        $ApiKey = ConvertFrom-SecureString -SecureString $secure -AsPlainText
    }
    if (-not $Model) { $Model = $presetDef.Model }
    if (-not $ApiKey) { $ApiKey = '' }

    $wanted = [ordered]@{
        LLM_API_KEY      = $ApiKey
        LLM_BASE_URL     = $presetDef.BaseUrl
        LLM_MODEL        = $Model
        CONTAINER_RUNTIME = $ContainerRuntime
        SANDBOX_SOCKET   = $SandboxSocket
    }

    if (-not (Test-Path $envPath)) {
        $lines = @()
        foreach ($k in $wanted.Keys) { $lines += "$k=$($wanted[$k])" }
        $lines += ''
        $lines += '# --- Database (used by -Mode compose only) ---'
        $lines += 'DB_ROOT_PASSWORD=change-me-root'
        $lines += 'DB_NAME=aria_conductor'
        $lines += 'DB_USER=aria'
        $lines += 'DB_PASSWORD=change-me'
        $lines += ''
        $lines += '# --- Ports (optional) ---'
        $lines += '# BACKEND_PORT=8080'
        $lines += '# FRONTEND_PORT=3000'
        $lines += '# VITE_PORT=5173'
        Set-Content -Path $envPath -Value ($lines -join "`n") -NoNewline
        return [pscustomobject]@{ Created = $true; AddedKeys = @($wanted.Keys); Path = $envPath }
    }

    $present = @{}
    foreach ($line in (Get-Content $envPath)) {
        # Mirror Load-DotEnv's grammar exactly: trim the line, then require the key name to be
        # followed immediately by '='. The value may be empty, so a blank `KEY=` still counts as
        # present. A space-padded `KEY = value` is NOT loadable - Load-DotEnv's `^(name)=(.*)$`
        # cannot parse it - so it must not count as present, or the key would end up neither
        # repaired nor readable while the launcher reported success.
        $trimmed = $line.Trim()
        if ($trimmed -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $present[$Matches[1]] = $true }
    }

    $added = @()
    $append = @()
    foreach ($k in $wanted.Keys) {
        if (-not $present.ContainsKey($k)) {
            $value = $wanted[$k]
            # A blank placeholder must not be written for the key the user has to supply.
            if ($k -eq 'LLM_API_KEY' -and -not $value) { continue }
            $append += "$k=$value"
            $added += $k
        }
    }
    if ($append.Count -gt 0) {
        $content = (Get-Content $envPath -Raw).TrimEnd() + "`n" + ($append -join "`n") + "`n"
        Set-Content -Path $envPath -Value $content -NoNewline
    }

    return [pscustomobject]@{ Created = $false; AddedKeys = $added; Path = $envPath }
}

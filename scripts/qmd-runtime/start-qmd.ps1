# Portable qmd runtime for the aria-memory-vault.
# Works with podman or docker on Windows.
param(
  [ValidateSet("build", "init", "start", "stop", "status", "logs")]
  [string]$Command = "start"
)

$ErrorActionPreference = "Stop"
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Image = "aria-qmd:2.9.0"
$Container = "aria-qmd"
$Vault = $env:ARIA_MEMORY_VAULT_DIR
if (-not $Vault) { $Vault = Join-Path $HOME "aria-memory-vault" }
$env:ARIA_MEMORY_VAULT_DIR = $Vault

function Get-Runtime {
  if (Get-Command podman -ErrorAction SilentlyContinue) { return "podman" }
  if (Get-Command docker -ErrorAction SilentlyContinue) { return "docker" }
  throw "podman or docker is required"
}

function Invoke-Qmd {
  param([string[]]$Args)
  $rt = Get-Runtime
  & $rt run --rm -v "${Vault}:/vault" -v aria-qmd-home:/root -w /vault $Image @Args
}

$rt = Get-Runtime

switch ($Command) {
  "build" {
    & $rt build -t $Image $Dir
  }
  "init" {
    if (-not (Test-Path $Vault)) { throw "vault not found: $Vault" }
    & $rt volume create aria-qmd-home | Out-Null
    Invoke-Qmd @("collection", "add", "/vault/memory/verified", "--name", "memory-verified")
    Invoke-Qmd @("collection", "add", "/vault/knowledge/verified", "--name", "knowledge-verified")
    Invoke-Qmd @("update")
    Invoke-Qmd @("embed")
  }
  "start" {
    if (-not (Test-Path $Vault)) { throw "vault not found: $Vault" }
    & $rt image exists $Image | Out-Null
    if ($LASTEXITCODE -ne 0) { & $rt build -t $Image $Dir }
    & $rt rm -f $Container | Out-Null
    & $rt run -d --name $Container `
      -p 127.0.0.1:8181:8181 `
      -v "${Vault}:/vault" `
      -v aria-qmd-home:/root `
      -w /vault `
      $Image mcp --http --host 0.0.0.0 | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
      try {
        Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8181/health | Out-Null
        Write-Host "qmd MCP healthy at http://127.0.0.1:8181/mcp"
        return
      } catch { Start-Sleep -Seconds 1 }
    }
    throw "qmd did not become healthy; check: $PSCommandPath logs"
  }
  "stop" {
    & $rt rm -f $Container
  }
  "status" {
    & $rt ps --filter "name=$Container" --format "{{.Names}} {{.Status}}"
    try { Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8181/health | Select-Object -ExpandProperty Content } catch {}
  }
  "logs" {
    & $rt logs --tail 100 $Container
  }
}

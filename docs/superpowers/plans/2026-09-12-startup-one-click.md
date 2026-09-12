# One-Click Local-Dev Startup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `scripts/start.ps1` and `scripts/stop.ps1` so one command starts the whole local-dev stack in the opencode-capable topology, with environment checking, safe auto-repair, an explicit mode summary, and no reliance on already-open shell PATH state.

**Architecture:** An orchestrator (`start.ps1`) reuses the existing `start-backend.ps1` / `start-frontend.ps1` rather than rewriting them. Logic lives in three focused libraries: `env-setup.ps1` (`.env` authoring + key validation), `preflight.ps1` (toolchain probes, Maven shim, port probe, health polling), and the existing `container-runtime.ps1` (runtime resolution plus machine start, socket resolution, image and sandbox-server ensure). Runtime state (PIDs, logs) goes in `.run/`.

**Tech Stack:** PowerShell 7+, podman machine on WSL2, Docker Compose V2 as the podman provider, Vite dev server, Spring Boot via Maven.

**Spec:** `docs/superpowers/specs/2026-09-12-startup-one-click-design.md`

**Branch:** `feat/one-click-startup`

**Test runner:** `pwsh -NoProfile -File e2e/startup-e2e.ps1` (stub-driven, zero external dependencies, not wired into CI — same pattern as `e2e/container-runtime-e2e.ps1`).

---

## File structure

| File | Responsibility |
|------|----------------|
| `scripts/lib/env-setup.ps1` (create) | Validate the LLM key; create or top up `.env` without overwriting |
| `scripts/lib/preflight.ps1` (create) | Toolchain probe, Maven shim materialization, port probe, HTTP health polling |
| `scripts/lib/container-runtime.ps1` (modify) | Add machine start, socket resolution, sandbox image and server ensure |
| `scripts/start.ps1` (create) | 8-phase orchestration, mode summary, browser open |
| `scripts/stop.ps1` (create) | Stop backends by PID file, stop containers, clean `.run/` |
| `e2e/startup-e2e.ps1` (create) | Stub-driven scenario tests for all of the above |
| `.gitignore` (modify) | Add `.run/` |
| `README.md`, `AGENTS.md`, `.env.example` (modify) | Documentation truth-up |
| Deletions | `scripts/quickstart.ps1`, `scripts/monitor-env-deletion.ps1`, scratch files |

Conventions used throughout: every function is dot-sourceable and side-effect free unless its name starts with `Ensure`/`Start`/`Initialize`. Functions that touch the machine accept the paths/values they need as parameters so tests never depend on the real repo state.

---

### Task 1: Test harness scaffold, `Test-LlmKeyValid`, `.run/` ignore

**Files:**
- Create: `e2e/startup-e2e.ps1`
- Create: `scripts/lib/env-setup.ps1`
- Modify: `.gitignore`

- [ ] **Step 1: Write the failing test**

Create `e2e/startup-e2e.ps1`:

```powershell
#!/usr/bin/env pwsh
# Scenario tests for the one-click startup libraries.
# Zero external dependencies: stub CLIs are injected via a temp PATH and every
# scenario runs in a fresh pwsh child so host state can never leak in.
# Run: pwsh -NoProfile -File e2e/startup-e2e.ps1
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvSetupLib = Join-Path $ProjectRoot "scripts/lib/env-setup.ps1"
$PreflightLib = Join-Path $ProjectRoot "scripts/lib/preflight.ps1"
$RuntimeLib = Join-Path $ProjectRoot "scripts/lib/container-runtime.ps1"

Get-ChildItem ([System.IO.Path]::GetTempPath()) -Directory -Filter "act-startup-*" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

$StubDir = Join-Path ([System.IO.Path]::GetTempPath()) ("act-startup-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $StubDir | Out-Null

$failures = 0
# Untyped $Cond on purpose: ($out -match "...") yields an Object[] for multi-line
# output, and PowerShell 7.6 refuses to bind an array to a [bool] parameter. The
# explicit [bool] cast below applies the usual single-item unwrap rules.
function Assert-True($Name, $Cond, $Detail) {
    if ([bool]$Cond) {
        Write-Host "  PASS: $Name" -ForegroundColor Green
    } else {
        $script:failures++
        Write-Host "  FAIL: $Name ($Detail)" -ForegroundColor Red
    }
}

# Runs a snippet in a fresh pwsh process and returns its combined output.
# Never throws: a broken scenario comes back as text so it fails its own assertion
# instead of aborting the whole run. -PathPrepend / -PathReplace make the child's
# PATH hermetic, which is required by the tool-presence scenarios in tasks 3, 6, 7, 8.
function Invoke-Snippet {
    param(
        [Parameter(Mandatory)][string]$Snippet,
        # Files written into a fresh temp dir that is prepended to the child PATH.
        [hashtable]$Stubs = @{},
        # Extra dir prepended after the stub dir; also kept when -PathReplace is set.
        [string]$PathPrepend,
        # Replaces the child PATH; the stub dir and -PathPrepend stay in front of it.
        [string]$PathReplace
    )
    try {
        $dir = Join-Path $StubDir ([guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $dir | Out-Null
        foreach ($name in $Stubs.Keys) {
            Set-Content -Path (Join-Path $dir $name) -Value $Stubs[$name]
        }
        $entries = @()
        if ($Stubs.Count -gt 0) { $entries += $dir }
        if ($PathPrepend) { $entries += $PathPrepend }
        $prelude = ''
        if ($PathReplace) {
            $prelude = "`$env:PATH = '" + ((@($entries) + @($PathReplace)) -join ';') + "'`n"
        } elseif ($entries.Count -gt 0) {
            $prelude = "`$env:PATH = '" + ($entries -join ';') + ";' + `$env:PATH`n"
        }
        $file = Join-Path $dir "scenario.ps1"
        Set-Content -Path $file -Value ($prelude + $Snippet)
        return (pwsh -NoProfile -File $file 2>&1 | Out-String)
    } catch {
        return "SNIPPET-ERROR: $($_.Exception.Message)"
    }
}

# Writes a fake podman CLI whose behaviour is driven by FAKE_PODMAN_* variables set
# inside the scenario, and which appends every invocation to FAKE_PODMAN_LOG.
# Recognised variables: FAKE_PODMAN_SOCKET (printed for `info`), FAKE_PODMAN_MACHINE
# ('running' or anything else), FAKE_PODMAN_SERVER ('running'), FAKE_PODMAN_IMAGE
# ('present'), FAKE_PODMAN_LOG (file path). `compose` and `build` always succeed.
function New-FakePodman([string]$Dir) {
    New-Item -ItemType Directory -Path $Dir -Force | Out-Null
    $stub = @'
# No param() block on purpose: a [Parameter()] attribute would make this an advanced
# script, and PowerShell would then bind dash options to common parameters - silently
# swallowing "-d" (bound as -Debug) and erroring on ambiguous ones like "-p". $args
# keeps every token literal.
$joined = ($args -join ' ')
if ($env:FAKE_PODMAN_LOG) { Add-Content -Path $env:FAKE_PODMAN_LOG -Value $joined }
switch -Regex ($joined) {
    '^info'          { Write-Output $env:FAKE_PODMAN_SOCKET; exit 0 }
    '^machine list'  { if ($env:FAKE_PODMAN_MACHINE -eq 'running') { Write-Output 'podman-machine-default* wsl 1 day ago Currently running' } else { Write-Output 'podman-machine-default* wsl 1 day ago Never' }; exit 0 }
    '^machine start' { if ($env:FAKE_PODMAN_LOG) { Add-Content -Path $env:FAKE_PODMAN_LOG -Value 'MACHINE-START' }; exit 0 }
    '^ps'            { if ($env:FAKE_PODMAN_SERVER -eq 'running') { Write-Output 'aria-opensandbox' }; exit 0 }
    '^image exists'  { if ($env:FAKE_PODMAN_IMAGE -eq 'present') { exit 0 } else { exit 1 } }
    default          { exit 0 }
}
'@
    Set-Content -Path (Join-Path $Dir 'podman.ps1') -Value $stub
}

$FakePodmanDir = Join-Path $StubDir "fakepodman"
New-FakePodman $FakePodmanDir
$EmptyPathDir = Join-Path $StubDir "emptypath"
New-Item -ItemType Directory -Path $EmptyPathDir -Force | Out-Null
```

### Harness API (binding for Tasks 2–9)

`Invoke-Snippet -Snippet <here-string> [-PathPrepend <dir>] [-PathReplace <dir>]` returns the
child's combined stdout+stderr as one string. Never throws.

`New-FakePodman -Dir <dir>` writes a `podman.ps1` stub. `$FakePodmanDir` and `$EmptyPathDir` are
created once at the top of the harness, after `New-FakePodman` is defined.

**This section supersedes the Step 1 test snippets written for Tasks 3, 6, 7 and 8 below.** Use
these instead — the earlier versions set `$env:PATH` inside the snippet and hand-rolled stub
files, which produced tests that were not hermetic (see the Task 1 code-quality review).

**Task 3 Step 1 (replaces the version below).** `-PathReplace $EmptyPathDir` guarantees `mvn` is
absent regardless of the developer's PATH:

```powershell
    Write-Host "Initialize-MavenShim scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @" ... "@ -PathReplace $EmptyPathDir
```
with this snippet body:

```powershell
. '$PreflightLib'
`$root = Join-Path '$StubDir' 'mvnshim'
New-Item -ItemType Directory -Path (Join-Path `$root '.mvn/wrapper') -Force | Out-Null
Set-Content -Path (Join-Path `$root '.mvn/wrapper/maven-wrapper.jar') -Value 'stub'
`$dir = Initialize-MavenShim -ProjectRoot `$root -RunDir (Join-Path `$root '.run')
Write-Output ("RESULT dirSet=" + [bool]`$dir)
Write-Output ("RESULT shimExists=" + (Test-Path (Join-Path `$dir 'mvn.cmd')))
Write-Output ("RESULT shimContent=" + ((Get-Content (Join-Path `$dir 'mvn.cmd') -Raw) -match 'org.apache.maven.wrapper.MavenWrapperMain'))
```

and the second scenario uses `-PathReplace $EmptyPathDir` with a root that has no wrapper jar,
asserting `RESULT noJar=False`.

**Task 6 Step 1 (replaces the version below).** Three scenarios, all with
`-PathPrepend $FakePodmanDir`:

1. `FAKE_PODMAN_SOCKET` unset → `Get-SandboxSocketPath` returns empty.
2. `$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'` → returns
   `/run/user/1000/podman/podman.sock`.
3. `$env:FAKE_PODMAN_MACHINE = 'stopped'`, `$env:FAKE_PODMAN_LOG = '<temp>\calls.txt'` →
   `Start-PodmanMachineIfNeeded` returns `True` and the log contains `MACHINE-START`.

**Task 7 Step 1 (replaces the version below).** One scenario with
`-PathPrepend $FakePodmanDir`, setting `$env:FAKE_PODMAN_SERVER = ''`,
`$env:FAKE_PODMAN_IMAGE = ''`, `$env:FAKE_PODMAN_LOG = '<temp>\calls.txt'`, then calling
`Ensure-OpenSandboxServer -Runtime podman -ProjectRoot '<repo>'` and
`Ensure-OpencodeSandboxImage -Runtime podman -ProjectRoot '<repo>'`. Assert the log contains
`compose up -d opensandbox-server` and `build -t aria-conductor/opencode-sandbox:1.1`.

**Task 8 Step 1 (replaces the version below).** One `-DryRun` scenario with
`-PathPrepend $FakePodmanDir`, in which the snippet first writes a valid `.env` into a temp
dry-run root, then exports the variables the launcher reads, then invokes the script:

```powershell
`$root = Join-Path '$StubDir' 'dryrun'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
Set-Content -Path (Join-Path `$root '.env') -Value "LLM_API_KEY=sk-test1234567890`n"
`$env:CONTAINER_RUNTIME = 'podman'
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
& '$ProjectRoot\scripts\start.ps1' -DryRun -NonInteractive -ProjectRoot `$root
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
```

Assertions: `-DryRun exits 0`; the output matches `Topology\s*:\s*local-dev`,
`Provider\s*:\s*opencode` and `Runtime\s*:\s*podman`; `.env` still contains
`LLM_API_KEY=sk-test1234567890` (not overwritten); `.run/backend.pid` does not exist.
Note that asserts `-DryRun` does **not** create `.env` — creation is Task 2's coverage.

**Task 1 Step 1, continued — the scenario body** (unchanged by the harness revision):

```powershell
try {
    Write-Host "Test-LlmKeyValid scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
Write-Output ("RESULT real=" + (Test-LlmKeyValid 'sk-test-not-a-real-key-0000'))
Write-Output ("RESULT empty=" + (Test-LlmKeyValid ''))
Write-Output ("RESULT placeholder=" + (Test-LlmKeyValid 'your-api-key-here'))
Write-Output ("RESULT short=" + (Test-LlmKeyValid 'sk-1'))
"@
    Assert-True "real key is valid" ($out -match "RESULT real=True") $out
    Assert-True "empty key is invalid" ($out -match "RESULT empty=False") $out
    Assert-True "placeholder key is invalid" ($out -match "RESULT placeholder=False") $out
    Assert-True "too-short key is invalid" ($out -match "RESULT short=False") $out
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — the snippet errors because `scripts/lib/env-setup.ps1` does not exist.

- [ ] **Step 3: Implement `Test-LlmKeyValid`**

Create `scripts/lib/env-setup.ps1`:

```powershell
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
```

- [ ] **Step 4: Add `.run/` to `.gitignore`**

In `.gitignore`, inside the `# Runtime data` block, after `data/`, add:

```gitignore
.run/
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`, exit code 0.

- [ ] **Step 6: Commit**

```bash
git add e2e/startup-e2e.ps1 scripts/lib/env-setup.ps1 .gitignore
git commit -m "feat(startup): add llm key validation and startup e2e harness"
```

---

### Task 2: `Ensure-EnvFile` — create on first run, top up existing

**Files:**
- Modify: `scripts/lib/env-setup.ps1`
- Modify: `e2e/startup-e2e.ps1`

- [ ] **Step 1: Write the failing tests**

Add this block to `e2e/startup-e2e.ps1` immediately after the `Test-LlmKeyValid` assertions and **before** the `} finally {` line:

```powershell
    Write-Host "Ensure-EnvFile scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
`$root = Join-Path '$StubDir' 'fresh'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
`$r = Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/run/user/1000/podman/podman.sock' -NonInteractive -Preset deepseek -ApiKey 'sk-test-not-a-real-key-0000'
`$text = Get-Content (Join-Path `$root '.env') -Raw
Write-Output ("RESULT created=" + `$r.Created)
Write-Output ("RESULT hasKey=" + (`$text -match 'LLM_API_KEY=sk-test-not-a-real-key-0000'))
Write-Output ("RESULT hasBase=" + (`$text -match 'LLM_BASE_URL=https://api.deepseek.com'))
Write-Output ("RESULT hasRuntime=" + (`$text -match 'CONTAINER_RUNTIME=podman'))
Write-Output ("RESULT hasSocket=" + (`$text -match 'SANDBOX_SOCKET=/run/user/1000/podman/podman.sock'))
"@
    Assert-True "creates .env with all required keys" (($out -match "RESULT created=True") -and ($out -match "RESULT hasKey=True") -and ($out -match "RESULT hasBase=True") -and ($out -match "RESULT hasRuntime=True") -and ($out -match "RESULT hasSocket=True")) $out

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
`$root = Join-Path '$StubDir' 'existing'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
Set-Content -Path (Join-Path `$root '.env') -Value "LLM_API_KEY=sk-existing123456`nLLM_BASE_URL=https://api.openai.com/v1`nLLM_MODEL=my-model`n"
`$r = Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/run/user/1000/podman/podman.sock' -NonInteractive
`$text = Get-Content (Join-Path `$root '.env') -Raw
Write-Output ("RESULT added=" + (`$r.AddedKeys -join ','))
Write-Output ("RESULT keptKey=" + (`$text -match 'LLM_API_KEY=sk-existing123456'))
Write-Output ("RESULT keptModel=" + (`$text -match 'LLM_MODEL=my-model'))
Write-Output ("RESULT addedRuntime=" + (`$text -match 'CONTAINER_RUNTIME=podman'))
Write-Output ("RESULT overwroteModel=" + (`$text -match 'LLM_MODEL=deepseek-v4-flash'))
"@
    Assert-True "adds only missing keys to an existing .env" (($out -match "RESULT added=CONTAINER_RUNTIME,SANDBOX_SOCKET") -and ($out -match "RESULT keptKey=True") -and ($out -match "RESULT keptModel=True") -and ($out -match "RESULT addedRuntime=True")) $out
    Assert-True "never overwrites an existing value" ($out -match "RESULT overwroteModel=False") $out
```

The fixture must carry the whole `LLM_*` block even though only `LLM_MODEL` is asserted on:
`Ensure-EnvFile` tops up **every** missing key (design decision D4), so a fixture missing
`LLM_BASE_URL` would legitimately produce `AddedKeys` of
`LLM_BASE_URL,CONTAINER_RUNTIME,SANDBOX_SOCKET` and the `RESULT added=` assertion below would be
unreachable. The real `.env` has the same shape — `.env.example` ships the `LLM_*` values while
`CONTAINER_RUNTIME` and `SANDBOX_SOCKET` are the commented-out ones.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `Ensure-EnvFile` is not recognized.

- [ ] **Step 3: Implement `Ensure-EnvFile`**

Append to `scripts/lib/env-setup.ps1`:

```powershell
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
```

The two branches treat a blank `LLM_API_KEY` differently **on purpose**:

- **create** writes the `LLM_API_KEY=` slot even when it is empty, so a freshly generated file has
  the same shape as `.env.example` and the user can see where the key goes.
- **top up** skips a blank key, because injecting an empty `LLM_API_KEY=` into a file the user
  already curates adds noise without adding information.

Both paths end the same way: `Test-LlmKeyValid` rejects the empty value and the launcher stops with
`LLM_API_KEY is missing or still a placeholder in .env. Set a real key and retry.` The blank case can
only arise in non-interactive use without `-ApiKey`; the interactive first-run flow always writes a
real key.

**Additional regression scenarios** (added after the code-quality review; keep them in the harness):

1. **`-ContainerRuntime docker`** on a fresh root writes `CONTAINER_RUNTIME=docker` and the socket
   passed in — the launcher records the runtime it actually resolved, so the next run agrees.
2. **Blank-valued key** (`LLM_API_KEY=` with no `CONTAINER_RUNTIME`) plus a real `-ApiKey`: the key
   counts as present, so no duplicate line is appended; assert exactly one `^LLM_API_KEY=` line.
3. **Space-padded key** (`LLM_MODEL = padded`) with no `CONTAINER_RUNTIME`: the padded line is not
   loadable, so `LLM_MODEL` must be treated as missing and repaired. Assert it appears in
   `AddedKeys`, and — the assertion that would have caught the divergence — that after
   `Load-DotEnv` the effective value is the preset model, not the padded text.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`, exit code 0. The added-keys assertion must read exactly `RESULT added=CONTAINER_RUNTIME,SANDBOX_SOCKET`.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/env-setup.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): guided .env creation that never overwrites existing values"
```

---

### Task 3: `Resolve-MavenCommand` and the Maven shim

**Files:**
- Create: `scripts/lib/preflight.ps1`
- Modify: `e2e/startup-e2e.ps1`

Background: `start-backend.ps1` calls `mvn` directly and must not be modified. So when `mvn` is missing, the launcher materializes a `mvn.cmd` shim in `.run/bin` and prepends that directory to the child process PATH. Because the shim is resolved by `Get-Command`, `start-backend.ps1`'s existing `Test-Command "mvn"` passes without any change to that script.

- [ ] **Step 1: Write the failing tests**

Add to `e2e/startup-e2e.ps1` before `} finally {`:

```powershell
    Write-Host "Resolve-MavenCommand scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$PreflightLib'
`$root = Join-Path '$StubDir' 'mvnshim'
New-Item -ItemType Directory -Path (Join-Path `$root '.mvn/wrapper') -Force | Out-Null
Set-Content -Path (Join-Path `$root '.mvn/wrapper/maven-wrapper.jar') -Value 'stub'
`$dir = Initialize-MavenShim -ProjectRoot `$root -RunDir (Join-Path `$root '.run')
`$shim = Join-Path `$dir 'mvn.cmd'
Write-Output ("RESULT dirSet=" + [bool]`$dir)
Write-Output ("RESULT shimExists=" + (Test-Path `$shim))
Write-Output ("RESULT shimContent=" + ((Get-Content `$shim -Raw) -match 'org.apache.maven.wrapper.MavenWrapperMain'))

`$root2 = Join-Path '$StubDir' 'nomvn'
New-Item -ItemType Directory -Path `$root2 -Force | Out-Null
`$dir2 = Initialize-MavenShim -ProjectRoot `$root2 -RunDir (Join-Path `$root2 '.run')
Write-Output ("RESULT noJar=" + [bool]`$dir2)
"@
    Assert-True "writes a wrapper shim when the jar exists" (($out -match "RESULT dirSet=True") -and ($out -match "RESULT shimExists=True") -and ($out -match "RESULT shimContent=True")) $out
    Assert-True "returns nothing when neither mvn nor the wrapper jar exists" ($out -match "RESULT noJar=False") $out
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `scripts/lib/preflight.ps1` does not exist.

- [ ] **Step 3: Implement the preflight library**

Create `scripts/lib/preflight.ps1`:

```powershell
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/preflight.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): bootstrap maven through the repo wrapper jar when mvn is absent"
```

---

### Task 4: `Get-PortHolder`

**Files:**
- Modify: `scripts/lib/preflight.ps1`
- Modify: `e2e/startup-e2e.ps1`

- [ ] **Step 1: Write the failing tests**

Add to `e2e/startup-e2e.ps1` before `} finally {`:

```powershell
    Write-Host "Get-PortHolder scenarios:" -ForegroundColor Cyan

    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $busyPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    try {
        $out = Invoke-Snippet @"
. '$PreflightLib'
`$holder = Get-PortHolder -Port $busyPort
Write-Output ("RESULT busy=" + [bool]`$holder)
Write-Output ("RESULT pid=" + `$holder.Pid)
`$free = Get-PortHolder -Port 1
Write-Output ("RESULT free=" + [bool]`$free)
"@
        Assert-True "reports a holder for a busy port" ($out -match "RESULT busy=True") $out
        Assert-True "reports no holder for a free port" ($out -match "RESULT free=False") $out
    } finally {
        $listener.Stop()
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `Get-PortHolder` is not recognized.

- [ ] **Step 3: Implement `Get-PortHolder`**

Append to `scripts/lib/preflight.ps1`:

```powershell
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/preflight.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): detect which process holds a port"
```

---

### Task 5: `Wait-HttpHealthy`

**Files:**
- Modify: `scripts/lib/preflight.ps1`
- Modify: `e2e/startup-e2e.ps1`

- [ ] **Step 1: Write the failing tests**

Add to `e2e/startup-e2e.ps1` before `} finally {`:

```powershell
    Write-Host "Wait-HttpHealthy scenarios:" -ForegroundColor Cyan

    $healthy = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $healthy.Start()
    $healthyPort = ([System.Net.IPEndPoint]$healthy.LocalEndpoint).Port
    $job = Start-Job -ScriptBlock {
        param($port)
        $l = $healthy = $null
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
        $listener.Start()
        while ($true) {
            $client = $listener.AcceptTcpClient()
            $stream = $client.GetStream()
            $reader = New-Object System.IO.StreamReader($stream)
            while ($reader.Peek() -gt -1) { $null = $reader.ReadLine() }
            $body = '{"status":"UP"}'
            $resp = "HTTP/1.1 200 OK`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n$body"
            $bytes = [System.Text.Encoding]::ASCII.GetBytes($resp)
            $stream.Write($bytes, 0, $bytes.Length)
            $client.Close()
        }
    } -ArgumentList $healthyPort
    try {
        $out = Invoke-Snippet @"
. '$PreflightLib'
Write-Output ("RESULT up=" + (Wait-HttpHealthy -Url 'http://127.0.0.1:$healthyPort/actuator/health' -TimeoutSeconds 20 -ExpectBodyMatch '"status"\s*:\s*"UP"'))
Write-Output ("RESULT wrongBody=" + (Wait-HttpHealthy -Url 'http://127.0.0.1:$healthyPort/actuator/health' -TimeoutSeconds 2 -ExpectBodyMatch 'NEVER_MATCHES'))
Write-Output ("RESULT unreachable=" + (Wait-HttpHealthy -Url 'http://127.0.0.1:1/health' -TimeoutSeconds 2))
"@
        Assert-True "returns true when the body matches" ($out -match "RESULT up=True") $out
        Assert-True "returns false when the body never matches" ($out -match "RESULT wrongBody=False") $out
        Assert-True "returns false when unreachable" ($out -match "RESULT unreachable=False") $out
    } finally {
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
        $healthy.Stop()
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `Wait-HttpHealthy` is not recognized.

- [ ] **Step 3: Implement `Wait-HttpHealthy`**

Append to `scripts/lib/preflight.ps1`:

```powershell
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
        try {
            $resp = Invoke-WebRequest -Uri $Url -TimeoutSec 5 -UseBasicParsing
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/preflight.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): poll http health with a bounded timeout"
```

---

### Task 6: `Get-SandboxSocketPath` and `Start-PodmanMachineIfNeeded`

**Files:**
- Modify: `scripts/lib/container-runtime.ps1`
- Modify: `e2e/startup-e2e.ps1`

- [ ] **Step 1: Write the failing tests**

Add to `e2e/startup-e2e.ps1` before `} finally {`:

```powershell
    Write-Host "Sandbox socket and podman machine scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:PATH = '$(Join-Path $StubDir "sockstubs")'
`$path = Get-SandboxSocketPath
Write-Output ("RESULT socket=" + `$path)
"@ -Stubs @{}
    Assert-True "returns nothing when podman is unavailable" ($out -match "RESULT socket=$") $out

    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:PATH = '$StubDir\podmansock'
`$path = Get-SandboxSocketPath
Write-Output ("RESULT socket=" + `$path)
"@ -Stubs @{}

    Assert-True "resolves the socket from podman info" ($out -match "RESULT socket=/run/user/1000/podman/podman.sock") $out
```

Before the `try {` block of `e2e/startup-e2e.ps1`, add the stub creation:

```powershell
# podman stub whose `info --format ...` prints the rootless socket path, and whose
# `machine list` reports a stopped machine so the start path is exercised.
New-Item -ItemType Directory -Path (Join-Path $StubDir "podmansock") -Force | Out-Null
Set-Content -Path (Join-Path $StubDir "podmansock/podman.ps1") -Value @'
param([Parameter(ValueFromRemainingArguments=$true)]$Rest)
if ($Rest -contains 'info') {
    Write-Output 'unix:///run/user/1000/podman/podman.sock'
    exit 0
}
exit 1
'@
New-Item -ItemType Directory -Path (Join-Path $StubDir "podmanstopped") -Force | Out-Null
Set-Content -Path (Join-Path $StubDir "podmanstopped/podman.ps1") -Value @'
param([Parameter(ValueFromRemainingArguments=$true)]$Rest)
if ($Rest -contains 'info') { exit 1 }
if ($Rest -contains 'list') { Write-Output 'podman-machine-default*  wsl  stopped'; exit 0 }
if ($Rest -contains 'start') { Add-Content -Path (Join-Path $env:TEMP 'act-startup-started.txt') -Value 'start'; exit 0 }
exit 1
'@
```

And add a third scenario before `} finally {`:

```powershell
    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:PATH = '$StubDir\podmanstopped'
`$r = Start-PodmanMachineIfNeeded
Write-Output ("RESULT started=" + `$r)
"@
    Assert-True "starts a stopped podman machine" ($out -match "RESULT started=True") $out
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `Get-SandboxSocketPath` is not recognized.

- [ ] **Step 3: Implement both functions**

Append to `scripts/lib/container-runtime.ps1`:

```powershell
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/container-runtime.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): resolve the sandbox socket from the engine and start a stopped machine"
```

---

### Task 7: `Ensure-OpenSandboxServer` and `Ensure-OpencodeSandboxImage`

**Files:**
- Modify: `scripts/lib/container-runtime.ps1`
- Modify: `e2e/startup-e2e.ps1`

- [ ] **Step 1: Write the failing tests**

Add to `e2e/startup-e2e.ps1` before `} finally {`:

```powershell
    Write-Host "Ensure-OpenSandboxServer / image scenarios:" -ForegroundColor Cyan

    New-Item -ItemType Directory -Path (Join-Path $StubDir "podmanfull") -Force | Out-Null
    Set-Content -Path (Join-Path $StubDir "podmanfull/podman.ps1") -Value @'
param([Parameter(ValueFromRemainingArguments=$true)]$Rest)
$log = Join-Path $env:TEMP 'act-startup-calls.txt'
Add-Content -Path $log -Value ($Rest -join ' ')
if ($Rest -contains 'ps') { exit 0 }                 # nothing running
if ($Rest -contains 'image') { exit 1 }              # image absent
if ($Rest -contains 'compose') { exit 0 }
if ($Rest -contains 'build') { exit 0 }
exit 0
'@
    Remove-Item (Join-Path $env:TEMP 'act-startup-calls.txt') -ErrorAction SilentlyContinue
    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:PATH = '$StubDir\podmanfull'
Ensure-OpenSandboxServer -Runtime podman -ProjectRoot '$ProjectRoot'
Ensure-OpencodeSandboxImage -Runtime podman -ProjectRoot '$ProjectRoot'
Write-Output "RESULT done"
"@
    $calls = Get-Content (Join-Path $env:TEMP 'act-startup-calls.txt') -Raw
    Assert-True "starts the sandbox server via compose" ($calls -match 'compose up -d opensandbox-server') $calls
    Assert-True "builds the sandbox image when absent" ($calls -match 'build -t aria-conductor/opencode-sandbox:1.1') $calls
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `Ensure-OpenSandboxServer` is not recognized.

- [ ] **Step 3: Implement both functions**

Append to `scripts/lib/container-runtime.ps1`:

```powershell
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/container-runtime.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): ensure the opensandbox server and sandbox image exist"
```

---

### Task 8: `scripts/start.ps1` — the orchestrator

**Files:**
- Create: `scripts/start.ps1`
- Modify: `e2e/startup-e2e.ps1`

Testability seam: `-DryRun` runs phases 1–4 plus the mode summary and exits without starting anything. The real acceptance run in Step 6 covers phases 5–8.

- [ ] **Step 1: Write the failing tests**

Add to `e2e/startup-e2e.ps1` before `} finally {`:

```powershell
    Write-Host "start.ps1 -DryRun scenarios:" -ForegroundColor Cyan

    $dryRoot = Join-Path $StubDir "dryrun"
    New-Item -ItemType Directory -Path $dryRoot -Force | Out-Null
    $out = Invoke-Snippet @"
`$env:PATH = '$StubDir\podmanfull'
& '$ProjectRoot\scripts\start.ps1' -DryRun -NonInteractive -ProjectRoot '$dryRoot'
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
"@
    Assert-True "-DryRun exits 0" ($out -match "RESULT exit=0") $out
    Assert-True "-DryRun prints the mode block" (($out -match "Topology\s*:\s*local-dev") -and ($out -match "Provider\s*:\s*opencode") -and ($out -match "Runtime\s*:\s*podman")) $out
    Assert-True "-DryRun creates .env" (Test-Path (Join-Path $dryRoot ".env")) $out
    Assert-True "-DryRun does not create .run pids" (-not (Test-Path (Join-Path $dryRoot ".run/backend.pid"))) $out
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `scripts/start.ps1` does not exist.

- [ ] **Step 3: Implement `scripts/start.ps1`**

Create `scripts/start.ps1`:

```powershell
#!/usr/bin/env pwsh
<#
.SYNOPSIS
One-click local-dev startup for Aria Conductor.

Defaults to the opencode provider on podman in the local-dev topology (backend and
frontend on the host, OpenSandbox in a container) - the only topology in which the
opencode provider works. Use -Mode compose for the legacy full-stack compose stack,
which is langchain-only.

.PARAMETER Mode
local (default) or compose.

.PARAMETER DryRun
Run the environment checks (including the port check, which only reports), print the mode
block, then exit. Nothing is mutated: no containers are started, no ports are freed, no
processes are launched and no `.run/` state is written.
#>
param(
    [ValidateSet('local', 'compose')][string]$Mode = 'local',
    [switch]$DryRun,
    [switch]$NonInteractive,
    # Test seam: defaults to the repository root.
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
. (Join-Path $PSScriptRoot "lib/env-setup.ps1")
. (Join-Path $PSScriptRoot "lib/preflight.ps1")

$RunDir = Join-Path $ProjectRoot ".run"

$provider = if ($Mode -eq 'local') { 'opencode' } else { 'langchain' }
$topology = if ($Mode -eq 'local') { 'local-dev (backend + frontend on host)' } else { 'full-stack compose (backend in a container)' }

function Write-Phase([int]$Number, [string]$Text) {
    Write-Host ("[{0}/8] {1}" -f $Number, $Text) -ForegroundColor Cyan
}

function Get-EnvValue([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

# ── Phase 1: environment check ───────────────────────────────────────────────
Write-Host "Aria Conductor - one-click start ($Mode)" -ForegroundColor Cyan
Write-Phase 1 "Checking environment"

Load-DotEnv $ProjectRoot

# This flow targets podman by default: the opencode sandbox needs a runtime whose socket
# the OpenSandbox server can mount, and Docker Desktop is commonly installed alongside
# podman, where auto-detection would prefer docker. Pin podman when it is usable and only
# fall back to docker with a loud warning. Whatever is chosen here is what Phase 2 records
# in .env, so the next run agrees with this one.
if (-not $env:CONTAINER_RUNTIME) {
    if (Get-Command podman -ErrorAction SilentlyContinue) {
        & podman info *> $null
        if ($LASTEXITCODE -eq 0) { $env:CONTAINER_RUNTIME = 'podman' }
    }
    if (-not $env:CONTAINER_RUNTIME) {
        Write-Host "      podman is not usable - falling back to docker" -ForegroundColor Yellow
        $env:CONTAINER_RUNTIME = 'docker'
    }
}

$runtimeInfo = Resolve-ContainerRuntime
$runtime = $runtimeInfo.Runtime
if (-not $runtime) { throw "No container runtime available. Install podman (or docker) and retry." }
if ($runtime -eq 'podman') {
    if (Start-PodmanMachineIfNeeded) { Write-Host "      podman machine started" -ForegroundColor DarkGray }
}
foreach ($tool in @('java', 'node', 'pnpm')) {
    if (-not (Test-ToolchainCommand $tool)) { throw "$tool not found on PATH. Install it and retry." }
}
$mavenShimDir = Initialize-MavenShim -ProjectRoot (Join-Path $ProjectRoot 'agent-control-tower') -RunDir $RunDir
if ($mavenShimDir) {
    $env:PATH = "$mavenShimDir;$env:PATH"
    Write-Host "      mvn absent - using the repo wrapper (shim in .run/bin)" -ForegroundColor Yellow
}
if (-not (Test-ToolchainCommand 'mvn')) {
    throw "Maven not found and the wrapper bootstrap is unavailable. Install Maven 3.9+, or open a NEW terminal (a PATH change does not affect shells that are already open)."
}
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker info *> $null
    if ($LASTEXITCODE -eq 0 -and $runtime -eq 'podman') {
        Write-Host "      NOTE: Docker Desktop is also running. 'docker compose' targets Docker, not podman." -ForegroundColor Yellow
    }
}

# ── Phase 2: env guidance ────────────────────────────────────────────────────
Write-Phase 2 "Checking .env"
$socket = Get-SandboxSocketPath
if (-not $socket) {
    if ($runtime -eq 'podman') { throw "Could not read the podman socket path (podman info). Is the machine running?" }
    $socket = '/var/run/docker.sock'
}
$envResult = Ensure-EnvFile -ProjectRoot $ProjectRoot -SandboxSocket $socket `
    -ContainerRuntime $runtime -NonInteractive:$NonInteractive -ApiKey $env:LLM_API_KEY
if ($envResult.Created) {
    Write-Host "      created .env" -ForegroundColor DarkGray
} elseif ($envResult.AddedKeys.Count -gt 0) {
    Write-Host "      added to .env: $($envResult.AddedKeys -join ', ')" -ForegroundColor DarkGray
}
$envValues = @{}
foreach ($line in (Get-Content (Join-Path $ProjectRoot '.env'))) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.+)$') { $envValues[$Matches[1]] = $Matches[2] }
}
if (-not (Test-LlmKeyValid $envValues['LLM_API_KEY'])) {
    throw "LLM_API_KEY is missing or still a placeholder in .env. Set a real key and retry."
}

# ── Compose mode: the runtime owns the whole stack ───────────────────────────
if ($Mode -eq 'compose') {
    Write-Phase 3 "Starting the full-stack compose stack"
    Push-Location $ProjectRoot
    try {
        & $runtime compose up -d --build
        if ($LASTEXITCODE -ne 0) { throw "compose up failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    $composeBackendPort = Get-EnvValue 'BACKEND_PORT' '8080'
    $composeDashboardPort = Get-EnvValue 'FRONTEND_PORT' '3000'
    Write-Phase 7 "Reporting"
    Write-Host ""
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Aria Conductor - COMPOSE STACK STARTING" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Topology : $topology"
    Write-Host "  Provider : $provider"
    Write-Host "  Runtime  : $runtime ($($runtimeInfo.Mode))"
    Write-Host "  Dashboard: http://localhost:$composeDashboardPort"
    Write-Host "  Backend  : http://localhost:$composeBackendPort"
    Write-Host "---------------------------------------------------------"
    Write-Host "  NOTE: the opencode provider is NOT usable in this topology - the" -ForegroundColor Yellow
    Write-Host "  containerized backend cannot reach the sandbox endpoints. This stack" -ForegroundColor Yellow
    Write-Host "  runs the langchain provider. Run without -Mode for opencode." -ForegroundColor Yellow
    Write-Host "---------------------------------------------------------"
    Write-Host "  Logs : $runtime compose logs -f"
    Write-Host "  Stop : $runtime compose down"
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Phase 8 "Done"
    exit 0
}

# ── Phase 3: resource preparation ────────────────────────────────────────────
Write-Phase 3 "Preparing container resources"
if ($Mode -eq 'local' -and -not $DryRun) {
    Ensure-OpencodeSandboxImage -Runtime $runtime -ProjectRoot $ProjectRoot | Out-Null
    Ensure-OpenSandboxServer -Runtime $runtime -ProjectRoot $ProjectRoot | Out-Null
}

# ── Phase 4: port pre-check ──────────────────────────────────────────────────
Write-Phase 4 "Checking ports"
$backendPort = [int](Get-EnvValue 'BACKEND_PORT' '8080')
$frontendPort = [int](Get-EnvValue 'VITE_PORT' '5173')
$sandboxPort = [int](Get-EnvValue 'OPENSANDBOX_PORT' '8090')
$portsToCheck = if ($Mode -eq 'local') { @($backendPort, $sandboxPort, $frontendPort) } else { @($backendPort, $frontendPort) }
foreach ($port in $portsToCheck) {
    $holder = Get-PortHolder -Port $port
    if ($holder) {
        Write-Host "      port $port is held by $($holder.Name) (PID $($holder.Pid))" -ForegroundColor Yellow
        if ($DryRun) { continue }   # a dry run only reports; it never stops anything
        if ($NonInteractive) { throw "Port $port is in use. Stop PID $($holder.Pid) ($($holder.Name)) and retry." }
        $answer = Read-Host "      Stop PID $($holder.Pid) ($($holder.Name))? [y/N]"
        if ($answer -notmatch '^(?i)y') { throw "Aborted: port $port is still in use." }
        & taskkill /PID $holder.Pid /T /F | Out-Null
    }
}

# ── Mode confirmation ────────────────────────────────────────────────────────
function Write-ModeSummary([string]$Topology, [string]$Provider, [string]$RuntimeLine,
                          [string]$SandboxLine, [hashtable]$Checks) {
    Write-Host ""
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Aria Conductor - READY" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  Topology : $Topology"
    Write-Host "  Provider : $Provider"
    Write-Host "  Runtime  : $RuntimeLine"
    if ($SandboxLine) { Write-Host "  Sandbox  : $SandboxLine" }
    Write-Host "  Database : h2 (file)"
    Write-Host "---------------------------------------------------------"
    foreach ($k in $Checks.Keys) {
        Write-Host ("  {0,-10}: {1,-40} [{2}]" -f $k, $Checks[$k].Url, $Checks[$k].Result)
    }
    Write-Host "  Swagger  : http://localhost:$backendPort/swagger-ui.html"
    Write-Host "---------------------------------------------------------"
    Write-Host "  Logs : .run\backend.log   .run\frontend.log"
    Write-Host "  Stop : pwsh -File scripts\stop.ps1"
    Write-Host "=========================================================" -ForegroundColor Green
}

if ($DryRun) {
    $sandboxLine = if ($Mode -eq 'local') { "aria-opensandbox  http://localhost:$sandboxPort" } else { '' }
    Write-ModeSummary -Topology $topology -Provider $provider `
        -RuntimeLine "$runtime ($($runtimeInfo.Mode))" `
        -SandboxLine $sandboxLine -Checks @{}
    Write-Host ""
    Write-Host "-DryRun: environment OK, nothing started." -ForegroundColor Yellow
    exit 0
}

# ── Phase 5: start ───────────────────────────────────────────────────────────
Write-Phase 5 "Starting services"
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
$env:VITE_BACKEND_PORT = "$backendPort"

$backendArgs = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'start-backend.ps1'),
                 '-AdkProvider', $provider, '-SkipBuild')
if ($Mode -eq 'compose') { $backendArgs += '-SkipSandbox' }
$backend = Start-Process pwsh -ArgumentList $backendArgs -NoNewWindow -PassThru `
    -RedirectStandardOutput (Join-Path $RunDir 'backend.log') `
    -RedirectStandardError (Join-Path $RunDir 'backend.err.log')
Set-Content -Path (Join-Path $RunDir 'backend.pid') -Value $backend.Id

$frontend = Start-Process pwsh -ArgumentList @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'start-frontend.ps1')) `
    -NoNewWindow -PassThru `
    -RedirectStandardOutput (Join-Path $RunDir 'frontend.log') `
    -RedirectStandardError (Join-Path $RunDir 'frontend.err.log')
Set-Content -Path (Join-Path $RunDir 'frontend.pid') -Value $frontend.Id

# ── Phase 6: health verification ─────────────────────────────────────────────
Write-Phase 6 "Waiting for health"
$checks = [ordered]@{}
$backendOk = Wait-HttpHealthy -Url "http://localhost:$backendPort/actuator/health" -TimeoutSeconds 300 -ExpectBodyMatch '"status"\s*:\s*"UP"'
$checks['Dashboard'] = @{ Url = "http://localhost:$frontendPort"; Result = if (Wait-HttpHealthy -Url "http://localhost:$frontendPort" -TimeoutSeconds 120) { 'OK' } else { 'FAIL' } }
$checks['Backend'] = @{ Url = "http://localhost:$backendPort"; Result = if ($backendOk) { 'OK' } else { 'FAIL' } }
$allOk = $backendOk -and ($checks['Dashboard'].Result -eq 'OK')
if ($Mode -eq 'local') {
    $sandboxOk = Wait-HttpHealthy -Url "http://localhost:$sandboxPort/health" -TimeoutSeconds 120
    $allOk = $allOk -and $sandboxOk
}

# ── Phase 7: mode confirmation ───────────────────────────────────────────────
Write-Phase 7 "Reporting"
$sandboxLine = if ($Mode -eq 'local') { "aria-opensandbox  http://localhost:$sandboxPort" } else { '' }
Write-ModeSummary -Topology $topology -Provider $provider `
    -RuntimeLine "$runtime ($($runtimeInfo.Mode))" `
    -SandboxLine $sandboxLine -Checks $checks
if ($allOk) {
    Start-Process "http://localhost:$frontendPort" | Out-Null
}

# ── Phase 8: exit ────────────────────────────────────────────────────────────
Write-Phase 8 "Done"
if (-not $allOk) {
    Write-Host "One or more services did not become healthy. Tail of the backend log:" -ForegroundColor Red
    Get-Content (Join-Path $RunDir 'backend.log') -Tail 25 -ErrorAction SilentlyContinue
    Write-Host "Retry after fixing, or stop everything with: pwsh -File scripts\stop.ps1" -ForegroundColor Yellow
    exit 1
}
Write-Host "All services healthy." -ForegroundColor Green
exit 0
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`.

- [ ] **Step 5: Verify PowerShell parses the script**

Run: `pwsh -NoProfile -Command "[void][System.Management.Automation.Language.Parser]::ParseFile('D:/project/aria-conductor/scripts/start.ps1', [ref]$null, [ref]$null); 'PARSE OK'"`
Expected: `PARSE OK`.

- [ ] **Step 6: Real acceptance run**

Stop everything first (`pwsh -NoProfile -File scripts/stop.ps1 -SkipContainers` once it exists; until then stop services manually), then:

```bash
pwsh -NoProfile -File scripts/start.ps1
```

Expected: 8 phase lines, a READY block where Dashboard, Backend and Sandbox all read `[OK]`, the browser opens, exit code 0.

- [ ] **Step 7: Commit**

```bash
git add scripts/start.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): one-click launcher with preflight, health checks and mode summary"
```

---

### Task 9: `scripts/stop.ps1`

**Files:**
- Create: `scripts/stop.ps1`
- Modify: `e2e/startup-e2e.ps1`

- [ ] **Step 1: Write the failing test**

Add to `e2e/startup-e2e.ps1` before `} finally {`:

```powershell
    Write-Host "stop.ps1 scenarios:" -ForegroundColor Cyan

    $stopRoot = Join-Path $StubDir "stop"
    $stopRun = Join-Path $stopRoot ".run"
    New-Item -ItemType Directory -Path $stopRun -Force | Out-Null
    $victim = Start-Process pwsh -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep 300') -PassThru
    Set-Content -Path (Join-Path $stopRun 'backend.pid') -Value $victim.Id
    Set-Content -Path (Join-Path $stopRun 'frontend.pid') -Value $victim.Id
    $out = Invoke-Snippet @"
& '$ProjectRoot\scripts\stop.ps1' -ProjectRoot '$stopRoot' -SkipContainers
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
Write-Output ("RESULT runDirGone=" + (-not (Test-Path '$stopRun')))
"@
    Start-Sleep -Seconds 2
    Assert-True "stop.ps1 exits 0" ($out -match "RESULT exit=0") $out
    Assert-True "stop.ps1 terminates the recorded processes" (-not (Get-Process -Id $victim.Id -ErrorAction SilentlyContinue)) $out
    Assert-True "stop.ps1 removes .run" ($out -match "RESULT runDirGone=True") $out
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: FAIL — `scripts/stop.ps1` does not exist.

- [ ] **Step 3: Implement `scripts/stop.ps1`**

Create `scripts/stop.ps1`:

```powershell
#!/usr/bin/env pwsh
<#
.SYNOPSIS
Stops the services started by scripts/start.ps1.

.PARAMETER All
Also stops the sandbox containers and the podman machine.
#>
param(
    [switch]$All,
    # Test seams.
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$SkipContainers
)

$ErrorActionPreference = "Continue"
$RunDir = Join-Path $ProjectRoot ".run"

Write-Host "Stopping Aria Conductor services..." -ForegroundColor Cyan

foreach ($name in @('backend', 'frontend')) {
    $pidFile = Join-Path $RunDir "$name.pid"
    if (-not (Test-Path $pidFile)) { continue }
    $processId = (Get-Content $pidFile -Raw).Trim()
    if ($processId -match '^\d+$') {
        & taskkill /PID $processId /T /F *> $null
        Write-Host "  stopped $name (PID $processId)" -ForegroundColor DarkGray
    }
}

if (-not $SkipContainers) {
    Push-Location $ProjectRoot
    try {
        & podman compose stop opensandbox-server *> $null
        Write-Host "  stopped aria-opensandbox" -ForegroundColor DarkGray
        if ($All) {
            $sandboxes = & podman ps --filter 'name=sandbox-' --format '{{.Names}}' 2>$null
            foreach ($s in $sandboxes) { & podman stop $s *> $null }
            & podman machine stop *> $null
            Write-Host "  stopped sandbox containers and the podman machine" -ForegroundColor DarkGray
        }
    } finally {
        Pop-Location
    }
}

Remove-Item $RunDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "Done." -ForegroundColor Green
exit 0
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pwsh -NoProfile -File e2e/startup-e2e.ps1`
Expected: `All scenarios PASSED`.

- [ ] **Step 5: Real acceptance run**

```bash
pwsh -NoProfile -File scripts/start.ps1
pwsh -NoProfile -File scripts/stop.ps1
```

Expected after stop: ports 8080, 8090 and 5173 have no listener, no `aria-*` containers are running, and `.run/` is gone.

- [ ] **Step 6: Commit**

```bash
git add scripts/stop.ps1 e2e/startup-e2e.ps1
git commit -m "feat(startup): stop script driven by the launcher pid files"
```

---

### Task 10: Documentation truth-up

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `.env.example`

- [ ] **Step 1: Rewrite the README startup section**

In `README.md`, replace the "Container Runtime Selection" section body so that:

1. The primary instruction becomes:

```markdown
## Starting the stack

```powershell
.\scripts\start.ps1          # local-dev + opencode + podman (default)
.\scripts\stop.ps1           # stop everything
```

`start.ps1` checks the environment, starts the podman machine when needed, creates or tops up
`.env`, prepares the sandbox image and server, verifies health, then prints the running mode.
The opencode provider requires the **local-dev topology** — backend and frontend on the host,
OpenSandbox in a container. That is what this script starts.

`-Mode compose` runs the legacy full-stack compose stack instead. That topology cannot run the
opencode provider, so it uses langchain.
```

2. Delete the false claim `# Docker available → full OpenCode sandbox` and the phrase
   `full stack with OpenCode sandbox`.
3. Delete the note that begins `> **podman + host backend + opencode**: the full-stack compose
   topology runs the backend in a container, where the opencode provider is NOT usable` —
   it is superseded by the new section. Keep the `opensandbox-config.toml` reference by moving
   one sentence of it into the new compose paragraph.
4. Replace every `(requires Docker)` with `(requires a container runtime)`.
5. In the prerequisites table, the Docker/Podman row becomes
   `| Docker / Podman | 24+ / 5+ | podman is the default for local dev; docker is supported |`.

- [ ] **Step 2: Update AGENTS.md "Run full-stack locally"**

Replace the numbered list under `### Run full-stack locally` in `AGENTS.md` with:

```markdown
1. One-click: `pwsh -NoProfile -File scripts/start.ps1` (local-dev + opencode + podman; checks
   the environment, prepares the sandbox, verifies health, prints the mode)
2. Stop: `pwsh -NoProfile -File scripts/stop.ps1`
3. Legacy full-stack compose (langchain only): `pwsh -NoProfile -File scripts/start.ps1 -Mode compose`
4. ADK without a sandbox (langchain only): `cd langchain-adk && python -m uvicorn src.server:app --port 9300`
```

- [ ] **Step 3: Update `.env.example`**

In `.env.example`, replace the commented `SANDBOX_SOCKET` block with:

```
# Host socket mounted into the OpenSandbox server for sandbox creation.
# Leave unset for Docker. For podman, scripts/start.ps1 fills this in automatically from
# `podman info` (rootless: /run/user/1000/podman/podman.sock, rootful: /run/podman/podman.sock).
# SANDBOX_SOCKET=
```

And add above the `LLM_API_KEY` line:

```
# --- LLM Provider (DeepSeek is the preset used by scripts/start.ps1) ---
```

- [ ] **Step 4: Verify no stale claims remain**

Run: `grep -n "requires Docker\|full stack with OpenCode\|Docker available" README.md AGENTS.md`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add README.md AGENTS.md .env.example
git commit -m "docs(startup): local-dev opencode is the default path; remove stale compose claims"
```

---

### Task 11: Delete superseded and scratch files

**Files (delete):**
- `scripts/quickstart.ps1`
- `scripts/monitor-env-deletion.ps1`
- `e2e-test-setup.ps1`, `tmp-osb-smoke.ps1`, `NUL` (if present)
- `.env-monitor.log`, `.env-alert.txt` (if present)

This task is destructive. **Confirm the exact list with the user before running Step 1**, and check `git status` first: `scripts/quickstart.ps1` is tracked (so it is a real deletion to commit), while the rest are untracked scratch.

- [ ] **Step 1: Confirm the list, then delete**

```bash
git rm scripts/quickstart.ps1
rm -f scripts/monitor-env-deletion.ps1 e2e-test-setup.ps1 tmp-osb-smoke.ps1 NUL .env-monitor.log .env-alert.txt
```

- [ ] **Step 2: Verify nothing referenced the deleted files**

Run: `grep -rn "quickstart\.ps1\|monitor-env-deletion" --include=*.md --include=*.ps1 --include=*.sh --include=*.yml . | grep -v node_modules`
Expected: no output. If `README.md` still references `quickstart.ps1`, fix it in the same commit.

- [ ] **Step 3: Commit**

```bash
git add -A scripts/quickstart.ps1
git commit -m "chore(startup): remove superseded quickstart and scratch scripts"
```

---

## Waves & dependencies

Tasks 1–9 each append to the same file, `e2e/startup-e2e.ps1`. That file is therefore a
single-writer resource and serializes them — there is a file intersection, so no two of them may
be dispatched concurrently. Task 10 is disjoint and can run alongside any code wave.

| Wave | Task | Files written | Concurrency |
|------|------|---------------|-------------|
| 1 | Task 1 | `e2e/startup-e2e.ps1`, `scripts/lib/env-setup.ps1`, `.gitignore` | single |
| 2 | Task 2 | `scripts/lib/env-setup.ps1`, `e2e/startup-e2e.ps1` | single |
| 3 | Task 3 | `scripts/lib/preflight.ps1`, `e2e/startup-e2e.ps1` | single |
| 4 | Task 4 | `scripts/lib/preflight.ps1`, `e2e/startup-e2e.ps1` | single |
| 5 | Task 5 | `scripts/lib/preflight.ps1`, `e2e/startup-e2e.ps1` | single |
| 6 | Task 6 | `scripts/lib/container-runtime.ps1`, `e2e/startup-e2e.ps1` | single |
| 7 | Task 7 | `scripts/lib/container-runtime.ps1`, `e2e/startup-e2e.ps1` | single |
| 8 | Task 8 | `scripts/start.ps1`, `e2e/startup-e2e.ps1` | single |
| 9 | Task 9 | `scripts/stop.ps1`, `e2e/startup-e2e.ps1` | single |
| 10 | Task 10 | `README.md`, `AGENTS.md`, `.env.example` | **parallel with 1–9** |
| 11 | Task 11 | deletions | after 10 |

Dependency notes:

- Wave 2 needs `scripts/lib/env-setup.ps1` from wave 1; waves 4–5 need `preflight.ps1` from wave 3;
  wave 7 needs `container-runtime.ps1`'s existing content; wave 8 needs all three libraries;
  wave 9 needs `start.ps1`'s `.run/*.pid` convention from wave 8.
- Task 11 must follow Task 10, so the README stops pointing at `quickstart.ps1` before the file is
  removed.

Working agreements for execution:

- Implementation agents do **not** run `git add` or `git commit`. The controller stages and commits
  serially at the end of each wave using the commit message given in that task.
- Implementation agents do not run `git checkout`, `git reset`, or `git stash`.
- The in-task test runs are the TDD loop, not independent verification. After each wave the
  controller runs the full `e2e/startup-e2e.ps1` plus, for waves 8 and 9, the real acceptance run
  from the task, and a separate reviewer compares the diff against this plan before the wave is
  accepted.

## Plan self-review

**Spec coverage**

| Spec item | Task |
|---|---|
| §4 files table | Tasks 1–3, 8, 9, 11 |
| §5 phase 1 environment check | Task 8 (phase 1), Tasks 3, 6 |
| §5 phase 2 env guidance | Task 2, Task 8 (phase 2) |
| §5 phase 3 resource preparation | Task 7, Task 8 (phase 3) |
| §5 phase 4 port pre-check | Task 4, Task 8 (phase 4) |
| §5 phase 5 start | Task 8 (phase 5) |
| §5 phase 6 health verification | Task 5, Task 8 (phase 6) |
| §5 phase 7 mode confirmation + browser | Task 8 (phase 7) |
| §5 phase 8 exit | Task 8 (phase 8) |
| §5 port resolution incl. `VITE_BACKEND_PORT` | Task 8 (phase 5) |
| §6 auto-repair matrix | Tasks 2, 3, 6, 7, 8 |
| §7 mode summary layout | Task 8 (`Write-ModeSummary`) |
| §8 `stop.ps1` | Task 9 |
| §9 `.env` contract | Tasks 1, 2 |
| §10 documentation cleanup | Task 10 |
| §10 scratch deletion | Task 11 |
| §11 stub scenarios | Tasks 1–7, 9 |
| §11 real acceptance run | Task 8 step 6, Task 9 step 5 |
| §12 risks | Tasks 8 (exit paths), 9 (`-All`), 10 |

**Known deviation from the spec, deliberate:** spec §4 says `container-runtime.ps1` gains the preflight helpers. The plan instead puts port probing, health polling, Maven and env logic in two new focused libraries, leaving `container-runtime.ps1` to container concerns only. This follows the spec's intent (§5 architecture is unchanged) while keeping each file to one responsibility.

**Type/name consistency:** `Resolve-ContainerRuntime` / `Load-DotEnv` (existing), `Test-LlmKeyValid`, `Ensure-EnvFile` → `@{Created;AddedKeys;Path}`, `Test-ToolchainCommand`, `Initialize-MavenShim`, `Get-PortHolder` → `@{Port;Pid;Name}`, `Wait-HttpHealthy`, `Get-SandboxSocketPath`, `Start-PodmanMachineIfNeeded`, `Ensure-OpenSandboxServer`, `Ensure-OpencodeSandboxImage`, `Write-ModeSummary`. Same names used in every task and in the tests.

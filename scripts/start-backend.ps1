#!/usr/bin/env pwsh
param(
    [string]$Profile = "h2",
    [switch]$SkipBuild,
    [string]$AdkProvider = "opencode",
    [switch]$SkipSandbox
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"

# Shared container-runtime helpers (Load-DotEnv, Resolve-ContainerRuntime)
. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
# Pure startup checks (Resolve-WorkspaceRoots, Get-BridgeDistProblem)
. (Join-Path $PSScriptRoot "lib/startup-checks.ps1")
Load-DotEnv $ProjectRoot

# -SkipSandbox means no container runtime is available: default the provider to the
# Host-capable core qoder unless -AdkProvider was set explicitly (an explicit
# provider always wins). Host mode needs no Docker or Podman at all.
if ($SkipSandbox -and -not $PSBoundParameters.ContainsKey('AdkProvider')) {
    $AdkProvider = "qoder"
    Write-Host "  -SkipSandbox: defaulting ADK provider to qoder (Host mode needs no container runtime)."
}
$sandboxMode = $AdkProvider -eq "opencode" -and -not $SkipSandbox

# ── Run-owned absolute paths (F30: outside the checkout, never cwd-relative) ──
# A run's worktree, generated configuration and retained results are owned by the
# backend's run roots, and the qoder bridge entry is a committed artifact of the
# repository. The run roots default to a user-scoped location OUTSIDE this checkout
# (LOCALAPPDATA\AriaConductor\workspaces on Windows; $HOME/.aria-conductor/workspaces
# otherwise), so a bare `git` in a run's scratch directory cannot resolve to the
# operator repository and the result clones never land inside it. Paths are absolute
# and do not depend on the working directory the Spring Boot process happens to run in
# (mvn spring-boot:run -pl act-app runs in act-app/). An operator-provided value
# (ARIA_WORKSPACES_RUNTIME_ROOT / ARIA_WORKSPACES_RESULT_ROOT, including one loaded
# from .env) always wins.
$runtimeRootFromOperator = [bool]$env:ARIA_WORKSPACES_RUNTIME_ROOT
$resultRootFromOperator = [bool]$env:ARIA_WORKSPACES_RESULT_ROOT
$workspaceRoots = Resolve-WorkspaceRoots
if (-not $env:ARIA_WORKSPACES_RUNTIME_ROOT) {
    $env:ARIA_WORKSPACES_RUNTIME_ROOT = $workspaceRoots.RuntimeRoot
}
if (-not $env:ARIA_WORKSPACES_RESULT_ROOT) {
    $env:ARIA_WORKSPACES_RESULT_ROOT = $workspaceRoots.ResultRoot
}
$bridgeEntry = Join-Path $ProjectRoot "packages/qoder-acp-bridge/dist/main.js"
if (-not $env:ARIA_CORES_QODER_BRIDGE_ENTRY -and (Test-Path -LiteralPath $bridgeEntry -PathType Leaf)) {
    $env:ARIA_CORES_QODER_BRIDGE_ENTRY = $bridgeEntry
}

# Prerequisites check
function Test-Command($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Error "$cmd is not installed. $hint"
        exit 1
    }
}

Test-Command "java" "Install JDK 21: https://adoptium.net/"
Test-Command "mvn" "Install Maven 3.9+: https://maven.apache.org/"

# ── Preflight: report toolchain status (non-fatal warnings only) ──
Write-Host "Preflight:" -ForegroundColor Cyan

$javaVersion = (java -version 2>&1 | Select-Object -First 1)
Write-Host "  Java  : $javaVersion"
if ($javaVersion -notmatch '"21') {
    Write-Warning "JDK 21 is recommended (found: $javaVersion)."
}

$mvnVersion = (mvn -version 2>&1 | Select-Object -First 1)
Write-Host "  Maven : $mvnVersion"

# F30: print where the run roots resolved, so the operator can see the clones land
# outside the checkout (an operator-provided value is marked as such).
$runtimeRootOrigin = if ($runtimeRootFromOperator) { "operator" } else { "default" }
$resultRootOrigin = if ($resultRootFromOperator) { "operator" } else { "default" }
Write-Host "  Workspace runtime root: $($env:ARIA_WORKSPACES_RUNTIME_ROOT) [$runtimeRootOrigin]"
Write-Host "  Workspace result root : $($env:ARIA_WORKSPACES_RESULT_ROOT) [$resultRootOrigin]"

# ── Qoder HOST launch preflight (F3/F4/F10/F13) ──
# The run launcher clears the process environment, so the core's shell sees only
# the PATH this backend was started with (plus aria.cores.toolchain-path). Report
# what the core can and cannot resolve, with the locations the launcher will use.
$qoderCli = Join-Path $env:USERPROFILE ".qoder/bin/qodercli"
if (Test-Path -LiteralPath $qoderCli -PathType Container) {
    $cliVersioned = Get-ChildItem -LiteralPath $qoderCli -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'qodercli*' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($cliVersioned) {
        Write-Host "  qoder CLI: $($cliVersioned.FullName)" -ForegroundColor DarkGray
    } else {
        Write-Warning "No qodercli executable under $qoderCli. A HOST qoder run cannot start: install the Qoder CLI, or set ARIA_CORES_QODER_EXECUTABLE to its absolute path."
    }
} else {
    Write-Host "  qoder CLI: not found under $qoderCli (a HOST qoder run needs ARIA_CORES_QODER_EXECUTABLE then)." -ForegroundColor DarkGray
}
# F31: a stale packages/qoder-acp-bridge/dist after a merge makes the run-owned core
# fail with "E_USAGE Unknown flag: --child-env-file" while the API surfaces only the
# generic control-secret handshake failure. Verify the bridge dist before launching;
# rebuild it when pnpm is available, otherwise stop with the exact commands.
$bridgeDir = Join-Path $ProjectRoot "packages/qoder-acp-bridge"
$bridgeProblem = Get-BridgeDistProblem -BridgeDir $bridgeDir
if ($bridgeProblem) {
    Write-Warning "The qoder bridge dist is not fresh: $bridgeProblem"
    if (Get-Command pnpm -ErrorAction SilentlyContinue) {
        Write-Host "  Rebuilding the bridge: pnpm install --frozen-lockfile && pnpm build" -ForegroundColor Yellow
        Push-Location $bridgeDir
        try {
            pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) {
                Write-Error "pnpm install --frozen-lockfile failed in $bridgeDir"
                exit 1
            }
            pnpm build
            if ($LASTEXITCODE -ne 0) {
                Write-Error "pnpm build failed in $bridgeDir"
                exit 1
            }
        } finally {
            Pop-Location
        }
        $bridgeProblem = Get-BridgeDistProblem -BridgeDir $bridgeDir
        if ($bridgeProblem) {
            Write-Error "The qoder bridge is still not fresh after a rebuild: $bridgeProblem"
            exit 1
        }
        Write-Host "  qoder bridge rebuilt: dist/main.js is fresh." -ForegroundColor DarkGray
    } else {
        Write-Error ("The qoder bridge dist is not fresh and pnpm is not installed. Rebuild it manually (do not use npm or yarn):" +
            "`n  cd $bridgeDir`n  pnpm install --frozen-lockfile`n  pnpm build")
        exit 1
    }
}
foreach ($tool in @("gh", "jq")) {
    if (Get-Command $tool -ErrorAction SilentlyContinue) {
        Write-Host "  $tool : $((Get-Command $tool).Source)" -ForegroundColor DarkGray
    } else {
        Write-Warning "$tool is not on this PATH; a HOST core will not see it either. Add its directory to ARIA_CORES_TOOLCHAIN_PATH (comma-separated) if a workflow step needs it."
    }
}

# Container runtime status (required only for -AdkProvider opencode)
foreach ($rt in @("docker", "podman")) {
    if (Get-Command $rt -ErrorAction SilentlyContinue) {
        & $rt info *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  $rt : running"
        } else {
            Write-Warning "$rt is installed but not running. Required only for -AdkProvider opencode."
        }
    } else {
        Write-Host "  $rt : not installed" -ForegroundColor DarkGray
    }
}

$runtimeInfo = $null
$runtimeError = $null
try {
    $runtimeInfo = Resolve-ContainerRuntime
} catch {
    $runtimeError = $_.Exception.Message
}
if ($runtimeError) {
    Write-Warning "Container runtime: $runtimeError"
} elseif ($runtimeInfo.Runtime) {
    $reason = if ($runtimeInfo.Mode -eq "explicit") { "explicit: CONTAINER_RUNTIME" } else { "auto-detected" }
    Write-Host "  Container runtime: $($runtimeInfo.Runtime) ($reason)"
} else {
    Write-Warning "No container runtime available. Required only for -AdkProvider opencode."
}

if (-not $env:DEEPSEEK_API_KEY) {
    Write-Warning "DEEPSEEK_API_KEY is not set; real LLM calls will fail."
} else {
    Write-Host "  DeepSeek API key: set"
}

if (-not $env:GITHUB_TOKEN) {
    Write-Warning "GITHUB_TOKEN is not set. The backend resolves the git credential per operation, so a credential stored with POST /api/v1/packs/pack-git-0001/credentials is used by the next branch operation without a restart - but a HOST core only receives the resolved pack credential, never an ambient GH_TOKEN/GITHUB_TOKEN. A gh CLI login is not adopted either. Store it once (or export GITHUB_TOKEN before starting) if BA/Dev steps need private repository access."
}

# Windows: the host cores find their own runtimes; no Python runtime is needed.

# ── OpenSandbox server (required for opencode provider in Sandbox mode) ──
if ($sandboxMode) {
    Write-Host "Checking OpenSandbox server..." -ForegroundColor Cyan
    if ($runtimeError) {
        Write-Error "Container runtime unavailable: $runtimeError"
        exit 1
    }
    $rt = $runtimeInfo.Runtime
    if (-not $rt) {
        Write-Error "Neither docker nor podman is available. The opencode provider in Sandbox mode requires a container runtime for the OpenSandbox server. Install Docker or podman, or run in Host mode with -SkipSandbox (qoder)."
        exit 1
    }

    $startedSandbox = $false
    try {
        # Shared helper: no-ops when aria-opensandbox already runs, else `compose up -d`.
        $startedSandbox = Ensure-OpenSandboxServer -Runtime $rt -ProjectRoot $ProjectRoot
    } catch {
        if ($rt -eq "podman") {
            Write-Host "podman hint: verify the socket is enabled (podman machine ssh 'systemctl --user is-active podman.socket') and SANDBOX_SOCKET in .env matches its VM path." -ForegroundColor Yellow
        }
        Write-Error "Failed to start OpenSandbox server: $($_.Exception.Message)"
        exit 1
    }
    if ($startedSandbox) {
        Write-Host "Started OpenSandbox server ($rt compose)" -ForegroundColor DarkGray
        # `compose up -d` returns before the server accepts connections; the backend warms the
        # sandbox shortly after this, so give it a moment rather than racing it.
        Start-Sleep -Seconds 3
    }

    # Default OpenSandbox URL for local dev (host port 8090). The IPv4 loopback,
    # never "localhost": an OPENCODE_SANDBOX_SERVER_URL env var overrides the yml,
    # and the direct execd endpoints the SDK dials carry the server-advertised
    # host, so a "localhost" base exposes the IPv6-loopback resolution flake
    # ("Failed to connect to localhost/[0:0:0:0:0:0:0:1]:<port>").
    if (-not $env:OPENCODE_SANDBOX_SERVER_URL) {
        $env:OPENCODE_SANDBOX_SERVER_URL = "http://127.0.0.1:8090"
    }
}

# ── LLM credentials (injected into sandbox env for opencode provider) ──
if (-not $env:DEEPSEEK_API_KEY -and $env:LLM_API_KEY) {
    $env:DEEPSEEK_API_KEY = $env:LLM_API_KEY
}

Write-Host "Starting Aria Conductor backend..." -ForegroundColor Cyan
Write-Host "  Profile: $Profile"
Write-Host "  ADK Provider: $AdkProvider"
Write-Host "  Port: 8080"
if ($AdkProvider -eq "opencode") {
    Write-Host "  OpenSandbox: $($env:OPENCODE_SANDBOX_SERVER_URL)" -ForegroundColor DarkGray
}

Set-Location $BackendDir

if (-not $SkipBuild) {
    Write-Host "Installing backend modules (mvn install -DskipTests -q)..." -ForegroundColor Yellow
    mvn install -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "mvn install failed"; exit 1 }
}

Write-Host "Launching Spring Boot..." -ForegroundColor Green
# R-F6 mitigation: JDK 21 HttpClient HTTP/1.1 idle-connection keep-alive tuning.
# The default keepalive.timeout (1200s = 20min) is below the 15-31min opencode task window,
# so idle connections get dropped mid-task; raising it (plus a larger connection pool) reduces
# those drops. NOTE: this cannot fix opencode serve's own timeout on the sandbox side.
# java.net.preferIPv4Stack: "localhost" resolves to the IPv6 loopback ([::1]) on this host and
# the JDK client dials the first resolved address, so the direct execd endpoints (host
# "localhost" as advertised by the OpenSandbox server) intermittently fail at connect time
# ("Failed to connect to localhost/[0:0:0:0:0:0:0:1]:<port>"). Pinning IPv4 makes the loopback
# resolution deterministic and kills that flake for every dialed URL.
mvn spring-boot:run -pl act-app "-Dspring-boot.run.profiles=$Profile" "-Dspring-boot.run.jvmArguments=--enable-preview -Djdk.httpclient.keepalive.timeout=3600 -Djdk.httpclient.connectionPoolSize=8 -Djava.net.preferIPv4Stack=true" "-Dspring-boot.run.arguments=--adk.default-provider=$AdkProvider"

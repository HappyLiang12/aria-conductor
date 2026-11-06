# Podman Container Runtime Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Aria Conductor 启动链路支持用户选择 podman 替代 docker（`CONTAINER_RUNTIME` + `SANDBOX_SOCKET` 配置），并保证 opencode 模式沙箱（OpenSandbox）在 podman 下可用。

**Architecture:** 新增共享运行时解析库（PowerShell + bash 各一份，被所有启动脚本 dot-source），解析规则 = 显式 `CONTAINER_RUNTIME` 严格模式 → docker 优先的自动探测。OpenSandbox 容器挂载的引擎 socket 由 `SANDBOX_SOCKET` 参数化（默认 `/var/run/docker.sock`，podman 用 VM 内路径）。Java `SandboxRunner` 补齐同样的环境变量优先级。E2E 场景测试用 stub CLI 驱动解析逻辑。

**Tech Stack:** PowerShell 7 / bash、Docker Compose / Podman Compose、Java 21 (JUnit 5 + AssertJ)、Playwright（复用现有套件）。

**Spec:** `docs/superpowers/specs/2026-08-16-podman-support-design.md`（分支 `feat/podman-support` 已基于 origin/main 4ed1b59 建立，spec 已提交为 46bb619）。

**Conventions:** 每任务完成即提交（pre-commit 护栏：format + TS type check + sensitive scan）。执行环境：Windows 22H2 + pwsh 7；工作区 `C:\Users\User\.qoder\worktree\aria-conductor\fvHa7E`。

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `scripts/lib/container-runtime.ps1` | Create | PowerShell 共享库：`Load-DotEnv` / `Test-RuntimeCli` / `Resolve-ContainerRuntime` |
| `scripts/lib/container-runtime.sh` | Create | bash 共享库：`load_dotenv` / `runtime_cli_ok` / `resolve_container_runtime` |
| `e2e/container-runtime-e2e.ps1` | Create | PowerShell 场景测试（stub CLI 注入） |
| `e2e/container-runtime-e2e.sh` | Create | bash 场景测试（spec 未点名但覆盖 sh 脚本，与 ps1 等价） |
| `scripts/start-backend.ps1` / `.sh` | Modify | dot-source 库、preflight 报告、opencode 段运行时抽象 |
| `scripts/quickstart.ps1` / `.sh` | Modify | 运行时解析 + `$RT compose`（顺带修复 quickstart.ps1 的 ProjectRoot 双重 Parent 缺陷） |
| `docker-compose.yml` | Modify | `opensandbox-server` socket 挂载参数化（1 处） |
| `.env.example` | Modify | 新增 `CONTAINER_RUNTIME` / `SANDBOX_SOCKET` 注释示例 |
| `agent-control-tower/act-execution/.../sandbox/SandboxRunner.java` | Modify | `resolveRuntime` 静态方法 + `detectRuntime` 环境变量优先级 |
| `agent-control-tower/act-execution/.../sandbox/SandboxRunnerTest.java` | Modify | 新增优先级测试 |
| `README.md`、`AGENTS.md`、`agent-control-tower/opencode-sandbox/README.md` | Modify | 文档 |
| `report-podman-e2e.md` + `e2e/screenshots/podman/` | Create (证据) | Spike B 证据归档（遵循项目 `report-*.md` 惯例） |

---

### Task 1: 验证 Spike A — podman machine + OpenSandbox 兼容性（风险退休门禁）

**Files:** 无代码改动（临时使用未跟踪的 `docker-compose.podman.yml`，用后即删）

- [ ] **Step 1.1: 确认 podman 可用**

Run:
```powershell
podman --version; podman machine list
```
Expected: podman ≥ 4.9 已安装且存在一个 machine（未运行则 `podman machine start`）。

**Contingency:** 若 podman 未安装 → 按 spec §8.1 停在此处，向用户报告并确认：继续代码任务（Task 2-8 不依赖 podman），文档标注"podman 实验性"；本任务剩余步骤待 podman 可用后补做。

- [ ] **Step 1.2: 启用并验证 rootless socket（VM 内）**

Run:
```powershell
podman machine ssh "systemctl --user is-active podman.socket"
```
Expected: `active`。若为 `inactive`：
```powershell
podman machine ssh "systemctl --user enable --now podman.socket"
podman machine ssh "ls -l /run/user/1000/podman/podman.sock"
```
Expected: socket 文件存在（rootful 备选：`podman machine set --rootful` + `podman machine ssh "sudo systemctl enable --now podman.socket"` → 路径 `/run/podman/podman.sock`）。记录实际可用路径，供后续任务引用。

- [ ] **Step 1.3: 构建 opencode 沙箱镜像到 podman 镜像库**

Run:
```powershell
podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox
```
Expected: 构建成功，`podman images` 中出现 `aria-conductor/opencode-sandbox:1.1`。

- [ ] **Step 1.4: 创建临时 override 并启动 opensandbox-server**

Create `docker-compose.podman.yml` (repo root, untracked):
```yaml
services:
  opensandbox-server:
    volumes:
      - /run/user/1000/podman/podman.sock:/var/run/docker.sock
      - opensandbox_data:/root/.opensandbox
```
（若 Step 1.2 使用 rootful，路径改为 `/run/podman/podman.sock`。）

Run:
```powershell
podman compose up -d opensandbox-server
curl -s http://localhost:8090/health
```
Expected: `{"status":"healthy"}`。若启动失败，`podman compose logs opensandbox-server` 定位（socket 权限/路径问题最常见）。

- [ ] **Step 1.5: 通过 API 创建/销毁沙箱，验证 Windows 主机可达 execd 端口（关键门禁）**

Run:
```powershell
$body = '{"image":{"uri":"aria-conductor/opencode-sandbox:1.1"},"entrypoint":["opencode","serve","--hostname","0.0.0.0","--port","4096"],"timeout":1800}'
$sb = Invoke-RestMethod -Method Post -Uri "http://localhost:8090/v1/sandboxes" -ContentType "application/json" -Body $body
$sb.id
# poll until Running (up to ~120s):
Invoke-RestMethod -Uri "http://localhost:8090/v1/sandboxes/$($sb.id)"
$ep = Invoke-RestMethod -Uri "http://localhost:8090/v1/sandboxes/$($sb.id)/endpoints/4096"
$ep
```
Expected: 沙箱状态 `Running`；endpoints 返回 `http://127.0.0.1:<mapped>/proxy/4096` 形式的 URL（mapped 落在 40000-60000 范围内）。然后验证 Windows 主机可达：
```powershell
Invoke-WebRequest -Uri ($ep.url ?? "http://127.0.0.1:$($ep.port)/proxy/4096/health") -UseBasicParsing -TimeoutSec 15
podman ps   # 沙箱容器在列
Invoke-RestMethod -Method Delete -Uri "http://localhost:8090/v1/sandboxes/$($sb.id)"
```
Expected: 请求返回（任意 HTTP 状态即证明端口转发打通，404/200 均可接受）；`podman ps` 显示沙箱容器；删除成功。

**Gate:** 若本步骤失败（端口不可达 / 容器无法创建）→ 按 spec §8.1 记录失败细节于 `report-podman-e2e.md`，与用户确认回退策略后再继续；成功则 podman 风险已退休。

- [ ] **Step 1.6: 清理临时文件**

Run:
```powershell
podman compose down
Remove-Item docker-compose.podman.yml -Force
```
Expected: 无残留（`git status` 无新增文件）。

---

### Task 2: SandboxRunner 运行时优先级 TDD（Java）

**Files:**
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/sandbox/SandboxRunnerTest.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/sandbox/SandboxRunner.java`

- [ ] **Step 2.1: 写失败测试**

在 `SandboxRunnerTest.java` 的 `buildRunCommand_normalizesWindowsPathsToForwardSlashes` 测试之后、类结束 `}` 之前追加：

```java
    // ---- runtime resolution precedence (CONTAINER_RUNTIME env override) ----

    @Test
    void resolveRuntime_noEnvVar_autoDetectsDockerFirstThenPodman() {
        assertThat(SandboxRunner.resolveRuntime(null, true, true)).isEqualTo("docker");
        assertThat(SandboxRunner.resolveRuntime("", false, true)).isEqualTo("podman");
        assertThat(SandboxRunner.resolveRuntime("   ", false, false)).isNull();
    }

    @Test
    void resolveRuntime_explicitEnvWithCliPresent_winsOverDetection() {
        assertThat(SandboxRunner.resolveRuntime("docker", true, true)).isEqualTo("docker");
        assertThat(SandboxRunner.resolveRuntime("DOCKER", true, false)).isEqualTo("docker");
        assertThat(SandboxRunner.resolveRuntime(" podman ", false, true)).isEqualTo("podman");
    }

    @Test
    void resolveRuntime_explicitEnvValidButCliMissing_disablesSandbox() {
        // Strict: no cross-runtime fallback when the user explicitly chose one.
        assertThat(SandboxRunner.resolveRuntime("docker", false, true)).isNull();
        assertThat(SandboxRunner.resolveRuntime("podman", true, false)).isNull();
    }

    @Test
    void resolveRuntime_invalidEnvValue_isIgnoredAndAutoDetected() {
        assertThat(SandboxRunner.resolveRuntime("nerdctl", true, true)).isEqualTo("docker");
        assertThat(SandboxRunner.resolveRuntime("containerd", false, true)).isEqualTo("podman");
    }
```

- [ ] **Step 2.2: 运行测试确认失败**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=SandboxRunnerTest`
Expected: FAIL — 编译错误 `cannot find symbol: method resolveRuntime`。

- [ ] **Step 2.3: 实现最小代码**

`SandboxRunner.java` 两处修改：

(a) import 区（`java.util.regex.Pattern;` 之后）加：
```java
import java.util.Locale;
```

(b) 替换 `detectRuntime()` 方法并在其后新增 `resolveRuntime`：

```java
    private void detectRuntime() {
        containerRuntime = resolveRuntime(System.getenv("CONTAINER_RUNTIME"),
                commandExists("docker"), commandExists("podman"));
        if (containerRuntime == null) {
            log.warn("No container runtime detected. Sandbox tools disabled.");
        }
    }

    /**
     * Resolves the container runtime, in precedence order:
     * <ol>
     * <li>{@code CONTAINER_RUNTIME} env var set to docker|podman AND its CLI exists → that runtime</li>
     * <li>{@code CONTAINER_RUNTIME} valid but CLI missing → {@code null} (sandbox disabled, no cross-runtime fallback)</li>
     * <li>{@code CONTAINER_RUNTIME} invalid/blank → ignored (auto-detection applies)</li>
     * <li>auto-detect: docker first, then podman; neither → {@code null}</li>
     * </ol>
     */
    static String resolveRuntime(String explicitEnv, boolean dockerExists, boolean podmanExists) {
        if (explicitEnv != null && !explicitEnv.isBlank()) {
            String rt = explicitEnv.trim().toLowerCase(Locale.ROOT);
            if ("docker".equals(rt) || "podman".equals(rt)) {
                if (("docker".equals(rt) && dockerExists) || ("podman".equals(rt) && podmanExists)) {
                    return rt;
                }
                log.warn("CONTAINER_RUNTIME='{}' is set but its CLI is unavailable. Sandbox tools disabled.", rt);
                return null;
            }
            log.warn("CONTAINER_RUNTIME='{}' is invalid (expected docker|podman). Ignoring and auto-detecting.", explicitEnv);
        }
        if (dockerExists) return "docker";
        if (podmanExists) return "podman";
        return null;
    }
```

- [ ] **Step 2.4: 运行测试确认通过**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=SandboxRunnerTest`
Expected: PASS（含既有 8 个测试，全绿）。

- [ ] **Step 2.5: 模块全量回归**

Run: `cd agent-control-tower && mvn test -pl act-execution`
Expected: BUILD SUCCESS（docker 路径无回归）。

- [ ] **Step 2.6: 提交**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/sandbox/SandboxRunner.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/sandbox/SandboxRunnerTest.java
git commit -m "feat(sandbox): honor CONTAINER_RUNTIME env in SandboxRunner detection"
```

---

### Task 3: PowerShell 运行时解析库 + E2E 场景测试

**Files:**
- Create: `scripts/lib/container-runtime.ps1`
- Create: `e2e/container-runtime-e2e.ps1`

- [ ] **Step 3.1: 先写场景测试（预期失败：库不存在）**

Create `e2e/container-runtime-e2e.ps1`:

```powershell
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
```

- [ ] **Step 3.2: 运行确认失败**

Run: `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1`
Expected: FAIL — 每个场景报 `The term 'Resolve-ContainerRuntime' is not recognized`（库不存在）。

- [ ] **Step 3.3: 创建库**

Create `scripts/lib/container-runtime.ps1`:

```powershell
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
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name))) {
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
    & $Runtime info *> $null
    return ($LASTEXITCODE -eq 0)
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
                throw "CONTAINER_RUNTIME=podman is set but podman is not available. Install podman, ensure a machine is running ('podman machine start'), then retry."
            }
            throw "CONTAINER_RUNTIME=docker is set but docker is not available. Install/start Docker Desktop, or switch CONTAINER_RUNTIME to podman."
        }
        return @{ Runtime = $rt; Mode = "explicit" }
    }
    if (Test-RuntimeCli "docker") { return @{ Runtime = "docker"; Mode = "auto" } }
    if (Test-RuntimeCli "podman") { return @{ Runtime = "podman"; Mode = "auto" } }
    return @{ Runtime = $null; Mode = "auto" }
}
```

- [ ] **Step 3.4: 运行确认通过**

Run: `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1`
Expected: 10 个场景全部 PASS，退出码 0。

- [ ] **Step 3.5: 提交**

```bash
git add scripts/lib/container-runtime.ps1 e2e/container-runtime-e2e.ps1
git commit -m "feat(scripts): container-runtime resolution lib + pwsh e2e scenario tests"
```

---

### Task 4: bash 运行时解析库 + E2E 场景测试

**Files:**
- Create: `scripts/lib/container-runtime.sh`
- Create: `e2e/container-runtime-e2e.sh`

- [ ] **Step 4.1: 先写场景测试（预期失败：库不存在）**

Create `e2e/container-runtime-e2e.sh`:

```bash
#!/usr/bin/env bash
# E2E scenario tests for container-runtime resolution (scripts/lib/container-runtime.sh).
# Zero external dependencies: stub docker/podman CLIs are injected via a temp PATH,
# and every scenario runs in a fresh bash process so a real docker/podman
# on the host can never leak into the test.
# Run: bash e2e/container-runtime-e2e.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
LIB_PATH="$PROJECT_ROOT/scripts/lib/container-runtime.sh"

STUB_ROOT="$(mktemp -d)"
FAILURES=0

pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1 ($2)"; FAILURES=$((FAILURES + 1)); }

# run_scenario <CONTAINER_RUNTIME value> <stub>[:dead] ...
# Creates stub CLIs ("name" = running, "name:dead" = engine down) in an isolated
# dir, then resolves in a fresh bash whose PATH only exposes that dir.
run_scenario() {
    local runtime_env="$1"; shift
    local dir="$STUB_ROOT/$(date +%s)-$RANDOM"
    mkdir -p "$dir"
    for spec in "$@"; do
        local name="${spec%:*}" mode="${spec#*:}"
        [ "$spec" = "$name" ] && mode="ok"
        if [ "$mode" = "dead" ]; then
            printf '#!/bin/bash\nexit 1\n' > "$dir/$name"
        else
            printf '#!/bin/bash\n[ "$1" = "info" ] && exit 0\nexit 1\n' > "$dir/$name"
        fi
        chmod +x "$dir/$name"
    done
    cat > "$dir/scenario.sh" <<EOF
export PATH="$dir"
export CONTAINER_RUNTIME="$runtime_env"
source "$LIB_PATH"
resolve_container_runtime
echo "RESULT runtime=\${CONTAINER_RT:-} mode=\${CONTAINER_RT_MODE:-}"
EOF
    bash "$dir/scenario.sh" 2>&1
}

echo "Container-runtime resolution scenarios:"

out="$(run_scenario "docker" "docker")"
case "$out" in *"runtime=docker mode=explicit"*) pass "explicit docker + docker available";; *) fail "explicit docker + docker available" "$out";; esac

out="$(run_scenario "podman" "podman")"
case "$out" in *"runtime=podman mode=explicit"*) pass "explicit podman + podman available";; *) fail "explicit podman + podman available" "$out";; esac

out="$(run_scenario "docker" "")"
case "$out" in *"docker is not available"*) pass "explicit docker + CLI missing -> hard error";; *) fail "explicit docker + CLI missing -> hard error" "$out";; esac

out="$(run_scenario "podman" "podman:dead")"
case "$out" in *"podman is not available"*) pass "explicit podman + engine not running -> hard error with podman hint";; *) fail "explicit podman + engine not running -> hard error" "$out";; esac

out="$(run_scenario "nerdctl" "docker")"
case "$out" in *"is invalid"*) pass "explicit invalid value -> hard error";; *) fail "explicit invalid value -> hard error" "$out";; esac

out="$(run_scenario "" "docker" "podman")"
case "$out" in *"runtime=docker mode=auto"*) pass "auto + docker running -> docker";; *) fail "auto + docker running -> docker" "$out";; esac

out="$(run_scenario "" "podman")"
case "$out" in *"runtime=podman mode=auto"*) pass "auto + only podman running -> podman";; *) fail "auto + only podman running -> podman" "$out";; esac

out="$(run_scenario "" "")"
case "$out" in *"runtime= mode=auto"*) pass "auto + neither available -> null runtime";; *) fail "auto + neither available -> null runtime" "$out";; esac

echo "load_dotenv scenarios:"

dotenv_dir="$STUB_ROOT/dotenv"
mkdir -p "$dotenv_dir"
cat > "$dotenv_dir/.env" <<'EOF'
# comment line
CONTAINER_RUNTIME=podman
SANDBOX_SOCKET=/run/user/1000/podman/podman.sock
INVALID LINE WITHOUT EQUALS
EOF

out="$(bash -c "
export CONTAINER_RUNTIME=docker
source '$LIB_PATH'
load_dotenv '$dotenv_dir'
echo RESULT runtime=\$CONTAINER_RUNTIME socket=\${SANDBOX_SOCKET:-}
")"
case "$out" in *"runtime=docker socket=/run/user/1000/podman/podman.sock"*) pass "load_dotenv parses KEY=VALUE, preserves existing env";; *) fail "load_dotenv parsing" "$out";; esac

empty_dir="$STUB_ROOT/noenv"
mkdir -p "$empty_dir"
out="$(bash -c "
source '$LIB_PATH'
load_dotenv '$empty_dir'
echo RESULT ok
")"
case "$out" in *"RESULT ok"*) pass "load_dotenv missing .env is a no-op";; *) fail "load_dotenv missing .env" "$out";; esac

rm -rf "$STUB_ROOT"

echo ""
if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES scenario(s) FAILED"
    exit 1
fi
echo "All scenarios PASSED"
exit 0
```

- [ ] **Step 4.2: 运行确认失败**

Run: `bash e2e/container-runtime-e2e.sh`
Expected: FAIL — 每个场景报 `resolve_container_runtime: command not found`（库不存在）。

- [ ] **Step 4.3: 创建库**

Create `scripts/lib/container-runtime.sh`:

```bash
#!/usr/bin/env bash
# Shared container-runtime resolution for Aria Conductor startup scripts.
# Source from another script: source "$SCRIPT_DIR/lib/container-runtime.sh"

# load_dotenv <project_root>: loads KEY=VALUE pairs from <project_root>/.env
# into the environment. Existing environment variables are never overwritten.
load_dotenv() {
    local project_root="$1"
    local env_file="$project_root/.env"
    local line name value
    [ -f "$env_file" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|'#'*) continue ;;
        esac
        case "$line" in
            *=*) ;;
            *) continue ;;
        esac
        name="${line%%=*}"
        value="${line#*=}"
        case "$name" in
            ''|*[!A-Za-z0-9_]*) continue ;;
        esac
        if [ -z "${!name:-}" ]; then
            export "$name=$value"
        fi
    done < "$env_file"
}

# runtime_cli_ok <runtime>: true when the CLI exists AND `info` succeeds.
runtime_cli_ok() {
    command -v "$1" >/dev/null 2>&1 && "$1" info >/dev/null 2>&1
}

# resolve_container_runtime: sets CONTAINER_RT and CONTAINER_RT_MODE globals.
# Strict mode (CONTAINER_RUNTIME set): invalid value or unavailable CLI is a
# hard error (prints to stderr, returns 1).
# Auto mode: docker first, then podman; CONTAINER_RT empty when neither is usable.
resolve_container_runtime() {
    local explicit="${CONTAINER_RUNTIME:-}"
    if [ -n "$explicit" ]; then
        local rt
        rt="$(printf '%s' "$explicit" | tr '[:upper:]' '[:lower:]')"
        if [ "$rt" != "docker" ] && [ "$rt" != "podman" ]; then
            echo "ERROR: CONTAINER_RUNTIME='$explicit' is invalid. Use 'docker' or 'podman'." >&2
            return 1
        fi
        if ! runtime_cli_ok "$rt"; then
            if [ "$rt" = "podman" ]; then
                echo "ERROR: CONTAINER_RUNTIME=podman is set but podman is not available. Install podman, ensure a machine is running ('podman machine start'), then retry." >&2
            else
                echo "ERROR: CONTAINER_RUNTIME=docker is set but docker is not available. Install/start Docker, or switch CONTAINER_RUNTIME to podman." >&2
            fi
            return 1
        fi
        CONTAINER_RT="$rt"
        CONTAINER_RT_MODE="explicit"
        return 0
    fi
    if runtime_cli_ok docker; then CONTAINER_RT="docker"; CONTAINER_RT_MODE="auto"; return 0; fi
    if runtime_cli_ok podman; then CONTAINER_RT="podman"; CONTAINER_RT_MODE="auto"; return 0; fi
    CONTAINER_RT=""
    CONTAINER_RT_MODE="auto"
    return 0
}
```

- [ ] **Step 4.4: 运行确认通过**

Run: `bash e2e/container-runtime-e2e.sh`
Expected: 10 个场景全部 PASS，退出码 0。

- [ ] **Step 4.5: 提交**

```bash
git add scripts/lib/container-runtime.sh e2e/container-runtime-e2e.sh
git commit -m "feat(scripts): container-runtime resolution lib + bash e2e scenario tests"
```

---

### Task 5: start-backend 脚本接线（ps1 + sh）

**Files:**
- Modify: `scripts/start-backend.ps1`
- Modify: `scripts/start-backend.sh`

- [ ] **Step 5.1: start-backend.ps1 — 引入库 + preflight + opencode 段**

三处修改（使用 SearchReplace，原文来自当前文件）：

(a) 替换：
```powershell
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"
```
为：
```powershell
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"

# Shared container-runtime helpers (Load-DotEnv, Resolve-ContainerRuntime)
. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
Load-DotEnv $ProjectRoot
```

(b) 替换 docker preflight 块（原 `if (Get-Command docker ...)` 块）：
```powershell
if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Docker: running"
    } else {
        Write-Warning "Docker Desktop is not running. Required only for -AdkProvider opencode."
    }
} else {
    Write-Warning "Docker is not installed. Required only for -AdkProvider opencode."
}
```
为：
```powershell
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
```

(c) 替换 OpenSandbox 段（原 `if ($AdkProvider -eq "opencode" -and -not $SkipSandbox) { ... }` 整块，含 `docker ps` 检查与 `docker compose up`）：
```powershell
if ($AdkProvider -eq "opencode" -and -not $SkipSandbox) {
    Write-Host "Checking OpenSandbox server..." -ForegroundColor Cyan
    if ($runtimeError) {
        Write-Error "Container runtime unavailable: $runtimeError"
        exit 1
    }
    $rt = $runtimeInfo.Runtime
    if (-not $rt) {
        Write-Error "Neither docker nor podman is available. The opencode provider requires a container runtime for the OpenSandbox server. Install Docker or podman, or use -SkipSandbox / -AdkProvider langchain."
        exit 1
    }

    $sandboxRunning = & $rt ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>$null
    if (-not $sandboxRunning) {
        Write-Host "Starting OpenSandbox server ($rt compose)..." -ForegroundColor Yellow
        Push-Location $ProjectRoot
        & $rt compose up -d opensandbox-server
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Failed to start OpenSandbox server"
            if ($rt -eq "podman") {
                Write-Host "podman hint: verify the socket is enabled (podman machine ssh 'systemctl --user is-active podman.socket') and SANDBOX_SOCKET in .env matches its VM path." -ForegroundColor Yellow
            }
            Pop-Location; exit 1
        }
        Pop-Location
        Start-Sleep -Seconds 3
    }

    # Default OpenSandbox URL for local dev (host port 8090)
    if (-not $env:OPENCODE_SANDBOX_SERVER_URL) {
        $env:OPENCODE_SANDBOX_SERVER_URL = "http://localhost:8090"
    }
}
```

- [ ] **Step 5.2: start-backend.sh — 引入库 + preflight + opencode 段**

三处修改：

(a) 替换：
```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/agent-control-tower"
```
为：
```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/agent-control-tower"

# Shared container-runtime helpers (load_dotenv, resolve_container_runtime)
# shellcheck source=lib/container-runtime.sh
source "$SCRIPT_DIR/lib/container-runtime.sh"
load_dotenv "$PROJECT_ROOT"
```

(b) 在 `java -version 2>&1 | grep -q "21" || echo "WARNING: ..."` 行之后插入：
```bash
# Container runtime status (required only for the opencode provider)
echo "Container runtimes:"
for rt in docker podman; do
    if command -v "$rt" &> /dev/null; then
        if "$rt" info &> /dev/null; then
            echo "  $rt: running"
        else
            echo "  WARNING: $rt is installed but not running. Required only for the opencode provider."
        fi
    else
        echo "  $rt: not installed"
    fi
done
if resolve_container_runtime; then
    if [ -n "$CONTAINER_RT" ]; then
        if [ "$CONTAINER_RT_MODE" = "explicit" ]; then
            echo "  selected: $CONTAINER_RT (explicit: CONTAINER_RUNTIME)"
        else
            echo "  selected: $CONTAINER_RT (auto-detected)"
        fi
    else
        echo "  WARNING: No container runtime available. Required only for the opencode provider."
    fi
fi
```

(c) 替换：
```bash
# ── OpenSandbox server (required for opencode provider) ──
if [ "$ADK_PROVIDER" = "opencode" ] && [ "$SKIP_SANDBOX" != "true" ]; then
    echo "Checking OpenSandbox server..."
    if ! docker ps &>/dev/null; then
        echo "ERROR: Docker is not running. Start Docker Desktop first, or use --skip-sandbox."
        exit 1
    fi

    if ! docker ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>/dev/null | grep -q "aria-opensandbox"; then
        echo "Starting OpenSandbox server (docker compose)..."
        (cd "$PROJECT_ROOT" && docker compose up -d opensandbox-server)
        sleep 3
    fi

    # Default OpenSandbox URL for local dev (host port 8090)
    export OPENCODE_SANDBOX_SERVER_URL="${OPENCODE_SANDBOX_SERVER_URL:-http://localhost:8090}"
fi
```
为：
```bash
# ── OpenSandbox server (required for opencode provider) ──
if [ "$ADK_PROVIDER" = "opencode" ] && [ "$SKIP_SANDBOX" != "true" ]; then
    echo "Checking OpenSandbox server..."
    if ! resolve_container_runtime; then
        exit 1
    fi
    if [ -z "$CONTAINER_RT" ]; then
        echo "ERROR: Neither docker nor podman is available. The opencode provider requires a container runtime for the OpenSandbox server. Install Docker or podman, or use --skip-sandbox / ADK_PROVIDER=langchain."
        exit 1
    fi

    if ! "$CONTAINER_RT" ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>/dev/null | grep -q "aria-opensandbox"; then
        echo "Starting OpenSandbox server ($CONTAINER_RT compose)..."
        if ! (cd "$PROJECT_ROOT" && "$CONTAINER_RT" compose up -d opensandbox-server); then
            echo "ERROR: Failed to start OpenSandbox server" >&2
            if [ "$CONTAINER_RT" = "podman" ]; then
                echo "podman hint: verify the socket is enabled (podman machine ssh 'systemctl --user is-active podman.socket') and SANDBOX_SOCKET in .env matches its VM path." >&2
            fi
            exit 1
        fi
        sleep 3
    fi

    # Default OpenSandbox URL for local dev (host port 8090)
    export OPENCODE_SANDBOX_SERVER_URL="${OPENCODE_SANDBOX_SERVER_URL:-http://localhost:8090}"
fi
```

- [ ] **Step 5.3: 语法检查**

Run:
```powershell
pwsh -NoProfile -Command "$null = [scriptblock]::Create((Get-Content -Raw 'scripts/start-backend.ps1')); 'SYNTAX OK'"
bash -n scripts/start-backend.sh && echo "SYNTAX OK"
```
Expected: 两行 `SYNTAX OK`（bash -n 无输出且退出码 0）。

- [ ] **Step 5.4: 冒烟运行（preflight 路径，功能全量验证在 Task 9）**

Run（观察 preflight 输出后 Ctrl+C 停止）:
```powershell
pwsh -NoProfile -File scripts/start-backend.ps1 -SkipBuild -SkipSandbox
```
Expected: preflight 打印 `docker : running|not installed`、`podman : ...` 及 `Container runtime: <rt> (<reason>)` 行；后端正常启动后 Ctrl+C 停止。（langchain + SkipSandbox 不触发容器依赖，安全。）

- [ ] **Step 5.5: 提交**

```bash
git add scripts/start-backend.ps1 scripts/start-backend.sh
git commit -m "feat(scripts): runtime-aware OpenSandbox startup in start-backend"
```

---

### Task 6: quickstart 脚本接线（ps1 + sh）

**Files:**
- Modify: `scripts/quickstart.ps1`
- Modify: `scripts/quickstart.sh`

- [ ] **Step 6.1: quickstart.ps1 — 运行时解析（顺带修复 ProjectRoot 双重 Parent 缺陷）**

三处修改：

(a) 替换：
```powershell
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# Check Docker
$dockerAvailable = $false
try {
    $null = Get-Command docker -ErrorAction Stop
    $null = docker compose version 2>&1
    $dockerAvailable = $true
} catch {}

if ($dockerAvailable) {
    Write-Host "Docker detected. Starting with Docker Compose..." -ForegroundColor Green
```
为：
```powershell
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# Shared container-runtime helpers (Load-DotEnv, Resolve-ContainerRuntime)
. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
Load-DotEnv $ProjectRoot

# Resolve container runtime (docker | podman; strict when CONTAINER_RUNTIME is set)
$rt = $null
$rtLabel = ""
try {
    $runtimeInfo = Resolve-ContainerRuntime
    $rt = $runtimeInfo.Runtime
    $rtLabel = if ($runtimeInfo.Mode -eq "explicit") { "configured via CONTAINER_RUNTIME" } else { "auto-detected" }
} catch {
    Write-Error $_.Exception.Message
    exit 1
}

if ($rt) {
    Write-Host "Container runtime: $rt ($rtLabel). Starting with Compose..." -ForegroundColor Green
```

(b) 替换 compose 调用与命令提示行：
```powershell
    docker compose up -d --build
```
为：
```powershell
    & $rt compose up -d --build
```
以及：
```powershell
    Write-Host "Commands:" -ForegroundColor Cyan
    Write-Host "  docker compose ps              # Check status"
    Write-Host "  docker compose logs -f         # View logs"
    Write-Host "  docker compose down            # Stop"
```
为：
```powershell
    Write-Host "Commands:" -ForegroundColor Cyan
    Write-Host "  $rt compose ps              # Check status"
    Write-Host "  $rt compose logs -f         # View logs"
    Write-Host "  $rt compose down            # Stop"
```

(c) 替换回退分支文案：
```powershell
} else {
    Write-Host "Docker not found. Falling back to local development mode." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "NOTE: OpenCode sandbox mode requires Docker for the OpenSandbox server." -ForegroundColor Red
    Write-Host "      Without Docker, only langchain ADK provider is available." -ForegroundColor Red
```
为：
```powershell
} else {
    Write-Host "Neither docker nor podman detected. Falling back to local development mode." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "NOTE: OpenCode sandbox mode requires Docker or podman for the OpenSandbox server." -ForegroundColor Red
    Write-Host "      Without a container runtime, only langchain ADK provider is available." -ForegroundColor Red
```
以及末尾：
```powershell
    Write-Host "To use opencode provider, install Docker and re-run this script." -ForegroundColor Cyan
```
为：
```powershell
    Write-Host "To use opencode provider, install Docker or podman and re-run this script." -ForegroundColor Cyan
```
还有回退分支内的注释行：
```powershell
    # No-Docker path: default ADK provider is langchain (opencode requires Docker)
    $adkProvider = "langchain"
    Write-Host "Using ADK provider: $adkProvider (Docker required for opencode)" -ForegroundColor Yellow
```
为：
```powershell
    # No-container-runtime path: default ADK provider is langchain (opencode requires docker/podman)
    $adkProvider = "langchain"
    Write-Host "Using ADK provider: $adkProvider (container runtime required for opencode)" -ForegroundColor Yellow
```

- [ ] **Step 6.2: quickstart.sh — 运行时解析**

三处修改：

(a) 替换脚本头（docker 检测与 SCRIPT_DIR 位置调整）：
```bash
# Check Docker
if command -v docker &> /dev/null && docker compose version &> /dev/null 2>&1; then
    echo "Docker detected. Starting with Docker Compose..."
    echo ""

    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
    cd "$PROJECT_ROOT"
```
为：
```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Shared container-runtime helpers (load_dotenv, resolve_container_runtime)
# shellcheck source=lib/container-runtime.sh
source "$SCRIPT_DIR/lib/container-runtime.sh"
load_dotenv "$PROJECT_ROOT"

# Resolve container runtime (docker | podman; strict when CONTAINER_RUNTIME is set)
if ! resolve_container_runtime; then
    exit 1
fi

if [ -n "$CONTAINER_RT" ]; then
    if [ "$CONTAINER_RT_MODE" = "explicit" ]; then
        echo "Container runtime: $CONTAINER_RT (configured via CONTAINER_RUNTIME). Starting with Compose..."
    else
        echo "Container runtime: $CONTAINER_RT (auto-detected). Starting with Compose..."
    fi
    echo ""

    cd "$PROJECT_ROOT"
```

(b) 替换 compose 调用与提示：
```bash
    docker compose up -d --build
```
为：
```bash
    "$CONTAINER_RT" compose up -d --build
```
以及：
```bash
    echo "Check status: docker compose ps"
    echo "View logs:    docker compose logs -f"
    echo "Stop:         docker compose down"
```
为：
```bash
    echo "Check status: $CONTAINER_RT compose ps"
    echo "View logs:    $CONTAINER_RT compose logs -f"
    echo "Stop:         $CONTAINER_RT compose down"
```

(c) 替换回退分支文案：
```bash
else
    echo "Docker not found. Falling back to local development mode."
    echo ""
    echo "NOTE: OpenCode sandbox mode requires Docker for the OpenSandbox server."
    echo "      Without Docker, only langchain ADK provider is available."
```
为：
```bash
else
    echo "Neither docker nor podman detected. Falling back to local development mode."
    echo ""
    echo "NOTE: OpenCode sandbox mode requires Docker or podman for the OpenSandbox server."
    echo "      Without a container runtime, only langchain ADK provider is available."
```
以及回退分支内：
```bash
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

    echo "Starting backend (langchain provider, Docker required for opencode)..."
```
为：
```bash
    echo "Starting backend (langchain provider, container runtime required for opencode)..."
```
以及末尾：
```bash
    echo "To use opencode provider, install Docker and re-run this script."
```
为：
```bash
    echo "To use opencode provider, install Docker or podman and re-run this script."
```

- [ ] **Step 6.3: 语法检查**

Run:
```powershell
pwsh -NoProfile -Command "$null = [scriptblock]::Create((Get-Content -Raw 'scripts/quickstart.ps1')); 'SYNTAX OK'"
bash -n scripts/quickstart.sh && echo "SYNTAX OK"
```
Expected: 两行 `SYNTAX OK`。（功能全量验证在 Task 9 Spike B。）

- [ ] **Step 6.4: 提交**

```bash
git add scripts/quickstart.ps1 scripts/quickstart.sh
git commit -m "feat(scripts): runtime-aware quickstart (docker|podman) + ProjectRoot fix"
```

---

### Task 7: docker-compose.yml socket 参数化 + .env.example

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`

- [ ] **Step 7.1: compose socket 挂载参数化**

替换 `opensandbox-server` 的 volumes 块：
```yaml
    volumes:
      # Docker runtime mode: the server creates/kills sandbox containers via the Docker socket
      - /var/run/docker.sock:/var/run/docker.sock
```
为：
```yaml
    volumes:
      # Container-runtime socket used to create/kill sandbox containers. Defaults to
      # Docker; set SANDBOX_SOCKET for podman (rootless: /run/user/1000/podman/podman.sock,
      # rootful: /run/podman/podman.sock).
      - ${SANDBOX_SOCKET:-/var/run/docker.sock}:/var/run/docker.sock
```

- [ ] **Step 7.2: .env.example 新增配置项**

在 `# OPENSANDBOX_PORT=8090` 行之后、`# --- Tool Configuration` 之前插入：
```
# --- Container Runtime (optional) ---
# docker | podman. Empty = auto-detect (docker preferred, podman fallback).
# CONTAINER_RUNTIME=podman
# Host socket mounted into the OpenSandbox server for sandbox creation.
# podman rootless: /run/user/1000/podman/podman.sock ; podman rootful: /run/podman/podman.sock
# SANDBOX_SOCKET=
```

- [ ] **Step 7.3: 验证 compose 配置可解析**

Run:
```powershell
podman compose config --quiet
```
（若本机无 podman 则用 `docker compose config --quiet`。两者都没有时跳过，Task 9 必验。）
Expected: 退出码 0、无输出。再验证默认值生效：
```powershell
podman compose config | Select-String "docker.sock"
```
Expected: 显示 `${SANDBOX_SOCKET:-/var/run/docker.sock}` 解析为 `/var/run/docker.sock`（默认路径不变）。

- [ ] **Step 7.4: 提交**

```bash
git add docker-compose.yml .env.example
git commit -m "feat(compose): parametrize OpenSandbox socket mount for podman"
```

---

### Task 8: 文档更新（README / AGENTS.md / opencode-sandbox README）

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `agent-control-tower/opencode-sandbox/README.md`

- [ ] **Step 8.1: README.md — 六处修改**

(a) Tech Stack 表：
`| Containerization | Docker, Docker Compose |` → `| Containerization | Docker / Podman, Docker Compose / Podman Compose |`

(b) Quick Start 前置条件：
`- Docker and Docker Compose v2` → `- Docker (or podman) and Docker Compose v2 (or podman compose)`

(c) 新增 "Container Runtime Selection" 章节 — 将 `## Development Setup\n\nFor local development without Docker:` 替换为：
```markdown
## Container Runtime Selection

Startup scripts and the OpenSandbox server support **Docker** (default) and **podman**.

- If `CONTAINER_RUNTIME` is unset, scripts auto-detect: docker (running) → podman (running).
- Set `CONTAINER_RUNTIME=docker|podman` in `.env` to force one runtime (strict: hard error when unavailable).
- The OpenSandbox server mounts the container socket from `SANDBOX_SOCKET` (default `/var/run/docker.sock`).

### podman machine (Windows)

1. `podman machine init` then `podman machine start` (Podman Desktop users: start from the app).
2. Rootless (recommended): the user socket service is enabled by default inside the VM. Verify:
   `podman machine ssh "ls -l /run/user/1000/podman/podman.sock"`
   If missing: `podman machine ssh "systemctl --user enable --now podman.socket"`
3. Rootful: `podman machine set --rootful`, then `podman machine ssh "sudo systemctl enable --now podman.socket"`.
4. In `.env` set:
   ```
   CONTAINER_RUNTIME=podman
   SANDBOX_SOCKET=/run/user/1000/podman/podman.sock   # rootless (or /run/podman/podman.sock for rootful)
   ```
5. Build the sandbox image into podman's store:
   `podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox`
6. Start as usual (`docker compose` commands become `podman compose ...`):
   `podman compose up -d` or `./scripts/quickstart.sh`

> Note: OpenSandbox has no native podman runtime; podman is served through its Docker-compatible socket. Sandbox support under podman is validated by the project's E2E suite (see `e2e/container-runtime-e2e.ps1`).

## Development Setup

For local development without Docker:
```

(d) Dev prerequisites 表：
`| Docker | 24+ | `docker --version` (required for opencode provider) |` → `| Docker / Podman | 24+ / 4.9+ | `docker --version` or `podman --version` (required for opencode provider) |`

(e) OpenSandbox 段 — 在
```bash
docker compose up -d opensandbox-server
```
后追加：
```bash
# or with podman:
# podman compose up -d opensandbox-server
```
并在
```bash
docker build -t aria-conductor/opencode-sandbox:1.0 agent-control-tower/opencode-sandbox
```
后追加：
```bash
# or: podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox
```

(f) Environment Variables 表末尾（`| `DB_NAME` | `aria_conductor` | Database name |` 后）追加两行：
```markdown
| `CONTAINER_RUNTIME` | auto-detect | Container runtime: `docker` or `podman` (auto-detect: docker preferred) |
| `SANDBOX_SOCKET` | `/var/run/docker.sock` | Host container-engine socket mounted into the OpenSandbox server |
```

- [ ] **Step 8.2: AGENTS.md — 四处修改**

(a) Module 表 opencode-sandbox 行：`Docker image for OpenCode sandbox` → `Container image (docker/podman) for OpenCode sandbox`

(b) "Run full-stack locally" 第 1 步：
`1. OpenSandbox: `docker compose up -d opensandbox-server` (required for opencode provider)`
→
`1. OpenSandbox: `docker compose up -d opensandbox-server` (podman: `podman compose up -d opensandbox-server`; set `SANDBOX_SOCKET` in .env; required for opencode provider)`

(c) High-Risk Areas 表末尾追加：
```markdown
| Container runtime selection (`scripts/lib/container-runtime.*`) | podman socket/config mismatch blocks opencode sandbox | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |
```

(d) Validation Command Mapping 表末尾追加：
```markdown
| Container runtime scenario tests | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |
```

- [ ] **Step 8.3: opencode-sandbox/README.md — podman 变体**

Build 段：
```bash
docker build -t aria-conductor/opencode-sandbox:1.1 .
```
→
```bash
docker build -t aria-conductor/opencode-sandbox:1.1 .
# or with podman:
podman build -t aria-conductor/opencode-sandbox:1.1 .
```
Smoke test 段首行后追加 podman 变体：
```bash
docker run --rm aria-conductor/opencode-sandbox:1.1
# or: podman run --rm aria-conductor/opencode-sandbox:1.1
```

- [ ] **Step 8.4: 提交**

```bash
git add README.md AGENTS.md agent-control-tower/opencode-sandbox/README.md
git commit -m "docs: podman container runtime selection guide"
```

---

### Task 9: 验证 Spike B — podman 全链路真实 E2E + Playwright 回归 + 证据归档

**Files:**
- Create (证据): `report-podman-e2e.md`、`e2e/screenshots/podman/`

**前置:** Task 1 已完成且 gate 通过；`.env` 已配置 `LLM_API_KEY` / `DEEPSEEK_API_KEY`。

- [ ] **Step 9.1: Phase B1 — quickstart 全栈（自动探测路径）**

在 `.env` 中临时注释 `CONTAINER_RUNTIME`（验证 auto-detect），保留 `SANDBOX_SOCKET=/run/user/1000/podman/podman.sock`（Task 1 实测路径）。

Run: `.\scripts\quickstart.ps1`
Expected: 输出 `Container runtime: podman (auto-detected)...`；随后：
```powershell
podman ps
curl -s http://localhost:8090/health      # {"status":"healthy"}
curl -s http://localhost:8080/actuator/health   # UP
```
全绿后 `podman compose down`。

- [ ] **Step 9.2: Phase B2 — local-dev 拓扑 + 真实 LLM opencode E2E（显式配置路径）**

恢复 `.env` 中 `CONTAINER_RUNTIME=podman`（显式严格模式）。启动后端与前端：
```powershell
Start-Process pwsh -ArgumentList "-NoProfile","-File","scripts\start-backend.ps1","-AdkProvider","opencode" -NoNewWindow
Start-Process pwsh -ArgumentList "-NoProfile","-File","scripts\start-frontend.ps1" -NoNewWindow
```
Expected: preflight 输出 `Container runtime: podman (explicit: CONTAINER_RUNTIME)`；opensandbox-server 经 podman compose 自动拉起；`http://localhost:8080/api/v1/adk/providers/opencode/health` 返回健康。

创建真实 LLM DB provider（按项目惯例，优先 DB LlmProvider）：
```powershell
$body = '{"name":"deepseek-qa","type":"OPENAI","apiKey":"<DEEPSEEK_API_KEY>","baseUrl":"https://api.deepseek.com/v1","defaultModel":"deepseek-chat","enabled":true}'
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/llm-providers" -ContentType "application/json" -Body $body
```
Expected: 201/200 且 provider 在 Settings 页可见、激活。

- [ ] **Step 9.3: 运行 Playwright opencode 套件（podman 栈）**

Run: `cd agent-control-tower/act-dashboard && npx playwright test opencode-adk-e2e.spec.ts`
Expected: 全部通过（沙箱创建→agent 运行→完成全链路在 podman 下工作）。截图自动落在 `test-results/`；另手动截取关键页面存至 `e2e/screenshots/podman/`（dashboard、runs 完成态）。
（可选加跑 `real-llm-scenarios.spec.ts`；时间/token 预算允许时执行，作为 spec §7.2 的完整证据。）

- [ ] **Step 9.4: 证据归档**

Create `report-podman-e2e.md`（遵循项目 `report-*.md` 惯例），内容包含：
- 环境（podman 版本、machine 配置、rootless/rootful、socket 路径）
- Spike A 结果（health、沙箱 create/delete、40000-60000 端口转发实测）
- Spike B 结果（auto-detect 与 explicit 两路径、Playwright 套件通过明细）
- 遗留限制与发现
把截图复制到 `e2e/screenshots/podman/`，提交：
```bash
git add report-podman-e2e.md e2e/screenshots/podman/
git commit -m "docs(podman): spike B evidence - podman full-chain E2E"
```

- [ ] **Step 9.5: 清理**

停止后端/前端进程；`podman compose down`；确认 `podman ps` 无沙箱残留。

---

### Task 10: docker 路径回归 + 推送 + CI 全绿

**Files:** 无（验证任务）

- [ ] **Step 10.1: 场景测试复跑（docker 基线行为不变）**

Run:
```powershell
pwsh -NoProfile -File e2e/container-runtime-e2e.ps1
bash e2e/container-runtime-e2e.sh
```
Expected: 两个 harness 全部 PASS（其中 "auto + docker running → docker" 即 docker 默认优先的回归断言）。

- [ ] **Step 10.2: 全量 Java 测试**

Run: `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2`
Expected: BUILD SUCCESS（SandboxRunner 既有测试 + 新增测试全绿）。

- [ ] **Step 10.3: 推送分支并创建 PR**

```bash
git push -u origin feat/podman-support
```
然后按项目 PR 惯例创建（title 建议 `feat: podman container runtime support`，body 引用 spec 与 spike 证据）。

- [ ] **Step 10.4: CI 监控至全绿**

按项目惯例轮询（`gh pr checks` / `gh run watch`）直至 `.github/workflows/ci.yml` 的必需 job（java-tests、frontend-build、e2e-workflow-governance 等）全部 green；记录最终 green run id/URL 作为验证证据。
Expected: CI 全绿（CI 保持 docker 路径，验证默认行为零回归）。

---

## Self-Review Notes

- Spec §3 配置模型 → Task 3/4（解析库）、Task 2（Java）、Task 7（.env.example）；§4 脚本 → Task 5/6；§5 compose/Java → Task 7/2；§6 错误矩阵 → 解析库 throw/return 语义 + Task 5 硬错误接线；§7 E2E → Task 3/4 harness + Task 9 Playwright 复用；§8 spike → Task 1（门禁）/Task 9（全链）；§9 文档 → Task 8；§10 验证 → Task 10。
- 占位符检查：无 TBD/TODO；所有代码块为完整内容。
- 类型一致性：PS 库 `Resolve-ContainerRuntime` 返回 `@{Runtime;Mode}` ↔ harness 断言 `runtime=<v> mode=<v>` ↔ Task 5/6 消费 `$runtimeInfo.Runtime/.Mode`；bash 库全局 `CONTAINER_RT`/`CONTAINER_RT_MODE` ↔ harness ↔ Task 5/6 消费同名变量；Java `resolveRuntime(String,boolean,boolean)` 签名在 Task 2 测试与实现一致。

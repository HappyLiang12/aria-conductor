# Task 5 报告：start-backend 脚本接线（ps1 + sh）

- 分支：`feat/podman-support`
- 日期：2026-08-17
- 提交：`dd568ce`（feat(scripts): runtime-aware OpenSandbox startup in start-backend）
- 报告提交：`（见文末）`

## 目标

将 Task 3/4 交付的共享容器运行时库（`scripts/lib/container-runtime.ps1` / `container-runtime.sh`）接入两个后端启动脚本，使 OpenSandbox 检查与启动支持 docker / podman 双运行时，并输出容器运行时状态 preflight 信息。

## 修改总览

| 文件 | 修改数 | 说明 |
|------|--------|------|
| `scripts/start-backend.ps1` | 3 | (a) 引入 lib + Load-DotEnv；(b) docker preflight → docker/podman 循环 + Resolve-ContainerRuntime；(c) OpenSandbox 段 runtime-aware 化（含 podman 失败提示） |
| `scripts/start-backend.sh` | 3 | (a) source lib + load_dotenv；(b) 插入容器 runtime 状态输出；(c) OpenSandbox 段 runtime-aware 化（含 podman 失败提示） |

diff 统计：2 files changed, **90 insertions(+), 19 deletions(-)**。

---

## 1. start-backend.ps1 修改 (a)：引入共享库

**修改前：**

```powershell
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"

# Prerequisites check
```

**修改后：**

```powershell
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"

# Shared container-runtime helpers (Load-DotEnv, Resolve-ContainerRuntime)
. (Join-Path $PSScriptRoot "lib/container-runtime.ps1")
Load-DotEnv $ProjectRoot

# Prerequisites check
```

- 点源加载 `lib/container-runtime.ps1`，注册 `Load-DotEnv` / `Test-RuntimeCli` / `Resolve-ContainerRuntime`。
- 启动即调用 `Load-DotEnv $ProjectRoot`：加载项目根 `.env`（若存在）到进程环境，不覆盖已有变量；`.env` 缺失时安全跳过（本 worktree 无 `.env`，验证无报错）。

## 2. start-backend.ps1 修改 (b)：docker preflight → 双运行时状态 + Resolve-ContainerRuntime

**修改前（单 docker 检查）：**

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

**修改后（docker + podman 循环 + 运行时解析）：**

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

- 对 docker / podman 分别输出安装与运行状态（未安装用暗灰色提示，已安装未运行用 WARNING）。
- 调用 `Resolve-ContainerRuntime` 解析选定运行时：严格模式下（`CONTAINER_RUNTIME` 显式设置且不可用）捕获其 throw 存入 `$runtimeError` 并输出 WARNING，不中断启动；自动模式输出所选运行时与原因（explicit / auto-detected）。

## 3. start-backend.ps1 修改 (c)：OpenSandbox 段 runtime-aware 化

**修改前（硬编码 docker）：**

```powershell
if ($AdkProvider -eq "opencode" -and -not $SkipSandbox) {
    Write-Host "Checking OpenSandbox server..." -ForegroundColor Cyan
    try {
        $null = docker ps 2>&1
    } catch {
        Write-Error "Docker is not running. Start Docker Desktop first, or use -SkipSandbox."
        exit 1
    }

    $sandboxRunning = docker ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>$null
    if (-not $sandboxRunning) {
        Write-Host "Starting OpenSandbox server (docker compose)..." -ForegroundColor Yellow
        Push-Location $ProjectRoot
        docker compose up -d opensandbox-server
        if ($LASTEXITCODE -ne 0) { Write-Error "Failed to start OpenSandbox server"; Pop-Location; exit 1 }
        Pop-Location
        Start-Sleep -Seconds 3
    }

    # Default OpenSandbox URL for local dev (host port 8090)
    if (-not $env:OPENCODE_SANDBOX_SERVER_URL) {
        $env:OPENCODE_SANDBOX_SERVER_URL = "http://localhost:8090"
    }
}
```

**修改后（复用 preflight 解析结果，运行时无关）：**

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

- `$rt` 来自 preflight 的 `Resolve-ContainerRuntime` 结果，`ps` / `compose` 均以解析出的运行时执行。
- podman 启动失败时追加专项提示（socket 状态检查 + SANDBOX_SOCKET 与 VM 路径匹配）。
- 与计划原文差异：`docker ps --format` 参数在计划原文写为 `.Names`，实际文件为 `{{.Names}}`（docker CLI 格式占位符），以实际文件为准保留 `{{.Names}}`。

## 4. start-backend.sh 修改 (a)：引入共享库

**修改前：**

```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/agent-control-tower"
```

**修改后：**

```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/agent-control-tower"

# Shared container-runtime helpers (load_dotenv, resolve_container_runtime)
# shellcheck source=lib/container-runtime.sh
source "$SCRIPT_DIR/lib/container-runtime.sh"
load_dotenv "$PROJECT_ROOT"
```

- `source` 加载 `lib/container-runtime.sh`（注册 `load_dotenv` / `runtime_cli_ok` / `resolve_container_runtime`），并调用 `load_dotenv` 加载项目根 `.env`。

## 5. start-backend.sh 修改 (b)：插入容器 runtime 状态输出

在 `java -version 2>&1 | grep -q "21" || echo "WARNING: JDK 21 recommended..."` 之后插入：

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

- 与 ps1 版对称：输出 docker / podman 状态，`resolve_container_runtime` 设置 `CONTAINER_RT` / `CONTAINER_RT_MODE` 全局变量供后续 OpenSandbox 段复用。

## 6. start-backend.sh 修改 (c)：OpenSandbox 段 runtime-aware 化

**修改前（硬编码 docker）：**

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

**修改后（复用 CONTAINER_RT，运行时无关）：**

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

- 与 ps1 版对称：`resolve_container_runtime` 失败（严格模式）或 `CONTAINER_RT` 为空时给出明确错误并退出；`compose up` 失败时输出 podman 专项提示。

---

## 与计划原文的差异说明

1. **`--format` 参数**：ps1 第 70 行 / sh 第 45 行实际文件为 `--format "{{.Names}}"`，计划原文写 `.Names`。docker/podman CLI 的格式占位符语法要求 `{{.Names}}`，以实际文件为准（等价语义，仅补全占位符写法）。
2. **行尾（CRLF→LF）**：worktree 检出时（`core.autocrlf=true`）`start-backend.ps1` / `start-backend.sh` 为 CRLF，导致 `bash -n` 报 `syntax error near unexpected token $'in\r'`。两个脚本与 `lib/container-runtime.sh`（LF）统一为 LF 后语法检查通过；git index 中均为 LF，diff 无行尾噪音，提交后 blob 保持 LF（warning 中 "LF will be replaced by CRLF" 仅为 autocrlf 提示，不影响内容）。ps1 在 pwsh 下 CRLF/LF 均可执行，行尾统一不影响 Windows 使用。
3. **工作区无 `.env`**（gitignored，仅存在于 d:/project/aria-conductor 主工作区）：`Load-DotEnv` / `load_dotenv` 在 `.env` 缺失时安全跳过（两库均为首行守卫），冒烟运行验证无报错，preflight 显示 `auto-detected`（无 `CONTAINER_RUNTIME` 显式设置）。

---

## Step 5.3 语法检查输出

```text
$ bash -n scripts/start-backend.sh && echo "SYNTAX OK"
SH SYNTAX OK

$ pwsh -NoProfile -Command '$null = [scriptblock]::Create((Get-Content -Raw ''scripts/start-backend.ps1'')); ''PS1 SYNTAX OK'''
PS1 SYNTAX OK
```

两行均通过（行尾统一为 LF 后执行；转换前 CRLF 版本 bash -n 报错，见差异说明 2）。

## Step 5.4 冒烟运行输出（preflight 路径）

命令：`pwsh -NoProfile -File scripts/start-backend.ps1 -SkipBuild -SkipSandbox`
（`$env:Path` 预先追加 `D:\tools\apache-maven-3.9.16\bin`，本机 mvn 不在 PATH）

```text
Preflight:
  Java  : openjdk version "21.0.11" 2026-04-21 LTS
  Maven : Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
WARNING: docker is installed but not running. Required only for -AdkProvider opencode.
  podman : running
  Container runtime: podman (auto-detected)
WARNING: DEEPSEEK_API_KEY is not set; real LLM calls will fail.
WARNING: GH_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox.
Starting Aria Conductor backend...
  Profile: h2
  ADK Provider: langchain
  Port: 8080
Launching Spring Boot...
```

- 验证点全部命中：`docker ...`（本机 Docker Desktop 已退出 → `installed but not running` WARNING）、`podman : running`（podman machine 在运行）、`Container runtime: podman (auto-detected)`。
- 后端完整启动：Flyway 46 个迁移应用成功、`ADK providers registered: [langchain, opencode]`、LangChain ADK server 在 9300 端口启动且 health check 通过（`200 OK`）、Aria default agent 初始化（43 orchestration tools）。
- 停止方式：`taskkill /PID <java pid> /T /F` 终止 Spring Boot 进程树，mvn 随之以 BUILD FAILURE（Process terminated，预期）退出，脚本返回提示符。`-SkipSandbox` + langchain 未触发任何容器依赖，符合预期。

---

## 提交

```text
dd568ce feat(scripts): runtime-aware OpenSandbox startup in start-backend
 2 files changed, 90 insertions(+), 19 deletions(-)
 Pre-commit Guardrail: [1/3] Format ✓ [2/3] TS skip ✓ [3/3] Sensitive scan ✓ ALL CHECKS PASSED
```

报告提交：`docs(podman): task5 report`（hash 见 `git log --oneline -2`，本文件提交自身无法自引用）

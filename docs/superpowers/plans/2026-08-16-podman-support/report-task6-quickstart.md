# Task 6 报告：quickstart 脚本接线（ps1 + sh）

- 分支：`feat/podman-support`
- 日期：2026-08-17
- 代码提交：`1afd76e`（feat(scripts): runtime-aware quickstart (docker|podman) + ProjectRoot fix）
- 报告提交：`（见文末）`

## 目标

将 Task 3/4 交付的共享容器运行时库（`scripts/lib/container-runtime.ps1` / `container-runtime.sh`）接入 `scripts/quickstart.ps1` 与 `scripts/quickstart.sh`，使快速启动脚本支持 docker / podman 双运行时（compose 分支），并在无容器运行时场景下正确回退本地开发模式。同时顺带修复 quickstart.ps1 第 9 行的 ProjectRoot 双重 Parent 缺陷。

## 修改总览

| 文件 | 修改数 | 说明 |
|------|--------|------|
| `scripts/quickstart.ps1` | 6 | (a) ProjectRoot 修复 + 引入 lib + Load-DotEnv + Resolve-ContainerRuntime；(b) compose 调用与命令提示 runtime-aware；(c) 回退分支文案 runtime-aware |
| `scripts/quickstart.sh` | 6 | (a) SCRIPT_DIR/PROJECT_ROOT 提前 + source lib + load_dotenv + resolve_container_runtime；(b) compose 调用与提示 runtime-aware；(c) 回退分支文案 runtime-aware |

diff 统计：2 files changed, **55 insertions(+), 35 deletions(-)**。

---

## 1. quickstart.ps1 修改 (a)：ProjectRoot 修复 + 引入共享库

**修改前：**

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

**修改后：**

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

- **ProjectRoot 缺陷修复**：原 `Split-Path -Parent (Split-Path -Parent $PSScriptRoot)` 双重 Parent 得到仓库父目录（`scripts` 上两级），现改为单层 `Split-Path -Parent $PSScriptRoot` 得到项目根。该缺陷此前会使 `Set-Location $ProjectRoot` 与 `.env` 检查作用在错误目录。
- 点源 `lib/container-runtime.ps1`，注册 `Load-DotEnv` / `Resolve-ContainerRuntime`；`Load-DotEnv $ProjectRoot` 加载项目根 `.env`（若存在），不覆盖已有环境变量。
- `Resolve-ContainerRuntime` 严格模式（`CONTAINER_RUNTIME` 显式设置且无效/不可用）时 `throw`，此处 `catch` 后 `Write-Error` + `exit 1`（硬失败）；auto 模式无运行时则 `$rt = $null` 走回退分支。`$rt` 真值判断替代原 `$dockerAvailable`。

## 2. quickstart.ps1 修改 (b)：compose 调用与命令提示 runtime-aware

**compose 调用：**

```powershell
# 修改前
    docker compose up -d --build
# 修改后
    & $rt compose up -d --build
```

**命令提示：**

```powershell
# 修改前
    Write-Host "Commands:" -ForegroundColor Cyan
    Write-Host "  docker compose ps              # Check status"
    Write-Host "  docker compose logs -f         # View logs"
    Write-Host "  docker compose down            # Stop"
# 修改后
    Write-Host "Commands:" -ForegroundColor Cyan
    Write-Host "  $rt compose ps              # Check status"
    Write-Host "  $rt compose logs -f         # View logs"
    Write-Host "  $rt compose down            # Stop"
```

- `& $rt compose ...` 以解析后的运行时（docker 或 podman）执行 compose；提示行使用同一变量，与 Task 5 start-backend 的 `& $rt compose` 模式一致。

## 3. quickstart.ps1 修改 (c)：回退分支文案 runtime-aware

**回退分支开头：**

```powershell
# 修改前
} else {
    Write-Host "Docker not found. Falling back to local development mode." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "NOTE: OpenCode sandbox mode requires Docker for the OpenSandbox server." -ForegroundColor Red
    Write-Host "      Without Docker, only langchain ADK provider is available." -ForegroundColor Red
# 修改后
} else {
    Write-Host "Neither docker nor podman detected. Falling back to local development mode." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "NOTE: OpenCode sandbox mode requires Docker or podman for the OpenSandbox server." -ForegroundColor Red
    Write-Host "      Without a container runtime, only langchain ADK provider is available." -ForegroundColor Red
```

**注释与 provider 提示：**

```powershell
# 修改前
    # No-Docker path: default ADK provider is langchain (opencode requires Docker)
    $adkProvider = "langchain"
    Write-Host "Using ADK provider: $adkProvider (Docker required for opencode)" -ForegroundColor Yellow
# 修改后
    # No-container-runtime path: default ADK provider is langchain (opencode requires docker/podman)
    $adkProvider = "langchain"
    Write-Host "Using ADK provider: $adkProvider (container runtime required for opencode)" -ForegroundColor Yellow
```

**末尾提示：**

```powershell
# 修改前
    Write-Host "To use opencode provider, install Docker and re-run this script." -ForegroundColor Cyan
# 修改后
    Write-Host "To use opencode provider, install Docker or podman and re-run this script." -ForegroundColor Cyan
```

## 4. quickstart.sh 修改 (a)：脚本头调整 + 引入共享库

**修改前：**

```bash
# Check Docker
if command -v docker &> /dev/null && docker compose version &> /dev/null 2>&1; then
    echo "Docker detected. Starting with Docker Compose..."
    echo ""

    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
    cd "$PROJECT_ROOT"
```

**修改后：**

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

- `SCRIPT_DIR` / `PROJECT_ROOT` 提前到脚本开头计算（`set -euo pipefail` 之后），供整个脚本（含 else 分支）使用——原 else 分支内重复定义的 `SCRIPT_DIR` 被删除（见第 6 节）。
- `source lib/container-runtime.sh` + `load_dotenv "$PROJECT_ROOT"`。
- `resolve_container_runtime` 严格模式失败时返回 1，此处 `exit 1`；成功且 `CONTAINER_RT` 非空进入 compose 分支，空则走 else 回退。

## 5. quickstart.sh 修改 (b)：compose 调用与提示 runtime-aware

**compose 调用：**

```bash
# 修改前
    docker compose up -d --build
# 修改后
    "$CONTAINER_RT" compose up -d --build
```

**提示：**

```bash
# 修改前
    echo "Check status: docker compose ps"
    echo "View logs:    docker compose logs -f"
    echo "Stop:         docker compose down"
# 修改后
    echo "Check status: $CONTAINER_RT compose ps"
    echo "View logs:    $CONTAINER_RT compose logs -f"
    echo "Stop:         $CONTAINER_RT compose down"
```

## 6. quickstart.sh 修改 (c)：回退分支文案 runtime-aware

**回退分支开头：**

```bash
# 修改前
else
    echo "Docker not found. Falling back to local development mode."
    echo ""
    echo "NOTE: OpenCode sandbox mode requires Docker for the OpenSandbox server."
    echo "      Without Docker, only langchain ADK provider is available."
    echo ""

    # Check prerequisites
    MISSING=""
    command -v java &> /dev/null || MISSING="$MISSING java"
# 修改后
else
    echo "Neither docker nor podman detected. Falling back to local development mode."
    echo ""
    echo "NOTE: OpenCode sandbox mode requires Docker or podman for the OpenSandbox server."
    echo "      Without a container runtime, only langchain ADK provider is available."
    echo ""

    # Check prerequisites
    MISSING=""
    command -v java &> /dev/null || MISSING="$MISSING java"
```

**回退分支内（删除冗余 SCRIPT_DIR 重定义 + 文案）：**

```bash
# 修改前
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

    echo "Starting backend (langchain provider, Docker required for opencode)..."
# 修改后
    echo "Starting backend (langchain provider, container runtime required for opencode)..."
```

**末尾提示：**

```bash
# 修改前
    echo "To use opencode provider, install Docker and re-run this script."
# 修改后
    echo "To use opencode provider, install Docker or podman and re-run this script."
```

## 与计划原文的差异说明

1. **行尾统一为 LF**：修改前两个文件均为 CRLF（Windows checkout）。计划允许在 bash -n 因 CRLF 报错时统一为 LF；为与 Task 5 已处理的 start-backend 系列（`start-backend.ps1` / `start-backend.sh` / `lib/container-runtime.sh` 均为 LF）保持一致，本次直接在修改完成后统一为 LF 再做语法检查。git 提交时仓库按 autocrlf 规则存储 LF（提交输出仅提示 "LF will be replaced by CRLF" 的工作区转换警告，diff 内容不受影响）。
2. **ps1 (a) 的 catch 行为**：计划原文 `catch { Write-Error $_.Exception.Message; exit 1 }` 与实装一致；`$rtLabel` 在 auto 模式无运行时（`$rt = $null`）时也为 "auto-detected"，但不会打印（`if ($rt)` 分支未进入），无副作用。
3. **sh (c) 删除 else 分支内 `SCRIPT_DIR` 重定义**：计划原文即为整体替换（删除该行）；因修改 (a) 已将 `SCRIPT_DIR` 提前至脚本头，else 分支内重定义冗余，按计划删除。
4. **保留未提及的文案**：ps1 回退分支内 "Install them and try again, or install Docker for the easiest setup." 与 sh 侧对应行（`echo "Install them and try again, or install Docker for the easiest setup."`）不在计划替换清单内，保持原样——与计划一致（计划未要求）。
5. **`if [ -n "$CONTAINER_RT" ]` 结构**：计划原文将原 `if command -v docker ...; then` 整体改为 `if [ -n "$CONTAINER_RT" ]; then`，else 分支保持原回退逻辑不变，实装与计划一致。

## 验证结果

1. **pwsh 语法检查**：

   ```text
   $ pwsh -NoProfile -Command "$null = [scriptblock]::Create((Get-Content -Raw 'scripts/quickstart.ps1')); 'SYNTAX OK'"
   SYNTAX OK
   ```

2. **bash 语法检查**：

   ```text
   $ bash -n scripts/quickstart.sh && echo "SYNTAX OK"
   SYNTAX OK
   ```

   两行均为 `SYNTAX OK`，符合计划 Step 6.3 预期。

3. **代码正确性**：逐处对照计划原文完成替换（见上），逻辑与 Task 5 已接线的 start-backend 模式一致（`Resolve-ContainerRuntime` / `resolve_container_runtime` 的严格/自动模式、`& $rt compose` / `"$CONTAINER_RT" compose` 调用）。功能全量验证留待 Task 9（Spike B）。

## 提交

```text
1afd76e feat(scripts): runtime-aware quickstart (docker|podman) + ProjectRoot fix
 2 files changed, 55 insertions(+), 35 deletions(-)
 Pre-commit Guardrail: [1/3] Format ✓ [2/3] TS skip ✓ [3/3] Sensitive scan ✓ ALL CHECKS PASSED
```

报告提交：`docs(podman): task6 report`（hash 见 `git log --oneline -2`，本文件提交自身无法自引用）

# Task 3 执行报告 — PowerShell 容器运行时解析库 + E2E 场景测试

> **Status: DONE** — TDD 红灯（库缺失，ExitCode 1）→ 绿灯（10 场景全 PASS，ExitCode 0）证据完整，代码与报告已提交。

## 1. 环境信息

| 项目 | 值 |
|------|-----|
| 操作系统 | Windows 22H2（10.0.19045） |
| Shell | PowerShell 7（pwsh） |
| 工作区 | `C:\Users\User\.qoder\worktree\aria-conductor\fvHa7E` |
| git 分支 | `feat/podman-support`（git 元数据位于工作区外 `d:/project/aria-conductor/.git`） |
| 设计文档 | `docs/superpowers/specs/2026-08-16-podman-support-design.md`（§3 CONTAINER_RUNTIME/SANDBOX_SOCKET 配置模型、§4.1 .env 加载、§7.1 场景测试矩阵） |
| 计划文档 | `docs/superpowers/plans/2026-08-16-podman-support.md`（Task 3） |
| 本机真实运行时 | podman 5.8.3（machine Stopped）、Docker Desktop（已退出）——测试用 stub CLI + 隔离 PATH，不受影响 |

## 2. Step 3.1 — 先写场景测试（RED）

创建 `e2e/container-runtime-e2e.ps1`（121 行），逐字按计划原文。测试设计要点：

- **零外部依赖**：stub docker/podman CLI（`.ps1` 脚本，`info` 成功即 `exit 0`，否则 `exit 1`）注入临时目录并设为子进程 PATH。
- **进程隔离**：每个场景在全新 `pwsh -NoProfile -File` 子进程中运行（`Invoke-Scenario` 函数生成 `scenario.ps1`），宿主真实 docker/podman 永不泄漏进测试；同时顺带验证本机已有 podman/Docker 时 PATH 隔离的有效性。
- **stub 约定**：`"name"` = 运行中 stub；`"name-dead"` = 引擎不可用 stub（`exit 1`）。
- 8 个解析场景 + 2 个 `Load-DotEnv` 场景，共 10 个断言（覆盖 spec §7.1 场景矩阵：显式 docker/podman、CLI 缺失、引擎未启动、非法值、自动探测 docker 优先/podman 次之/均不可用返回 null、.env 解析与缺失 no-op）。

## 3. Step 3.2 — 运行确认失败（RED 证据）

命令：`pwsh -NoProfile -File e2e/container-runtime-e2e.ps1` → **ExitCode 1**

输出摘要（库不存在，dot-source 失败）：

```
Container-runtime resolution scenarios:
.: ...\scenario.ps1:3
  . 'C:\Users\User\.qoder\worktree\aria-conductor\fvHa7E\scripts\lib\container-runtime.ps1'
    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    The term 'C:\...\scripts\lib\container-runtime.ps1' is not
    recognized as a name of a cmdlet, function, script file, or executable program. ...
```

红灯达成：`scripts/lib/container-runtime.ps1` 不存在，场景子进程在 dot-source 处终止。

**已知的预期失败形态差异（记录备查）**：计划预期错误文本为 `The term 'Resolve-ContainerRuntime' is not recognized`；实际因为 dot-source 的库文件本身不存在，错误为 `container-runtime.ps1 is not recognized`（函数定义从未加载，语义等价：库缺失）。此外父进程 `$ErrorActionPreference = "Stop"` 将子进程 stderr 错误转为 terminating error，父脚本在第 1 个场景的 `Assert-True` 处中止（`Cannot convert value "System.Object[]" to type "System.Boolean"`），未跑完 10 个场景。二者均为**红灯阶段**（库文件缺失）的固有表现，Step 3.4 库存在后不再出现——子进程错误经 `try/catch` 捕获并 `Write-Output` 到 stdout，stderr 为空，父脚本正常跑完全部场景。

## 4. Step 3.3 — 创建库（GREEN 实施）

创建 `scripts/lib/container-runtime.ps1`（62 行），逐字按计划原文，三个函数：

- `Load-DotEnv` — 读取项目 `.env`，跳过空行/注释/无等号行，`KEY=VALUE` 正则解析，**已有环境变量永不覆盖**（`[Environment]::GetEnvironmentVariable` 判空后 `SetEnvironmentVariable(..., "Process")`）；`.env` 缺失时 no-op。
- `Test-RuntimeCli` — `Get-Command` 检查 CLI 存在，且 `& $Runtime info` 退出码为 0（引擎可达）才视为可用。
- `Resolve-ContainerRuntime` — 返回 `@{ Runtime; Mode }`：
  - 严格模式（`CONTAINER_RUNTIME` 已设置）：非法值（非 docker/podman）→ `throw "...is invalid. Use 'docker' or 'podman'."`；CLI 缺失 → `throw`（podman 附带 `podman machine start` 提示，docker 提示安装/启动 Docker Desktop）。
  - 自动模式：docker（运行中）优先 → podman → 均不可用返回 `@{ Runtime = $null; Mode = "auto" }`。

## 5. Step 3.4 — 运行确认通过（GREEN 证据）

命令：`pwsh -NoProfile -File e2e/container-runtime-e2e.ps1` → **ExitCode 0**

输出（完整，10 场景全 PASS）：

```
Container-runtime resolution scenarios:
  PASS: explicit docker + docker available
  PASS: explicit podman + podman available
  PASS: explicit docker + CLI missing -> hard error
  PASS: explicit podman + engine not running -> hard error with podman hint
  PASS: explicit invalid value -> hard error
  PASS: auto + docker running -> docker
  PASS: auto + only podman running -> podman
  PASS: auto + neither available -> null runtime
Load-DotEnv scenarios:
  PASS: Load-DotEnv parses KEY=VALUE, skips comments/invalid, preserves existing env
  PASS: Load-DotEnv missing .env is a no-op

All scenarios PASSED
```

**隔离性验证**：本机真实存在 podman 5.8.3（machine Stopped）与 Docker Desktop（已退出），但全部场景 PASS 且语义正确（尤其 "auto + docker running" 与 "auto + neither available"）——证明 stub PATH 隔离有效，真实 CLI 未泄漏（否则本机 podman 会干扰 "auto + neither" 场景）。TDD 红→绿闭环达成。

## 6. Step 3.5 — 提交

代码提交：

```bash
git add scripts/lib/container-runtime.ps1 e2e/container-runtime-e2e.ps1
git commit -m "feat(scripts): container-runtime resolution lib + pwsh e2e scenario tests"
```

输出摘要：

```
[feat/podman-support 95a2f20] feat(scripts): container-runtime resolution lib + pwsh e2e scenario tests
 2 files changed, 183 insertions(+)
 create mode 100644 e2e/container-runtime-e2e.ps1
 create mode 100644 scripts/lib/container-runtime.ps1
[Aria Conductor · Pre-commit Guardrail]
  [1/3] Format check... ✓ Passed
  [2/3] Type check (TypeScript)... ✓ Skipped (no TS/TSX changes in act-dashboard/src)
  [3/3] Sensitive file scan... ✓ No sensitive files detected
  ✓ ALL CHECKS PASSED
```

## 7. 文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `scripts/lib/container-runtime.ps1` | 新增（62 行） | 容器运行时解析库：`Load-DotEnv` / `Test-RuntimeCli` / `Resolve-ContainerRuntime` |
| `e2e/container-runtime-e2e.ps1` | 新增（121 行） | 10 场景 E2E 测试（stub CLI + 隔离 PATH + 子进程隔离） |

## 8. 提交列表

| Commit | 说明 |
|--------|------|
| `95a2f20` | feat(scripts): container-runtime resolution lib + pwsh e2e scenario tests |

## 9. 结论

- **Status: DONE** — TDD 证据完整：Step 3.2 红灯（库缺失 ExitCode 1）→ Step 3.3 最小实现 → Step 3.4 绿灯（10 场景全 PASS，ExitCode 0，pre-commit 3/3 通过）。
- 解析规则与 spec §3/§7.1 一致：显式 `CONTAINER_RUNTIME=docker|podman` → 严格模式（非法值/CLI 不可用抛错，podman 带 `podman machine start` 提示）；未设置 → 自动探测 docker → podman → 均不可用 `Runtime=$null`；`.env` 加载不覆盖已有环境变量。
- 唯一记录项：Step 3.2 实际失败文本与计划预期的表述差异（dot-source 文件不存在 vs 函数不可识别）及父脚本提前中止，均属红灯阶段固有现象，Step 3.4 已证实不再出现，无需修复。

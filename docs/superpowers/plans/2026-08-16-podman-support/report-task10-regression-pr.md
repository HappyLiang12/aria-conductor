# Task 10 — docker 路径回归 + 全量测试 + 推送 + PR + CI 全绿

**Branch:** `feat/podman-support`（worktree `C:\Users\User\.qoder\worktree\aria-conductor\fvHa7E`）
**Date:** 2026-08-17
**Status:** DONE

> 本任务是 podman-support 计划的最终收尾：验证 docker 默认路径零回归，全量测试通过，推送分支并创建 PR，CI 全绿。

## 1. Step 10.1 — 场景测试复跑（docker 基线行为不变）

### e2e/container-runtime-e2e.ps1（期望 10/10 PASS）

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

结果：**10/10 PASS**。其中 "auto + docker running → docker" 为 docker 默认回归断言，通过。

### e2e/container-runtime-e2e.sh（期望 11/11 PASS）

```
Container-runtime resolution scenarios:
  PASS: explicit docker + docker available
  PASS: explicit podman + podman available
  PASS: explicit docker + CLI missing -> hard error (rc=1)
  PASS: explicit podman + engine not running -> hard error with podman hint (rc=1)
  PASS: explicit invalid value -> hard error (rc=1)
  PASS: auto + docker running -> docker
  PASS: auto + only podman running -> podman
  PASS: auto + neither available -> null runtime (rc=0)
load_dotenv scenarios:
  PASS: load_dotenv parses KEY=VALUE, preserves existing env
  PASS: load_dotenv missing .env is a no-op
  PASS: load_dotenv strips CRLF line endings

All scenarios PASSED
```

结果：**11/11 PASS**（含 CRLF 场景，比 ps1 多 1 个）。

## 2. Step 10.2 — compose 配置回归验证（docker 语法路径）

命令：`& "C:\Program Files\RedHat\Podman\podman.exe" compose config --quiet`

- **exit=0** ✅。compose provider 确认为外部 `C:\Program Files\Docker\Docker\resources\bin\docker-compose.exe`（podman-compose 委托），双重验证了插值语法。
- 注：首次调用在沙箱内报 "Access is denied"，以完整权限重试成功（podman.exe 位于 Program Files，沙箱限制所致）。

### SANDBOX_SOCKET 参数化验证

| 环境状态 | `podman compose config` 输出 source |
|---|---|
| `$env:SANDBOX_SOCKET="/run/user/1000/podman/podman.sock"` | `source: /run/user/1000/podman/podman.sock` |
| 环境变量已清除 | `source: /var/run/docker.sock`（默认值） |

参数化仍生效，清除环境变量后无残留（`SANDBOX_SOCKET-present=False`）。

## 3. Step 10.3 — 全量 Java 测试

命令：`mvn clean test "-Dspring.profiles.active=h2"`（9 模块 reactor，Java 21，Maven 3.9.16）

- **BUILD SUCCESS**（Total time 05:19 min）
- **测试总数：3354 run / 0 failures / 0 errors / 16 skipped**
- **SandboxRunnerTest（Task 2 新增）：18/18 PASS**（含 CONTAINER_RUNTIME 无效值告警、docker/podman CLI 缺失禁用、auto 探测等场景日志）
- 其余关键回归：SchemaConsistencySmokeTest 1/1、SddGoldenChainRegressionTest 1/1、RunControllerRegressionTest 2/2、ModuleBoundaryTest 10/10、V43SeedConfigTest 9/9 等全部通过。
- 首次运行因 PowerShell 未引号包裹 `-Dspring.profiles.active=h2` 报 `Unknown lifecycle phase`，加引号后成功（记录在 `tmp-task10-mvn.log`，未跟踪不提交）。

## 4. Step 10.4 — 推送分支

- 分支验证：`git rev-parse --abbrev-ref HEAD` → `feat/podman-support` ✅（所有 git 操作均在正确 worktree 执行）
- `git status`：tracked 文件干净；53 个历史遗留未跟踪文件（旧截图/report/tmp 日志等）**未 add**。
- `git push -u origin feat/podman-support` → 新分支推送成功（`* [new branch] feat/podman-support -> feat/podman-support`）。

## 5. Step 10.5 — 创建 PR

命令：`gh pr create --repo HappyLiang12/aria-conductor --base main --head feat/podman-support --title "feat: podman container runtime support" --body-file tmp-task10-pr-body.md`

- **PR #72：https://github.com/HappyLiang12/aria-conductor/pull/72**
- Body 涵盖：CONTAINER_RUNTIME/SANDBOX_SOCKET 配置模型、脚本运行时抽象（ps1+sh）、SandboxRunner 环境变量优先级、compose socket 参数化 + OpenSandbox eip、e2e 场景测试、文档；验证证据（Spike A / Spike B / eip 实验）；报告路径清单；CI 保持 docker 路径说明。

## 6. Step 10.6 — CI 监控至全绿

Run：**31998629009**（pull_request，branch feat/podman-support）
URL：https://github.com/HappyLiang12/aria-conductor/actions/runs/31998629009

| Job | 结果 | 耗时 |
|---|---|---|
| Detect Changed Trees | ✅ pass | 5s |
| Build Backend (once) | ✅ pass | 43s |
| Java Unit Tests (common-agent) | ✅ pass | 50s |
| Java Unit Tests (dashboard-app) | ✅ pass | 1m42s |
| Java Unit Tests (execution) | ✅ pass | 1m47s |
| Java Unit Tests (knowledge-aria) | ✅ pass | 3m12s |
| Java Integration Tests (Failsafe) | ✅ pass | 1m52s |
| Frontend Build Check | ✅ pass | 40s |
| E2E Smoke Test (Backend Endpoints) | ✅ pass | 44s |
| E2E Playwright (shard 1/4) | ✅ pass | 6m43s |
| E2E Playwright (shard 2/4) | ✅ pass | 2m51s |
| E2E Playwright (shard 3/4) | ✅ pass | 2m45s |
| E2E Playwright (shard 4/4) | ✅ pass | 1m58s |
| Docker Build Verification | ✅ pass | 1m48s |
| Python Tests + Coverage | ⏭️ skipping（无变更） | — |
| TS MCP Server Tests + Coverage | ⏭️ skipping（无变更） | — |

`gh pr checks 72` 最终 exit=0，**全部必需 job green，无需重跑**。总耗时约 10 分钟（从创建 PR 到全绿）。

> 后续 docs 提交触发的完整 run 亦全部 green（4/4 连续成功）：31998629009（7m39s）→ 31999242464（7m53s，报告 commit 后）→ 31999944548（7m42s）→ **32000583678**（最终，PR checks 现指向此 run，7m45s）。最终 green run：**32000583678**（https://github.com/HappyLiang12/aria-conductor/actions/runs/32000583678）。

## 7. 结论

- docker 默认路径零回归：双 harness（ps1 10/10、sh 11/11）与 `podman compose config`（docker-compose.exe 解析）均验证通过。
- 全量 Java 测试 3354 个全部通过（0 failure / 0 error）。
- PR #72 已创建，CI run 31998629009 全绿（含 java 单测/集成、frontend、e2e-playwright×4、docker-build）。
- 无遗留问题；podman 支持为本地门禁，CI 保持 docker 路径（符合计划预期）。

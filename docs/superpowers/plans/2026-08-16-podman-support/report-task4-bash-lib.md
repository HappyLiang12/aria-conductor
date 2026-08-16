# Task 4 Report — bash 容器运行时解析库 + E2E 场景测试

- 日期: 2026-08-17
- 分支: `feat/podman-support`
- 状态: DONE
- 提交: `9c4ced9` feat(scripts): container-runtime resolution lib + bash e2e scenario tests

## 文件清单

| 文件 | 变更 |
|---|---|
| `scripts/lib/container-runtime.sh` | 新增 — `load_dotenv` / `runtime_cli_ok` / `resolve_container_runtime` |
| `e2e/container-runtime-e2e.sh` | 新增 — 10 个 E2E 场景测试（stub CLI 注入 + 隔离 PATH） |

## TDD 证据

### Step 4.2 — 失败阶段（库不存在，预期失败）

先创建 `e2e/container-runtime-e2e.sh` 后运行，库尚未创建：

```
Container-runtime resolution scenarios:
  FAIL: explicit docker + docker available (.../scenario.sh: line 4: resolve_container_runtime: command not found
RESULT runtime= mode=)
  FAIL: explicit podman + podman available (resolve_container_runtime: command not found)
  FAIL: explicit docker + CLI missing -> hard error (resolve_container_runtime: command not found)
  FAIL: explicit podman + engine not running -> hard error (resolve_container_runtime: command not found)
  FAIL: explicit invalid value -> hard error (resolve_container_runtime: command not found)
  FAIL: auto + docker running -> docker (resolve_container_runtime: command not found)
  FAIL: auto + only podman running -> podman (resolve_container_runtime: command not found)
  FAIL: auto + neither available -> null runtime (resolve_container_runtime: command not found)
load_dotenv scenarios:
  FAIL: load_dotenv parsing (load_dotenv: command not found)
  PASS: load_dotenv missing .env is a no-op   ← 断言与库无关（无 .env 本就 no-op）
9 scenario(s) FAILED
EXIT_CODE=1
```

符合 TDD 预期：库不存在 → 8 个 runtime 场景 + load_dotenv 解析场景报 `command not found`。
（load_dotenv no-op 场景断言仅检查 `RESULT ok`，库缺失时行为一致，属测试固有属性，如实记录。）

### Step 4.4 — 通过阶段（10 场景全部 PASS）

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
load_dotenv scenarios:
  PASS: load_dotenv parses KEY=VALUE, preserves existing env
  PASS: load_dotenv missing .env is a no-op

All scenarios PASSED
EXIT_CODE=0
```

## 偏差说明（Deviation）

- 库实现：`rt="$(printf '%s' "$explicit" | tr '[:upper:]' '[:lower:]')"` → `rt="${explicit,,}"`。
  原因：测试隔离设计 `export PATH="$dir"`（防止宿主真实 docker/podman 泄漏）使外部工具 `tr` 不可用，导致 4 个显式场景失败（`tr: command not found`）。
  修复：改用 bash 内置 `${var,,}`（bash 4.0+，MSYS2 5.2 支持），语义与 `tr '[:upper:]' '[:lower:]'` 的 ASCII 转换等价，场景意图与所有断言不变。
- 环境备注：`run_scenario "docker" ""` / `run_scenario "" ""` 的空 stub 参数会在 stderr 产生两条无害的 `Is a directory` 警告（`printf > "$dir/"`），不影响 stdout 断言，测试脚本保持与计划逐字一致。

## 验证命令

```bash
bash e2e/container-runtime-e2e.sh   # 10 场景 PASS，退出码 0
```

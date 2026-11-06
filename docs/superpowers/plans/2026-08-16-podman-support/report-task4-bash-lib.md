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

## Task 4 评审修复（Review Fixes）

- 日期: 2026-08-17
- 提交: `2082ed2` fix(scripts): validate env name first char, preserve empty env vars, quiet empty stub specs

| # | 严重度 | 修复内容 | 文件 |
|---|---|---|---|
| 1 | Important | `load_dotenv` 名称首字符校验：`''\|*[!A-Za-z0-9_]*` → `''\|[!A-Za-z_]*\|*[!A-Za-z0-9_]*`，首字符必须是字母或下划线，与 ps1 版 `^[A-Za-z_][A-Za-z0-9_]*$` 对齐（数字开头键如 `1BAD_NAME` 被静默跳过） | `scripts/lib/container-runtime.sh` |
| 2 | low | 空但已设置的 env var 不被 `.env` 覆盖：`[ -z "${!name:-}" ]` → `[ -z "${!name+x}" ]`，用间接展开检测"已设置"（含空值），bash 3+ 兼容，与 ps1 版 cffc7ab 修复对齐 | `scripts/lib/container-runtime.sh` |
| 3 | low | harness 空 stub spec 跳过：`for spec in "$@"` 中增加 `[ -n "$spec" ] || continue`，消除 `printf > "$dir/"` 产生的 `Is a directory` stderr 噪声 | `e2e/container-runtime-e2e.sh` |
| 4 | 测试加固 | dotenv 场景 `.env` 增加 `1BAD_NAME=should-be-skipped` 行，证明数字开头键被跳过且不中断脚本（Fix 1 回归覆盖） | `e2e/container-runtime-e2e.sh` |

### 修复后验证

```bash
$ bash e2e/container-runtime-e2e.sh
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

All scenarios PASSED   # 退出码 0，stderr 无 "Is a directory" 噪声
```

Fix 2 手动检查（bash）：`FOO=""` 已设置时 source 库并加载含 `FOO=bar` 的 `.env` → `FOO` 保持为空；未设置的 `BAZ` 正常从 `.env` 加载为 `qux`（`${!name+x}` 语义正确区分"已设置但为空"与"未设置"）。

## Task 4 代码质量评审修复（Quality Review Fixes）

- 日期: 2026-08-17
- 提交: `8aff251` fix(scripts): strip CRLF from .env values, assert exit codes in bash harness, trap cleanup

| # | 严重度 | 修复内容 | 文件 |
|---|---|---|---|
| C1 | Critical | `.env` 值 CRLF 泄漏：仓库 `.env.example` 为 CRLF 行尾，`cp .env.example .env` 后 `load_dotenv` 读到 `CONTAINER_RUNTIME=podman\r`，strict 模式判定 `podman\r` ≠ podman → 硬错误 "is invalid"（`SANDBOX_SOCKET` 同理）。修复：read 后立即 `line="${line%$'\r'}"` 去除行尾 `\r`（bash 内建展开，bash 4+ 可用，不用 sed/tr 外部命令以兼容 harness 隔离 PATH） | `scripts/lib/container-runtime.sh` |
| I1 | Important | harness 退出码契约：scenario.sh 捕获并 `exit $rc` 传播 `resolve_container_runtime` 退出码；`run_scenario` 返回子进程退出码；8 个 resolution 场景断言扩展 `rc=0`（success / auto-neither）/ `rc=1`（hard error 场景），rc=1 场景用双向交替 pattern `*rc=1*"msg"*\|*"msg"*rc=1*`（ERROR 行在 RESULT 行之前，单方向 pattern 会漏匹配） | `e2e/container-runtime-e2e.sh` |
| M1 | Minor | trap 清理：`trap 'rm -rf "$STUB_ROOT"' EXIT` 统一清理，删除脚本末尾显式 `rm -rf`，避免双重删除 | `e2e/container-runtime-e2e.sh` |
| M2 | Minor | bash 版本要求文档化：头注释增加 `# Requires bash 4.0+ (uses ${var,,} and ${!name+x} parameter expansions).` | `scripts/lib/container-runtime.sh` |
| 回归 | C1 覆盖 | 新增 CRLF 回归场景：`crtlf_dir` 下写入 CRLF `.env`（`CONTAINER_RUNTIME=podman\r\n` + `SANDBOX_SOCKET=...\r\n`），断言 `load_dotenv` 剥离行尾 `\r` 后值为 `podman` / socket 路径。harness 场景数 10 → 11 | `e2e/container-runtime-e2e.sh` |

### 修复后验证

```bash
$ bash e2e/container-runtime-e2e.sh   # 11/11 PASS，退出码 0，stderr 0 字节
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

CR bug 复现验证：CRLF `.env`（`CONTAINER_RUNTIME=podman\r\n`）+ podman stub CLI（隔离 PATH），fresh bash 中 `load_dotenv` + `resolve_container_runtime` → `RESULT rc=0 runtime=podman mode=explicit`（修复前硬错误 "is invalid"）。


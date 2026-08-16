# Task 8 报告：文档更新（README.md / AGENTS.md / opencode-sandbox README）— podman 容器运行时支持

- 分支：`feat/podman-support`
- 日期：2026-08-17
- 代码提交：`d09b7c2`（docs: podman container runtime selection guide）
- 报告提交：`（见文末）`

## 目标

为 podman 容器运行时支持收尾文档：README.md 六处修改（Tech Stack / Quick Start 前置条件 / 新增 Container Runtime Selection 章节 / Dev prerequisites 表 / OpenSandbox 段 / Environment Variables 表）、AGENTS.md 四处修改（Module 表 / Run full-stack / High-Risk Areas / Validation Command Mapping）、opencode-sandbox/README.md 两处 podman 变体。对应设计文档 `docs/superpowers/specs/2026-08-16-podman-support-design.md` §9。

## 修改总览

| 文件 | 修改数 | 说明 |
|------|--------|------|
| `README.md` | 6 | Tech Stack 表、Quick Start 前置条件、新增 Container Runtime Selection 章节（27 行）、Dev prerequisites 表、OpenSandbox 段两处 podman 变体、Environment Variables 表两行 |
| `AGENTS.md` | 4 | Module 表描述、Run full-stack 第 1 步、High-Risk Areas 追加 1 行、Validation Command Mapping 追加 1 行 |
| `agent-control-tower/opencode-sandbox/README.md` | 2 | Build 段追加 podman 变体、Smoke test 段追加 podman 变体 |

diff 统计：3 files changed, **42 insertions(+), 5 deletions(-)**。

---

## 1. README.md（六处）

### (a) Tech Stack 表

**修改前：** `| Containerization | Docker, Docker Compose |`

**修改后：** `| Containerization | Docker / Podman, Docker Compose / Podman Compose |`

- 与计划原文一致，无差异。

### (b) Quick Start 前置条件

**修改前：** `- Docker and Docker Compose v2`

**修改后：** `- Docker (or podman) and Docker Compose v2 (or podman compose)`

- 与计划原文一致，无差异。

### (c) 新增 "Container Runtime Selection" 章节

**锚点：** `## Development Setup\n\nFor local development without Docker:`（README.md 原有内容，与计划一致），替换为计划原文的章节 + 原 `## Development Setup` 开头（27 行新增）：

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

- 与计划原文逐字一致（含 Note 引用 `e2e/container-runtime-e2e.ps1`，该文件已由 Task 前序交付存在）；`Development Setup` 标题与说明行保留在其后，原有内容不受影响。

### (d) Dev prerequisites 表

**修改前：** `| Docker | 24+ | `docker --version` (required for opencode provider) |`

**修改后：** `| Docker / Podman | 24+ / 4.9+ | `docker --version` or `podman --version` (required for opencode provider) |`

- 与计划原文一致，无差异。

### (e) OpenSandbox 段（两处追加）

**e-1：** `docker compose up -d opensandbox-server` 代码块后追加：

```bash
# or with podman:
# podman compose up -d opensandbox-server
```

**e-2：** `docker build -t aria-conductor/opencode-sandbox:1.0 agent-control-tower/opencode-sandbox` 代码块后追加：

```bash
# or: podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox
```

- e-1 与计划原文一致。
- e-2：README 现有 docker build 命令 tag 为 **1.0**（Before You Begin 提示属实），计划原文的 podman 变体 tag 为 **1.1**——按计划原文执行，保留 tag 差异。原因：README 本节沿用了旧版 1.0 未更新，而新章节 (c) 与 opencode-sandbox/README.md 中已统一使用 1.1；podman 变体指向实际最新的 1.1 镜像标签，语义正确（README 的 1.0 docker 行属既有遗留，不在本任务范围）。

### (f) Environment Variables 表末尾追加两行

**锚点：** `| `DB_NAME` | `aria_conductor` | Database name |` 之后追加：

```markdown
| `CONTAINER_RUNTIME` | auto-detect | Container runtime: `docker` or `podman` (auto-detect: docker preferred) |
| `SANDBOX_SOCKET` | `/var/run/docker.sock` | Host container-engine socket mounted into the OpenSandbox server |
```

- 与计划原文一致；与 `.env.example` 中 Task 7 交付的 Container Runtime 配置块、Task 3/4 交付的脚本严格模式语义一致。

---

## 2. AGENTS.md（四处）

### (a) Module 表 opencode-sandbox 行

**修改前：** `| `opencode-sandbox` | Docker image for OpenCode sandbox (opencode provider) | `agent-control-tower/opencode-sandbox/Dockerfile` |`

**修改后：** `| `opencode-sandbox` | Container image (docker/podman) for OpenCode sandbox (opencode provider) | `agent-control-tower/opencode-sandbox/Dockerfile` |`

- 计划原文仅给出描述部分 `Docker image for OpenCode sandbox` → `Container image (docker/podman) for OpenCode sandbox`；实际文件行含 `(opencode provider)` 后缀与 Entry File 列，仅替换描述段，其余列保留。

### (b) "Run full-stack locally" 第 1 步

**修改前：** `1. OpenSandbox: `docker compose up -d opensandbox-server` (required for opencode provider)`

**修改后：** `1. OpenSandbox: `docker compose up -d opensandbox-server` (podman: `podman compose up -d opensandbox-server`; set `SANDBOX_SOCKET` in .env; required for opencode provider)`

- 与计划原文一致，无差异。

### (c) High-Risk Areas 表末尾追加

**锚点：** `| OpenCode sandbox / OpenSandbox | ... |` 行之后追加：

```markdown
| Container runtime selection (`scripts/lib/container-runtime.*`) | podman socket/config mismatch blocks opencode sandbox | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |
```

- 与计划原文一致；`scripts/lib/container-runtime.ps1` / `container-runtime.sh` 由 Task 3/4 交付，`e2e/container-runtime-e2e.ps1` / `.sh` 由 Task 前序交付，引用均真实存在。

### (d) Validation Command Mapping 表末尾追加

**锚点：** `| Docker full-stack | `docker compose up -d` |` 之后追加：

```markdown
| Container runtime scenario tests | `pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh` |
```

- 与计划原文一致，无差异。

---

## 3. opencode-sandbox/README.md（两处）

### Build 段

**修改前：**

```bash
docker build -t aria-conductor/opencode-sandbox:1.1 .
```

**修改后：**

```bash
docker build -t aria-conductor/opencode-sandbox:1.1 .
# or with podman:
podman build -t aria-conductor/opencode-sandbox:1.1 .
```

- 与计划原文一致，无差异。

### Smoke test 段

**修改前：**

```bash
docker run --rm aria-conductor/opencode-sandbox:1.1
# prints: opencode version X.Y.Z
```

**修改后：**

```bash
docker run --rm aria-conductor/opencode-sandbox:1.1
# or: podman run --rm aria-conductor/opencode-sandbox:1.1
# prints: opencode version X.Y.Z
```

- 与计划原文一致，无差异。

---

## 与计划原文的差异说明

| # | 位置 | 差异 | 说明 |
|---|------|------|------|
| 1 | README.md (e)-2 | README 现有 docker build tag 为 `1.0`，计划 podman 变体为 `1.1` | 按计划原文执行（Before You Begin 已提示，以实际文件为准）；README 1.0 为既有遗留，podman 变体指向最新 1.1 标签 |
| 2 | AGENTS.md (a) | 实际文件行含 `(opencode provider)` 后缀，计划原文未包含 | 仅替换描述段，保留后缀与 Entry File 列，语义完整 |

其余 11 处与计划原文逐字一致。

## 验证结果

1. `git diff` 逐处核对：README.md 6 处、AGENTS.md 4 处、opencode-sandbox/README.md 2 处，全部符合计划原文（差异仅上表 2 项，已按说明处理）。
2. 引用完整性：`e2e/container-runtime-e2e.ps1` / `.sh`（存在）、`scripts/lib/container-runtime.*`（Task 3/4 交付）、`.env.example` Container Runtime 块（Task 7 交付）均真实存在。
3. 提交时 Pre-commit Guardrail 全部通过：Format check ✓ / TS check 跳过（无 TS 变更）/ Sensitive file scan ✓。

## 提交

```text
d09b7c2 docs: podman container runtime selection guide
 3 files changed, 42 insertions(+), 5 deletions(-)
 Pre-commit Guardrail: [1/3] Format ✓ [2/3] TS skip ✓ [3/3] Sensitive scan ✓ ALL CHECKS PASSED
```

报告提交：`docs(podman): task8 report`（hash 见 `git log --oneline -2`，本文件提交自身无法自引用）

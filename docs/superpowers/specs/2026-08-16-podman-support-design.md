# Podman Container Runtime Support — Design

Date: 2026-08-16
Status: Approved (brainstorming session, 3 design sections confirmed)
Branch: feat/podman-support

## 1. Background & Goal

Aria Conductor 的启动链路（`scripts/start-backend.*`、`scripts/quickstart.*`、`docker-compose.yml`）硬编码依赖 Docker。目标是让用户可以选择使用 podman 替代 docker 作为容器运行时，并确保 opencode 模式的沙箱（OpenSandbox）在 podman 下依然可用。

关键调研事实：

- 后端与 OpenSandbox 服务器通过 HTTP 通信（`opencode.sandbox-server-url`），不直接依赖 docker CLI/socket。
- OpenSandbox 服务器容器通过挂载 `/var/run/docker.sock` 创建/销毁沙箱容器（docker-compose.yml 中的 `opensandbox-server` 服务）。
- OpenSandbox 官方仅支持 Docker 与 Kubernetes 两种运行时，**无原生 podman 运行时**；其 docker 运行时通过 docker Python SDK 连接（遵循 `DOCKER_HOST` 惯例）。podman 提供 Docker API 兼容 socket，因此必须通过挂载 podman 的 docker-compat socket 实现。
- `SandboxRunner`（工具沙箱，`act-execution`）已实现 docker→podman 自动探测，只需补充环境变量优先级。
- CI（GitHub Actions / Ubuntu）保持 docker 不变；podman 支持面向本地开发环境。

## 2. Scope

In scope:

- 容器运行时选择机制：`CONTAINER_RUNTIME` 环境变量 + 自动探测（docker 优先）。
- OpenSandbox socket 挂载参数化：`SANDBOX_SOCKET` 环境变量。
- `scripts/start-backend.ps1|sh`、`scripts/quickstart.ps1|sh` 的运行时抽象。
- `docker-compose.yml` 的 socket 挂载参数化。
- Java `SandboxRunner` 的环境变量优先级。
- E2E 场景测试：脚本运行时解析场景测试 + 复用现有 Playwright opencode 套件做 podman 路径回归。
- 文档：README、.env.example、opencode-sandbox/README.md、AGENTS.md。

Out of scope:

- CI 增加 podman 矩阵（后续独立任务）。
- OpenSandbox 上游 podman 原生支持（社区无先例，风险见 §8）。
- 修改后端与 OpenSandbox 的 HTTP 通信路径。

## 3. Config Model

两个环境变量，`.env` 是唯一配置入口：

| Variable | Values | Default | Purpose |
|----------|--------|---------|---------|
| `CONTAINER_RUNTIME` | `docker` / `podman` | unset (auto-detect) | 选择容器运行时 CLI |
| `SANDBOX_SOCKET` | host socket path | `/var/run/docker.sock` | 挂载进 OpenSandbox 服务器容器的引擎 socket |

### 3.1 Resolution rules (scripts and SandboxRunner share the same precedence)

1. `CONTAINER_RUNTIME` explicitly set → **strict mode**: the named CLI must exist AND be running, otherwise hard error with targeted hint, non-zero exit.
2. Unset → **auto-detect**: docker running → docker; else podman running → podman; else degradation path (see §6).

### 3.2 Podman socket combos (documented; user picks per environment)

- **Rootless combo (recommended)**: rootless podman + `SANDBOX_SOCKET=/run/user/1000/podman/podman.sock`（podman machine VM 内 `core` 用户 UID=1000；rootless podman.socket 用户服务在 podman machine 中默认启用——spike 第 1 步实测确认，若未启用则按 README 排障指引手动启用）。
- **Rootful combo**: `podman machine set --rootful` + VM 内 `sudo systemctl enable --now podman.socket` + `SANDBOX_SOCKET=/run/podman/podman.sock`。

在 podman machine（Windows）中，容器运行于 Linux VM 内，卷挂载的源路径在 VM 内解析，因此 `SANDBOX_SOCKET` 必须使用 VM 内部路径。

## 4. Script Changes

### 4.1 Minimal .env loader

每个脚本（ps1 与 sh）开头新增最小 `.env` 加载函数：解析 `KEY=VALUE` 行；已存在的进程环境变量不被覆盖。与 docker compose 自动读 `.env` 的行为保持一致，使用户只需配置一处。

### 4.2 `Resolve-ContainerRuntime` function

- Strict mode: 显式 `CONTAINER_RUNTIME` 时校验 CLI 存在且 `info` 可用，失败硬错误退出。
- Auto mode: 探测 `docker info` 运行中 → docker；否则 `podman info` 运行中 → podman。
- Preflight 输出显示所选运行时与选择原因（`explicit: CONTAINER_RUNTIME` 或 `auto-detected`）。

### 4.3 start-backend.ps1|sh

- 所有 `docker ps` → `$RT ps`；`docker compose up -d opensandbox-server` → `$RT compose up -d opensandbox-server`。
- Preflight 报告运行时状态（docker/podman 各自 running/not installed），替换现有仅 docker 的提示。
- `-SkipSandbox` 行为不变（完全绕过容器依赖）。
- Windows 下 podman 已安装但 machine 未启动 → 硬错误并提示 `podman machine start`。

### 4.4 quickstart.ps1|sh

- 运行时解析同 §4.2。
- `docker compose up -d --build` → `$RT compose up -d --build`。
- 两者都不可用 → 回退本地开发模式（langchain、无沙箱，与现状一致）。

## 5. Compose & Config File Changes

### 5.1 docker-compose.yml（1 处改动）

```yaml
opensandbox-server:
  volumes:
    - ${SANDBOX_SOCKET:-/var/run/docker.sock}:/var/run/docker.sock
```

其余服务（mariadb/backend/frontend/langchain-adk）运行时无关，`podman compose` 直接可用。默认值不变，docker 用户零感知。

### 5.2 .env.example additions（注释掉的默认值）

```
# Container runtime: docker | podman. Empty = auto-detect (docker preferred).
CONTAINER_RUNTIME=
# Host socket mounted into the OpenSandbox server for sandbox creation.
# podman rootless: /run/user/1000/podman/podman.sock ; podman rootful: /run/podman/podman.sock
SANDBOX_SOCKET=
```

### 5.3 Java SandboxRunner

`detectRuntime()` 优先级改为：`CONTAINER_RUNTIME` 环境变量（合法值 docker/podman，CLI 存在时）→ 现有 docker → podman 探测 → null（log warn，沙箱工具禁用，现状行为）。无效的环境变量值按未设置处理（log warn）。TDD 覆盖。

## 6. Error Handling Matrix

| Scenario | Behavior |
|----------|----------|
| 显式 `CONTAINER_RUNTIME=docker` 但 docker 不可用 | 硬错误：提示安装 docker 或改配 podman |
| 显式 `CONTAINER_RUNTIME=podman` 但 podman 未安装 | 硬错误 + 安装指引 |
| podman 已装但 machine 未启动（Windows） | 硬错误 + `podman machine start` 提示 |
| 自动模式两者都不可用 | quickstart 回退本地开发模式（langchain、无沙箱）；start-backend + opencode 模式硬错误并列出两种运行时指引 |
| opensandbox-server compose 启动失败 | 保留现有错误；podman 路径追加专项提示（如 socket 未启用时提示启用 `podman.socket` 的命令） |
| SandboxRunner 显式运行时不可用 | log warn + 沙箱工具禁用（现状行为） |

## 7. E2E Scenario Tests

### 7.1 脚本运行时解析场景测试（新增 `e2e/container-runtime-e2e.ps1`）

pwsh 运行，零外部依赖。通过 PATH 注入 stub 命令（临时目录放置假的 `docker`/`podman` 可执行文件）驱动解析逻辑：

| Scenario | Expected |
|----------|----------|
| 显式 `CONTAINER_RUNTIME=docker` + docker 可用 | 选 docker（严格模式） |
| 显式 `CONTAINER_RUNTIME=podman` + podman 可用 | 选 podman（严格模式） |
| 显式指定但 CLI 不可用 | 硬错误 + 针对性提示，非零退出 |
| 自动模式 + docker 运行中 | 选 docker（向后兼容基线） |
| 自动模式 + 仅 podman 运行中 | 选 podman |
| 自动模式 + 两者都不可用 | 降级路径（quickstart）或硬错误（opencode 模式） |

断言 preflight 输出的运行时与选择原因（`explicit` vs `auto-detected`）及退出码。

### 7.2 opencode 功能 E2E 回归（复用现有 Playwright 套件）

现有 `oc-*.spec.ts`（opencode 沙箱全链路）与 `rl-*.spec.ts`（真实 LLM）套件原样复用：

- 基线：docker 栈跑一遍（现状证据）。
- 新路径：podman 栈（quickstart 以 podman 启动全套）跑同一批套件。
- 结果一致 = opencode 模式沙箱在 podman 下无回归、后端/前端行为未破坏。

### 7.3 CI 定位

CI（Ubuntu + docker）保持现状不变。podman 场景测试为本地验证门禁，spike 执行并归档证据（遵循项目 QA 报告惯例）。

## 8. Validation Spike (implementation gate 1, must pass before remaining work)

在当前 Windows + podman machine 环境实测：

1. machine 启动、rootless socket 可用。
2. `podman compose up -d opensandbox-server`（`SANDBOX_SOCKET` 指向 rootless socket）→ `/health` 通过。
3. 通过 OpenSandbox API 创建沙箱 → 容器出现在 podman 中 → execd 端点从 Windows 主机可达。**重点验证 40000-60000 端口范围经 podman machine 的转发**。
4. 真实 LLM 全链路 E2E：opencode provider 创建 agent → run → 沙箱内真实执行 → 完成。
5. 运行 §7.1 场景测试与 §7.2 Playwright 套件（podman 栈）。

### 8.1 Risk & fallback

- OpenSandbox 的 docker 运行时对 podman compat socket 的兼容性无社区先例；若 spike 发现硬性不兼容 → 文档标注"podman 实验性支持，沙箱建议 docker"，并将备选路径写入文档：podman machine VM 内原生运行 `uvx opensandbox-server`（`DOCKER_HOST` 指向 podman socket），后端 HTTP 配置不变。
- 若 podman machine 端口转发对 40000-60000 范围失效 → 缩小端口范围或文档化限制，标记实验性。
- spike 结论（含失败情形）写回本设计文档。

## 9. Documentation Changes

- README：新增 "Container Runtime Selection" 小节——自动探测顺序、`.env` 配置示例、podman machine 完整设置步骤（启动 machine、启用 socket、`podman build` 构建 opencode-sandbox 镜像、运行 quickstart/start-backend）、排障提示。
- `opencode-sandbox/README.md`：补充 `podman build` 命令。
- AGENTS.md：High-Risk Areas 表补充 podman 相关验证命令。

## 10. Verification

- Java TDD：`SandboxRunner` 环境变量优先级与无效值处理测试；现有测试全绿（docker 路径回归）。
- E2E 场景测试：§7.1（新增）与 §7.2（复用，docker 基线 + podman 新路径）。
- 默认路径（未设置任何新变量）= docker 行为完全不变；CI 全绿。

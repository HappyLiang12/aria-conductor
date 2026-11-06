# Task 7 报告：docker-compose.yml socket 参数化 + .env.example 新增配置项

- 分支：`feat/podman-support`
- 日期：2026-08-17
- 代码提交：`41830ec`（feat(compose): parametrize OpenSandbox socket mount for podman）
- 报告提交：`（见文末）`

## 目标

将 `opensandbox-server` 的容器运行时 socket 挂载参数化为 `${SANDBOX_SOCKET:-/var/run/docker.sock}`，使 podman 用户可通过环境变量切换 socket 路径（rootless `/run/user/1000/podman/podman.sock` / rootful `/run/podman/podman.sock`），docker 用户零感知（默认值不变）。同时在 `.env.example` 中新增 Container Runtime 配置说明。对应设计文档 `docs/superpowers/specs/2026-08-16-podman-support-design.md` §3 / §5.1 / §5.2。

## 修改总览

| 文件 | 修改数 | 说明 |
|------|--------|------|
| `docker-compose.yml` | 1 | opensandbox-server volumes 块 socket 挂载参数化（1 行替换为 3 行注释 + 1 行挂载） |
| `.env.example` | 1 | `# OPENSANDBOX_PORT=8090` 之后、`# --- Tool Configuration` 之前插入 Container Runtime 配置块（7 行） |

diff 统计：2 files changed, **11 insertions(+), 2 deletions(-)**。

---

## 1. docker-compose.yml：socket 挂载参数化

**修改前：**

```yaml
    volumes:
      # Docker runtime mode: the server creates/kills sandbox containers via the Docker socket
      - /var/run/docker.sock:/var/run/docker.sock
      # Persist server-managed metadata (SQLite store at ~/.opensandbox/opensandbox.db)
      - opensandbox_data:/root/.opensandbox
```

**修改后：**

```yaml
    volumes:
      # Container-runtime socket used to create/kill sandbox containers. Defaults to
      # Docker; set SANDBOX_SOCKET for podman (rootless: /run/user/1000/podman/podman.sock,
      # rootful: /run/podman/podman.sock).
      - ${SANDBOX_SOCKET:-/var/run/docker.sock}:/var/run/docker.sock
      # Persist server-managed metadata (SQLite store at ~/.opensandbox/opensandbox.db)
      - opensandbox_data:/root/.opensandbox
```

- 实际文件原文与计划完全一致（含注释行），按计划原文执行等价替换。
- `SANDBOX_SOCKET` 未设置时解析为 `/var/run/docker.sock`（默认路径不变，docker 用户零感知）；设置时覆盖为指定 socket 路径。
- 挂载目标保持 `/var/run/docker.sock` 不变：OpenSandbox 通过挂载的 compat socket 创建沙箱，Spike A 已实测兼容（rootless podman machine VM 内路径 `/run/user/1000/podman/podman.sock`）。

## 2. .env.example：新增 Container Runtime 配置块

**修改后（`# OPENSANDBOX_PORT=8090` 之后、`# --- Tool Configuration (optional) ---` 之前）：**

```dotenv
# OPENSANDBOX_PORT=8090

# --- Container Runtime (optional) ---
# docker | podman. Empty = auto-detect (docker preferred, podman fallback).
# CONTAINER_RUNTIME=podman
# Host socket mounted into the OpenSandbox server for sandbox creation.
# podman rootless: /run/user/1000/podman/podman.sock ; podman rootful: /run/podman/podman.sock
# SANDBOX_SOCKET=

# --- Tool Configuration (optional) ---
```

- 插入位置与计划一致；`CONTAINER_RUNTIME` 供 Task 3/4 交付的 `Resolve-ContainerRuntime` / `resolve_container_runtime` 严格模式使用（与 start-backend/quickstart 已接线模式一致）。
- 均为注释行，默认不启用任何行为，对现有 docker 用户无影响。

## 验证结果

1. **compose 配置可解析**（本机 podman 5.8.3；`podman compose config` 为纯本地解析，不需要 podman machine 运行）：

   ```text
   $ podman compose config --quiet; echo "EXIT_CODE=$LASTEXITCODE"
   EXIT_CODE=0
   ```

   退出码 0、无配置输出。注：podman 5.x 会打印 `Executing external compose provider ...` 提示（本次解析由 Docker Desktop 的 docker-compose.exe provider 完成），属正常提示非错误。

2. **默认值生效（docker 用户零感知）**：

   ```text
   $ podman compose config | Select-String "docker.sock"
           source: /var/run/docker.sock
           target: /var/run/docker.sock
   ```

   `${SANDBOX_SOCKET:-/var/run/docker.sock}` 解析为 `/var/run/docker.sock`，默认路径不变。

3. **SANDBOX_SOCKET 覆盖生效（podman 场景）**：

   ```text
   $ $env:SANDBOX_SOCKET="/run/user/1000/podman/podman.sock"; podman compose config | Select-String "podman.sock"
           source: /run/user/1000/podman/podman.sock
   ```

   环境变量覆盖后 source 切换为 podman rootless socket，参数化真正生效（rootful `/run/podman/podman.sock` 同理）。

## 与计划原文的差异说明

无差异。`docker-compose.yml` 与 `.env.example` 原文均与计划一致，按计划原文执行。

## 提交

```text
41830ec feat(compose): parametrize OpenSandbox socket mount for podman
 2 files changed, 11 insertions(+), 2 deletions(-)
 Pre-commit Guardrail: [1/3] Format ✓ [2/3] TS skip ✓ [3/3] Sensitive scan ⚠ WARNING (.env.example / docker-compose.yml，含占位符无真实密钥，评审后允许提交)
```

报告提交：`docs(podman): task7 report`（hash 见 `git log --oneline -2`，本文件提交自身无法自引用）

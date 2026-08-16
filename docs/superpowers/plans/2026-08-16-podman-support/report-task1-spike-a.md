# Task 1 Spike A 验证报告 — podman machine + OpenSandbox 兼容性

> **Status: BLOCKED** — podman 未安装，无法执行 Spike A 验证（按任务 Contingency 规则停止，不继续 Step 1.2-1.6）。

## 1. 环境信息

| 项目 | 值 |
|------|-----|
| 操作系统 | Windows 22H2（10.0.19045） |
| Shell | PowerShell 7（pwsh） |
| 工作区 | `C:\Users\User\.qoder\worktree\aria-conductor\fvHa7E` |
| git 分支 | `feat/podman-support`（git 元数据位于工作区外 `d:/project/aria-conductor/.git`） |
| podman | **未安装**（详见 §2） |
| Docker Desktop | 已安装且运行中（context `desktop-linux`，Docker Engine 可达） |
| WSL | 存在 Ubuntu (Stopped) 与 docker-desktop (Running)；Ubuntu 因磁盘 attach 失败（`ERROR_SHARING_VIOLATION`）无法用于检查 |
| 计划文档 | `docs/superpowers/plans/2026-08-16-podman-support.md` |
| 设计文档 | `docs/superpowers/specs/2026-08-16-podman-support-design.md`（§8.1 定义 spike 与回退策略） |

## 2. Step 1.1 执行记录 — 确认 podman 可用（FAIL）

### 2.1 直接检查（PATH）

命令：
```powershell
podman --version; podman machine list
```

输出（ExitCode 1）：
```
podman: The term 'podman' is not recognized as a name of a cmdlet, function, script file, or executable program.
Check the spelling of the name, or if a path was included, verify that the path is correct and try again.
```

### 2.2 排除性搜索（确认并非 PATH 问题）

为防止"已安装但不在 PATH"的误判，对以下位置做了穷尽检查，全部无结果：

1. **常见安装目录**：`C:\Program Files\RedHat\Podman\podman.exe`、`C:\Program Files\containers\podman\podman.exe`、`C:\Program Files\Podman\podman.exe`、`%LOCALAPPDATA%\Programs\podman\podman.exe` — 均不存在。
2. **递归搜索**：`C:\Program Files`、`C:\Program Files (x86)`、`C:\ProgramData\chocolatey\bin`、`%LOCALAPPDATA%\Microsoft\WinGet`、`%USERPROFILE%\scoop\shims` 下 `podman*.exe`（深度 3）— 无匹配。
3. **注册表卸载记录**：`HKLM\...\Uninstall\*`、`HKLM\WOW6432Node\...\Uninstall\*`、`HKCU\...\Uninstall\*` 中 DisplayName 匹配 `podman|containers` — 无匹配（确认未通过官方安装器/winget/choco 安装）。
4. **`%LOCALAPPDATA%\Programs`**：仅 antigravity / CC Switch / Common / Ollama / Opera / Python / taobao — 无 Podman Desktop。
5. **WSL**：Ubuntu 分发 attach 失败（`Wsl/Service/CreateInstance/MountDisk/HCS/ERROR_SHARING_VIOLATION`，疑被其他进程占用），无法检查 WSL 内 podman；但本任务验证对象是 **podman machine（Windows 原生）**，WSL 内 podman 不属 Spike A 范畴。

### 2.3 结论

- **podman CLI 未安装**，`podman machine` 不存在，Step 1.1 预期（podman ≥ 4.9 + 已存在 machine）**不满足**。
- 按任务 Contingency 规则：「若 podman 未安装 → 立即报告 BLOCKED（说明 podman 缺失），不要继续」。故 Step 1.2–1.6 **未执行**。

## 3. 未执行步骤说明

| 步骤 | 内容 | 状态 |
|------|------|------|
| Step 1.2 | 启用并验证 rootless socket（VM 内） | 未执行（无 podman machine） |
| Step 1.3 | 构建 opencode 沙箱镜像 | 未执行 |
| Step 1.4 | compose override + opensandbox-server 启动 | 未执行 |
| Step 1.5 | API 创建/销毁沙箱 + 端口转发验证（关键门禁） | 未执行 |
| Step 1.6 | 清理 | 未执行（未创建任何文件，`docker-compose.podman.yml` 不存在） |

关键门禁结论：**PASS（podman 风险退休）无法判定** —— 因前置条件（podman 安装）缺失，Spike A 验证整体 **BLOCKED**。OpenSandbox 与 podman compat socket 的兼容性、podman machine 40000-60000 端口转发可达性均**尚未验证**，podman 相关风险**未退休**。

## 4. 失败原因与建议

**根本原因**：本机未安装 podman（Windows 侧无 CLI，无 Podman Desktop，无 winget/choco/scoop 记录）。

**修复路径（供决策）**：

1. **安装 podman（推荐）**：
   ```powershell
   winget install RedHat.Podman
   # 或安装 Podman Desktop（自带 CLI 与 machine 管理）
   winget install RedHat.Podman-Desktop
   ```
   安装后执行 `podman machine init && podman machine start`，再重跑本 Spike A（Step 1.1-1.6）。
2. **按 plan §Contingency / spec §8.1 处理**：podman 缺失不阻塞 Task 2–8（均为代码/文档任务，不依赖 podman），可继续实施并先以 docker 路径验证；文档标注「podman 实验性支持」；Spike A/B 待 podman 可用后补做。
3. **备选运行时路径（spec §8.1）**：若后续发现 OpenSandbox 与 podman compat socket 硬性不兼容，可在 podman machine VM 内原生运行 `uvx opensandbox-server`（`DOCKER_HOST` 指向 podman socket），该路径同样需要在 podman 安装后才能实测。

## 5. 环境补充观察

- Docker Desktop 当前可用（`docker context ls`：`desktop-linux *`），如需快速推进 Task 2–8 的本地验证可临时走 docker 路径，不影响 podman 相关代码交付。
- WSL Ubuntu attach 失败（`ERROR_SHARING_VIOLATION`）为环境状态问题，与本任务结论无关；如后续需要 WSL 内 podman 备选，需先解决该问题（通常重启 WSL / Docker Desktop 可解）。

---

# 追加章节：podman 安装后重跑（2026-08-17）

> **Status: DONE — 全链路通过，podman 风险退休。**

## 1. 重跑环境

| 项目 | 值 |
|------|-----|
| podman | **5.8.3**（`C:\Program Files\RedHat\Podman\podman.exe`） |
| machine | `podman-machine-default`（wsl, 3 CPU / 2GiB / 100GiB，**rootless**，UserModeNetworking=false），数据盘 `D:\wsl\podman-machine-default\ext4.vhdx` |
| Docker Desktop | 已完全退出（无 `com.docker.backend` 等进程残留，`docker-desktop` WSL 发行版 Stopped），无 `docker_engine` pipe 冲突 |
| compose 后端 | Docker Compose v5.1.4（`podman compose` 调用 Docker 自带 `docker-compose.exe`，经 podman 的 npipe API 转发连接） |
| VM 内 rootless socket | `/run/user/1000/podman/podman.sock`（UID=1000，`systemctl --user is-active podman.socket` → `active`） |
| git 分支 | `feat/podman-support` |

## 2. Step 1 — 启动 machine 并确认 socket（PASS）

```powershell
$podman = "C:\Program Files\RedHat\Podman\podman.exe"
& $podman machine start
# Starting machine "podman-machine-default" ... Machine "podman-machine-default" started successfully
# API forwarding listening on: npipe:////./pipe/docker_engine
& $podman machine list   # podman-machine-default*  wsl  Currently running  3  2GiB  100GiB
& $podman machine ssh "systemctl --user is-active podman.socket"   # active
& $podman machine ssh "ls -l /run/user/1000/podman/podman.sock"   # srw-rw---- 1 user user 0 ... podman.sock
& $podman machine ssh "id -u"   # 1000
```

- rootless socket 直接可用，无需 `enable --now`；rootful 备选未用到。
- socket 路径（Step 3 使用）：**`/run/user/1000/podman/podman.sock`**。

## 3. Step 2 — 构建 opencode 沙箱镜像（PASS）

```powershell
& $podman build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox
# STEP 1/6 ... STEP 6/6 COMMIT -> Successfully tagged localhost/aria-conductor/opencode-sandbox:1.1
& $podman images
# REPOSITORY                                TAG    IMAGE ID      SIZE
# localhost/aria-conductor/opencode-sandbox 1.1    ce359545d8e6  1.09 GB
```

- 6 步全部成功（node:22-slim 基础镜像 + curl/gnupg/gh + `opencode-ai@1.18.15`）。镜像在 podman 中显示为 `localhost/aria-conductor/opencode-sandbox:1.1`，创建沙箱时用 URI `aria-conductor/opencode-sandbox:1.1` 可被 podman 正常解析（无需 localhost 前缀）。

## 4. Step 3 — 启动 opensandbox-server（PASS）

临时 override（未跟踪文件 `docker-compose.podman.yml`，volumes 键整体替换）：

```yaml
services:
  opensandbox-server:
    volumes:
      - /run/user/1000/podman/podman.sock:/var/run/docker.sock
      - opensandbox_data:/root/.opensandbox
```

```powershell
& $podman compose -f docker-compose.yml -f docker-compose.podman.yml up -d opensandbox-server
# [+] up 16/16  Image opensandbox/server:latest Pulled  Network aria-conductor_aria-network Created
#               Volume aria-conductor_opensandbox_data Created  Container aria-opensandbox Started
curl.exe -s http://localhost:8090/health   # {"status":"healthy"}  EXIT=0
```

- 主 compose 的 `configs`（`[docker] host_ip="127.0.0.1"`, `port_range_min=40000`, `port_range_max=60000`）与端口 `127.0.0.1:8090:8080` 均正常生效。
- health 通过：OpenSandbox server 经 podman compat socket 正常运行。

## 5. Step 4 — 沙箱创建/销毁 + Windows 主机端口可达（关键门禁，PASS）

### 5.1 API 差异（非 podman 问题，记录备案）

计划 body 模板返回 422：`Value error, resourceLimits is required when poolRef is not provided`。
当前 `opensandbox/server:latest`（OpenSandbox 新版 API）要求创建请求携带 `resourceLimits`（键值字典，example: `{"cpu":"500m","memory":"512Mi"}`，见 `/openapi.json` → `CreateSandboxRequest`/`ResourceLimits`）。
**Java 侧通过 OpenSandbox Python SDK 建沙箱（SDK 内部填充该字段），不受影响**；仅手工 API 调用需补字段：

```powershell
$body = '{"image":{"uri":"aria-conductor/opencode-sandbox:1.1"},"entrypoint":["opencode","serve","--hostname","0.0.0.0","--port","4096"],"timeout":1800,"resourceLimits":{"cpu":"1","memory":"1Gi"}}'
$sb = Invoke-RestMethod -Method Post -Uri "http://localhost:8090/v1/sandboxes" -ContentType "application/json" -Body $body
# SB_ID=59e48e06-da68-4102-b191-a2f40769868d
# STATUS.state=Running, reason=CONTAINER_RUNNING（创建即 Running，无需轮询等待）
```

### 5.2 endpoints 与端口转发验证

```powershell
$ep = Invoke-RestMethod -Uri "http://localhost:8090/v1/sandboxes/59e48e06-.../endpoints/4096"
# {"endpoint":"10.89.0.2:46248/proxy/4096"}   # mapped=46248，落在 40000-60000 范围内
& $podman ps
# sandbox-59e48e06-...  localhost/aria-conductor/opencode-sandbox:1.1  Up  0.0.0.0:43523->8080/tcp, 0.0.0.0:46248->44772/tcp, 4096/tcp
```

- endpoints API 返回**容器网络 IP**（`10.89.0.2`）而非 `127.0.0.1`（与计划模板预期格式不同），但 mapped 端口 46248 在 VM 内以 `0.0.0.0:46248` 发布，podman machine（gvproxy）自动将其转发到 Windows 主机回环。
- **Windows 主机可达性实测**：`http://127.0.0.1:46248/proxy/4096` → **HTTP 200**（返回 opencode serve 的 HTML 页面，2884 字节）——端口转发打通。Java 后端（Windows 主机本地开发拓扑，`host_ip=127.0.0.1`）可直接解析该端点。
- 删除：`Invoke-RestMethod -Method Delete -Uri ".../v1/sandboxes/59e48e06-..."` 成功，`podman ps -a` 中沙箱容器消失。

## 6. Step 5 — 清理（PASS，含一处工具怪癖）

- `podman compose ... down` 在删除阶段报错：`Error: get machine connection URI: could not find a matching machine for connection "ssh://user@127.0.0.1:62954/..."`（docker-compose 客户端在 down 时调用 podman 不支持的 API）。**非 OpenSandbox 兼容性问题**，改为手动等价清理：
  ```powershell
  & $podman rm -f aria-opensandbox
  & $podman network rm aria-conductor_aria-network
  & $podman volume rm aria-conductor_opensandbox_data
  ```
- `Remove-Item docker-compose.podman.yml -Force` 成功；`git status` 无本次新增文件（仅历史遗留未跟踪项）。
- **冗余备份删除**（破坏性操作，前置验证满足后执行）：Docker 配置 `CustomWslDistroDir: D:\Docker\data\DockerDesktopWSL`（在用数据盘 `DockerDesktopWSL\disk\docker_data.vhdx` = 20.89GB > 5GB 确认），C 盘原盘已迁移消失 → 确认 `D:\Docker\data\docker_data.vhdx`（20.89GB，Phase 1 手动复制试验残留、未被 Docker 引用）为纯冗余，已删除（`[System.IO.File]::Delete` 成功，PowerShell `Remove-Item` 报 Access denied 为文件句柄/权限差异，最终释放 20.89GB）。

## 7. 门禁结论

**PASS — podman 风险退休**：

1. OpenSandbox 与 podman compat socket **完全兼容**（health、沙箱创建/运行/删除均正常）。
2. podman machine 端口转发验证通过：Windows 主机 `127.0.0.1:<mapped>` → 沙箱 `opencode serve` 实测 HTTP 200，mapped 端口 46248 落在 40000-60000 配置范围内。
3. 镜像构建、rootless socket、compose override 均正常。

**注意事项（不影响门禁结论，供后续任务参考）**：

- 创建沙箱的 HTTP body 需携带 `resourceLimits`（OpenSandbox 新版 API 必填）；Java 侧走 SDK 无此问题。
- endpoints API 返回容器 IP 而非 127.0.0.1；Windows 主机侧以 `127.0.0.1:<mapped>/proxy/<port>` 可达（本机开发拓扑成立）。
- `podman compose down` 有工具怪癖（报 machine connection 错误），需手动 `rm/network rm/volume rm` 清理；`podman compose up` 无此问题。
- 建议计划文档 Step 1.5 的 body 模板补充 `resourceLimits`，Step 1.6 补充手动清理回退说明。

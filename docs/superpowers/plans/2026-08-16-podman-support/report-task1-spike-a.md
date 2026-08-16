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

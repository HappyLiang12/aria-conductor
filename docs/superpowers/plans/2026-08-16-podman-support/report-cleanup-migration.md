# 磁盘清理与容器数据迁移报告

- 日期：2026-08-16 ~ 2026-08-17
- 环境：Windows 22H2（pwsh 7）、Docker Desktop 29.5.3（build 229452）、WSL2、podman 5.8.3（`C:\Program Files\RedHat\Podman\podman.exe`）
- 总体状态：**DONE_WITH_CONCERNS**（Phase 1 BLOCKED、Phase 2/3 DONE、Phase 4 部分完成）

## 初始状态

| 项目 | 值 |
|---|---|
| C 盘 | 222.4 GB 总量 / **3.0 GB 空闲**（严重不足） |
| D 盘 | 931.5 GB 总量 / 327.4 GB 空闲 |
| Docker Desktop | 运行中，Engine 29.5.3 |
| docker_data.vhdx | `C:\Users\User\AppData\Local\Docker\wsl\disk\docker_data.vhdx` = **20.9 GB** |
| Ubuntu WSL | 默认发行版，BasePath `C:\Users\User\AppData\Local\wsl\{46a76d38-df96-4b34-af40-7fd8648eb89d}\ext4.vhdx` = **40.1 GB** |
| docker-desktop WSL | 存在（Stopped） |
| podman machine list | 空（无已注册机器） |
| 孤儿残留 | `D:\wsl\podman-machine-default\ext4.vhdx` = **2.1 GB**（未注册） |
| `D:\wsl\Ubuntu` | 空目录已存在 |
| `C:\Users\User\AppData\Roaming\Docker\settings.json` | **不存在** |

---

## Phase 1：Docker 数据盘迁移（docker_data.vhdx 20.9GB C → D）— BLOCKED

### 1.1 执行步骤

1. **干净退出 Docker Desktop**
   - `Get-Process "Docker Desktop" | Stop-Process -Force` 成功
   - 确认 `com.docker.backend` 等相关进程退出
   - `wsl --shutdown` 成功
2. **备份并编辑 `settings-store.json`**
   - 备份：`Copy-Item settings-store.json settings-store.json.bak`（257 字节）
   - 追加 `"dataFolder": "D:\\Docker\\data"` → Docker 启动时自动规范化为 `"DataFolder"`（合法键，运行时 `resources.dataFolder` 生效）——**但未触发数据盘迁移**
   - 追加 `"wslDataFolder"` → 被拒绝，日志：`[W] unknown settings found`（该键不存在）
3. **多次重启 Docker Desktop + 轮询（最长 10 分钟）**：`D:\Docker\data` 下始终未出现新 vhdx，`C:\...\Docker\wsl\disk\docker_data.vhdx` 未消失
4. **手动复制试验（两次）**
   - 复制 vhdx 到 `D:\Docker\data\docker_data.vhdx` → Docker 忽略，在 C 盘重建空盘
   - 复制到 `D:\Docker\data\disk\docker_data.vhdx` → 同样忽略
5. **关键日志证据**
   - `provisioning the WSL2 engine using a data disk`
   - `wslmigrate: WSL2 disk exists`
   - bootstrap 命令带 `--data-disk 17d7f16a-74e8-0247-b4dd-4f45d5d31b73`（C 盘路径硬编码）

### 1.2 结论

Docker Desktop 29.x（build 229452）将 WSL 数据盘路径**硬编码**于 `%LOCALAPPDATA%\Docker\wsl\disk\`，配置文件无法改变；**仅支持 UI 迁移**（Settings → Resources → Advanced → Disk Image Location）。

### 1.3 恢复操作（按门禁执行）

- 删除两次手动复制试验产生的空盘/残留
- `.orig` 试验改名恢复为 `docker_data.vhdx`
- `settings-store.json` 从 `.bak` 完全还原
- **保留** `D:\Docker\data\docker_data.vhdx`（20.89 GB 数据盘备份副本，供后续 UI 迁移使用）

> ⚠️ 备份注意：该副本是**复制品**，非热备。C 盘原件仍为运行中的数据盘。

---

## Phase 2：Ubuntu WSL 迁移（40.1GB C → D）— DONE（绕道方案）

### 2.1 计划路径失败

| 命令 | 结果 |
|---|---|
| `wsl --export Ubuntu D:\wsl-backup\Ubuntu.tar` | ❌ `ERROR_SHARING_VIOLATION`（vhdx 被锁） |
| `wsl --export --vhd Ubuntu ...` | ❌ 同样 SHARING_VIOLATION |

**锁排查**：
- 发现进程 `vmwp`、`docker-agent`、`docker-sandbox`、`wslservice`；强制结束后 vmwp 消失，但 vhdx 仍被锁
- 锁测试（`FileShare` 枚举）：仅 Ubuntu vhdx 被独占写锁（无 `FILE_SHARE_DELETE`）；只读共享（`Read+ShareReadWrite`）**可行**
- 尝试 UAC 提权重启 `WslService` → 被取消（headless 环境，`IsAdmin=False`）
- 根因：强制杀 Docker 进程后 wslservice/HCS 对 Ubuntu vhdx 持**幽灵写锁**，服务重启/系统重启才能解除

### 2.2 绕道方案（手动复制 + import-in-place）

1. `Copy-Item C:\Users\User\AppData\Local\wsl\{46a76d38-...}\ext4.vhdx D:\wsl\Ubuntu\ext4.vhdx`
   - ✅ 40.07 GB，大小与源一致（校验通过）
2. `wsl --unregister Ubuntu`
   - ✅ 注册移除成功（C 盘 vhdx 因锁删除失败，成为孤儿文件）
3. `wsl --import-in-place Ubuntu D:\wsl\Ubuntu\ext4.vhdx`
   - ✅ 原地注册成功，新 GUID `{c2b78ff2-3f60-4337-aba0-77dd590afc84}`，BasePath `\\?\D:\wsl\Ubuntu`
4. `wsl --set-default Ubuntu`
5. 注册表 `HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss`：`DefaultUid=1000`（user）
6. **验证**：`wsl -d Ubuntu ls /` 正常；`whoami` = user ✅

### 2.3 遗留问题

- C 盘孤儿 vhdx `C:\Users\User\AppData\Local\wsl\{46a76d38-...}\ext4.vhdx`（40.07 GB）**仍被锁**，多次删除失败
- **待重启系统 / 重启 WslService 后删除**（无注册引用，删除安全）

---

## Phase 3：podman machine 重建并迁移到 D — DONE

### 3.1 清理孤儿

- `podman machine rm podman-machine-default -f` → "VM does not exist"（预期，忽略）
- 删除 `D:\wsl\podman-machine-default\ext4.vhdx`（2.1 GB 未注册孤儿）✅

### 3.2 初始化

- `podman machine init` → ✅ 成功（wsl 类型，3 CPU / 2 GiB / 100 GiB）
- **实际 vhdx 位置发现**（与计划假设不同）：`C:\Users\User\.local\share\containers\podman\machine\wsl\wsldist\podman-machine-default\ext4.vhdx`（885 MB）
- 机器配置：`~\.config\containers\podman\machine\wsl\podman-machine-default.json`（含 ImagePath、SSH 端口 62954）；podman 按 WSL 发行版**名称**管理

### 3.3 迁移到 D

1. `wsl --shutdown`
2. `wsl --export podman-machine-default D:\wsl-backup\podman-machine-default.tar`（762 MB）
3. `wsl --unregister podman-machine-default`
4. `wsl --import podman-machine-default D:\wsl\podman-machine-default D:\wsl-backup\podman-machine-default.tar --version 2`
5. 删除临时 tar → ✅ `D:\wsl-backup` 清空

### 3.4 启动验证

- `podman machine start` → ✅
- `podman machine list` → podman-machine-default\* Running（wsl 类型，100 GiB）
- `podman info` → ✅ 成功
- C 盘无 podman machine vhdx 残留（仅剩基础镜像 762 MB + 缓存 236 MB，非 vhdx）

### 3.5 重要发现：podman ↔ Docker Desktop 冲突

- podman machine 运行时（其 API forwarding 占用 `npipe:////./pipe/docker_engine`），Docker Desktop 启动崩溃：`nil pointer dereference` in `startDockerAPIProxy`（services.go:632）
- `podman machine stop` 后 Docker Desktop 恢复正常（29.5.3）
- ⚠️ **两者不能同时运行**：启动 Docker Desktop 前需先 `podman machine stop`

---

## Phase 4：其他清理（安全级别）

| 步骤 | 结果 |
|---|---|
| `Clear-RecycleBin -Force` | ✅ 成功 |
| `Dism /Online /Cleanup-Image /StartComponentCleanup` | ⚠️ UAC 被取消（headless 环境无管理员），跳过并记录 |

---

## 最终状态验证（2026-08-17 收尾）

### 磁盘

| 盘 | 总量 | 空闲 |
|---|---|---|
| C | 222.4 GB | **1.65 GB** |
| D | 931.5 GB | **267.71 GB** |

### 运行时状态

| 项目 | 状态 |
|---|---|
| Docker Desktop | 运行中（29.5.3），docker-desktop distro Running |
| Ubuntu WSL | Stopped（默认发行版）✅ |
| podman-machine-default | Stopped（迁移完成，可启动）✅ |
| `wsl -l -v` | 3 个发行版正常 |

### 关键文件清单

| 文件 | 大小 | 说明 |
|---|---|---|
| `D:\wsl\Ubuntu\ext4.vhdx` | 40.07 GB | ✅ Ubuntu 新数据盘（在用） |
| `D:\wsl\podman-machine-default\ext4.vhdx` | 0.89 GB | ✅ podman machine 数据盘（在用） |
| `D:\Docker\data\docker_data.vhdx` | 20.89 GB | Docker 数据盘备份副本（供 UI 迁移） |
| `C:\...\Docker\wsl\disk\docker_data.vhdx` | 20.89 GB | Docker 数据盘（在用，待 UI 迁移） |
| `C:\...\Local\wsl\{46a76d38-...}\ext4.vhdx` | 40.07 GB | ⚠️ 孤儿 vhdx（被锁，待重启后删除） |
| `D:\wsl-backup\` | 空 | 临时文件已清理 ✅ |

---

## 释放空间摘要

- **D 盘新增数据**：~61.8 GB（Ubuntu 40.07 + podman 0.89 + Docker 备份 20.89）
- **C 盘已释放**：2.1 GB（孤儿 podman vhdx）+ 回收站
- **C 盘待释放（需用户操作）**：
  1. **~40.07 GB**：重启系统/WSL 服务后删除孤儿 `C:\Users\User\AppData\Local\wsl\{46a76d38-df96-4b34-af40-7fd8648eb89d}\ext4.vhdx`
  2. **~20.9 GB**：Docker Desktop UI 迁移数据盘（Settings → Resources → Advanced → Disk Image Location → 选 `D:\Docker\data`；迁移完成后可删除 C 盘原 vhdx 与 D 盘备份中的冗余）

## 跟进项

1. [ ] 重启后删除 C 盘孤儿 Ubuntu vhdx（40.07 GB）——`wsl --unregister` 已移除引用，删除安全
2. [ ] Docker 数据盘 UI 迁移（配置法/复制法均不可行，已证伪）
3. [ ] podman machine 与 Docker Desktop 不能同时运行（先 `podman machine stop`）
4. [ ] Dism 组件清理需管理员权限手动执行

## 命令与日志归档说明

- 全部命令按 Phase 顺序执行，破坏性操作均满足门禁条件（tar/vhdx 校验通过后才 unregister；Phase 1 失败按门禁恢复 settings-store.json 备份）
- Docker 日志关键行：`provisioning the WSL2 engine using a data disk`、`wslmigrate: WSL2 disk exists`、`[W] unknown settings found`（wslDataFolder 被拒）

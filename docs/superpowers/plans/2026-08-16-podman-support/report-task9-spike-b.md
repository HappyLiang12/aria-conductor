# Task 9 过程记录：Spike B — podman 全链路真实 E2E + 证据归档

- 分支：`feat/podman-support`
- 日期：2026-08-17
- 最终结论：**DONE_WITH_CONCERNS**（详见根目录 `report-podman-e2e.md`）

> 本文是过程记录（含原始输出与时间线），结论性内容见 `report-podman-e2e.md`。

## 0. 任务口径

- Step 9.1 Phase B1：quickstart 全栈（auto-detect 路径）
- Step 9.2 Phase B2：local-dev 拓扑 + 显式配置路径（真实 LLM）
- Step 9.3 Phase B3：Playwright opencode 套件
- Step 9.4：证据归档（report-podman-e2e.md + 本文件 + 截图 + commit）
- Step 9.5：清理

## 1. Phase B1（auto-detect 全栈）— 通过

- `.env` 临时注释 `CONTAINER_RUNTIME`，保留 `SANDBOX_SOCKET` 语义（未显式设置，由默认 `/var/run/docker.sock` 生效）
- `podman machine start` → machine 就绪（rootless，socket `unix:///run/user/1000/podman/podman.sock`）
- `pwsh -NoProfile -File scripts/quickstart.ps1`：
  - 输出 `Container runtime: podman (auto-detected)`
  - 5 服务容器在列（mariadb/backend/frontend/adk/opensandbox）
  - `curl http://localhost:8090/health` → `{"status":"healthy"}`；`http://localhost:8080/actuator/health` → UP
- **阻断与绕过**：podman-compose 不支持 `configs:` 段 + socket 路径解析缺陷 → opensandbox-server 用 `podman run` 直启（挂载 WSL2 内 `/var/run/docker.sock` + `opensandbox-config.toml` + `OPENSANDBOX_INSECURE_SERVER=YES`）
- 验证后清理：`podman compose down` 报 machine connection 错误（Spike A 已知怪癖）→ 手动 `podman rm -f` + `network rm` + `volume rm`

## 2. Phase B2（explicit 路径 + 真实 LLM）— 通过（核心链路）

- 恢复 `.env` `CONTAINER_RUNTIME=podman`
- 启动后端：`Start-Process pwsh -ArgumentList "-NoProfile","-File","scripts/start-backend.ps1","-AdkProvider","opencode" -NoNewWindow`
  - 中途 java.exe "Access is denied"（Bash 工具展开 `$env:Path` 导致子进程 PATH 损坏）→ 修复：写 `launch-b2-backend.ps1`（内含 `$env:Path += ";D:\tools\apache-maven-3.9.16\bin"` + DEEPSEEK_API_KEY 注入），`pwsh -File` 执行
  - preflight 输出：`Container runtime: podman (explicit: CONTAINER_RUNTIME)`
- 启动前端 `start-frontend.ps1`（修复其 ProjectRoot 双层 Split-Path bug 后成功，Vite 5173）
- 后端就绪：轮询 8080 actuator/health → UP
- `GET /api/v1/adk/providers/opencode/health` → healthy
- LLM provider：GET /api/v1/llm-providers 已有 active DeepSeek（defaultModel=deepseek-v4-flash）→ 跳过创建（符合任务规则）
- 首次 Playwright 运行因 opencode 为默认 provider 与测试 L233 硬编码冲突 → 以默认 langchain 重启后端（B2 explicit 证据已留存），供 B3 重跑

## 3. Phase B3：Playwright opencode 套件 — 三次运行，最终定位

### 运行 1（L233 失败，15.5s）— 测试环境配置冲突

- 断言：langchain 行缺 `Default` 标记；后端以 `-AdkProvider opencode` 启动 → opencode.isDefault=true（API 实证）
- 归类：测试硬编码假设，非 podman 缺陷 → 以默认 langchain 重启后端重跑

### 运行 2（L358 失败，50.3s）— podman 集成真缺陷，本 Spike 内修复

- run FAILED，`errorMessage="Workspace upload failed: Request timed out"` 不匹配 /sandbox|serve|OpenSandbox/
- 证据链：沙箱创建成功（02673e41，2.6s）→ SDK `POST http://10.89.0.3:55629/proxy/44772/files/upload` 超时（10.89.0.3=沙箱容器 bridge IP，Windows 不可达）
- 根因（深挖 SDK 1.0.18 源码 + server v0.1.0 容器内源码）：
  - SDK `FilesystemAdapter.write()` 使用 `execdEndpoint`（`GET /v1/sandboxes/{id}/endpoints/{port}?use_server_proxy=false`）
  - server v0.1.0 `_resolve_public_host()`：`[server].host=0.0.0.0` → `_resolve_bind_ip()`（UDP connect 8.8.8.8 的 getsockname）→ 返回容器 IP
  - **`DockerConfig` 类无 `host_ip` 字段**，`[docker].host_ip=127.0.0.1` 配置完全无效（项目代码注释假设其生效是错误假设，Docker 下同样失败）
- 实验 1（失败）：`[server].host=127.0.0.1` → server 监听容器内 127.0.0.1，podman rootless port-forward 目标被拒 → 8090 不可达 → 回滚
- **修复**：patch server 镜像 `aria-conductor/opensandbox-server:0.1.0-podman`（config.py `DockerConfig.host_ip` 字段 + docker.py `_resolve_public_host` 优先返回 host_ip），重启 aria-opensandbox → health OK

### 运行 3（L360 失败，29.7s）— 外部 LLM 账户余额（最终归类：非 podman/代码缺陷）

- run **COMPLETED**（patch 生效：沙箱就绪 URL `http://127.0.0.1:53903/proxy/4096`）但 `finalOutput=null`
- 后端日志：`OpenCode task finished (0 input / 0 output tokens)`，3.7s；`Completing run: status=COMPLETED, iterations=1, tokens=0`；`final turnCount=0`
- 手动复现（直连 serve v1.18.15，`POST /session` + `POST /session/:id/message`）：
  ```json
  "error": { "name": "APIError", "data": { "message": "Insufficient Balance",
  "statusCode": 402, "metadata": { "url": "https://api.deepseek.com/chat/completions" } } }
  ```
- 佐证：容器内 `LLM_API_KEY`/`DEEPSEEK_API_KEY` 均已注入；opencode.json 正确（provider=deepseek, model=deepseek-v4-flash, baseURL=https://api.deepseek.com）；402 响应含 DeepSeek 网关特征（CloudFront、x-ds-trace-id）
- 结论：podman 全链路（沙箱创建→workspace 上传→serve→会话→LLM 出网调用）全部实证；失败点为 DeepSeek 账户余额（外部环境）

## 4. 证据归档

- `report-podman-e2e.md`（仓库根，结论性报告）
- 截图 4 张 → `e2e/screenshots/podman/`（overview/runs-list/run-details/playwright-failed）
- 本过程记录
- 临时脚本（launch-*.ps1、diag-*.ps1、check-*.ps1、collect-shots.ps1、gather-env.ps1 等）与 `opensandbox-server-src/`（patch 源码）+ `Dockerfile.podman` 保留在工作区供审查；`*.zip/trace` 未归档（体积大）

## 5. 提交

```text
docs(podman): spike B evidence - podman full-chain E2E
（含 report-podman-e2e.md + e2e/screenshots/podman/）
```

## 6. 遗留（下一步建议）

1. DeepSeek 账户充值后重跑 `opencode-adk-e2e.spec.ts`（预期全绿）
2. patch 镜像 `0.1.0-podman` 的 host_ip 修复建议回传 opensandbox upstream 或固化进交付镜像 tag
3. `.env` 补齐 `SANDBOX_SOCKET=/run/user/1000/podman/podman.sock` 显式化（当前靠默认路径工作）

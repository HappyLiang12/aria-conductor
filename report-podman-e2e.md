# Spike B 报告：podman 全链路真实 E2E 验证（Task 9）

- 分支：`feat/podman-support`
- 日期：2026-08-17
- 验证人：Aria Conductor agent（Task 9 Spike B）
- 门禁结论：**DONE_WITH_CONCERNS**（全链路证明 podman 集成可用；Playwright 最后一项断言失败，根因定位为外部 LLM 账户余额不足——非 podman/代码缺陷）

## 1. 环境

| 项 | 值 |
|----|----|
| podman client | 5.8.3（`C:\Program Files\RedHat\Podman\podman.exe`，windows/amd64） |
| podman server | 5.8.6（linux/amd64，WSL2 VM） |
| machine | `podman-machine-default`，wsl 类型，3 CPU / 2GiB / 100GiB，rootless=true，cgroup v2 |
| socket 路径 | `unix:///run/user/1000/podman/podman.sock`（rootless；WSL2 内 `/var/run/docker.sock` symlink 指向同一 socket，供 OpenSandbox server 挂载） |
| Docker Desktop | 已退出（podman 与 Docker 不可同时运行，Spike A 已实证） |
| `.env` 配置 | `CONTAINER_RUNTIME=podman`（显式）、`LLM_API_KEY=sk-4130a4****`、`LLM_BASE_URL=https://api.deepseek.com`、`LLM_MODEL=deepseek-v4-flash`、DB_* 四件套 |
| `.env` 缺失项 | `DEEPSEEK_API_KEY`、`GH_TOKEN`、`SANDBOX_SOCKET` 未设置（LLM 调用路径由 `LLM_API_KEY` 覆盖；`SANDBOX_SOCKET` 由 compose 默认 `/var/run/docker.sock`，WSL2 内指向 podman socket，实际可用——已实证） |
| 沙箱镜像 | `localhost/aria-conductor/opencode-sandbox:1.1`（Spike A 构建，1.09 GB） |
| opensandbox server 镜像 | `localhost/aria-conductor/opensandbox-server:0.1.0-podman`（Spike B 修补镜像，见 §6.1） |
| 后端 | Java 21（jdk-21.0.11），Maven 3.9.16（D:\tools\apache-maven-3.9.16），`scripts/start-backend.ps1` |
| 前端 | Vite（`act-dashboard`，node 22，pnpm），`scripts/start-frontend.ps1` |

## 2. Phase B1：quickstart 全栈（auto-detect 路径）

**执行**：临时注释 `.env` 中 `CONTAINER_RUNTIME`（验证自动探测）→ `podman machine start` → `pwsh -NoProfile -File scripts/quickstart.ps1`（全栈 compose：mariadb/backend/frontend/adk/opensandbox）。

**结果**：

| 检查点 | 结果 |
|--------|------|
| quickstart 输出 | `Container runtime: podman (auto-detected)` ✅（含 docker 探测 fallback 逻辑生效） |
| `podman ps` | 5 容器在列：aria-mariadb（healthy）、aria-backend、aria-frontend、aria-langchain-adk、aria-opensandbox ✅ |
| `curl -s http://localhost:8090/health` | `{"status":"healthy"}` ✅ |
| `curl -s http://localhost:8080/actuator/health` | `UP` ✅ |

**compose down 怪癖**（Spike A 已知，再次实证）：`podman compose down` 偶发报 machine connection 错误；手动 `podman rm -f` 各容器 + `podman network rm` + `podman volume rm` 完成清理。

**B1 期间阻断项**（已绕过）：podman-compose 的 `configs:` 段不支持 + socket 路径解析缺陷，opensandbox-server 改用 `podman run` 直启（`-v /var/run/docker.sock:/var/run/docker.sock` + 挂载 `opensandbox-config.toml` + `OPENSANDBOX_INSECURE_SERVER=YES`）。该限制与 compose 文件缺陷归入遗留限制（§6.4）。

## 3. Phase B2：local-dev 拓扑 + 显式配置路径

**执行**：恢复 `.env` `CONTAINER_RUNTIME=podman` → 后台启动 `scripts/start-backend.ps1 -AdkProvider opencode` + `scripts/start-frontend.ps1`。

**结果**：

| 检查点 | 结果 |
|--------|------|
| start-backend preflight | `Container runtime: podman (explicit: CONTAINER_RUNTIME)` ✅ |
| 后端就绪 | 轮询 `http://localhost:8080/actuator/health` → UP（首次 mvn install 耗时较长） ✅ |
| opencode provider 健康 | `GET /api/v1/adk/providers/opencode/health` → healthy ✅ |
| LLM provider | 已有 `DeepSeek`（type=OPENAI, baseUrl=`https://api.deepseek.com`, defaultModel=`deepseek-v4-flash`, **active=true**, apiKeyMasked=`****c230`）→ 按任务规则跳过重复创建 ✅ |
| 沙箱生命周期 | 自动创建 sandbox 容器（`sandbox-165e692d-…`，`opencode-sandbox:1.1`），serve v1.18.15 健康 ✅ |

## 4. Phase B3：Playwright opencode 套件（podman 栈）

命令：`cd agent-control-tower/act-dashboard && npx playwright test opencode-adk-e2e.spec.ts`（node_modules 已由 `pnpm install` 就绪）。

**三次运行明细**：

| # | 结果 | 耗时 | 失败断言 | 根因 | 归类 |
|---|------|------|----------|------|------|
| 1 | FAILED | 15.5s | L233 langchain 行缺 `Default` 标记 | 测试硬编码假设 langchain 是默认 provider；本次以 `-AdkProvider opencode` 启动使 opencode 为默认（API 已实证 isDefault=true） | 测试环境配置冲突（非 podman） |
| 2 | FAILED | 50.3s | L358 run FAILED，`errorMessage="Workspace upload failed: Request timed out"` 不匹配 /sandbox\|serve\|OpenSandbox/ | **podman 集成缺陷（真缺陷）**：server v0.1.0 `_resolve_public_host` 返回沙箱容器 bridge IP（10.89.0.3），Windows 主机不可达 → SDK upload 超时 | podman 缺陷 → 已修复（§6.1） |
| 3 | FAILED | 29.7s | L360 COMPLETED run 必须非空 finalOutput（received null） | **DeepSeek API 返回 `402 Insufficient Balance`**（账户余额不足）：opencode 成功调用 `https://api.deepseek.com/chat/completions`，错误在 message `info.error` 中，`parts` 为空 → finalOutput 空 | 外部 LLM 账户环境（非 podman/代码） |

**第 3 次运行证据链**（run `d477f902-7379-48d0-ae1c-4b4179e81108`，agent `08007d2b-…`）：

1. `11:42:15.461` Run created → `11:42:15.506` task path provider=opencode
2. `11:42:18.665` `Wrote opencode.json (provider=deepseek, model=deepseek-v4-flash)`
3. `11:42:27.989` 沙箱就绪 **`http://127.0.0.1:53903/proxy/4096`**（patch 生效：endpoint host=127.0.0.1，Windows 可达）✅
4. `11:42:28.448` OpenCode task started（session `ses_ff22fb9b3ffe2h6ZUQnrmrFduj`）
5. `11:42:32.138` OpenCode task finished（**0 input / 0 output tokens**）
6. `11:42:32.145` Completing run: **status=COMPLETED, iterations=1, tokens=0**
7. `11:42:32.148` SessionStateManager: final turnCount=0 → trajectory 无 assistant 消息 → finalOutput=null

**手动复现确认**（对 live sandbox 直连 serve，`POST /session` + `POST /session/:id/message`）：

```json
"error": {
  "name": "APIError",
  "data": {
    "message": "Insufficient Balance",
    "statusCode": 402,
    "metadata": { "url": "https://api.deepseek.com/chat/completions" }
  }
}
```

响应来自 DeepSeek 网关（CloudFront/ELB + `x-ds-trace-id`），证明：沙箱容器出网 ✅、`LLM_API_KEY` 注入 ✅（容器内 `echo ${LLM_API_KEY:+set}` → set）、opencode.json provider/model 解析 ✅、请求到达 DeepSeek 并被余额检查拦截 ✅。**失败点 100% 在 LLM 账户余额，podman 链路上无任何故障。**

## 5. 证据截图清单（`e2e/screenshots/podman/`）

| 文件 | 内容 |
|------|------|
| `dashboard-overview-run-completed.png` | Overview 活动时间线显示 Run d477f902 · COMPLETE（COMPLETED） |
| `runs-list-completed-d477f902.png` | Runs 列表：d477f902 COMPLETED（1/1 iterations, 0 tokens, 16s） |
| `run-d477f902-details-completed.png` | Run 详情：COMPLETED + trajectory 仅 user 消息（无 assistant → finalOutput=null 的可视化印证） |
| `opencode-adk-e2e-OpenCode--dda9f-ntime-switch-_-run-_-verify-chromium-test-failed-1.png` | Playwright 失败时页面截图（L360 断言） |

## 6. 遗留限制与发现

### 6.1 上游缺陷（已在本 Spike 中 patch，未回传上游）

opensandbox/server **v0.1.0**：`DockerConfig` 类**没有 `host_ip` 字段**（配置注释假设存在但代码不读）；`[server].host=0.0.0.0` 时 `_resolve_public_host()` 调 `_resolve_bind_ip()`（UDP connect 8.8.8.8 的 getsockname）返回**沙箱容器 bridge IP**——后端运行在容器运行时主机（Windows local-dev）时该 IP 不可达，Docker 下同样失败（本项目历史无 Docker 真实 COMPLETED 记录佐证）。

**Spike B 修补**：`opensandbox-server-src/`（自容器拷贝的 v0.1.0 源码）+ `Dockerfile.podman` → 构建 `aria-conductor/opensandbox-server:0.1.0-podman`：`DockerConfig` 增加 `host_ip: Optional[str]` 字段，`_resolve_public_host()` 优先返回 `[docker].host_ip`。产物镜像已在 podman 镜像库（164 MB）。

### 6.2 podman rootless 端口转发怪癖

podman rootless port-forward 的目标是**容器 IP**（非 localhost）：opensandbox server 监听容器内 `127.0.0.1` 时（实验1：`[server].host=127.0.0.1`）Windows 侧 8090 不可达（实测 000）。**结论：server 必须 `host="0.0.0.0"` + `[docker].host_ip="127.0.0.1"` 组合**——patch 镜像已按此配置（`agent-control-tower/opensandbox-config.toml` 含注释说明）。

### 6.3 podman compose 限制

- `podman compose down` 偶发报 machine connection 错误 → 手动 `podman rm -f`/`network rm`/`volume rm` 兜底（Spike A 已知，再次实证）。**本次清理实证根因线索**：podman compose 调用了外部 compose provider `C:\Program Files\Docker\Docker\resources\bin\docker-compose.exe`（Docker Desktop 的 docker-compose，即使 Docker Desktop 已退出仍被 podman 配置为 external provider）——该 exe 连接不到 podman machine 的 socket 即报错。规避：用 `podman compose` 自带 provider 或直接 `podman rm -f` 清理。
- compose `configs:` 段与 socket 路径解析在 podman-compose 下不可用 → 全栈路径以 `podman run` 直启 opensandbox-server 兜底（B1 期间曾临时把 compose 改为 bind-mount + 硬编码 socket，验证后已回滚，保持 Task 7 的 `SANDBOX_SOCKET` 参数化交付形态不变）

### 6.4 环境/测试资产限制

- `.env` 缺 `DEEPSEEK_API_KEY`/`GH_TOKEN`/`SANDBOX_SOCKET`（LLM_API_KEY 覆盖 LLM 路径，无需阻断；SANDBOX_SOCKET 由默认 socket 路径实际生效）
- DeepSeek 账户余额不足（402）→ 真实 LLM 任务产出（finalOutput）无法在本 Spike 内完成；待账户充值后重跑 `opencode-adk-e2e.spec.ts` 即可全绿（沙箱/上传/serve/会话链路已全部实证）
- opencode 版本 1.18.15（沙箱镜像内置）；`deepseek-v4-flash` 为 DB provider 现有配置的模型名，DeepSeek 网关 402 时未走到模型校验（余额检查先行）

## 7. 门禁判定

- ✅ Phase B1：auto-detect 路径 + 全栈服务健康
- ✅ Phase B2：explicit 路径 + preflight + opencode provider 健康 + LLM 调用链路（402 证明链路全程可达）
- ⚠️ Phase B3：Playwright 3 次运行 1 次配置冲突、1 次 podman 真缺陷（已修复）、1 次外部账户余额（非 podman/代码缺陷）

**结论：DONE_WITH_CONCERNS**——podman 全链路（compose/health/sandbox 生命周期/workspace 上传/opencode serve/会话/LLM 出网）已在真实环境逐环实证；剩余唯一失败点（finalOutput 为空）根因为 DeepSeek 账户余额不足，属测试环境问题而非 podman 集成缺陷。修复 podman 缺陷（§6.1）的 patch 镜像与说明已随本报告归档，供后续回传上游或纳入交付镜像。

## 8. 提交

```text
docs(podman): spike B evidence - podman full-chain E2E
```

## 后续更新（2026-08-17）：eip 方案取代 patch 镜像

§6.1 的 patch 方案已被更优方案取代（详见 `docs/superpowers/plans/2026-08-16-podman-support/report-task9b-eip-experiment.md`）：

- **发现**：`opensandbox/server:latest`（v0.2.2）原生实现 `[server] eip`（最高优先级）与 `[docker] host_ip`（第二优先级）；v0.1.0 无 eip 字段。
- **结论**：docker-compose.yml 的 opensandbox-config 已加 `eip = "127.0.0.1"`（提交见分支 log），docker/podman 通吃，**无需 patch 镜像**。
- **清理**：Spike 临时资产（opensandbox-server-src/、sandbox-src/、sandbox-sources.jar、agent-control-tower/opensandbox-config.toml）已删除；`0.1.0-podman` 本地镜像不再需要。

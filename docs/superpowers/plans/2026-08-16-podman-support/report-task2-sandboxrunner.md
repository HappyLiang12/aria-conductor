# Task 2 执行报告 — SandboxRunner CONTAINER_RUNTIME 优先级 TDD（Java）

> **Status: DONE** — TDD 四步证据完整（红 → 绿），模块全量回归 BUILD SUCCESS，代码与报告已提交。

## 1. 环境信息

| 项目 | 值 |
|------|-----|
| 操作系统 | Windows 22H2（10.0.19045） |
| Shell | PowerShell 7（pwsh） |
| 工作区 | `C:\Users\User\.qoder\worktree\aria-conductor\fvHa7E` |
| git 分支 | `feat/podman-support`（git 元数据位于工作区外 `d:/project/aria-conductor/.git`） |
| JDK | OpenJDK 21.0.11（Microsoft，`C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot`） |
| Maven | 3.9.16（`D:\tools\apache-maven-3.9.16`；系统 PATH 无 mvn，IDEA 捆绑 3.6.3 不适用 Java 21） |
| 设计文档 | `docs/superpowers/specs/2026-08-16-podman-support-design.md`（§5.3 Java SandboxRunner、§6 错误处理矩阵） |
| 计划文档 | `docs/superpowers/plans/2026-08-16-podman-support.md`（Task 2） |

## 2. Step 2.1 — 写失败测试（RED）

在 `SandboxRunnerTest.java` 的 `buildRunCommand_normalizesWindowsPathsToForwardSlashes` 测试之后、类结束 `}` 之前追加 4 个测试方法（按项目 4 空格缩进规范格式化，断言逻辑与计划原文完全一致）：

```java
// ---- runtime resolution precedence (CONTAINER_RUNTIME env override) ----

@Test
void resolveRuntime_noEnvVar_autoDetectsDockerFirstThenPodman() {
    assertThat(SandboxRunner.resolveRuntime(null, true, true)).isEqualTo("docker");
    assertThat(SandboxRunner.resolveRuntime("", false, true)).isEqualTo("podman");
    assertThat(SandboxRunner.resolveRuntime("   ", false, false)).isNull();
}

@Test
void resolveRuntime_explicitEnvWithCliPresent_winsOverDetection() {
    assertThat(SandboxRunner.resolveRuntime("docker", true, true)).isEqualTo("docker");
    assertThat(SandboxRunner.resolveRuntime("DOCKER", true, false)).isEqualTo("docker");
    assertThat(SandboxRunner.resolveRuntime(" podman ", false, true)).isEqualTo("podman");
}

@Test
void resolveRuntime_explicitEnvValidButCliMissing_disablesSandbox() {
    // Strict: no cross-runtime fallback when the user explicitly chose one.
    assertThat(SandboxRunner.resolveRuntime("docker", false, true)).isNull();
    assertThat(SandboxRunner.resolveRuntime("podman", true, false)).isNull();
}

@Test
void resolveRuntime_invalidEnvValue_isIgnoredAndAutoDetected() {
    assertThat(SandboxRunner.resolveRuntime("nerdctl", true, true)).isEqualTo("docker");
    assertThat(SandboxRunner.resolveRuntime("containerd", false, true)).isEqualTo("podman");
}
```

新增测试只测纯函数 `resolveRuntime`（静态、无本机 CLI 依赖），不依赖 `ReflectionTestUtils` 固定字段的既有模式，也不受本机 docker/podman 环境变量干扰。

## 3. Step 2.2 — 运行测试确认失败（RED 证据）

命令：`mvn test -pl act-execution -Dtest=SandboxRunnerTest`

输出（ExitCode 1，预期编译失败）：

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project act-execution: Compilation failure: Compilation failure:
[ERROR] /C:/Users/User/.qoder/worktree/aria-conductor/fvHa7E/agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/sandbox/SandboxRunnerTest.java:[127,33] cannot find symbol
[ERROR]   symbol:   method resolveRuntime(<nulltype>,boolean,boolean)
[ERROR]   location: class io.aria.conductor.execution.sandbox.SandboxRunner
[ERROR] ...（共 10 处同类错误，覆盖 4 个新增测试的全部断言调用）
```

确认：红灯达成，`resolveRuntime` 方法不存在。

## 4. Step 2.3 — 实现最小代码（GREEN 实施）

`SandboxRunner.java` 两处修改：

(a) import 区追加 `import java.util.Locale;`（位于 `java.util.regex.Pattern;` 之后，与计划一致）。

(b) `detectRuntime()` 改为委托 `resolveRuntime`（传入 `System.getenv("CONTAINER_RUNTIME")` 与两个 CLI 探测结果），其后新增静态方法：

```java
private void detectRuntime() {
    containerRuntime = resolveRuntime(System.getenv("CONTAINER_RUNTIME"),
            commandExists("docker"), commandExists("podman"));
    if (containerRuntime == null) {
        log.warn("No container runtime detected. Sandbox tools disabled.");
    }
}

static String resolveRuntime(String explicitEnv, boolean dockerExists, boolean podmanExists) {
    if (explicitEnv != null && !explicitEnv.isBlank()) {
        String rt = explicitEnv.trim().toLowerCase(Locale.ROOT);
        if ("docker".equals(rt) || "podman".equals(rt)) {
            if (("docker".equals(rt) && dockerExists) || ("podman".equals(rt) && podmanExists)) {
                return rt;
            }
            log.warn("CONTAINER_RUNTIME='{}' is set but its CLI is unavailable. Sandbox tools disabled.", rt);
            return null;
        }
        log.warn("CONTAINER_RUNTIME='{}' is invalid (expected docker|podman). Ignoring and auto-detecting.", explicitEnv);
    }
    if (dockerExists) return "docker";
    if (podmanExists) return "podman";
    return null;
}
```

优先级语义（与 spec §5.3 一致）：显式合法值 + CLI 存在 → 严格采用该运行时；显式合法值但 CLI 缺失 → `null`（沙箱禁用，**不做跨运行时回退**，对应 §6 错误矩阵"SandboxRunner 显式运行时不可用 → log warn + 沙箱工具禁用"）；无效/空白值 → 忽略并自动探测（docker → podman → null）。

## 5. Step 2.4 — 运行测试确认通过（GREEN 证据）

命令：`mvn test -pl act-execution -Dtest=SandboxRunnerTest`

测试结果（核心证据）：

```
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

18 个用例 = 14 个既有（参数化展开计数）+ 4 个新增，全部通过。**红灯转绿灯达成。**

### 5.1 关于单类过滤运行的 jacoco 说明（设计已知行为，非本次改动引入）

上述命令的构建最终 ExitCode 1，原因在 `agent-control-tower/pom.xml` 第 242-245 行有明确注释说明：

> Bind to test phase so the unit lane (`mvn test`) enforces the ratchet. ... **Filtered local runs (-Dtest=Foo) under-cover by design: add -Djacoco.skip=true.**

- 单类过滤（`-Dtest=SandboxRunnerTest`）时 jacoco 覆盖率只统计该类的执行数据，必然不达标（lines 0.00 < 0.58），`jacoco:check` 报 `Coverage checks have not been met`。
- 尝试 `-Djacoco.skip=true`：report goal 按属性跳过，但 check goal 的 `<skip>` 显式绑定 `${skip.unit.tests}`（与 surefire 共用，置 true 会连测试一起跳过），因此该开关对 check 无效——属项目既有配置风格。
- **测试本身 18/18 全绿是 TDD 红→绿的确凿证据；完整覆盖率验证由 Step 2.5 全量回归承担（BUILD SUCCESS，见下）。**

## 6. Step 2.5 — 模块全量回归

命令：`mvn test -pl act-execution`（不带 `-Dtest`）

输出（ExitCode 0）：

```
[INFO] Tests run: 643, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.12:check (check) @ act-execution ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  44.454 s
```

结论：docker 路径无回归，覆盖率检查（lines ≥ 0.58 / branches ≥ 0.42）达标，新增 `resolveRuntime` 分支被完整覆盖。

## 7. Step 2.6 — 提交

代码提交：

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/sandbox/SandboxRunner.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/sandbox/SandboxRunnerTest.java
git commit -m "feat(sandbox): honor CONTAINER_RUNTIME env in SandboxRunner detection"
```

报告提交：

```bash
git add docs/superpowers/plans/2026-08-16-podman-support/report-task2-sandboxrunner.md
git commit -m "docs(podman): task2 report"
```

提交 hash：

- 代码提交：`c75d2e9`（feat(sandbox): honor CONTAINER_RUNTIME env in SandboxRunner detection）
- 报告提交：`e650c11`（docs(podman): task2 report）

## 8. 结论

- **Status: DONE** — TDD 四步（失败测试 → 编译失败 → 最小实现 → 18/18 全绿）证据完整；模块全量回归 643 测试 BUILD SUCCESS；docker 默认路径行为完全不变（未设置 `CONTAINER_RUNTIME` 时自动探测 docker 优先，与现状一致）。
- 新增语义：显式 `CONTAINER_RUNTIME=docker|podman` 且 CLI 存在时严格采用；CLI 缺失时沙箱禁用（不跨运行时回退）；无效值忽略并自动探测。
- 唯一偏差说明：Step 2.4 单类过滤运行下 jacoco check 必然失败（pom 注释明示的 by-design 行为），测试结果本身 18/18 全绿，验收以 Step 2.5 全量回归 BUILD SUCCESS 为准。未超出任务范围，无需重构。

## 9. 提交列表

| Commit | 说明 |
|--------|------|
| `c75d2e9` | feat(sandbox): honor CONTAINER_RUNTIME env in SandboxRunner detection |
| `e650c11` | docs(podman): task2 report |

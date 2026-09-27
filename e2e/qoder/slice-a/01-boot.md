# A1 — Linux artifact pin + image boot inside OpenSandbox

- **Task**: Slice A gate A1 (plan `docs/superpowers/plans/2026-09-17-qoder-cli-provider.md`)
- **Run date**: 2026-09-17/18 (local +08:00; the recorded runs finished between 23:49:48 and 00:00:42 — see the run ledger in Step 5)
- **Host / runtime**: Windows host, **podman** (podman machine) — the same image store the local
  OpenSandbox server uses (`aria-opensandbox`, `opensandbox-server` compose service, host port 8090;
  socket bind `/run/user/1000/podman/podman.sock` → `/var/run/docker.sock`)
- **Verdict**: PASS — a real, checksum-pinned Linux Qoder CLI artifact exists; the image carrying it
  boots inside the local OpenSandbox server and prints the pinned CLI version.

## Exit criteria

| Criterion (brief Task A1) | Status | Evidence |
|---|---|---|
| Official Linux distribution method + version ≥ 1.1.41 + checksum recorded | PASS | Step 1 |
| Image pinned as `aria-conductor/qoder-sandbox:0.1` | PASS | Step 3 |
| `QoderImageBootE2ETest` genuinely red, then green | PASS | Step 5 |
| CLI version recorded for B4 + checksum-pinned artifact | PASS | Steps 1/3 (`1.1.41`) |

## Step 1 — official Linux distribution method (gate)

Official documentation (Qoder docs, "Installation and Upgrade"): <https://docs.qoder.com/cli/installation>.
It documents exactly two official methods:

1. **Install script (recommended)** — downloads a native standalone executable:
   `curl -fsSL https://qoder.com/install | bash` (macOS/Linux, `amd64`/`arm64`).
2. **npm package (legacy, needs Node.js ≥ 20)** — `npm install -g @qoder-ai/qodercli`.

Raw evidence:

```
$ curl -fsSL https://docs.qoder.com/cli/installation | grep -o "https://qoder.com/install[^\"<]*" | head -3
https://qoder.com/install
https://qoder.com/install.ps1
https://qoder.com/install.cmd

$ curl -fsSL https://docs.qoder.com/cli/installation | grep -o "@qoder-ai/qodercli[^\"<]*" | head -3
@qoder-ai/qodercli
@qoder-ai/qodercli@latest
@qoder-ai/qodercli
```

```
$ curl -fsSL https://qoder.com/install | grep -nE "^BASE_URL=|manifest_url=\"|bin_name=\"|checksum=\$"
13:BASE_URL="https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli"
222:  local manifest_url="$BASE_URL/channels/manifest.json"
224:    manifest_url="$BASE_URL/channels/$normalized_requested_version/manifest.json"
319:  local bin_name="qodercli"
```

```
$ npm view @qoder-ai/qodercli@1.1.41 version bin engines
version = '1.1.41'
bin = {
  qoder: 'bundle/qoder-npm-dispatcher.cjs',
  qodercli: 'bundle/qodercli.js'
}
engines = { node: '>=20.0.0' }

$ npm view @qoder-ai/qodercli@latest version
1.1.55
```

The install script resolves a **per-version release manifest** whose entries carry the platform
archive URL **and its sha256**; it verifies the checksum **before** extracting, then delegates to
the downloaded binary's `qodercli install --force` subcommand (both script-internal behaviors
verified against the script itself — raw output below):

```
$ curl -fsSL https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/channels/manifest.json | head -c 120
{
  "latest": "1.1.55",
  "published_at": "2026-09-17T12:53:54.074Z",
  "files": [
    {
      "os": "darwin",
      "ar

$ curl -fsSL https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/channels/1.1.41/manifest.json \
    | tr -d ' \n' | grep -o '{"os":"linux","arch":"amd64","url":"[^"]*","sha256":"[^"]*"}'
{"os":"linux","arch":"amd64","url":"https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/releases/1.1.41/qodercli-linux-x64.tar.gz","sha256":"1c2d174b098eb472a9ac19a80b89a664fdcb7d37dca09e64f44243faa4e3eca7"}
```

Script-internal behaviors above, re-verified against the live install script (2026-09-18):

```
$ curl -fsSL https://qoder.com/install | grep -nE "Verifying checksum|Checksum mismatch|Extracting|install --force"
290:    echo "==> Verifying checksum..."
299:      fatal_error "Checksum mismatch" \
309:  echo "==> Extracting..."
327:  if ! "$extract_dir/$bin_name" install --force "$@"; then

$ curl -fsSL https://qoder.com/install | grep -nE "BUN_OPTIMIZED_X64_REQUIRED_CPU_FLAGS=|selecting baseline|arch=\"amd64-baseline\""
150:BUN_OPTIMIZED_X64_REQUIRED_CPU_FLAGS=(sse4_2 popcnt avx avx2 bmi1 bmi2 fma)
211:      echo "==> CPU lacks Bun optimized x64 requirements; selecting baseline binary"
212:      arch="amd64-baseline"
```

The first grep output shows the checksum verification (lines 290/299) preceding extraction (309) and
the `install --force` delegation to the extracted binary (327). The second shows the CPU rule behind
the Dockerfile pin: on `linux/amd64` the launcher falls back to `amd64-baseline` only when any of
`sse4_2 popcnt avx avx2 bmi1 bmi2 fma` is missing (line 212), so a CPU exposing all seven (Step 2
below) gets the optimized `amd64` artifact this image pins.

```
$ curl -sIL https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/releases/1.1.41/qodercli-linux-x64.tar.gz | grep -iE "content-length|HTTP/"
HTTP/1.1 200 OK
Content-Length: 52463927
```

**Gate result: PASS — a verifiable official Linux distribution exists (docs + release manifest +
sha256). Nothing was mirrored, re-hosted or invented.**

### Pin decision

| Item | Value |
|---|---|
| Version pinned | **1.1.41** |
| Artifact | `https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/releases/1.1.41/qodercli-linux-x64.tar.gz` |
| sha256 (from the official manifest) | `1c2d174b098eb472a9ac19a80b89a664fdcb7d37dca09e64f44243faa4e3eca7` |
| Size | 52 463 927 bytes |
| Variant | `linux/amd64` (the optimized build; the OpenSandbox podman VM exposes all
  Bun-required CPU flags — see Step 2) |
| Latest at check time | 1.1.55 (2026-09-17T12:53:54Z) — not used |

Rationale: the frozen CLI facts used by Slices A–E were verified against `qodercli 1.1.41`
(`docs/reviews/2026-09-17-qoder-cli-acp-spike.md`), so the sandbox pins the same version rather
than the newest release; `1.1.41+` is satisfied and the version is a Docker `ARG`, so B4 can bump
it (with a new checksum) if a later decision requires it.

## Step 2 — Dockerfile

`agent-control-tower/qoder-sandbox/Dockerfile` — `FROM node:22-slim`; installs `curl` +
`ca-certificates`, downloads the pinned archive, **verifies the manifest sha256 with
`sha256sum -c -`**, extracts the `qodercli` binary to `/usr/local/bin/qodercli`, runs a build-time
smoke test with a throwaway `HOME`, sets `WORKDIR /workspace`, `CMD ["qodercli","--version"]`.
No credentials or provider config are baked in.

CPU-flag check that justifies the optimized (`amd64`, not `amd64-baseline`) artifact — the same
artifact the official install script selects for this flag set (rule quoted in Step 1):

```
$ podman run --rm docker.io/library/node:22-slim sh -c 'grep -m1 "^flags" /proc/cpuinfo | tr " " "\n" | sort -u | grep -E "^(sse4_2|popcnt|avx|avx2|bmi1|bmi2|fma)$" | tr "\n" " "'
avx avx2 bmi1 bmi2 fma popcnt sse4_2
```

Base-image prerequisite check (`curl` is the only tool `node:22-slim` lacks):

```
$ podman run --rm docker.io/library/node:22-slim sh -c 'for t in sha256sum install tar gzip curl; do printf "%s: " $t; command -v $t || echo MISSING; done'
sha256sum: /usr/bin/sha256sum
install: /usr/bin/install
tar: /usr/bin/tar
gzip: /usr/bin/gzip
curl: MISSING
```

## Step 3 — build (podman)

```
$ podman build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox
STEP 1/7: FROM node:22-slim
STEP 2/7: ARG QODERCLI_VERSION=1.1.41
STEP 3/7: ARG QODERCLI_LINUX_X64_SHA256=1c2d174b098eb472a9ac19a80b89a664fdcb7d37dca09e64f44243faa4e3eca7
STEP 4/7: ARG QODERCLI_LINUX_X64_URL=https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/releases/${QODERCLI_VERSION}/qodercli-linux-x64.tar.gz
STEP 5/7: RUN apt-get update     && apt-get install -y --no-install-recommends curl ca-certificates     && rm -rf /var/lib/apt/lists/*     && curl -fsSL --retry 2 --connect-timeout 30 --max-time 300 "$QODERCLI_LINUX_X64_URL" -o /tmp/qodercli.tar.gz     && echo "$QODERCLI_LINUX_X64_SHA256  /tmp/qodercli.tar.gz" | sha256sum -c -     && tar -xzf /tmp/qodercli.tar.gz -C /tmp     && install -m 0755 /tmp/qodercli /usr/local/bin/qodercli     && rm -f /tmp/qodercli /tmp/qodercli.tar.gz     && HOME=/tmp/qodercli-smoke qodercli --version     && rm -rf /tmp/qodercli-smoke
[apt output elided: "0 upgraded, 18 newly installed" — curl + ca-certificates]
/tmp/qodercli.tar.gz: OK
1.1.41
--> 93f514e8aa0d
STEP 6/7: WORKDIR /workspace
--> bc8128fd687c
STEP 7/7: CMD ["qodercli", "--version"]
COMMIT aria-conductor/qoder-sandbox:0.1
--> 004f664e9010
Successfully tagged localhost/aria-conductor/qoder-sandbox:0.1
004f664e90105f0a75433d9e73b4ffd53281414ef75ae3bf11c4bb2e8c0341a9
```

`/tmp/qodercli.tar.gz: OK` is `sha256sum -c -` confirming the downloaded artifact matches the
checksum published in the official manifest; the `1.1.41` line is the build-time `qodercli
--version` smoke test.

### Pin record (for B4)

```
$ podman images --format "{{.Repository}}:{{.Tag}}|{{.ID}}|{{.Digest}}|{{.Created}}" | grep -i qoder
localhost/aria-conductor/qoder-sandbox:0.1|004f664e9010|sha256:d7e3f4d5abc0c9b96507aa8902dbf21fb72705d1fc037f89e9379a13896c471b|6 seconds ago

$ podman inspect aria-conductor/qoder-sandbox:0.1 --format "Id={{.Id}} RepoDigests={{.RepoDigests}} Size={{.Size}}"
Id=004f664e90105f0a75433d9e73b4ffd53281414ef75ae3bf11c4bb2e8c0341a9 RepoDigests=[localhost/aria-conductor/qoder-sandbox@sha256:d7e3f4d5abc0c9b96507aa8902dbf21fb72705d1fc037f89e9379a13896c471b] Size=411990065

$ podman run --rm aria-conductor/qoder-sandbox:0.1
1.1.41
```

- Image reference: `aria-conductor/qoder-sandbox:0.1` (`localhost/aria-conductor/qoder-sandbox:0.1`)
- Image ID: `sha256:004f664e90105f0a75433d9e73b4ffd53281414ef75ae3bf11c4bb2e8c0341a9`
- Repo digest: `sha256:d7e3f4d5abc0c9b96507aa8902dbf21fb72705d1fc037f89e9379a13896c471b` (411 990 065 bytes ≈ 0.38 GiB)
- CLI version inside the image: **1.1.41**

## Step 4 — test

`agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderImageBootE2ETest.java`
(Failsafe tier; `@EnabledIfSystemProperty(named = "qoder.e2e.enabled", matches = "true")` — the
first use of that annotation in this repo). It creates a sandbox from the pinned image, waits for
the execd channel (the same `true`-probe gate production uses in
`OpenCodeAdkProvider#awaitExecdReady`), runs `qodercli --version 2>&1`, asserts the output contains
a semver **and** the pinned `1.1.41`, then kills the sandbox. No credentials are involved.

## Step 5 — red → green

### 5a. RED (before the image/pin existed — image store verified empty first)

```
$ podman images --format "{{.Repository}}:{{.Tag}}" | grep -i qoder
NO qoder-sandbox image in store (expected for the RED run)

$ cd agent-control-tower && mvn clean verify -pl act-execution -Dit.test=QoderImageBootE2ETest \
      -Dqoder.e2e.enabled=true -Djacoco.skip=true
[INFO] --- failsafe:3.5.3:integration-test (default) @ act-execution ---
[INFO] Running io.aria.conductor.execution.qoder.QoderImageBootE2ETest
23:51:36.117 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Starting create sandbox with startup source aria-conductor/qoder-sandbox:0.1 (timeout: 1800s) operation
23:51:41.880 [main] ERROR io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager -- Failed to create OpenSandbox sandbox for agent bc2cf18b-8062-4cb9-8b2b-1fd18d13ed40: Server error : 500 Internal Server Error {"code":"DOCKER::SANDBOX_IMAGE_PULL_FAILED","message":"Failed to pull image aria-conductor/qoder-sandbox:0.1: 403 Client Error for http+docker://localhost/v1.44/images/create?tag=0.1&fromImage=aria-conductor%2Fqoder-sandbox: Forbidden (\"denied: requested access to the resource is denied\")"}
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 6.556 s <<< FAILURE! -- in io.aria.conductor.execution.qoder.QoderImageBootE2ETest
[ERROR] io.aria.conductor.execution.qoder.QoderImageBootE2ETest.pinnedImageBootsAndRunsPinnedQoderCli -- Time elapsed: 6.527 s <<< ERROR!
io.aria.conductor.execution.adk.TaskExecutionException: OpenSandbox sandbox creation failed for agent bc2cf18b-8062-4cb9-8b2b-1fd18d13ed40: Server error : 500 Internal Server Error {"code":"DOCKER::SANDBOX_IMAGE_PULL_FAILED","message":"Failed to pull image aria-conductor/qoder-sandbox:0.1: 403 Client Error for http+docker://localhost/v1.44/images/create?tag=0.1&fromImage=aria-conductor%2Fqoder-sandbox: Forbidden (\"denied: requested access to the resource is denied\")"}
	at io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager.createSandboxWithRetry(OpenCodeSandboxManager.java:130)
	at io.aria.conductor.execution.qoder.QoderImageBootE2ETest.pinnedImageBootsAndRunsPinnedQoderCli(QoderImageBootE2ETest.java:58)
Caused by: com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException: Server error : 500 Internal Server Error {"code":"DOCKER::SANDBOX_IMAGE_PULL_FAILED","message":"Failed to pull image aria-conductor/qoder-sandbox:0.1: 403 Client Error for http+docker://localhost/v1.44/images/create?tag=0.1&fromImage=aria-conductor%2Fqoder-sandbox: Forbidden (\"denied: requested access to the resource is denied\")"} | [DOCKER::SANDBOX_IMAGE_PULL_FAILED] Failed to pull image aria-conductor/qoder-sandbox:0.1: 403 Client Error for http+docker://localhost/v1.44/images/create?tag=0.1&fromImage=aria-conductor%2Fqoder-sandbox: Forbidden ("denied: requested access to the resource is denied") | request_id=9164ceebd32249c6b8fbe6bc36d7f9fe
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
[INFO] --- failsafe:3.5.3:verify (default) @ act-execution ---
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-failsafe-plugin:3.5.3:verify (default) on project act-execution: 
[ERROR] 
[ERROR] See D:\project\aria-conductor\agent-control-tower\act-execution\target\failsafe-reports for the individual test results.
[ERROR] See dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
```

The server tried to pull `aria-conductor/qoder-sandbox:0.1` (403 — not published anywhere, by
design) because the image did not exist in the store yet. (Maven's own summary line in that output
points at its local run-artifact directory `act-execution/target/failsafe-reports`; the directory is
regenerated by the command above and is not committed.)

### 5b. First green attempt (image built) — real execd-readiness race

```
23:53:47.331 [main] ERROR com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.CommandsAdapter -- Failed to run command (length: 23)
java.net.ConnectException: Failed to connect to /127.0.0.1:48912
[ERROR] io.aria.conductor.execution.qoder.QoderImageBootE2ETest.pinnedImageBootsAndRunsPinnedQoderCli -- Time elapsed: 4.295 s <<< ERROR!
io.aria.conductor.execution.adk.TaskExecutionException: Command execution failed in sandbox 2ce2076d-a956-4d53-9ce6-2ab1317038f5: Network connectivity error: Failed to connect to /127.0.0.1:48912
	at io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager.runCommand(OpenCodeSandboxManager.java:388)
	at io.aria.conductor.execution.qoder.QoderImageBootE2ETest.pinnedImageBootsAndRunsPinnedQoderCli(QoderImageBootE2ETest.java:59)
```

Sandbox creation succeeded (`Successfully created sandbox: 2ce2076d-a956-4d53-9ce6-2ab1317038f5`);
the create call returned at 23:53:47.320 and the version command was sent 11 ms later (23:53:47.331),
while the execd endpoint was not accepting connections yet (the stack points at a direct `runCommand`
from the test body, line 59 — that revision had no gate). The test then gained the `awaitExecdReady`
gate (Step 4) that production already had — the sandbox was still killed cleanly by the test's
`finally` block.

### 5c. GREEN (same command as 5a, run after the build)

```
$ cd agent-control-tower && mvn verify -pl act-execution -Dit.test=QoderImageBootE2ETest \
      -Dqoder.e2e.enabled=true -Djacoco.skip=true
[INFO] --- failsafe:3.5.3:integration-test (default) @ act-execution ---
[INFO] Running io.aria.conductor.execution.qoder.QoderImageBootE2ETest
00:00:35.315 [main] INFO com.alibaba.opensandbox.sandbox.Sandbox -- Starting create sandbox with startup source aria-conductor/qoder-sandbox:0.1 (timeout: 1800s) operation
00:00:37.233 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully created sandbox: 711f8c78-e68a-456a-afef-81de1f0eaaa6
[A1] sandbox 711f8c78-e68a-456a-afef-81de1f0eaaa6 image aria-conductor/qoder-sandbox:0.1 `qodercli --version` => 1.1.41
00:00:42.668 [main] INFO com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.SandboxesAdapter -- Successfully terminated sandbox: 711f8c78-e68a-456a-afef-81de1f0eaaa6
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.111 s -- in io.aria.conductor.execution.qoder.QoderImageBootE2ETest
[INFO] Tests run: 864, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:25 min
```

(The two `Tests run:` totals are the unit lane — 864, printed earlier in the same log — and the
Failsafe lane — 1 — quoted as-is.)

### Run ledger (every local log artifact accounted for)

| Run (finished +08:00 / elapsed) | Command | Outcome | Established |
|---|---|---|---|
| 23:49:48 / 1:23 | the 5a command **without `clean`** (Failsafe never started, so `-Dit.test` is not independently observable in this log) | BUILD FAILURE at `jacoco:0.8.12:check` — `Rule violated for bundle act-execution: lines covered ratio is 0.45, but expected minimum is 0.58`; no Failsafe section, no dumpstream | `-Djacoco.skip=true` does not skip `jacoco:check`; the run needs `clean` (deviation 2 / concern 1) |
| 23:51:42 / 1:28 | `mvn clean verify ...` (5a) | BUILD FAILURE; Failsafe red: `DOCKER::SANDBOX_IMAGE_PULL_FAILED` / HTTP 403 (image absent from the store) | genuine red before the image existed |
| 23:52 (log saved) | `podman build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox` | image `004f664e9010` built; `sha256sum -c` OK; smoke `1.1.41` | image present in the podman store the OpenSandbox server shares |
| 23:53:48 / 1:09 | `mvn verify ...` (5b) | BUILD FAILURE; sandbox `2ce2076d` created, version command hit the execd race (test line 59 — no gate in that revision) | need for the `awaitExecdReady` gate |
| (no run) | — | `a1-green-attempt1` is a byte-identical copy of the 23:53 output (`cmp` clean) | nothing new was produced |
| 23:55:44 / 1:22 | `mvn verify ...` | BUILD SUCCESS; sandbox `290daf13-…`, 8.139 s; **no `[A1]` line** (that revision predates the stdout evidence line); whether the readiness gate was active is not determinable from this log — no probe failure is logged | first green run |
| 23:57:22 / 1:23 | `mvn verify ...` | BUILD SUCCESS; sandbox `8449f5d5-…`, 8.889 s; `[A1]` line present; one probe retry logged (stack `awaitExecdReady:93`, test body `:66`) | green with the evidence line; gate retry exercised |
| 00:00:42 / 1:25 | `mvn verify ...` (quoted in 5c) | BUILD SUCCESS; sandbox `711f8c78-…`, 8.111 s; the logged stack line numbers (`awaitExecdReady:97`, test body `:70`) match the committed test source | the run quoted in 5c; confirming re-run |

Timeline in words: the first green was 23:55; 23:57 was the first green that printed the `[A1]`
line; 00:00 is the run matching the committed test source. `a1-red.log` (23:49) and `a1-build.log`
(23:52) are the remaining two of the eight local log artifacts, and the five Failsafe dumpstreams
under `agent-control-tower/act-execution/target/failsafe-reports/` correspond exactly to the five
runs that reached Failsafe (23:51, 23:53, 23:55, 23:57, 00:00). The unit lane was green (864/864) in
every run that reached it.

**Provenance is the commands, not log files** — no uncommitted log path is the source of any
quotation above:

- **Step 1/2 network evidence**: every block starts with the exact `curl`/`npm view` command and
  shows its captured output (the install-script greps were re-run 2026-09-18).
- **Step 3 build block**: `podman build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox`
  (the omitted apt output and intermediate image IDs are as marked; a rebuild reproduces the
  `sha256sum -c`/`1.1.41` lines).
- **5a red block**: `cd agent-control-tower && mvn clean verify -pl act-execution -Dit.test=QoderImageBootE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true`
  on a host with no `aria-conductor/qoder-sandbox` image in the store.
- **5b race block**: the same command immediately after the build; the race is timing-dependent and
  may not reproduce on a warm machine.
- **5c green block**: `cd agent-control-tower && mvn verify -pl act-execution -Dit.test=QoderImageBootE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true`
  with the image present. The `[A1]` line is the test's stdout; Maven also writes it into its local
  run artifact under `act-execution/target/failsafe-reports/`, which the same command regenerates
  (like all `target/**` output, not committed).

No stray sandbox containers were left behind (`podman ps -a` shows only `aria-opensandbox`).

## Deviations from the brief

1. **Step order (2 → 4 → 5-red → 3 → 5-green instead of 2 → 3 → 4 → 5).** The brief's step 5
   requires the failure to happen "before the image/pin exists", and the task carries a hard
   "build genuinely red first" constraint; building the image (step 3) before writing/running the
   test would have made a genuine red impossible. The test was therefore written and run red
   before the image was built. The produced files and their contents are exactly as specified.
2. **`mvn clean` added to the red run.** A stale `act-execution/target/jacoco.exec` from earlier
   local builds made the `jacoco:check` execution (bound to the `test` phase, skip =
   `${skip.unit.tests}`, so `-Djacoco.skip=true` does *not* disable it) fail the build before
   Failsafe ever started; `clean` removes the stale exec data. See concern 1.
3. **Readiness gate in the test.** Added after the first green attempt (5b) hit the known
   execd-not-ready window; it mirrors production's `awaitExecdReady`.
4. **Build-time smoke test** (`HOME=/tmp/qodercli-smoke qodercli --version` inside the Dockerfile
   RUN) — an addition to fail the build fast on a broken artifact; the authoritative check remains
   the E2E test.

## Concerns

1. **`-Djacoco.skip=true` does not skip `jacoco:check` in this repo.** The check execution
   overrides the goal's default skip with `${skip.unit.tests}`, so on a tree with a stale
   `target/jacoco.exec` the brief's exact command fails the coverage ratchet before Failsafe runs
   (`Rule violated for bundle act-execution: lines covered ratio is 0.45, but expected minimum is
   0.58`). Workarounds: run `mvn clean` first (used here), or `-Dskip.unit.tests=true`.
2. **Execd readiness.** `createSandbox` skips the SDK health check (scheme-less server endpoint),
   so any consumer must probe `true` before the first real command — this applies to A2–A5 and B4
   as well.
3. **Auto-update not disabled.** The CLI ships with auto-upgrade enabled by default (per the
   official docs, `qoder update` / `--force` re-install). Nothing in this image prevents a runtime
   self-update from replacing the pinned binary inside a sandbox; B4 should decide whether to pin
   the behavior (e.g. an env var or config) once the bridge config layout is settled.
4. **Version drift vs. the newest release.** 1.1.55 exists (checked 2026-09-17). The pin is
   deliberate (matches the spike-verified 1.1.41), but any later bump must also update the sha256
   in the Dockerfile from the versioned manifest.
5. **No ARM coverage.** Only `linux/amd64` was pinned; the manifest also offers `arm64` /
   `*-musl` variants if a non-amd64 sandbox host ever appears.

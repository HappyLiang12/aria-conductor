# Live SDD scenario on the SANDBOX arm (2026-11-10)

This file records the third live attempt to run the SDD scenario end to end at the S1
release head. It exists because Gate A's SANDBOX arm was blocked by the podman machine's
`memory.max` defect and its HOST arm by the governed read-only toolset; after both blockers
were addressed on this host, the SANDBOX arm was run to conclusion.

**Outcome at the time of this attempt: the orchestration works; the deliverable does
not.** A pre-existing mainline policy denies `edit`/`write`/`patch`/`bash` to every
opencode run and wires the platform MCP only to the Aria assistant, so SDD BA/DEV/QA
workers are read-only in HOST and in SANDBOX. The chain was stopped after the second
read-only DEV/QA cycle. This is a product blocker, not an S1 regression (finding
F-E2E-1). **Section 8 records the post-fix rerun of the same scenario, which reached
COMPLETED with a real implementation and a `VERDICT=PASS` QA report.**

## 1. Provenance

- Feature worktree `D:/project/aria-agent-os`, branch `feat/agent-os-memory`, HEAD
  `e7629db0429edbda06376304e8f3057f05eb2ea5`.
- Fat jar `agent-control-tower/act-app/target/act-app-0.1.0-SNAPSHOT.jar`, 95,222,573
  bytes, sha256 `94a7fc4c92f639eab3ff8b180d20a26fdba2a9cd28ace497117f960ba79d1cf1`, built
  2026-11-10 01:46 +0800. The jar is the Gate B live jar. No main-code change landed
  after it: `git diff --stat 7f6647ab..HEAD -- '*/src/main/*'` is empty, so the jar's
  runtime code is the code at HEAD.
- Fresh H2 in `%TEMP%/s1-e2e/live`, `jdbc:h2:file:./data/act_db;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE`;
  Flyway applied 79 migrations to an empty schema, now at v82
  (`Successfully applied 79 migrations ... now at version v82`).
- Backend launched with the operator env file sourced in-process (never printed),
  `GITHUB_TOKEN` from `gh auth token` (never printed), and
  `--spring.profiles.active=h2 --adk.default-provider=opencode`. Health 200 at
  06:40:02Z. Seeded SDD agents resolve to opencode with no explicit execution mode,
  which `DefaultAgentExecutionPolicy` maps to SANDBOX; the Aria agent reads back
  `SANDBOX`.
- OpenSandbox server `opensandbox/server:latest`, healthy at `http://127.0.0.1:8090`
  (`GET /health` 200) at 06:37:20Z; sandbox image
  `localhost/aria-conductor/opencode-sandbox:1.1` built locally; execd
  `opensandbox/execd:v1.0.21` present.

## 2. Environment remediation (not product changes)

Two environment defects from Gate A were fixed before this run:

1. **podman memory limit.** `podman machine set --rootful` (rootful machine) makes
   `podman run --rm --memory 256m docker.io/library/node:22-slim true` exit 0; the
   `crun: open memory.max ...` failure is gone.
2. **OpenSandbox server crash on startup.** podman injects
   `NO_PROXY=localhost,.localhost,127.0.0.1,127.0.0.0/8,[::1],::1` into the container.
   The server's httpx build parses the bracketed IPv6 entries while building proxy mounts
   and dies with `httpx.InvalidURL: Invalid port: ':1]'` before uvicorn starts. Recreating
   the container with `-e NO_PROXY=localhost,127.0.0.1 -e no_proxy=localhost,127.0.0.1`
   (no bracketed entries) gives a clean start:
   `uvicorn.error: Application startup complete` and `GET /health` 200.

The server was run with the rootful podman socket mounted at
`/var/run/docker.sock` and the repository's unmodified `opensandbox-config.toml`
(`[runtime] type="docker"`, `[docker] host_ip="127.0.0.1"`, port range 40000-60000).
`podman image exists aria-conductor/opencode-sandbox:1.1` resolves locally.

## 3. Sandbox preflight — Aria chat

A first real turn proved the whole SANDBOX stack before the SDD chain:

```
POST /api/v1/aria/chat
{"conversationId":"s1-e2e-preflight","message":"Reply with the single word: ready"}
→ 200 runId 0578a989-56d4-4795-acf9-f61996513797, status COMPLETED, 4,445 tokens
GET /api/v1/memory/runs/0578a989.../context-manifest → 1 row (turn 2)
```

Log evidence: sandbox `af55e1bd-108e-4eda-89ab-299d109cbb46` created from
`aria-conductor/opencode-sandbox:1.1`, workspace and run configuration uploaded, the
run-owned core launched, the session answered, the writer-control stop returned
`{"writersStopped":true,"remaining":[]}` and the sandbox was terminated. This run also
logged the expected `aria.mcp.sandbox-host-address is blank` warning: the Aria agent is
eligible for the platform MCP, but the sandbox host address is not configured on this
host, so it ran without platform tools. That gap is environmental and does not affect
S1 acceptance (the Gate B memory acceptance used the HOST arm).

## 4. SDD chain

```
POST /api/v1/knowledge/d0000001-0000-0000-0000-000000000001/instantiate-workflow
{"parameters":{"issueRef":"#112","issueRepo":"HappyLiang12/aria-conductor",
 "repoUrl":"https://github.com/HappyLiang12/aria-conductor.git"}}
→ chain 33862f08-9521-411d-b1b5-9b1b8ef25279, RUNNING, 3 steps
```

Issue #112 is OPEN ("SDD spec review reads result-root through a duplicated filesystem
contract", labels bug/priority:P2). `gitBranchService.isAvailable()` was satisfied by the
`GITHUB_TOKEN` environment value, so instantiation passed the credential gate.

| # | Step | Run id | Status | Iter | Tokens | Manifests |
|---|---|---|---|---|---|---|
| 0 | BA | `b11dc7e1-24b6-489f-a478-280faef6f736` | COMPLETED | 1 | 2,872 | 1 |
| 1 | DEV 1 | `81a187cc-ad64-4030-a6a0-42ff464252dc` | COMPLETED | 1 | 455 | 1 |
| 2 | QA 1 | `c4bd50f4-36a2-43cc-9feb-766f529720d5` | COMPLETED | 1 | 5,640 | 1 |
| 1 | DEV 2 | `1f80ecde-31f0-4206-86a4-ee1cf8431100` | COMPLETED | 1 | 22,276 | 1 |
| 2 | QA 2 | `cfe88f4d-8af6-473f-9839-1d995334114b` | CANCELLED (operator) | 0 | — | 1 |

- BA produced a complete spec (Problem Statement, Proposed Solution, Acceptance Criteria,
  Error Handling). Approval `e50cf86e-2f27-40f8-8885-416f9bec904d` (`SPEC_REVIEW`,
  PENDING, expiry 07:13:20Z) carried the full spec content, not the run summary.
  `POST /api/v1/approvals/e50cf86e.../decide {"approved":true}` → 200
  `{"approvalId":"e50cf86e...","approved":true,"status":"processed"}`.
- The coordinator committed the spec to branch
  `sdd/33862f08-9521-411d-b1b5-9b1b8ef25279` (head
  `e4d7aa299c78a0d8e61c35f61e1d1000750e78d4`, message `sdd: approve spec`, one file
  `spec/spec.md`; confirmed through the GitHub API) and advanced to DEV.
- DEV 1 completed in 455 tokens with: "this session exposes only `glob`, `grep`, `read`,
  and `webfetch` — there is no Bash/Write/Edit tool ... I cannot run git clone, modify
  code, run Maven/tests, or git add/commit/push. I'm stopping rather than fabricating
  results."
- QA 1 completed read-only through the GitHub API and submitted `VERDICT=DEFECT` ("the
  branch delivers only the specification; zero implementation against the spec"), which
  rescheduled DEV.
- DEV 2 repeated the read-only finding after 22,276 tokens and again refused to fabricate
  an implementation. QA 2 was cancelled when the chain entered its second identical
  DEFECT cycle; the chain is `CANCELLED`, QA 2 is `CANCELLED`.
- Cancellation settled through the writer-control path: the sandbox `stop` control
  returned `writersStopped:true` and no `StopProof[allWritersStopped=false]` line appears
  in the backend log (0 occurrences; 7 writer-control stop lines).

## 5. Manifest evidence

Every executed run wrote exactly one manifest row; the cancelled QA 2 had already built
its context before launch, so it also has one row. Five runs, five rows.

| Run | Section ids |
|---|---|
| BA `b11dc7e1` | identity, policy.agent, goal, memory, knowledge:d0000001..., state, tools |
| DEV 1 `81a187cc` | identity, policy.agent, goal, memory, knowledge:57e2b843..., knowledge:d0000001..., state, tools |
| QA 1 `c4bd50f4` | same as DEV 1 |
| DEV 2 `1f80ecde` | same as DEV 1 |
| QA 2 `cfe88f4d` | same as DEV 1 |

The `memory` section is present on all five, including the BA turn that produced the
spec. No manifest row carries prompt text; rows contain section ids, sources, char and
token estimates only.

## 6. Root cause of the blocked deliverable (F-E2E-1)

The uploaded run configuration inside the DEV sandbox was read directly:

```
podman exec sandbox-7ea595d8... cat \
  /home/aria/run/81a187cc.../config/opencode/opencode.json
```

It contains the governed policy:

```json
"permission": { "*": "deny", "read": "allow", "list": "allow", "glob": "allow",
  "grep": "allow", "webfetch": "allow", "websearch": "allow",
  "edit": "deny", "write": "deny", "patch": "deny", "bash": "deny",
  "task": "deny", "question": "deny", "external_directory": "deny",
  "aria-conductor*": "allow" }
```

and no `mcp` block. The two facts combine:

1. `OpenCodeCoreAdapter.GOVERNED_CONFIGURATION_TEMPLATE` hardcodes
   `edit/write/patch/bash: deny` for every opencode launch, HOST and SANDBOX
   (`OpenCodeCoreAdapter.java:115-149`).
2. `RunMcpWiring.forRun` returns no endpoint unless the run's agent is the Aria
   assistant (`RunMcpWiring.java:51`), and the MCP design states it: "SDD workers never
   do" get the platform MCP block
   (`docs/superpowers/specs/2026-09-05-mcp-into-opencode-design.md:102`).

The `aria-conductor*` allowance therefore only helps the Aria assistant; a worker run has
neither native write/shell tools nor platform tools, so an opencode SDD worker can only
read. This policy is pre-existing mainline work, not S1: the deny lines came in with
`fdff463d feat(runtime): governed Qoder + OpenCode cores in HOST and SANDBOX, LangChain
ADK retired (#92)`, and `git show main:...OpenCodeCoreAdapter.java` carries the same
`"edit": "deny"`. Gate A saw the same wall in HOST and called it a HOST toolset blocker;
this run shows the SANDBOX arm is blocked by the same policy, so SANDBOX is not an
alternative delivery path for opencode workers.

## 7. Classification and consequence

- **Class:** product policy (pre-existing), not environment, not flake, not an S1
  regression.
- **Verified in this run:** sandbox creation from the local image, run-owned core launch,
  real model turns, BA spec generation, SPEC_REVIEW approval and decision, git branch
  creation and spec commit, workflow rescheduling on DEFECT, cancel settlement with a
  true stop proof, and one whitelisted manifest row per executed turn with the memory
  section present.
- **NOT VERIFIED:** an implementation produced by DEV, QA execution of the project's
  tests, and a passing SDD deliverable. The chain cannot reach a real COMPLETED verdict
  under the current worker tool policy.
- **Other untested route:** the qoder core has an ACP permission channel and could carry
  worker writes, but this host has no qoder credential (`QODER_PAT`/`QODER_API_KEY` were
  absent before this attempt and the CLI login is not adopted by the product, F1). That
  route remains NOT VERIFIED rather than simulated.

No credential or `.env` value was printed at any point. The chain branch is a normal SDD
artifact on the project repository; it was not merged.

## 8. Post-fix rerun: the same scenario reaches COMPLETED

F-E2E-1 was fixed in four commits on `feat/agent-os-memory`:

- `e4aee2d7` — RED: the sandbox-worker read-only tools test.
- `370513e9` — GREEN: a SANDBOX worker that is not the Aria assistant gets a second
  governed document with `edit/write/patch/bash: allow` (and the same `*`, task,
  question, `external_directory` refusals). Aria and every HOST run keep the shipped
  document byte-for-byte (`governedConfigurationJson()` is unchanged);
  `RunMcpWiring` is untouched, so a worker still gets no platform MCP block.
- `629ce79e` — structural tests (exact 15 permission keys and order, the worker differs
  from the shipped document in exactly the four flipped keys, Aria/HOST stay read-only,
  the Aria sibling keeps its MCP URL/header/bearer) plus the truthful javadoc: the
  container is the real security boundary and the uploaded config is a rewriteable
  guardrail.
- `89c565f1` — no-MCP case hardened; final HEAD of the lane.

The live rerun used the fat jar rebuilt at `89c565f1`
(`agent-control-tower/act-app/target/act-app-0.1.0-SNAPSHOT.jar`, 95,222,833 bytes,
sha256 `3f14484b4d9f61326b8525590c3b7eb17d92ae7ba9bcbaed96d9423d61b58e0a`, built
2026-11-10 20:25 +0800) on the same fresh-H2 `h2` profile and the same local
OpenSandbox server and image as section 1.

Chain `b3019551-f2f7-40f0-ab56-2e776c1ff2cb` (`development-workflow-instance`) reached
`COMPLETED` at `2026-11-10T13:09:24Z`; `GET /api/v1/workflows/{id}` reports steps
`[COMPLETED, COMPLETED, COMPLETED]`. All three executed runs are opencode/SANDBOX:

| Step | Run | Status | Tokens | Completed (UTC) |
|---|---|---|---|---|
| BA | `0bad2637-6edb-421f-a390-0faa4dfc461f` | COMPLETED | 257 | 12:30:54 |
| DEV | `e0eefcf3-ced0-4511-b7ba-e60f134f05c7` | COMPLETED | 627 | 12:49:51 |
| QA | `2c942372-9266-4739-85db-46ed91c7ed1a` | COMPLETED | 397 | 13:09:23 |

The SPEC_REVIEW approval was decided through the operator route (HTTP 200) and the spec
was committed on the chain branch.

- **DEV did the work for issue #112 in the sandbox.** It cloned `/workspace/repo`, found
  the sandbox image carries no JDK or Maven, bootstrapped JDK 21.0.13 and Maven 3.9.9
  under `/workspace/.tools`, implemented the spec-artifact locator (12 files,
  +1,050/−38: `RunArtifactLocator`, `RunArtifactProperties`, `RunArtifactStartupCheck`,
  `SpecArtifact`, `SpecReadResult`, the SDD coordinator wiring and tests), ran act-common
  151 tests, act-execution 1,351 (11 skipped), act-knowledge 269 (4 skipped) and the
  act-app subset, and pushed `8b5447cf` (`sdd dev`).
- **QA verified the implementation.** Its report records the full unit tier
  2,609 tests / 0 failures / 0 errors / 19 skipped, the integration tier for
  act-common/act-execution/act-knowledge 109 / 0 / 0 / 10, and a green full
  compile/install. One earlier integration run failed with 17 environment errors because
  `packages/qoder-acp-bridge/dist/main.js` had not been built; the QA run recorded it as
  failed and did not use it as evidence, then rebuilt the bridge and reran green. The
  report is committed as `b4401f40` (`sdd qa report`) with only `qa_report.md` in
  `git diff --stat HEAD~1`. Final text: `VERDICT=PASS`, `REPORT_ID=dc36ab84-a393-489f-8fdb-9ca8809d4c6f`.

Branch `sdd/b3019551-f2f7-40f0-ab56-2e776c1ff2cb` now carries
`b4401f40 sdd qa report`, `8b5447cf sdd dev`, `bac3aa5d sdd: approve spec`. It was not
merged; the deliverable is the scenario's evidence, not a mainline change.

This is the option-1 acceptance: the F-E2E-1 fix makes an opencode SDD worker able to
deliver in its run workspace while Aria and every HOST run stay read-only, proven by a
real chain that reached COMPLETED with no issue and no defect.

Residual (recorded, not a blocker for this scenario): a bash-enabled worker can still
reach the backend/MCP over loopback when `aria.mcp.auth-mode=none` (reviewer P2,
INFERRED), and a worker can rewrite its own `launch-record.json` because it runs as the
same unprivileged user (F-E2E-2). Both are documented in the QA report and are platform
follow-ups.

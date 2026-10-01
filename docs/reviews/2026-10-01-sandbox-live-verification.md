# Sandbox live verification - PR #94 (`fix/sandbox-execd-readiness-race`)

Date: 2026-10-01
Scope: the run-owned OpenCode core in SANDBOX mode, on the PR head (round 1 at `a808167a`,
round 2 at `2f3230ec` after the operator-provider wiring).
Host: Windows dev machine, podman 5.8.3 (WSL machine), local-dev topology (backend +
dashboard on the host, OpenSandbox server in a container), image
`localhost/aria-conductor/opencode-sandbox:1.1` (opencode 1.18.15, `/opt/aria/launch.mjs`
present).

This report follows the AGENTS.md evidence discipline: every claim below cites a command
that was run or an artifact path, with the observed output quoted. Anything not directly
observed is labelled NOT VERIFIED.

## Method

```pwsh
pwsh -NoProfile -File scripts/start.ps1 -NonInteractive     # stack + sandbox image + OpenSandbox server
curl -s -X POST http://127.0.0.1:8080/api/v1/agents -H "Content-Type: application/json" -d '{
  "name":"sbx-live-check","agentType":"NATIVE","role":"tester","model":"gpt-4o",
  "adkProvider":"opencode","executionMode":"SANDBOX","workspaceMode":"DIRECT",
  "workspacePath":"C:/Users/User/AppData/Local/Temp/aria-sbx-live",
  "config":{"taskApprovalRequired":false}}'
curl -s -X POST http://127.0.0.1:8080/api/v1/runs -H "Content-Type: application/json" \
  -d '{"agentId":"7b8560f6-c159-4026-8868-610d0c2bac72","promptSeed":"Read README.md and reply with the single word READY."}'
```

Two runs were executed: `fd9d9065-25d1-46eb-9779-5b51bf479bef` and
`c17db9df-24b2-4821-aeb7-0cee3961c8a2`. The second run was watched by a polling script that
catches the run-owned container (created via the OpenSandbox server) and dumps the delivered
configuration from inside it while the run is alive:

```bash
IMAGE=localhost/aria-conductor/opencode-sandbox:1.1
for i in $(seq 1 240); do
  ids=$(podman ps -q --filter "ancestor=$IMAGE" 2>/dev/null)
  for id in $ids; do
    podman exec "$id" sh -c '
      find /home/aria/run -maxdepth 5 -type f 2>/dev/null | sort
      grep -o "\"XDG_[A-Z_]*\":\"[^\"]*\"" /home/aria/run/*/launch-manifest.json 2>/dev/null
      cat /home/aria/run/*/config/opencode/opencode.json 2>&1
      for f in /proc/[0-9]*/comm; do cat "$f" 2>/dev/null; done | sort -u | head -20
    ' 2>&1
    exit 0
  done
  sleep 0.5
done
```

Backend-side evidence is read from `.run/backend.log`, which `scripts/start.ps1` writes.

## Observed: the run chain (both runs, `.run/backend.log`)

```
20:44:32.106 CoreRunLauncher : Froze run fd9d9065-... to core opencode/SANDBOX
20:44:34.662 Sandbox : create sandbox with startup source aria-conductor/opencode-sandbox:1.1
                     operation completed for sandbox 64c8519d-... (skipHealthCheck=true, sandbox may not be ready yet)
20:44:34.709 WARN RetryInterceptor : retrying POST http://localhost:44513/proxy/44772/files/upload:
                     attempt=2 cause=pre_send status=null backoff=500ms
20:44:35.246 SandboxLifecycle : Uploaded 1 entry(ies) into sandbox 64c8519d-... for run fd9d9065-...
20:44:35.260 SandboxLifecycle : Uploaded 1 run configuration entry(ies) into 'config' of run fd9d9065-...
20:44:35.260 OpenSandboxSdk : Launching the run-owned core in sandbox 64c8519d-... via the fixed image launcher
20:44:45.812 OpenSandboxSdk : Writer control 'stop' in sandbox 64c8519d-... exited with 0:
                     {"action":"stop","writersStopped":true,"terminated":[30],"remaining":[]}
20:44:46.743 SandboxesAdapter : Successfully terminated sandbox: 64c8519d-...
```

Run `c17db9df-...` repeated the identical chain (create at 20:45:42, the same
`RetryInterceptor ... attempt=2 cause=pre_send` upload retry at 20:45:42.650, uploads at
20:45:43.192/.202, launch at 20:45:43.203, stop-writers + terminate at 20:45:53).

Claims this confirms:

1. The execd warm-up race is REAL and reproducible: both runs hit a transport-level upload
   failure immediately after create (`skipHealthCheck=true`, execd not ready) and both were
   absorbed, then the upload completed.
2. The governed configuration upload runs before the launch
   ("Uploaded 1 run configuration entry(ies) into 'config'").
3. No session-open 502 occurred: each run reached a native session id (`ses_...`, quoted in
   the failure below), i.e. the health-gated `openSession` succeeded.
4. No `400 Expected object | null ... [\"model\"]`: the governed prompt POST was accepted and
   the failure came back as a *message-envelope* provider error.
5. Teardown is clean: stop-writers reports `writersStopped: true` and the sandbox is
   terminated; `podman ps -a` afterwards shows only `aria-opensandbox` (no orphaned run
   sandboxes).

## Observed: the run outcome (both runs FAILED fast, not hung)

```
curl http://127.0.0.1:8080/api/v1/runs/c17db9df-24b2-4821-aeb7-0cee3961c8a2
{"status":"FAILED","iterationCount":0,"totalTokensUsed":0,
 "errorMessage":"Run c17db9df-... failed: OpenCode returned an assistant message error for
 /session/ses_f088019a8ffeb0JyIrTtvB1cQ7/message: APIError: Error from provider (Console):
 OpenCode's free tier can only be used from within OpenCode",
 "createdAt":"2026-10-01T12:45:41.689869Z","updatedAt":"2026-10-01T12:45:53.9725..."}
```

Run `fd9d9065` produced the same failure (session `ses_f088122f2ffeJ3f7uNIF6Aob6s`). Wall
clock: ~14 s and ~12 s from run creation to terminal state - the failure mode this PR fixes
was a 45-minute hang until the deadline.

## Observed: the delivered configuration, read from inside the live sandbox

Captured by `.run/watch-sandbox.sh` against the run-owned container of `c17db9df-...`:

```
--- files delivered into the run control tree ---
/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/config/opencode/opencode.json
/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/data/opencode/log/opencode.log
/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/data/opencode/opencode.db
/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/launch-manifest.json
/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/launch-record.json

--- XDG roots recorded in the launch manifest ---
"XDG_CONFIG_HOME":"/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/config"
"XDG_DATA_HOME":"/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/data"
"XDG_CACHE_HOME":"/home/aria/run/c17db9df-24b2-4821-aeb7-0cee3961c8a2/cache"

--- governed opencode.json delivered at XDG_CONFIG_HOME ---
{ "$schema": "https://opencode.ai/config.json",
  "permission": { "*": "deny", "read": "allow", "list": "allow", "glob": "allow",
                  "grep": "allow", "edit": "deny", "write": "deny", "patch": "deny",
                  "bash": "deny", "webfetch": "deny", "task": "deny", "question": "deny",
                  "external_directory": "deny" } }

--- processes inside the sandbox ---
bootstrap.sh  execd  node  opencode  sh  sort  tail   (sort/tail are the exec pipeline itself)
```

Claims this confirms:

1. The governed deny-by-default configuration is delivered, unmodified, exactly where the
   manifest's `XDG_CONFIG_HOME` points - the failure this PR fixes (a host path resolving
   relative inside Linux, which silently lost the policy) does not reproduce.
2. opencode really consumed the XDG layout: it wrote its own state under
   `XDG_DATA_HOME/opencode/...`, so the manifest environment reached the core process.
3. The core ran from the fixed image launcher (`opencode` present next to `execd`).

## Round 2: the operator's model provider (`2f3230ec`)

Round 1 left the run without a served model: the governed document had no `model`/`provider`
member and the provider credential never reached the sandbox, so opencode fell back to its
own default and the free tier refused the consumer. `2f3230ec` wires the operator's active
`LlmProvider` into the governed document and injects `opencode.sandbox-env` into the launch
environment. Re-verified live with DeepSeek as the operator provider.

### Observed: the delivered document and the launch environment (live sandbox)

```
"model": "deepseek/deepseek-flash",
"provider": { "deepseek": { "npm": "@ai-sdk/openai-compatible",
                            "options": { "apiKey": "{env:LLM_API_KEY}",
                                         "baseURL": "https://api.deepseek.com" },
                            "models": { "deepseek-flash": {} } } }
...
--- provider key present in a sandbox process env? ---
LLM_API_KEY: yes
```

The permission policy is unchanged; the key is an env reference, never a literal in the file.

### Observed: opencode really calls the configured provider (stub proof)

A stub OpenAI-compatible endpoint inside the image received, from the governed document:

```
REQUEST POST /v1/chat/completions
AUTHORIZATION Bearer <LLM_API_KEY from the serve environment>
BODY {"model":"deepseek-flash","max_tokens":32000, ...}
```

and opencode completed the turn (`llm.provider=deepseek llm.model=deepseek-flash`). The
wiring - provider block, env reference, model id - is therefore proven end to end.

### Observed: the real provider answers (and what it answers)

Run `d695e31f-d75d-42df-bbda-1e2bd5c22b13` FAILED with the provider's own diagnosis:

```
APIError: Insufficient Balance (request_id: a55b2926-656b-407f-9cd0-4e028dc79432)
```

The chain reaches the model endpoint with the operator's key and model; the endpoint refuses
on account balance.

### Findings for the operator

- `LLM_MODEL=deepseek-v4-flash` (`.env`, and therefore the auto-created `LlmProvider` row)
  is not a model the endpoint serves. `GET https://api.deepseek.com/v1/models` lists
  `deepseek-flash` and `deepseek-v4-pro`; opencode fails the run with
  `ProviderModelNotFoundError: Model not found: deepseek/deepseek-v4-flash. Did you mean:
  deepseek-flash, deepseek-v4-pro?`. The local provider row was updated to `deepseek-flash`
  for this verification; the `.env` value still needs the operator's decision (it also feeds
  the backend's own LLM client).
- The DeepSeek account behind the provided key has no balance: both
  `https://api.deepseek.com/chat/completions` and `.../v1/chat/completions` answer
  `402 Insufficient Balance` for `deepseek-flash` and `deepseek-v4-flash` alike.

### Observed: one cold-start upload failure that exhausted both retry budgets

Run `e3e55e63-319a-4ea7-b092-7e09d3536724` failed before the core started:
`Workspace upload failed ...: Network connectivity error: Failed to connect to
localhost/127.0.0.1:59115`. The SDK's transport retried 4 attempts (~2 s total) and the
bounded upload retry consumed all 5 attempts (~18 s wall clock) before failing, i.e. the
freshly published endpoint was unreachable for ~18 s right after a stack restart; later runs
on the same stack succeeded immediately. Recorded as a warm-up-lag observation on the
Windows/WSL published-port relay - the run fails loudly instead of hanging, and a re-run
succeeds, but the budget may deserve a review if this recurs.

## NOT VERIFIED

- A successful model turn against the operator's gateway: every hop is proven (stub provider
  completes the turn; the real endpoint answers), but the account returns
  `402 Insufficient Balance`, so no agent answer, tool call or `finalOutput` was produced.
- The deny policy blocking a real tool call: no model turn completed, so no tool was invoked.
- Approval-gated runs: the verification agent was created with
  `"config":{"taskApprovalRequired":false}`; the approval path was not exercised.
- A non-default `SandboxLifecycle` control root (the cross-hop test covers the production
  default wiring only).

## Teardown

The stack is still running after this report (`pwsh -NoProfile -File scripts/stop.ps1`
stops the backend/dashboard/OpenSandbox container started by `start.ps1`). Seven runs (all
FAILED, each for the reason recorded above) and the agent `sbx-live-check` remain in the
local H2 database.

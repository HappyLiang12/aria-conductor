# OpenCode Sandbox Input Injection & Feedback Loop - Design Spec

## 1. Summary

Round-4 QA (PR #71 @ fe34bea) proved the opencode-mode SDD loop fails because
the BA agent runs in an EMPTY sandbox with NO issue content: the session calls
the `question` tool ("where is issue #38?") which hangs forever (no answer API
exists in opencode serve - verified: issues #19702/#27644, closed as not
planned), producing a zombie session that the synchronous HTTP POST watches
until the task deadline kills it.

This spec fixes the input gap and replaces the hang-prone interaction path with
a deterministic feedback loop through our OWN governance layer.

Design decisions confirmed with the user:

- Issue content: sandbox-side `gh` CLI reads the issue (image installs gh,
  GH_TOKEN injected as sandbox env).
- Project code: Dev agent checks out the project independently in the sandbox
  (git clone with GH_TOKEN).
- Feedback loop: route questions back to Aria/the user WITHOUT the opencode
  `question` tool - BA emits a QUESTIONS section in its spec; the SPEC_REVIEW
  approval UI presents it; the user answers via REJECT reason; the reason
  already flows back to the re-scheduled BA step (verified in
  SpecReviewCoordinator L169-170).

## 2. Root cause chain (verified)

```
instantiateTemplate("issueRef"="#38")
  -> Run.promptSeed = "Write spec for #38"        [issue body never enters data flow]
  -> buildTaskPrompt() = system rules + prompt    [no project context]
  -> executeTask -> sandbox /workspace            [workspaceBase/<agentId> is EMPTY: 0 files, verified]
  -> BA has nothing to analyze
  -> read tools find nothing -> question tool asks user
  -> question hangs (no answer endpoint in opencode serve)
  -> zombie session (dead ~10s in, 327 tokens)
  -> sync POST waits -> deadline (max-task-minutes) kills it
```

Secondary findings: application.yml `max-task-minutes: 30` overrides the code
default 45 (R4-F1); sandbox model is deepseek-v4-pro while the DB-activated
provider is deepseek-v4-flash (R4-F6); diagnosis endpoint emits empty process
sections because `ps` is missing in the image (R4-F5).

## 3. Design

### D1: Sandbox image - install gh CLI (opencode-sandbox)

File: `agent-control-tower/opencode-sandbox/Dockerfile`

- Install `gh` via the official GitHub CLI apt repo (Debian bookworm has no gh
  in default sources): keyring + sources.list.d + apt-get install gh.
- `git` already present in node:22-slim (buildpack-deps base).
- Bump image tag usage: `aria-conductor/opencode-sandbox:1.1` in
  `application.yml` (image property). Keep the 1.0 build instructions working.

### D2: GH_TOKEN credential injection

Files:
- `agent-control-tower/act-app/src/main/resources/application.yml`:
  `opencode.sandbox-env.GH_TOKEN: ${GH_TOKEN:}` (alongside DEEPSEEK_API_KEY)
- `scripts/start-backend.ps1`: preflight adds a warning when `-AdkProvider
  opencode` and GH_TOKEN is unset ("BA/Dev agents need GH_TOKEN to read issues
  and clone repos in the sandbox").
- gh requires no login flow when GH_TOKEN is set (headless verified).

### D3: opencode.json injection - disable question + fix model propagation (R4-F6, zombie source)

File: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java` (+ maybe a small builder)

- Before `uploadWorkspace`, generate/write an `opencode.json` into the agent
  workspace dir (workspaceBase/<agentId>) containing:
  ```json
  {
    "$schema": "https://opencode.ai/config.json",
    "permission": { "question": "deny" },
    "model": "<dbActiveProviderId>/<dbActiveDefaultModel>",
    "provider": {
      "<dbActiveProviderId>": {
        "options": {
          "apiKey": "{env:LLM_API_KEY}",
          "baseURL": "<dbActiveBaseUrl>"
        }
      }
    }
  }
  ```
- Source of truth: the ACTIVE DB LlmProvider (find the repository/service used
  by the execution module to resolve the active provider - grep
  LlmProviderRepository usage in act-execution). Fallback: OpenCodeProperties
  defaults (deepseek/deepseek-chat, api.deepseek.com/v1).
- This kills the zombie source deterministically (opencode `permission`
  config is the documented way; the old `tools` boolean config is deprecated).
- Env key: sandbox-env already injects DEEPSEEK_API_KEY; ALSO inject
  LLM_API_KEY as the generic alias so `{env:LLM_API_KEY}` resolves for
  OpenAI-compatible providers (add to application.yml sandbox-env:
  `LLM_API_KEY: ${LLM_API_KEY:${DEEPSEEK_API_KEY:}}`).

### D4: BA prompt guidance - fetch issue via gh (fixes the input gap)

Files:
- `agent-control-tower/act-app/src/main/resources/db/migration/V44__sdd_prompt_issue_guidance.sql` (new)
- Template YAML (V40/V43-seeded 'development-workflow'): extend BA prompt:

  "If the issue body is not already in your prompt, fetch it first with:
   gh issue view {issueRef} -R {issueRepo} --json title,body,labels
   (GH_TOKEN is already configured). Analyze the issue and write a spec with
   sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error
   Handling. If anything is ambiguous and requires the user to decide, end the
   spec with a '## Questions' section listing each question on its own line;
   omit the section when nothing is ambiguous. NEVER ask interactive questions
   - put everything into the spec."

- Template parameters: instantiateTemplate already substitutes {issueRef};
  add {issueRepo} to the parameter set (Aria passes owner/repo).

### D5: Dev prompt guidance - independent project checkout

Template YAML DEV prompt (same V44 migration):

  "Check out the project first: git clone {repoUrl} /workspace/repo (GH_TOKEN
  is configured for private repos). Implement the spec referenced by {specRef}.
  Report test commands and results in your final output."

- Add {repoUrl} to the template parameter set (Aria passes it).

### D6: Feedback loop via SPEC_REVIEW (user decision)

- No opencode question tool (no answer API exists - verified).
- BA emits '## Questions' in the spec (D4) -> the spec content is shown in the
  SPEC_REVIEW approval UI (markdown rendered, already working).
- User REJECTs with the answers in the reason field -> `rescheduleStep` already
  appends "Spec was rejected: <reason>" to the BA step re-run (verified
  SpecReviewCoordinator L169-170) -> BA prompt guidance (D4) already says to
  read the rejection feedback on re-run. Add one line to the BA prompt:
  "If the task message contains rejection feedback (Spec was rejected: ...),
  incorporate the reviewer's answers into the revised spec and drop the
  Questions section for answered items."
- Aria-side: AriaService/AriaDefaultAgentInitializer prompts gain: "When a
  SPEC_REVIEW rejection contains user answers, pass them along; Aria may also
  answer trivial questions itself using the issue body." (light-touch; the
  deterministic path is the user REJECT reason.)

### D7: Config fixes

- `application.yml` `opencode.max-task-minutes`: 30 -> 45 (R4-F1; matches the
  code default).
- Diagnosis endpoint hardening (R4-F5): OpenCodeSandboxManager.diagnose -
  process snapshot falls back to reading `/proc` when `ps` is absent (image
  has no procps); metrics rendered as JSON (gson/jackson available in
  act-execution) instead of object toString.

## 4. Non-goals

- opencode question-answer round trip via messageID reply (speculative, closed
  upstream) - replaced by D6.
- SSE streaming monitor (GET /event has version-specific bugs #26697/#27966;
  prompt_async + polling is a future enhancement).
- Mounting the project repo directly (SDK text-upload limits) - replaced by
  D5 git clone.

## 5. Test plan

- OpenCodeAdkProviderTest: workspace opencode.json generated with
  permission.question=deny + provider from active DB LlmProvider (mock
  repository); fallback when no active provider.
- OpenCodeSandboxManagerTest: diagnose proc fallback path + metrics JSON.
- SddWorkflowIntegrationTest: template parameters {issueRepo}/{repoUrl}
  substitute correctly; BA prompt contains gh guidance; DEV prompt contains
  clone guidance (V44 seed assertions, extend V43SeedConfigTest).
- Script/manual: rebuild image 1.1, `gh issue view -R` inside sandbox with
  GH_TOKEN, one real BA run producing a spec (or a spec + Questions) end to end.

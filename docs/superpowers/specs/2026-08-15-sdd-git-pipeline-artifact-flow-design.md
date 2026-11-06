# SDD Git Pipeline Artifact Flow & Verdict Fallback - Design Spec

## 1. Summary

Round-7 QA (PR #71 @ 9b7db35) achieved the first full-chain SDD execution in
opencode sandbox mode (BA -> SPEC_REVIEW -> approve -> Dev -> QA) and exposed 4
gaps, all traced to 3 systemic design omissions: no inter-step artifact flow
(workflow continuity), context injection assuming langchain-style backend tools
(opencode sandboxes have none), and a single-channel verdict (weak model must
reliably call submit_dod_review).

Design decisions confirmed with the user:

- Artifact handoff: Git push/pull on a chain-scoped temporary branch
  (sdd/<chainId>) - Dev pushes, QA clones; backend GitHub-API fallback
  guarantees artifacts survive weak-model git failures.
- Spec injection: the spec travels WITH the Git branch (spec/spec.md committed
  to sdd/<chainId>), so Dev/QA read the exact spec version that matches the code.
- Verdict fallback: QA prompt mandates VERDICT=<PASS|DEFECT|SPEC_GAP> marker;
  WorkflowAutoChainer parses the marker as an equivalent verdict source when
  submit_dod_review was not called (same marker pattern as SPEC_ID/REPORT_ID).

## 2. Root causes (from R7)

- R7-F1 (no workspace continuity): D5 designed Dev's INPUT (git clone) but never
  the OUTPUT (push/handoff). Per-agent sandboxes isolate each step's products.
- R7-F3 (spec body missing downstream): injectSpecReference injects a UUID
  reference only, assuming the agent can query the backend knowledge base;
  opencode sandbox agents have no backend tools.
- R7-F2 (verdict lost): verdict delivery is a single channel (tool call); the
  weak model produced qa_report + REPORT_ID but skipped submit_dod_review.
- R7-F4 (Dev confabulation): symptom of R7-F3 - without the spec body, Dev
  wandered and claimed unrun tests passed.

## 3. Design

### D-A: Git-centric pipeline (R7-F1, R7-F3)

Data flow:

```
instantiateTemplate(params: issueRef, issueRepo, repoUrl)
  -> chainId known -> branchName = sdd/<chainId>
BA sandbox: gh issue view -> write spec -> SPEC_ID marker
  -> user approves (SPEC_REVIEW)
  -> SpecReviewCoordinator.onApproved calls GitBranchService:
     1. create ref refs/heads/sdd/<chainId> from the repo default branch
     2. PUT spec/spec.md (approved spec body) to that branch
DEV sandbox (prompt): git clone --branch sdd/<chainId> {repoUrl} /workspace/repo
  -> read /workspace/repo/spec/spec.md -> implement -> run REAL tests
  -> git commit + push origin sdd/<chainId>
  -> backend fallback: if branch HEAD has no Dev commit after the step,
     GitBranchService collects /workspace/repo files from the Dev sandbox
     (OpenSandbox SDK files().read) and writes them back to the branch
QA sandbox (prompt): git clone --branch sdd/<chainId> {repoUrl} /workspace/repo
  -> verify code vs spec/spec.md -> qa_report.md -> VERDICT= marker
```

**GitBranchService** (new, act-execution, `io.aria.conductor.execution.git`):
- Pure GitHub REST API client (java.net.http + Jackson, no git binary):
  - `createBranch(repoUrl, branchName)` - get default branch + create ref
  - `putFile(repoUrl, branchName, path, content, message)` - Contents API PUT
  - `getFile(repoUrl, branchName, path)` - Contents API GET (base64 decode)
  - `branchHeadSha(repoUrl, branchName)` - compare/list refs
- Credential: GH_TOKEN environment variable (same var injected into sandboxes;
  backend reads it via Spring Environment/`${GH_TOKEN:}` binding).
- Errors: all failures logged + wrapped in a domain exception; spec-commit
  failure fails the approval transition loudly (no silent skip).

**Backend fallback collection** (weak-model push guarantee):
- After the Dev step completes (WorkflowAutoChainer Dev-step handler), if
  GitBranchService.branchHeadSha shows no new commit, collect the sandbox
  workspace text files via the existing OpenSandboxManager read path and write
  them to the branch via putFile. Small files only (same text-upload constraints
  as uploadWorkspace). This is the insurance path; the primary path is the
  agent's own push.

### D-B: Verdict marker fallback (R7-F2)

File: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/WorkflowAutoChainer.java`

- Extract `private Optional<QaVerdict> parseVerdictMarker(String output)` -
  regex `VERDICT\s*=\s*(PASS|DEFECT|SPEC_GAP)` (case-insensitive).
- In the no-verdict branch: try parseVerdictMarker(finalOutput) first; when
  present, apply the verdict through the SAME routing the tool path uses
  (extract `applyVerdict(chain, verdict)` shared by both paths so PASS/DEFECT/
  SPEC_GAP behavior is identical); when absent, keep the current failure
  message (F17).
- Marker is treated as equivalent to a tool submission - DoD record update,
  chain state transitions, and event publishing are identical.

### D-C: Dev/QA prompt corrections (R7-F4, R7-F3)

V45 migration (new: `agent-control-tower/act-app/src/main/resources/db/migration/V45__sdd_pipeline_prompts.sql`):

1. DEV prompt REPLACE (V44 text -> pipeline version):
   "Check out the project first: git clone --branch {branchName} {repoUrl}
   /workspace/repo (GH_TOKEN is configured for private repos). Read the spec at
   /workspace/repo/spec/spec.md and implement ONLY what it requires. Make code
   changes inside /workspace/repo. Run the real test commands and report their
   actual output. Do NOT claim tests passed unless you ran them and saw them
   pass. When done: git add -A && git commit -m 'sdd dev' && git push origin
   {branchName}."
2. QA prompt REPLACE (V40/V44 text -> pipeline version):
   "Check out the work first: git clone --branch {branchName} {repoUrl}
   /workspace/repo (GH_TOKEN is configured for private repos). Verify the code
   in /workspace/repo against the spec at /workspace/repo/spec/spec.md. Run the
   real tests and record their actual results. Write your findings to
   /workspace/qa_report.md. Submit your verdict with the submit_dod_review tool
   AND end your output with VERDICT=<PASS|DEFECT|SPEC_GAP> then
   REPORT_ID=<uuid>."
3. Verify from-strings against the exact stored text after V44 (read V44 SQL
   first; adjust byte-for-byte).

### D-D: Template parameters and Aria guidance

- Template declares {repoUrl} and {branchName} placeholders (extracted from the
  YAML by the existing parameter-name extraction - the whitelist accepts them
  automatically since it validates against declared names).
- branchName is generated by the coordinator from the chain id at instantiation
  time; Aria passes repoUrl/issueRepo/issueRef (Aria prompt already guided by
  T7; add "the system fills branchName automatically").
- {specRef} stays (UUID metadata in the QA report), but content comes from
  spec/spec.md.

### D-E: Branch lifecycle

- Branch name: sdd/<chainId> (UUID - unique, no collision).
- Created on spec approval; no auto-deletion this round (branches are the audit
  trail); cleanup is a follow-up.

## 4. Non-goals

- Shared chain-level sandbox (rejected in favor of Git handoff per user
  decision).
- Automatic branch cleanup / stale-branch GC.
- GitHub PR creation from sdd/<chainId> (review flow follow-up).
- Backend-driven test execution (tests run inside agent sandboxes).

## 5. Test plan

- GitBranchServiceTest (WireMock against api.github.com paths): createBranch
  (ref create + default branch lookup), putFile (Contents PUT with base64),
  getFile, branchHeadSha; error mapping for 404/401.
- SpecReviewCoordinatorTest: onApproved calls createBranch + putFile with the
  spec content; failure fails the transition.
- WorkflowAutoChainerSddTest: no-verdict + VERDICT=PASS/DEFECT/SPEC_GAP marker
  -> routes identically to tool path (chain state + DoD assertions); marker
  absent -> existing failure message.
- V43SeedConfigTest (extend): V45 prompts contain clone --branch {branchName},
  spec/spec.md, VERDICT= guidance; migration applies cleanly.
- WorkflowTemplateServiceTest/Integration: {branchName}/{repoUrl} substitute in
  instantiated prompts.
- MCP e2e driver (extend, nightly): after APPROVE, poll GitBranchService branch
  state (spec/spec.md present) and the QA verdict routing end-to-end.

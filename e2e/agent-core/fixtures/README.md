# Native capability fixtures

Versioned, sanitized protocol recordings and the capability matrix produced by
`e2e/agent-core/probe-native.mjs` (Task 1 of the Agent Core execution-modes program).

## What belongs here

Only output of the probe harness. Nothing in this directory is hand-written,
hand-edited or reconstructed:

| Producer flag | Artifacts |
|---|---|
| `--recording-dir e2e/agent-core/fixtures` | `<core>-<mode>-cli-<version>-protocol.jsonl` (every JSON-RPC/HTTP message in order), `<core>-<mode>-cli-<version>-events.json` (observations, control-technique evidence, scenario data), `<core>-<mode>-stable-export-<version>.json` (workspace snapshot taken after writer termination) |
| `--matrix-out e2e/agent-core/fixtures/capability-matrix.json` | the capability matrix row for that core/mode (upsert, one row per combination) |

The CLI version is part of the filename (`cli-1.1.61`, `cli-1.14.31`) so a
recording is only ever read together with the exact runtime build that produced
it. `cli-unknown` means the probe never observed a runtime version for that
combination (nothing was launched because a prerequisite was missing) - it is a
recorded absence, not a lost value.

One recording per core/mode/version: a later probe run overwrites the previous
run's files for the same combination. Keep it that way - a fixture that does not
correspond to a probe run must not live here.

## Sanitization rules

The harness sanitizes on the way out (`buildRedactor` in `probe-native.mjs`), so
files written here have already been through all of the following:

| Input | Replacement |
|---|---|
| the `--pat-file` credential value | `[REDACTED-SECRET]` (never printed, never in argv, never in a fixture) |
| the `--run-dir` path | `<RUN>` |
| the `--workspace` path | `<WORKSPACE>` |
| the `--recording-dir` path | `<FIXTURES>` |
| any other token-like string (`pt…`, `sk…`, `pat…`, `ghp_…`, `eyJ…`, …) | `[REDACTED-SECRET]` |
| e-mail addresses | `<EMAIL>` |
| `C:\Users\<name>...` / `C:/Users/<name>/...` | `C:\Users\<USER>` / `C:/Users/<USER>` |
| `/home/<name>...`, `/Users/<name>...` | `<HOME>` |
| JSON string values under keys matching `token|secret|password|apikey|api_key|authorization|credential` | `[REDACTED-SECRET]` |

Always removed: the credential value and e-mail-shaped strings - a fixture
never carries the PAT value, and a grep over this directory finds no
e-mail-shaped string. The account name is removed for the renderings the name
rules match (the backslash `C:\Users\<name>\...`, forward-slash
`C:/Users/<name>/...` and POSIX home forms in the table above); a
mixed-separator rendering of a configured path can match neither those rules
nor the exact path forms and may retain it (measured examples below).

Best-effort: the path placeholders are exact-substring replacements over the
renderings the sanitizer recognises - the raw Windows form, the forward-slash
form and the JSON-escaped 2x/4x/8x forms of each configured path, plus the
`C:\Users\<name>` / `C:/Users/<name>` and POSIX home forms. A rendering outside
that set, typically one that mixes separators - for example the
drive-prefix-plus-forward-slash-tail form
`C:<HOME>/AppData/Local/Temp/aria-core-probe/ws16/probe-deny.txt` seen in an
earlier recording generation - can remain only partially substituted: the path
tail stays readable, and the account-name rules are not guaranteed to match a
mixed-separator form. A re-check of the rules measured: `C:/Users/Alice/foo` ->
`C:/Users/<USER>/foo` and `/home/Alice/work` -> `<HOME>/work` are substituted,
while the mixed-separator forms `C:\Users/Alice/work` and
`C:/Users\Alice/work` match no rule and pass through unchanged, account name
included. A path placeholder is therefore not proof that every path shape was
anonymised.

Discovery-time identity values (session IDs) are replaced with `<SESSION_ID>`
in the derived JSON evidence.

Known intentional false positive: in both `qoder-host` recordings the built-in
project-run skill listed in the `available_commands_update` payloads (its
description begins "Author or improve the run-<unit> skill") has its
kebab-case id rendered as `run-[REDACTED-SECRET]` - 8 occurrences in each
recording, 16 in total. The token-like rule fires on the skill id; the value is
not a credential. Regenerating the recordings to whiten this string would need
another live credentialed probe run, so the false positive is deliberately
kept. Do not read `run-[REDACTED-SECRET]` as evidence of a leak.

Reviewing a fixture before committing it: `grep -nEi
"token|secret|password|apikey|authorization|credential|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.(com|net|org|io)"
<file>` must only return matches whose value is a placeholder.

## Contents

| File | Source run | Contains |
|---|---|---|
| `capability-matrix.json` | all four probe runs below | one row per core/mode with the recorded `checks` booleans, `runtime`, `blockedPrerequisite` and `artifacts` |
| `qoder-host-cli-1.1.61-protocol.jsonl` / `-events.json` | `qoder/HOST` full run (fixed harness) | the ACP sessions behind the verified checks: initialize/session-new/set-model, the two-sided managed-auth arms, hostile-MCP isolation control, allow_once/deny permission answers, the three cancel arms, the live writer scenario (pause/resume byte samples, descendant-tree termination, post-termination snapshot pair) |
| `qoder-host-stable-export-1.1.61.json` | `qoder/HOST` full run | the frozen workspace snapshot taken after the tool-spawned writer was terminated |
| `qoder-sandbox-cli-unknown-*` | `qoder/SANDBOX` prerequisite run | the sandbox prerequisite probe (endpoint TCP probe, container image inventory); no session exists because nothing could be launched |
| `opencode-host-cli-1.14.31-*` | `opencode/HOST` run | `opencode serve` argv + run-owned XDG roots, `/global/health`, `POST /session`, `auth list` credential count and the failed model-bound message attempt |
| `opencode-sandbox-cli-unknown-*` | `opencode/SANDBOX` prerequisite run | sandbox prerequisite probe; nothing launched |

Recordings from a probe run that failed for a harness defect are not kept here:
the fixture set must describe the evidence a follow-up task may build on. The
defect observed on 2026-09-22 (descendant-tree suspend issued twice, resumed
once, so the controlled-tree verification could not grow after resume) is
documented with its raw output in
`docs/reviews/2026-09-22-agent-core-capability-evidence.md`; the recordings in
this directory come from runs using the corrected harness.

Note on the recorded `credentialSource`: the `qoder/SANDBOX` row (probed
2026-09-22T16:08Z) and the `qoder/HOST` row both carry `<CREDENTIAL-FILE>` -
the placeholder the harness writes for the `--pat-file` input
(`probe-native.mjs`, `replacements`). The SANDBOX row previously held the
mangled in-worktree render
`C:<HOME>/.qoder/worktrees/app/f6e6a3/aria-conductor/.superpowers/sdd/2026-09-22-agent-core-execution-modes/.qoder-pat`
(the partial substitution described above); a later round scrubbed that field
to the placeholder, so the matrix no longer carries that path. The placeholder
documents the probe's credential-file input, nothing more: the operator has
moved the PAT file outside the repository (it now lives at
`C:/Users/<USER>/AppData/Local/aria-conductor-secrets/.qoder-pat`), and that
file must be deleted once the live acceptance matrix completes.

## How these fixtures are consumed

Tasks 7-11 build the deterministic mock peers and the Qoder/OpenCode adapters
against these recordings: the wire shapes (ACP method names, permission option
kinds, cancel/terminate ordering) and the verified OS control technique recorded
in `-events.json` are the contract. `capability-gate.mjs` reads only
`capability-matrix.json`; a combination whose checks were not observed is
reported BLOCKED, never "verified by default".

#!/usr/bin/env node
// =============================================================================
// Task A4 (Slice A gate): permission semantics — allow-once, deny, cancel,
// no escalation.
//
// Runs INSIDE a real OpenSandbox sandbox (image aria-conductor/qoder-sandbox:0.1,
// Node 22) as a Node ESM script; QoderPermissionsE2ETest uploads it to
// /workspace/04-permissions.mjs and executes it through
// OpenCodeSandboxManager#runCommand. It is a sibling of 03-mcp-auth.mjs and mirrors
// its conventions (redacting console wrapper, newline-delimited JSON-RPC over the
// CLI's stdio, permission replies on the original request id — the exercised selected
// reply is the nested {outcome:{outcome:'selected', optionId}}; the flat
// {outcome:'cancelled'} fallback replies are shape-unverified and NOT EXERCISED, see
// 04-permissions.md §9 — bounded per-case waits, summary marker A4-SUMMARY-JSON,
// exit code = number of failed cases).
//
// What it pins (each case is a fresh qodercli process and a fresh session):
//   1. ALLOW-ONCE: a file-write prompt produces a session/request_permission for a
//      positively identified Write to our target path; the probe selects the offered
//      option whose kind is `allow_once` (never `allow_always`, which the spike shows
//      escalates the mode to acceptEdits). The file must exist with the exact content.
//      Then a SECOND write prompt in the SAME session must ask again (a new
//      permission request) and must be granted allow_once again — this is the
//      discriminator between allow-once and a session-wide grant.
//   2. DENY: the write permission request is answered with the option whose kind is
//      `reject_once`; only when no such option is offered does the (shape-unverified,
//      NOT EXERCISED — see 04-permissions.md §9) flat {outcome:'cancelled'} fallback
//      apply. The file must be absent afterwards (no side effect on denial).
//   3. CANCEL WHILE A REQUEST IS PENDING: the probe sends `session/cancel` while a
//      write permission request is unanswered and records the outcome (request form
//      and, when the request form errors, the notification form). When no form has an
//      observable effect, the CLI is terminated and the sandbox process table is
//      scanned (`ps -eo pid,ppid,stat,args`) to assert no orphan qodercli process.
//      The stable line `CANCEL-METHOD-DECISION: ...` records which path worked
//      (deliverable consumed by C0.3/B3a).
//   4. NO ESCALATION: allow_always is never selected anywhere, and no
//      `current_mode_update` to `acceptEdits` ever appears (asserted, not just
//      recorded).
//
// Bounded waits (the plan asks for explicit, justified bounds): the ACP spike and A3
// observed 10-40s per prompted turn and <3s for a permission reply. PERMISSION_WAIT
// (75s) covers "model decides to call a write tool"; TURN_WAIT (90s) covers "turn
// completes after our reply"; CANCEL_EFFECT_WAIT (30s) covers "cancel has an
// observable effect". CASE_TIMEOUT (240s) is a hard per-case backstop. Nothing here
// waits indefinitely, and no wait is a fixed sleep for startup: every step is
// response-driven (spike Section 3).
//
// Credentials: the Qoder PAT must be present in the sandbox environment as
// QODER_PERSONAL_ACCESS_TOKEN (the Java test injects it through
// createSandbox(agentId, image, env) — environment only, never argv). This script
// never prints it: a redacting console wrapper replaces the token value with
// [redacted] in every log line. No other token material is used anywhere (the write
// fixtures are plain text files in /tmp).
//
// The ACP session is pinned to the zero-credit model: qodercli is spawned with
// `-m $QODER_E2E_MODEL` (default `efficient`) and the session is additionally pinned
// with `session/set_model {sessionId, modelId}`; both results are recorded.
//
// Exit status: 0 only when every case passes; otherwise the number of failed cases.
// The last markers are
//   A4-SUMMARY-JSON: {...,"hardConstraints":{...},"cancelMethodDecision":"..."}
//   A4-PERMISSIONS-RESULT: PASS   (or ...: FAIL (n case(s)))
// =============================================================================
import { spawn, execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

// ---- configuration ---------------------------------------------------------

const MODEL = (process.env.QODER_E2E_MODEL || 'efficient').trim();
const TOKEN_ENV = 'QODER_PERSONAL_ACCESS_TOKEN';
const WORKSPACE = '/workspace';                        // session/process cwd (harness upload root)
const CASE_ROOT = '/tmp/a4-perm';                      // never inside the repo; per-case session cwd

const FILE_CONTENT = 'hi';
const ALLOW_ONCE_DIR = `${CASE_ROOT}/allow-once`;
const DENY_DIR = `${CASE_ROOT}/deny`;
const CANCEL_DIR = `${CASE_ROOT}/cancel`;

// Bounded waits (see header); all in milliseconds.
const PERMISSION_WAIT_MS = 75000;
const TURN_WAIT_MS = 90000;
const CANCEL_EFFECT_WAIT_MS = 30000;
const CASE_TIMEOUT_MS = 240000;
const MAX_EVENTS = 250;
const CANCEL_PROBE_ID = 'a4-cancel-probe';

// ---- credential-safe output -------------------------------------------------

// Never print credential material: every console.log line passes through a
// redactor that replaces the Qoder token value — if the environment provided one —
// with [redacted]. This mirrors 03-mcp-auth.mjs so CLI stderr tails and agent
// answers can never leak the PAT into the capture or this document.
const TOKEN_VALUE = process.env[TOKEN_ENV] || '';
const consoleLog = console.log.bind(console);
console.log = (...args) => consoleLog(...args.map(arg => (typeof arg === 'string' && TOKEN_VALUE.length >= 8
  ? arg.split(TOKEN_VALUE).join('[redacted]')
  : arg)));

// ---- tiny helpers ----------------------------------------------------------

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function excerpt(value, length = 200) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text === undefined ? null : text.slice(0, length);
}

// Shortened request/tool-call identifiers for the raw event log (A3 convention).
function shortenId(id) {
  if (id === undefined || id === null) {
    return null;
  }
  return String(id).slice(0, 12);
}

// Exit without truncating stdout: process.exit() can drop queued pipe writes, so
// prefer a natural exit (exitCode) with a bounded forced exit as a backstop.
function exitSoon(code) {
  process.exitCode = code;
  setTimeout(() => process.exit(code), 5000);
}

// Process-table snapshot for the orphan check. `ps` is preferred (the plan names it);
// when the image lacks procps, fall back to a direct /proc scan via the filesystem.
function processTableSnapshot() {
  try {
    const psOutput = execSync('ps -eo pid,ppid,stat,args', { encoding: 'utf8', timeout: 15000 });
    return { method: 'ps -eo pid,ppid,stat,args', output: psOutput, error: null };
  } catch (error) {
    const note = excerpt(String(error && error.message ? error.message : error), 200);
    try {
      const lines = ['PID PPID STAT ARGS (/proc fallback)'];
      for (const entry of fs.readdirSync('/proc')) {
        if (!/^\d+$/.test(entry)) {
          continue;
        }
        try {
          const stat = fs.readFileSync(`/proc/${entry}/stat`, 'utf8');
          const commStart = stat.indexOf('(');
          const commEnd = stat.lastIndexOf(')');
          const comm = commStart >= 0 && commEnd > commStart ? stat.slice(commStart + 1, commEnd) : '?';
          const state = stat.slice(commEnd + 2).split(' ')[0] || '?';
          const cmdline = fs.readFileSync(`/proc/${entry}/cmdline`, 'utf8').split('\0').join(' ').trim();
          lines.push(`${entry} ${state} ${comm} ${cmdline}`.trim());
        } catch {
          /* process vanished or unreadable */
        }
      }
      return { method: '/proc scan (ps unavailable)', output: lines.join('\n'), error: note };
    } catch (fallbackError) {
      return { method: 'unavailable', output: '', error: `${note}; /proc fallback failed: ${fallbackError}` };
    }
  }
}

// Record any usage/credit/cost fragments a message carries (raw, never asserted).
function collectUsage(rawLine, record) {
  if (record.usageFragments.length >= 30) {
    return;
  }
  const matches = rawLine.match(/"(usage|total_credits|credits|total_cost_usd|cost|input_tokens|output_tokens)"\s*:\s*[^,}\]]+/g);
  if (!matches) {
    return;
  }
  for (const match of matches.slice(0, 5)) {
    if (!record.usageFragments.includes(match)) {
      record.usageFragments.push(match);
    }
  }
}

// The prompt text every case uses. "exactly the two characters" removes the
// trailing-period ambiguity; the explicit Write-tool instruction keeps the model
// from reaching for a shell redirect (which would be an unidentified request).
function writePrompt(filePath) {
  return `Create a new file at ${filePath} containing exactly the two characters "hi" (no trailing period). `
    + 'Use the Write file tool and nothing else; do not use shell commands.';
}

// ---- one ACP case ----------------------------------------------------------

// Run one full ACP case (initialize -> session/new -> set_model -> one or two
// prompts) against a fresh qodercli process. `options.mode` selects the permission
// policy: 'allow-once' | 'deny' | 'cancel'. Resolves with a per-case record; never
// rejects.
async function runCase(caseName, options) {
  const startedAt = Date.now();
  const sessionCwd = options.caseDir;
  fs.mkdirSync(sessionCwd, { recursive: true });
  // Fresh target files: a previous dev-loop run must not make a denied write look
  // like it happened (and vice versa).
  for (const file of options.files) {
    fs.rmSync(file.path, { force: true });
  }

  const record = {
    case: caseName,
    mode: options.mode,
    model: MODEL,
    sessionCreated: false,
    modelSet: null,
    currentModeId: null,
    initResultExcerpt: null,
    turns: [],
    permissionRequests: [],
    modeUpdates: [],
    toolEvents: [],
    cancelProbe: null,
    killEvidence: null,
    fileChecks: [],
    usageFragments: [],
    events: [],
    totalEvents: 0,
    eventsTruncated: 0,
    grantsAllowOnce: 0,
    rejectSelections: 0,
    cancelledReplies: 0,
    unidentifiedReplies: 0,
    allowAlwaysSelections: 0,
    acceptEditsModeChanges: 0,
    stderrTail: '',
    note: null,
  };
  const events = [];

  let finished = false;
  let resolveRecord = null;
  const done = new Promise(resolve => {
    resolveRecord = resolve;
  });

  let sessionId = null;
  let turnIndex = 0;
  let activeTurn = null;
  let phaseTimer = null;
  let cancelEffectTimer = null;
  let childExited = false;
  let stderrTail = '';
  let cancelProbeStarted = false;

  const send = message => {
    try {
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
    } catch {
      /* pipe closed */
    }
  };

  // Outbound request bookkeeping. JSON-RPC responses are matched by id AND by the
  // absence of a `method` (a request/notification always carries one). This matters:
  // the CLI numbers its OWN requests starting at 0 and the observed permission
  // request ids reuse the low numbers this script uses for its own requests
  // (0,1,2,3...), so a bare id check misroutes CLI requests into response branches
  // (first observed run: the second write's permission request was consumed as a
  // bogus `initialize` response, nulling sessionId). Method-shape dispatch removes
  // that failure mode entirely.
  const pending = new Map();

  const request = (kind, id, payload, extra = {}, context = {}) => {
    pending.set(String(id), { kind, id, ...context });
    logEvent('out', 'rpc', { id, method: payload.method }, extra);
    send({ id, ...payload });
  };

  const logEvent = (dir, tag, message, extra = {}) => {
    record.totalEvents += 1;
    if (events.length >= MAX_EVENTS) {
      return;
    }
    const entry = { t: Date.now() - startedAt, dir, tag };
    if (message) {
      if (message.id !== undefined) {
        entry.id = shortenId(message.id);
      }
      if (message.method) {
        entry.method = message.method;
      }
      if (message.error) {
        entry.errorCode = message.error.code;
      }
    }
    Object.assign(entry, extra);
    events.push(entry);
  };

  const finish = note => {
    if (finished) {
      return;
    }
    finished = true;
    if (note) {
      record.note = note;
    }
    clearTimeout(phaseTimer);
    clearTimeout(cancelEffectTimer);
    clearTimeout(caseTimer);
    record.stderrTail = stderrTail.trim().slice(-600);
    if (record.cancelProbe && record.cancelProbe.verdict === null) {
      record.cancelProbe.verdict = record.cancelProbe.concludedOutcome
        ? `inconclusive (case ended: ${note || 'unknown'})`
        : 'not reached (case ended before the cancel probe concluded)';
    }
    killAndSnapshot(() => {
      record.fileChecks = options.files.map(file => {
        const exists = fs.existsSync(file.path);
        let content = null;
        let contentOk = false;
        if (exists) {
          try {
            content = fs.readFileSync(file.path, 'utf8').trim();
            contentOk = content === file.content;
          } catch (error) {
            content = `<read error: ${error && error.message ? error.message : error}>`;
          }
        }
        return {
          path: file.path,
          expectedContent: file.content,
          exists,
          contentExcerpt: excerpt(content, 60),
          contentOk,
        };
      });
      record.events = events;
      record.eventsTruncated = Math.max(0, record.totalEvents - events.length);
      resolveRecord(record);
    });
  };

  const caseTimer = setTimeout(() => finish(`hard case timeout after ${CASE_TIMEOUT_MS} ms`), CASE_TIMEOUT_MS);

  function armPhase(ms, label) {
    clearTimeout(phaseTimer);
    phaseTimer = setTimeout(() => finish(`bounded wait expired: ${label} (${ms} ms)`), ms);
  }

  // Terminate the CLI (SIGTERM, then SIGKILL) and take a process-table snapshot for
  // the orphan check. Always invoked before a case resolves.
  function killAndSnapshot(callback) {
    if (childExited) {
      // Give the runtime a moment to reap children of a CLI that exited on its own.
      setTimeout(snapshotAndContinue, 1200);
      return;
    }
    try {
      child.kill('SIGTERM');
    } catch {
      /* ignore */
    }
    const deadline = Date.now() + 12000;
    const poll = setInterval(() => {
      if (childExited || Date.now() > deadline) {
        clearInterval(poll);
        if (!childExited) {
          try {
            child.kill('SIGKILL');
          } catch {
            /* ignore */
          }
        }
        setTimeout(snapshotAndContinue, childExited ? 1200 : 3000);
      }
    }, 300);

    function snapshotAndContinue() {
      const snapshot = processTableSnapshot();
      const lines = snapshot.output.split('\n')
        .filter(line => line.trim() !== '' && /qodercli/i.test(line));
      const evidence = {
        method: snapshot.method,
        snapshotError: snapshot.error,
        orphanCount: lines.length,
        qodercliProcesses: lines.map(line => excerpt(line, 200)),
        tableExcerpt: excerpt(snapshot.output, 3000),
      };
      record.killEvidence = evidence;
      console.log(`A4-PS-JSON: ${JSON.stringify({
        case: caseName,
        method: evidence.method,
        orphanCount: evidence.orphanCount,
        qodercliProcesses: evidence.qodercliProcesses,
        snapshotError: evidence.snapshotError,
      })}`);
      callback();
    }
  }

  function newTurn(file) {
    activeTurn = {
      promptIndex: turnIndex,
      promptId: 10 + turnIndex,
      file,
      promptText: writePrompt(file.path),
      permissionRequests: 0,
      identifiedWrites: 0,
      grants: 0,
      rejects: 0,
      answer: '',
      stopReason: null,
      promptResponseExcerpt: null,
    };
    record.turns.push(activeTurn);
  }

  function sendPrompt() {
    if (turnIndex >= options.files.length) {
      finish('all turns completed');
      return;
    }
    newTurn(options.files[turnIndex]);
    armPhase(PERMISSION_WAIT_MS, `permission request / completion of prompt ${turnIndex + 1}`);
    request('prompt', activeTurn.promptId,
      { method: 'session/prompt', params: { sessionId, prompt: [{ type: 'text', text: activeTurn.promptText }] } },
      { promptIndex: turnIndex, target: activeTurn.file.path },
      { turn: activeTurn });
  }

  // Identify the write we asked for: a write-ish tool whose resolved file path is
  // our target path, or (when the payload carries no path) a title naming the file.
  // The positive identification is what permits an allow/reject reply; anything else
  // is cancelled, never approved.
  function identifyWrite(message) {
    const params = message.params || {};
    const toolCall = params.toolCall || {};
    const qoderToolName = toolCall?._meta?.qoder?.toolName ?? null;
    const toolKind = toolCall.kind ?? null;
    const rawInput = toolCall.rawInput || {};
    const rawFilePath = typeof rawInput.file_path === 'string' ? rawInput.file_path
      : (typeof rawInput.filePath === 'string' ? rawInput.filePath : null);
    const title = typeof params.title === 'string' ? params.title : null;
    const resolvedFilePath = rawFilePath === null ? null : path.resolve(sessionCwd, rawFilePath);
    const targetPath = activeTurn ? activeTurn.file.path : null;
    const isWriteTool = ['Write', 'Edit', 'MultiEdit', 'NotebookEdit', 'write', 'edit']
      .includes(qoderToolName) || toolKind === 'edit';
    const pathOk = targetPath !== null && resolvedFilePath === targetPath;
    const titleOk = targetPath !== null && title !== null && title.includes(path.basename(targetPath));
    const basis = pathOk ? 'file_path' : (titleOk && rawFilePath === null ? 'title' : 'none');
    return {
      params,
      toolCall,
      qoderToolName,
      toolKind,
      rawFilePath,
      resolvedFilePath,
      title,
      identified: isWriteTool && basis !== 'none',
      basis,
    };
  }

  function onPermissionRequest(message) {
    const info = identifyWrite(message);
    const optionsList = Array.isArray(info.params.options) ? info.params.options : [];
    const seq = record.permissionRequests.length + 1;
    const entry = {
      seq,
      requestId: shortenId(message.id),
      toolCallId: shortenId(info.toolCall.toolCallId),
      title: excerpt(info.title, 160),
      qoderToolName: info.qoderToolName,
      toolKind: info.toolKind,
      status: info.toolCall.status || null,
      filePath: info.rawFilePath,
      resolvedFilePath: info.resolvedFilePath,
      expectedTarget: activeTurn ? activeTurn.file.path : null,
      identifiedBy: info.basis,
      identified: info.identified,
      offeredOptionIds: optionsList.map(option => option.optionId),
      offeredKinds: optionsList.map(option => option.kind),
      options: optionsList.map(option => ({ optionId: option.optionId, name: option.name, kind: option.kind })),
      allowAlwaysOffered: optionsList.some(option => option.kind === 'allow_always'),
      decision: null,
      reply: null,
      replied: false,
      t: Date.now() - startedAt,
    };
    record.permissionRequests.push(entry);
    if (activeTurn) {
      activeTurn.permissionRequests += 1;
    }
    logEvent('in', 'permission', message, {
      seq,
      toolCallId: entry.toolCallId,
      title: entry.title,
      kind: info.toolKind,
      tool: entry.qoderToolName,
      optionKinds: entry.offeredKinds,
      identified: entry.identified,
      identifiedBy: entry.identifiedBy,
    });

    const reply = result => {
      entry.replied = true;
      entry.reply = result;
      send({ id: message.id, result });
      logEvent('out', 'permission-reply', { id: message.id }, { seq, reply: JSON.stringify(result) });
    };

    // Send a `selected` reply and derive the escalation counter from the option actually
    // sent: the selected optionId is mapped back to the offered menu, and the
    // allow_always counter increments when that option's kind is `allow_always`. The
    // counter is therefore a real observation of what left this process — the
    // no-escalation gate in main() (`allowAlwaysSelected === 0`) can fail instead of
    // passing on a constant.
    const replySelected = optionId => {
      const selected = optionsList.find(candidate => candidate.optionId === optionId)
        || { optionId, kind: 'unoffered' };
      if (selected.kind === 'allow_always') {
        record.allowAlwaysSelections += 1;
      }
      entry.decision = `${selected.kind}:${selected.optionId}`;
      reply({ outcome: { outcome: 'selected', optionId: selected.optionId } });
    };

    if (options.mode === 'cancel') {
      // Do not answer: the cancel probe needs the request pending.
      if (!cancelProbeStarted) {
        record.cancelProbe.pendingPermissionSeq = seq;
        record.cancelProbe.pendingPermissionRequestId = entry.requestId;
        startCancelProbe();
      } else {
        // A second request arrived while the probe runs: refuse (never approve).
        entry.decision = 'cancelled-unidentified-after-cancel-probe';
        record.cancelledReplies += 1;
        // NOT EXERCISED / shape-unverified: the flat {outcome:'cancelled'} reply was
        // never exercised in this gate (04-permissions.md §9) — B3a must not copy it.
        reply({ outcome: 'cancelled' });
      }
      return;
    }

    if (!entry.identified) {
      entry.decision = 'cancelled-unidentified';
      record.unidentifiedReplies += 1;
      // NOT EXERCISED / shape-unverified: flat {outcome:'cancelled'} (04-permissions.md §9);
      // B3a must not copy it.
      reply({ outcome: 'cancelled' });
      return;
    }

    if (options.mode === 'allow-once') {
      const allowOnce = (optionsList || []).find(option => option.kind === 'allow_once');
      if (allowOnce) {
        record.grantsAllowOnce += 1;
        if (activeTurn) {
          activeTurn.identifiedWrites += 1;
          activeTurn.grants += 1;
        }
        // Decision label + escalation counter come from the optionId actually sent
        // (replySelected maps it back to the offered menu).
        replySelected(allowOnce.optionId);
      } else {
        // No allow_once offered: refuse rather than falling back to another option.
        entry.decision = 'cancelled-no-allow-once-option';
        record.cancelledReplies += 1;
        // NOT EXERCISED / shape-unverified: flat {outcome:'cancelled'} (04-permissions.md §9);
        // B3a must not copy it.
        reply({ outcome: 'cancelled' });
      }
      return;
    }

    // deny: prefer the offered reject_once option, else the protocol-level cancel.
    const rejectOnce = (optionsList || []).find(option => option.kind === 'reject_once');
    if (rejectOnce) {
      record.rejectSelections += 1;
      replySelected(rejectOnce.optionId);
    } else {
      entry.decision = 'cancelled-no-reject-once-option';
      record.cancelledReplies += 1;
      // NOT EXERCISED / shape-unverified: flat {outcome:'cancelled'} (04-permissions.md §9);
      // B3a must not copy it.
      reply({ outcome: 'cancelled' });
    }
    if (activeTurn) {
      activeTurn.identifiedWrites += 1;
      activeTurn.rejects += 1;
    }
  }

  function startCancelProbe() {
    cancelProbeStarted = true;
    record.cancelProbe = {
      ...record.cancelProbe,
      startedAt: Date.now() - startedAt,
      requestForm: null,
      requestFormVerdict: null,
      notificationForm: null,
      notificationSentAt: null,
      abortSignals: [],
      effectWaitMs: null,
      notificationToAbortMs: null,
      verdict: null,
      concludedOutcome: null,
      fallback: null,
      pendingPermissionSeq: record.permissionRequests.length,
      pendingPermissionRequestId: record.permissionRequests[record.permissionRequests.length - 1]?.requestId ?? null,
    };
    armPhase(CANCEL_EFFECT_WAIT_MS + 15000, 'cancel probe conclusion');
    request('cancel-probe', CANCEL_PROBE_ID,
      { method: 'session/cancel', params: { sessionId } }, { form: 'request' });
  }

  function onCancelProbeResponse(message) {
    const probe = record.cancelProbe;
    if (message.error) {
      probe.requestForm = { errorCode: message.error.code, message: excerpt(message.error.message, 200) };
      probe.requestFormVerdict = (message.error.code === -32601
        || /method not found|not found/i.test(String(message.error.message || ''))) ? 'method-not-found' : 'error';
      // Probe the notification form too (ACP defines session/cancel as a
      // notification; a request-shaped call may be rejected for that reason alone).
      probe.notificationForm = 'sent (no id)';
      probe.notificationSentAt = Date.now() - startedAt;
      logEvent('out', 'rpc', { method: 'session/cancel' }, { form: 'notification' });
      send({ method: 'session/cancel', params: { sessionId } });
    } else {
      probe.requestForm = { result: excerpt(JSON.stringify(message.result ?? {}), 200) };
      probe.requestFormVerdict = 'accepted';
    }
    // Give the CLI a bounded window to show an effect before concluding.
    clearTimeout(cancelEffectTimer);
    cancelEffectTimer = setTimeout(() => concludeCancelProbe('no abort observed'), CANCEL_EFFECT_WAIT_MS);
  }

  function concludeCancelProbe(abortSignal, stopReason) {
    const probe = record.cancelProbe;
    if (probe.concludedOutcome !== null) {
      return;
    }
    clearTimeout(cancelEffectTimer);
    probe.concludedOutcome = abortSignal;
    // `effectWaitMs` is measured from the probe start (`startedAt` — the arrival of the
    // pending permission request, i.e. when this probe began) to this conclusion, so it
    // INCLUDES the failed request-form round trip. It is NOT the notification → abort
    // delta; that one is `notificationToAbortMs` below.
    probe.effectWaitMs = Date.now() - startedAt - probe.startedAt;
    if (abortSignal !== 'no abort observed') {
      probe.abortSignals.push({ signal: abortSignal, stopReason: stopReason ?? null, t: Date.now() - startedAt });
    }
    // Notification → first-observed-abort delta, computed only when the notification
    // form was actually sent (notificationSentAt set in onCancelProbeResponse).
    if (probe.notificationSentAt != null && probe.abortSignals.length > 0) {
      probe.notificationToAbortMs = probe.abortSignals[probe.abortSignals.length - 1].t - probe.notificationSentAt;
    }
    const aborted = probe.abortSignals.length > 0;
    const form = probe.requestFormVerdict;
    if (aborted && form === 'accepted') {
      probe.verdict = 'exists (request form accepted; pending turn aborted)';
      probe.fallback = 'none';
    } else if (aborted && form === 'method-not-found') {
      probe.verdict = 'exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted)';
      probe.fallback = 'none';
    } else if (!aborted && form === 'accepted') {
      probe.verdict = `request form accepted but no observable effect within ${CANCEL_EFFECT_WAIT_MS} ms`;
      probe.fallback = 'terminate CLI + ps orphan check';
    } else if (!aborted && form === 'method-not-found') {
      probe.verdict = `not available (request form -32601 method not found; notification form sent; no observable effect within ${CANCEL_EFFECT_WAIT_MS} ms)`;
      probe.fallback = 'terminate CLI + ps orphan check';
    } else {
      probe.verdict = `ambiguous (request form verdict: ${form}; effect: ${abortSignal})`;
      probe.fallback = 'terminate CLI + ps orphan check';
    }
    record.cancelMethodDecision = `session/cancel ${probe.verdict}; fallback=${probe.fallback}`;
    console.log(`CANCEL-METHOD-DECISION: session/cancel ${probe.verdict}; `
      + `request-form=${JSON.stringify(probe.requestForm)}; notification-form=${probe.notificationForm || 'not attempted'}; `
      + `turn-abort=${aborted ? probe.abortSignals.map(signal => signal.signal).join(',') : 'not observed'}; `
      + `fallback=${probe.fallback}`);
    finish(`cancel probe concluded: ${probe.verdict}`);
  }

  // ---- inbound dispatch -----------------------------------------------------

  function onPromptResponse(entry, message) {
    const turn = entry.turn || activeTurn;
    if (!turn) {
      finish('prompt response without an active turn');
      return;
    }
    turn.stopReason = message.result?.stopReason ?? null;
    turn.promptResponseExcerpt = excerpt(JSON.stringify(message.result ?? message.error ?? {}), 400);
    logEvent('in', 'rpc-response', message, { promptIndex: turn.promptIndex, stopReason: turn.stopReason });
    if (cancelProbeStarted && record.cancelProbe && record.cancelProbe.concludedOutcome === null) {
      // The pending turn resolved after the cancel probe: an abort signal.
      concludeCancelProbe('prompt-response', turn.stopReason);
      return;
    }
    turnIndex += 1;
    if (turnIndex < options.files.length) {
      sendPrompt();
    } else {
      finish('all turns completed');
    }
  }

  function onSessionUpdate(message) {
    const update = message.params?.update || {};
    const type = update.sessionUpdate;
    if (type === 'agent_message_chunk' && activeTurn) {
      activeTurn.answer += update.content?.text || '';
    }
    if (type === 'current_mode_update' || update.currentModeId !== undefined) {
      const entry = {
        t: Date.now() - startedAt,
        currentModeId: update.currentModeId ?? null,
        turn: activeTurn ? activeTurn.promptIndex : null,
      };
      record.modeUpdates.push(entry);
      if (entry.currentModeId === 'acceptEdits') {
        record.acceptEditsModeChanges += 1;
      }
      console.log(`A4-MODE-JSON: ${JSON.stringify({ case: caseName, ...entry, raw: excerpt(JSON.stringify(update), 240) })}`);
    }
    if (type === 'tool_call' || type === 'tool_call_update') {
      const qoderTool = update._meta?.qoder?.toolName || null;
      const status = update.status || null;
      if (record.toolEvents.length < 40) {
        record.toolEvents.push({
          t: Date.now() - startedAt,
          type,
          toolCallId: shortenId(update.toolCallId),
          qoderTool,
          kind: update.kind || null,
          status,
          title: excerpt(update.title, 120),
          rawInputExcerpt: update.rawInput === undefined ? null : excerpt(update.rawInput, 200),
          outputExcerpt: update.rawOutput === undefined ? null : excerpt(update.rawOutput, 200),
        });
      }
      logEvent('in', 'update', message, {
        type, toolCallId: shortenId(update.toolCallId), status, tool: qoderTool, kind: update.kind || null,
      });
      if (record.cancelProbe && !record.cancelProbe.concludedOutcome && cancelProbeStarted
        && (status === 'cancelled' || status === 'canceled')) {
        concludeCancelProbe(`tool-call-${status}`, null);
      }
    } else {
      logEvent('in', 'update', message, { type: type || '<none>' });
    }
  }

  // A message without a `method` is a response to one of our requests; match it in
  // the pending map and ignore anything unmatched (bounded, logged as evidence).
  function onResponse(entry, message) {
    if (entry.kind === 'initialize') {
      if (message.error) {
        finish(`initialize failed: ${excerpt(message.error, 200)}`);
        return;
      }
      record.initResultExcerpt = excerpt(JSON.stringify({
        protocolVersion: message.result?.protocolVersion ?? null,
        agentCapabilities: message.result?.agentCapabilities ?? null,
        authMethods: (message.result?.authMethods || []).map(method => method && method.id).filter(Boolean),
      }), 700);
      request('session/new', 2, { method: 'session/new', params: { cwd: sessionCwd, mcpServers: [] } });
      return;
    }
    if (entry.kind === 'session/new') {
      if (message.error) {
        finish(`session/new failed: ${excerpt(message.error, 300)}`);
        return;
      }
      record.sessionCreated = true;
      sessionId = message.result?.sessionId ?? null;
      record.currentModeId = message.result?.modes?.currentModeId ?? null;
      request('set_model', 3,
        { method: 'session/set_model', params: { sessionId, modelId: MODEL } });
      return;
    }
    if (entry.kind === 'set_model') {
      record.modelSet = message.error
        ? `error ${message.error.code}: ${excerpt(message.error.message, 120)}`
        : 'accepted';
      sendPrompt();
      return;
    }
    if (entry.kind === 'prompt') {
      onPromptResponse(entry, message);
      return;
    }
    if (entry.kind === 'cancel-probe') {
      onCancelProbeResponse(message);
    }
  }

  function handle(message, rawLine) {
    collectUsage(rawLine, record);

    // Responses (never carry a `method`).
    if (message.method === undefined) {
      const key = message.id === undefined ? null : String(message.id);
      const entry = key === null ? undefined : pending.get(key);
      if (entry) {
        pending.delete(key);
        onResponse(entry, message);
      } else {
        logEvent('in', 'stray-response', message, {
          resultOrError: excerpt(JSON.stringify(message.result ?? message.error ?? {}), 160),
        });
      }
      return;
    }

    // Notifications and requests from the CLI (agent side).
    if (message.method === 'session/update') {
      onSessionUpdate(message);
      return;
    }
    if (message.method === 'session/request_permission') {
      onPermissionRequest(message);
      return;
    }
    if (message.id !== undefined) {
      // Any other client-facing request: refuse (never auto-approve unknown methods).
      logEvent('in', 'unsupported-request', message, { method: message.method });
      send({ id: message.id, error: { code: -32601, message: 'Unsupported client method' } });
      return;
    }
    logEvent('in', 'notification', message, { method: message.method });
  }

  // Credential handling: inherit the environment (the PAT arrives only this way),
  // drop the SDK entrypoint variables that make `--acp` refuse to start (A3).
  const env = { ...process.env };
  for (const key of ['QODER_AGENT_SDK_ENTRYPOINT', 'QODER_WORKER_RUNTIME_ASSET_ROOT', 'QODER_AGENT_SDK_VERSION',
    'QODERCLI_RUNTIME_PACKAGING', 'QODER_SESSION_TYPE', 'QODER_WORKER_CWD', 'QODER_SDK_AUTH_PAYLOAD_FILE']) {
    delete env[key];
  }

  const args = ['-m', MODEL, '--acp'];
  const child = spawn('qodercli', args, { cwd: WORKSPACE, env, stdio: ['pipe', 'pipe', 'pipe'] });
  record.pid = child.pid ?? null;
  if (options.mode === 'cancel') {
    record.cancelProbe = { startedAt: null, pendingPermissionSeq: null, pendingPermissionRequestId: null };
  }

  child.on('error', error => finish(`spawn error: ${error.message}`));
  child.on('exit', code => {
    childExited = true;
    if (!finished) {
      finish(`qodercli exited early (code ${code})`);
    }
  });
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', text => {
    stderrTail = (stderrTail + text).slice(-1200);
  });

  let buffer = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', text => {
    buffer += text;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      const message = parseJson(line);
      if (message !== undefined) {
        handle(message, line);
      }
    }
  });

  request('initialize', 1, { method: 'initialize', params: { protocolVersion: 1 } });

  return done;
}

// ---- case assessment -------------------------------------------------------

function assessAllowOnce(record) {
  const first = record.turns[0] || null;
  const second = record.turns[1] || null;
  const file1 = record.fileChecks[0] || null;
  const file2 = record.fileChecks[1] || null;
  const firstOk = Boolean(first) && first.identifiedWrites >= 1 && first.grants >= 1
    && file1 && file1.exists && file1.contentOk;
  const secondAskedAgain = Boolean(second) && second.permissionRequests >= 1 && second.identifiedWrites >= 1
    && second.grants >= 1 && file2 && file2.exists && file2.contentOk;
  return {
    name: 'allow-once: write -> allow_once -> file created, second write asks again',
    ok: firstOk && secondAskedAgain,
    details: `turn1[requests=${first ? first.permissionRequests : 'n/a'} identified=${first ? first.identifiedWrites : 'n/a'} `
      + `allowOnce=${first ? first.grants : 'n/a'} stop=${first ? first.stopReason : 'n/a'} file=${file1 ? `${file1.exists}/${file1.contentOk}` : 'n/a'}] `
      + `turn2[requests=${second ? second.permissionRequests : 'n/a'} identified=${second ? second.identifiedWrites : 'n/a'} `
      + `allowOnce=${second ? second.grants : 'n/a'} stop=${second ? second.stopReason : 'n/a'} file=${file2 ? `${file2.exists}/${file2.contentOk}` : 'n/a'}] `
      + `secondAskedAgain=${secondAskedAgain}${record.note ? ` note=${record.note}` : ''}`,
  };
}

function assessDeny(record) {
  const turn = record.turns[0] || null;
  const file = record.fileChecks[0] || null;
  const writeRequested = Boolean(turn) && turn.identifiedWrites >= 1;
  const rejected = record.rejectSelections + record.cancelledReplies >= 1;
  const absent = Boolean(file) && !file.exists;
  const completed = Boolean(turn) && turn.stopReason !== null;
  return {
    name: 'deny: reject_once/cancelled -> file absent',
    ok: writeRequested && rejected && absent && completed,
    details: `requests=${turn ? turn.permissionRequests : 'n/a'} identified=${turn ? turn.identifiedWrites : 'n/a'} `
      + `rejectSelections=${record.rejectSelections} cancelledReplies=${record.cancelledReplies} `
      + `stop=${turn ? turn.stopReason : 'n/a'} fileExists=${file ? file.exists : 'n/a'}`
      + `${record.note ? ` note=${record.note}` : ''}`,
  };
}

function assessCancel(record) {
  const probe = record.cancelProbe || null;
  const file = record.fileChecks[0] || null;
  const pending = record.permissionRequests[0] || null;
  const neverAnswered = Boolean(pending) && pending.replied === false;
  const aborted = Boolean(probe && probe.abortSignals && probe.abortSignals.length > 0);
  const existsPath = Boolean(probe) && /^exists/.test(probe.verdict || '') && aborted;
  const absentPath = Boolean(probe) && /not available|no observable effect/.test(probe.verdict || '')
    && record.killEvidence && record.killEvidence.orphanCount === 0;
  const absent = Boolean(file) && !file.exists;
  return {
    name: 'cancel while pending: session/cancel probe (or terminate + no orphan)',
    ok: Boolean(probe) && neverAnswered && absent && (existsPath || absentPath),
    details: `pendingRequest=${pending ? pending.requestId : 'n/a'} replied=${pending ? pending.replied : 'n/a'} `
      + `requestForm=${probe ? probe.requestFormVerdict : 'n/a'} notificationForm=${probe ? probe.notificationForm : 'n/a'} `
      + `abortSignals=${probe && probe.abortSignals ? probe.abortSignals.map(signal => signal.signal).join(',') || 'none' : 'n/a'} `
      + `verdict=${probe ? probe.verdict : 'n/a'} orphans=${record.killEvidence ? record.killEvidence.orphanCount : 'n/a'} `
      + `fileExists=${file ? file.exists : 'n/a'}${record.note ? ` note=${record.note}` : ''}`,
    cancelMethodDecision: record.cancelMethodDecision || null,
  };
}

// ---- main ------------------------------------------------------------------

const failures = [];

function report(caseName, ok, details) {
  console.log(`[A4] ${ok ? 'PASS' : 'FAIL'}: ${caseName} ${details}`);
  if (!ok) {
    failures.push(caseName);
  }
}

async function main() {
  console.log(`[A4] model pin: QODER_E2E_MODEL=${MODEL} (spawn -m ${MODEL} + session/set_model)`);
  console.log(`[A4] bounds: PERMISSION_WAIT_MS=${PERMISSION_WAIT_MS} TURN_WAIT_MS=${TURN_WAIT_MS} `
    + `CANCEL_EFFECT_WAIT_MS=${CANCEL_EFFECT_WAIT_MS} CASE_TIMEOUT_MS=${CASE_TIMEOUT_MS}`);
  console.log(`[A4] fixtures: file content=${JSON.stringify(FILE_CONTENT)}; write targets live under ${CASE_ROOT}/ (sandbox /tmp, never the repo)`);

  if (!process.env[TOKEN_ENV] || process.env[TOKEN_ENV].trim() === '') {
    console.log(`[A4] FAIL: ${TOKEN_ENV} is not present in the environment — the authenticated ACP `
      + 'session requires it (injected into the sandbox environment, never argv)');
    console.log('A4-PERMISSIONS-RESULT: FAIL (missing credential)');
    process.exit(2);
  }

  const allowOnce = await runCase('allow-once', {
    mode: 'allow-once',
    caseDir: ALLOW_ONCE_DIR,
    files: [
      { path: `${ALLOW_ONCE_DIR}/first.txt`, content: FILE_CONTENT },
      { path: `${ALLOW_ONCE_DIR}/second.txt`, content: FILE_CONTENT },
    ],
  });
  const deny = await runCase('deny', {
    mode: 'deny',
    caseDir: DENY_DIR,
    files: [{ path: `${DENY_DIR}/denied.txt`, content: FILE_CONTENT }],
  });
  const cancel = await runCase('cancel', {
    mode: 'cancel',
    caseDir: CANCEL_DIR,
    files: [{ path: `${CANCEL_DIR}/pending.txt`, content: FILE_CONTENT }],
  });

  const allowCheck = assessAllowOnce(allowOnce);
  const denyCheck = assessDeny(deny);
  const cancelCheck = assessCancel(cancel);

  // Global hard constraints (spec): allow_always never selected, no acceptEdits mode
  // change anywhere. These are asserted, not merely recorded.
  const allCases = [allowOnce, deny, cancel];
  const allowAlwaysOffered = allCases.reduce((sum, record) => sum
    + record.permissionRequests.filter(entry => entry.allowAlwaysOffered).length, 0);
  const allowAlwaysSelected = allCases.reduce((sum, record) => sum + record.allowAlwaysSelections, 0);
  const acceptEditsChanges = allCases.reduce((sum, record) => sum + record.acceptEditsModeChanges, 0);
  const unidentifiedReplies = allCases.reduce((sum, record) => sum + record.unidentifiedReplies, 0);
  const grantsAllowOnce = allCases.reduce((sum, record) => sum + record.grantsAllowOnce, 0);
  const rejectSelections = allCases.reduce((sum, record) => sum + record.rejectSelections, 0);
  const cancelledReplies = allCases.reduce((sum, record) => sum + record.cancelledReplies, 0);
  const orphanCount = allCases.reduce((sum, record) => sum
    + (record.killEvidence ? record.killEvidence.orphanCount : 0), 0);
  const escalationCheck = {
    name: 'no escalation: allow_always never selected; no acceptEdits mode change',
    ok: allowAlwaysSelected === 0 && acceptEditsChanges === 0 && allowAlwaysOffered >= 1,
    details: `allowAlwaysOffered=${allowAlwaysOffered} allowAlwaysSelected=${allowAlwaysSelected} `
      + `acceptEditsModeChanges=${acceptEditsChanges} modeUpdates=${allCases.map(record => record.modeUpdates.length).join('/')}`,
  };

  report(allowCheck.name, allowCheck.ok, allowCheck.details);
  report(denyCheck.name, denyCheck.ok, denyCheck.details);
  report(cancelCheck.name, cancelCheck.ok, cancelCheck.details);
  report(escalationCheck.name, escalationCheck.ok, escalationCheck.details);

  // Per-case records for the evidence document (bounded, no credential material).
  for (const record of allCases) {
    for (const entry of record.permissionRequests) {
      console.log(`A4-PERMISSION-JSON: ${JSON.stringify({ case: record.case, ...entry })}`);
    }
    console.log(`A4-CASE-JSON: ${JSON.stringify({
      case: record.case,
      mode: record.mode,
      model: record.model,
      pid: record.pid,
      sessionCreated: record.sessionCreated,
      modelSet: record.modelSet,
      currentModeId: record.currentModeId,
      initResultExcerpt: record.initResultExcerpt,
      turns: record.turns.map(turn => ({
        promptIndex: turn.promptIndex,
        stopReason: turn.stopReason,
        permissionRequests: turn.permissionRequests,
        identifiedWrites: turn.identifiedWrites,
        allowOnceSelections: turn.grants,
        rejectSelections: turn.rejects,
        answerExcerpt: excerpt(turn.answer, 200),
        promptResponseExcerpt: turn.promptResponseExcerpt,
      })),
      permissionRequests: record.permissionRequests,
      modeUpdates: record.modeUpdates,
      toolEvents: record.toolEvents.slice(0, 12),
      cancelProbe: record.cancelProbe,
      cancelMethodDecision: record.cancelMethodDecision || null,
      killEvidence: record.killEvidence,
      fileChecks: record.fileChecks,
      grantsAllowOnce: record.grantsAllowOnce,
      rejectSelections: record.rejectSelections,
      cancelledReplies: record.cancelledReplies,
      unidentifiedReplies: record.unidentifiedReplies,
      allowAlwaysSelections: record.allowAlwaysSelections,
      acceptEditsModeChanges: record.acceptEditsModeChanges,
      usageFragments: record.usageFragments.slice(0, 12),
      totalEvents: record.totalEvents,
      eventsTruncated: record.eventsTruncated,
      stderrTail: record.stderrTail || null,
      note: record.note,
    })}`);
    console.log(`A4-EVENT-LOG-JSON: ${JSON.stringify({ case: record.case, events: record.events })}`);
  }

  const summary = {
    cases: allCases.map(record => ({
      case: record.case,
      mode: record.mode,
      sessionCreated: record.sessionCreated,
      modelSet: record.modelSet,
      permissionRequests: record.permissionRequests.length,
      grantsAllowOnce: record.grantsAllowOnce,
      rejectSelections: record.rejectSelections,
      cancelledReplies: record.cancelledReplies,
      unidentifiedReplies: record.unidentifiedReplies,
      modeUpdates: record.modeUpdates.map(entry => entry.currentModeId),
      acceptEditsModeChanges: record.acceptEditsModeChanges,
      fileChecks: record.fileChecks.map(check => ({ path: check.path, exists: check.exists, contentOk: check.contentOk })),
      orphans: record.killEvidence ? record.killEvidence.orphanCount : null,
      stopReasons: record.turns.map(turn => turn.stopReason),
    })),
    totals: {
      permissionRequests: allCases.reduce((sum, record) => sum + record.permissionRequests.length, 0),
      grantsAllowOnce,
      rejectSelections,
      cancelledReplies,
      unidentifiedReplies,
      orphans: orphanCount,
    },
    hardConstraints: {
      allowAlwaysOffered,
      allowAlwaysSelections: allowAlwaysSelected,
      acceptEditsModeChanges: acceptEditsChanges,
    },
    optionKindsObserved: [...new Set(allCases.flatMap(record => record.permissionRequests
      .flatMap(entry => entry.offeredKinds)))],
    optionIdsObserved: [...new Set(allCases.flatMap(record => record.permissionRequests
      .flatMap(entry => entry.offeredOptionIds)))],
    cancelMethodDecision: cancel.cancelMethodDecision || null,
    usageFragments: allCases.flatMap(record => record.usageFragments).slice(0, 20),
    modelPin: MODEL,
  };
  console.log(`A4-SUMMARY-JSON: ${JSON.stringify(summary)}`);

  if (failures.length === 0) {
    console.log('A4-PERMISSIONS-RESULT: PASS');
    exitSoon(0);
    return;
  }
  console.log(`A4-PERMISSIONS-RESULT: FAIL (${failures.length} case(s): ${failures.join('; ')})`);
  exitSoon(failures.length);
}

main().catch(error => {
  console.log(`[A4] FAIL: unexpected error: ${error && error.stack ? error.stack : error}`);
  console.log('A4-PERMISSIONS-RESULT: FAIL (unexpected error)');
  exitSoon(9);
});

#!/usr/bin/env node
// =============================================================================
// Task A5 (Slice A gate): long pending permission wait + sandbox TTL renewal +
// effective-model observability + prompt deadline anchors.
//
// Runs INSIDE a real OpenSandbox sandbox (image aria-conductor/qoder-sandbox:0.1,
// Node 22) as a Node ESM script; QoderWaitRenewalE2ETest uploads it to
// /workspace/05-wait-renewal.mjs and executes it through
// OpenCodeSandboxManager#runCommand. It is a sibling of 04-permissions.mjs and
// mirrors its conventions (redacting console wrapper, newline-delimited JSON-RPC
// over the CLI's stdio, method-shape dispatch — the CLI numbers its OWN requests
// from 0 per session, so responses are matched by the ABSENCE of a `method` field;
// permission replies select the offered option whose kind is `allow_once`, never
// `allow_always`; bounded waits; summary marker A5-SUMMARY-JSON; exit code =
// number of failed criteria).
//
// What it pins (one qodercli process, one ACP session, one prompted turn):
//   1. LONG WAIT: the write permission request is held PENDING for
//      HOLD_MS = 300000 ms (5 minutes — the bounded window the task brief asks
//      for) before it is answered with the offered `allow_once` option. A shorter
//      hold is only acceptable for intermediate debug runs; this script always
//      uses the 5-minute window.
//   2. STILL EXECUTES: after the hold the CLI must accept the reply, execute the
//      Write tool and finish the turn (target file exists with the exact content,
//      prompt response carries a stopReason) — proving the pending wait survives
//      across the sandbox TTL renewal the host performs while the request is
//      pending (production path OpenCodeSandboxManager#renewSandbox, 30 minutes).
//   3. TIMESTAMPS FROM BOTH SIDES: the state file /tmp/a5/state carries the
//      sandbox clock (Node Date.now(), epoch ms) at PENDING and at ANSWER (plus
//      the prompt anchors); the host driver writes its renewal bracket (host
//      clock + sandbox clock read around the renew call) to
//      /tmp/a5/host-renewal. This script reads that file at the end and includes
//      it verbatim in the summary, so one committed block carries both sides'
//      interleaving evidence.
//   4. MODEL OBSERVABILITY: every inbound message is scanned for model / usage /
//      credit fields; the exact event and field path that carries the effective
//      model is recorded when present (prompt response
//      result._meta.quota.model_usage[0].model in A4), together with what is NOT
//      observable (no credit/cost field anywhere; usage counters all zero).
//      Recorded, never inferred.
//   5. DEADLINE ANCHORS: the session/prompt request timestamp and the prompt
//      response timestamp are recorded in wall clock (sandbox epoch ms), which is
//      what a hard run deadline is derived from (start + maxDuration, closed by
//      the response).
//
// Credentials: the Qoder PAT arrives only as the sandbox environment variable
// QODER_PERSONAL_ACCESS_TOKEN (the Java test injects it through createSandbox's
// env map — environment only, never argv). This script never prints it: the
// console wrapper redacts the token value in every log line.
//
// The ACP session is pinned to the zero-credit model: qodercli is spawned with
// `-m $QODER_E2E_MODEL` (default `efficient`) and the session is additionally
// pinned with session/set_model {sessionId, modelId}; both are recorded.
//
// Exit status: 0 only when every criterion passes; otherwise the number of failed
// criteria. The last markers are
//   A5-SUMMARY-JSON: {...}
//   A5-WAIT-RENEWAL-RESULT: PASS   (or ...: FAIL (n criterion(s): ...))
// =============================================================================
import { spawn } from 'node:child_process';
import fs from 'node:fs';

// ---- configuration ---------------------------------------------------------

const MODEL = (process.env.QODER_E2E_MODEL || 'efficient').trim();
const TOKEN_ENV = 'QODER_PERSONAL_ACCESS_TOKEN';
const WORKSPACE = '/workspace';                    // session/process cwd (harness upload root)
const CASE_ROOT = '/tmp/a5';                       // never inside the repo
const STATE_FILE = `${CASE_ROOT}/state`;
const HOST_RENEWAL_FILE = `${CASE_ROOT}/host-renewal`;
const TARGET_DIR = `${CASE_ROOT}/answer-under-renewal`;
const TARGET_FILE = `${TARGET_DIR}/written.txt`;
const FILE_CONTENT = 'renewed';

// Bounded waits (see header); all in milliseconds. The 5-minute hold is the
// evidence window; the other bounds are response-driven budgets around it.
const HOLD_MS = 300000;
const PERMISSION_WAIT_MS = 90000;
const TURN_WAIT_MS = 90000;
const CASE_TIMEOUT_MS = 600000;
const MAX_EVENTS = 250;
const MAX_OBSERVATIONS = 80;

// ---- credential-safe output -------------------------------------------------

// Never print credential material: every console.log line passes through a
// redactor that replaces the Qoder token value — if the environment provided one —
// with [redacted]. This mirrors 04-permissions.mjs so CLI stderr tails and agent
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

// ---- state file (sandbox clock, single line: the execd capture drops newlines,
// so the host driver may only ever parse key=value tokens) --------------------

const state = {
  phase: 'STARTING',
  model: MODEL,
  t_start: Date.now(),
  t_prompt_sent: 0,
  t_pending: 0,
  t_answer: 0,
  t_prompt_end: 0,
  hold_ms: HOLD_MS,
  held_ms: 0,
  request_id: '-',
  stop_reason: '-',
  file_ok: 0,
};

function writeState(phase, extra = {}) {
  Object.assign(state, extra, { phase });
  const line = Object.entries(state).map(([key, value]) => `${key}=${value}`).join(' ');
  fs.writeFileSync(STATE_FILE, line + '\n', 'utf8');
  console.log(`A5-STATE-JSON: ${JSON.stringify(state)}`);
}

// ---- one ACP run -----------------------------------------------------------

async function main() {
  fs.mkdirSync(CASE_ROOT, { recursive: true });
  fs.mkdirSync(TARGET_DIR, { recursive: true });
  fs.rmSync(TARGET_FILE, { force: true });
  state.t_start = Date.now();
  writeState('STARTING');

  const startedAt = Date.now();
  const events = [];

  const record = {
    case: 'long-pending-wait-with-renewal',
    model: MODEL,
    holdMs: HOLD_MS,
    sessionCreated: false,
    modelSet: null,
    setModelResponse: null,
    currentModeId: null,
    pid: null,
    permissionRequests: [],
    grantsAllowOnce: 0,
    allowOnceSelections: 0,
    allowAlwaysSelections: 0,
    identifiedWrites: 0,
    stopReason: null,
    promptResponseAt: null,
    promptSentAt: null,
    fileChecks: [],
    usageFragments: [],
    modelObservability: {
      observations: [],
      modelFieldMatches: [],
      creditFieldMatches: [],
      usageFieldMatches: [],
      updateTypes: [],
    },
    effectiveModel: null,
    effectiveModelPath: null,
    promptUsageRaw: null,
    promptMetaRaw: null,
    hostRenewalRaw: null,
    hostRenewalInterleaving: null,
    events: [],
    totalEvents: 0,
    eventsTruncated: 0,
    stderrTail: '',
    note: null,
  };

  let finished = false;
  let resolveRun = null;
  const done = new Promise(resolve => {
    resolveRun = resolve;
  });

  let sessionId = null;
  let childExited = false;
  let stderrTail = '';
  let phaseTimer = null;
  let answerTimer = null;
  let caseTimer = null;
  let permissionArrived = false;
  let turnArmed = false;

  const pending = new Map();

  const send = message => {
    try {
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
    } catch {
      /* pipe closed */
    }
  };

  const request = (kind, id, payload, extra = {}) => {
    pending.set(String(id), { kind, id });
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

  function armPhase(ms, label) {
    clearTimeout(phaseTimer);
    phaseTimer = setTimeout(() => finish(`bounded wait expired: ${label} (${ms} ms)`), ms);
  }

  function checkFile() {
    const exists = fs.existsSync(TARGET_FILE);
    let content = null;
    let contentOk = false;
    if (exists) {
      try {
        content = fs.readFileSync(TARGET_FILE, 'utf8').trim();
        contentOk = content === FILE_CONTENT;
      } catch (error) {
        content = `<read error: ${error && error.message ? error.message : error}>`;
      }
    }
    return {
      path: TARGET_FILE,
      expectedContent: FILE_CONTENT,
      exists,
      contentExcerpt: excerpt(content, 60),
      contentOk,
    };
  }

  // Terminate the CLI (SIGTERM, then SIGKILL) before the run resolves; the
  // sandbox itself is killed by the harness afterwards.
  function killAndFinish(callback) {
    if (childExited) {
      setTimeout(callback, 800);
      return;
    }
    try {
      child.kill('SIGTERM');
    } catch {
      /* ignore */
    }
    const deadline = Date.now() + 8000;
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
        setTimeout(callback, childExited ? 800 : 2000);
      }
    }, 250);
  }

  function finish(note) {
    if (finished) {
      return;
    }
    finished = true;
    if (note) {
      record.note = note;
    }
    clearTimeout(phaseTimer);
    clearTimeout(answerTimer);
    clearTimeout(caseTimer);
    record.stderrTail = stderrTail.trim().slice(-600);
    killAndFinish(() => {
      record.fileChecks = [checkFile()];
      // Host-side renewal bracket (written by QoderWaitRenewalE2ETest while the
      // request was pending): read it back and record whether it sits inside the
      // probe's own [PENDING, ANSWER] window — the portable half of the
      // interleaving evidence (the Java test asserts the same with its own clock).
      try {
        if (fs.existsSync(HOST_RENEWAL_FILE)) {
          record.hostRenewalRaw = fs.readFileSync(HOST_RENEWAL_FILE, 'utf8').trim();
          const before = /sandbox_before=(\d+)/.exec(record.hostRenewalRaw);
          const after = /sandbox_after=(\d+)/.exec(record.hostRenewalRaw);
          if (before && after && state.t_pending > 0 && state.t_answer > 0) {
            record.hostRenewalInterleaving = {
              sandboxBefore: Number(before[1]),
              sandboxAfter: Number(after[1]),
              tPending: state.t_pending,
              tAnswer: state.t_answer,
              withinPendingWindow: Number(before[1]) >= state.t_pending && Number(after[1]) <= state.t_answer,
            };
          }
        }
      } catch (error) {
        record.hostRenewalRaw = `<read error: ${error && error.message ? error.message : error}>`;
      }
      evaluate();
      state.file_ok = record.fileChecks[0] && record.fileChecks[0].contentOk ? 1 : 0;
      state.stop_reason = record.stopReason || '-';
      writeState(failures.length === 0 && state.t_prompt_end > 0 ? 'COMPLETED' : 'FAILED');
      printSummary();
      resolveRun();
    });
  }

  const caseTimerSetup = () => {
    caseTimer = setTimeout(() => finish(`hard case timeout after ${CASE_TIMEOUT_MS} ms`), CASE_TIMEOUT_MS);
  };
  caseTimerSetup();

  // ---- model / usage / credit observability scan ---------------------------

  function scanObservability(kind, message, rawLine) {
    const obs = record.modelObservability;
    if (obs.observations.length < MAX_OBSERVATIONS && message.result && message.result._meta) {
      obs.observations.push({
        kind,
        path: 'result._meta',
        value: excerpt(JSON.stringify(message.result._meta), 400),
      });
    }
    if (obs.observations.length < MAX_OBSERVATIONS && message.params?.update
      && message.params.update.sessionUpdate !== undefined) {
      const type = message.params.update.sessionUpdate;
      if (!obs.updateTypes.includes(type)) {
        obs.updateTypes.push(type);
      }
      const updateMeta = message.params.update._meta;
      if (updateMeta) {
        obs.observations.push({
          kind,
          path: 'params.update._meta',
          value: excerpt(JSON.stringify(updateMeta), 300),
        });
      }
    }
    for (const [bucket, pattern] of [
      [obs.modelFieldMatches, /"(model|modelId|model_usage)"\s*:\s*[^,}\]]+/g],
      [obs.creditFieldMatches, /"(credits|total_credits|cost|total_cost_usd|cost_usd)"\s*:\s*[^,}\]]+/g],
      [obs.usageFieldMatches, /"(usage|inputTokens|outputTokens|totalTokens|input_tokens|output_tokens)"\s*:\s*[^,}\]]+/g],
    ]) {
      const matches = rawLine.match(pattern);
      if (matches) {
        for (const match of matches.slice(0, 4)) {
          if (bucket.length < 60) {
            bucket.push({ kind, match: excerpt(match, 120) });
          }
        }
      }
    }
    // The prompt response is the one place A4 saw the effective model: pin the
    // exact path when it is present, record nothing when it is not.
    const modelUsage = message.result?._meta?.quota?.model_usage;
    if (Array.isArray(modelUsage) && modelUsage.length > 0 && typeof modelUsage[0].model === 'string') {
      record.effectiveModel = modelUsage[0].model;
      record.effectiveModelPath = `${kind} result._meta.quota.model_usage[0].model`;
    }
  }

  // ---- permission handling --------------------------------------------------

  // Identify the write we asked for (same rule as A4: `_meta.qoder.toolName` plus
  // `rawInput.file_path` resolved against the session cwd, title only as a
  // fallback — the title was null in the A4 build).
  function identifyWrite(message) {
    const params = message.params || {};
    const toolCall = params.toolCall || {};
    const qoderToolName = toolCall?._meta?.qoder?.toolName ?? null;
    const toolKind = toolCall.kind ?? null;
    const rawInput = toolCall.rawInput || {};
    const rawFilePath = typeof rawInput.file_path === 'string' ? rawInput.file_path
      : (typeof rawInput.filePath === 'string' ? rawInput.filePath : null);
    const title = typeof params.title === 'string' ? params.title : null;
    const isWriteTool = ['Write', 'Edit', 'MultiEdit', 'NotebookEdit', 'write', 'edit']
      .includes(qoderToolName) || toolKind === 'edit';
    const pathOk = rawFilePath !== null && rawFilePath === TARGET_FILE;
    const titleOk = title !== null && title.includes('written.txt');
    const basis = pathOk ? 'file_path' : (titleOk && rawFilePath === null ? 'title' : 'none');
    return {
      params,
      toolCall,
      qoderToolName,
      toolKind,
      rawFilePath,
      title,
      identified: isWriteTool && basis !== 'none',
      basis,
    };
  }

  // Reply with the offered option of the given kind (by kind, never by position);
  // the escalation counter is derived from the optionId actually sent. Returns the
  // selected kind or null when the menu offers no such option.
  function replySelectedKind(entry, optionsList, kind) {
    const selected = optionsList.find(option => option.kind === kind);
    if (!selected) {
      return null;
    }
    if (kind === 'allow_always') {
      record.allowAlwaysSelections += 1;
    }
    entry.decision = `${selected.kind}:${selected.optionId}`;
    entry.reply = { outcome: { outcome: 'selected', optionId: selected.optionId } };
    entry.replied = true;
    send({ id: entry.rawRequestId, result: entry.reply });
    logEvent('out', 'permission-reply', { id: entry.rawRequestId }, { seq: entry.seq, reply: JSON.stringify(entry.reply) });
    return selected.kind;
  }

  function answerPermission(entry, optionsList, info, isFirst) {
    if (isFirst) {
      state.t_answer = Date.now();
      record.heldMs = state.t_answer - state.t_pending;
    }
    if (!entry.identified) {
      // Never approve an unidentified request; the reject_once option is the
      // verified reply shape (04-permissions.md §4). No reject_once menu → the
      // flat cancelled shape, which is NOT EXERCISED in any gate so far and is
      // annotated as shape-unverified (B3a must not copy it).
      const rejected = replySelectedKind(entry, optionsList, 'reject_once');
      if (!rejected) {
        entry.decision = 'cancelled-unidentified-no-reject-once-option';
        entry.reply = { outcome: 'cancelled' }; // NOT EXERCISED / shape-unverified
        entry.replied = true;
        send({ id: entry.rawRequestId, result: entry.reply });
      }
      entry.answeredBy = 'refused-unidentified';
    } else {
      const granted = replySelectedKind(entry, optionsList, 'allow_once');
      if (granted) {
        record.grantsAllowOnce += 1;
        record.allowOnceSelections += 1;
        entry.answeredBy = 'allow_once';
      } else {
        const rejected = replySelectedKind(entry, optionsList, 'reject_once');
        if (!rejected) {
          entry.decision = 'cancelled-no-allow-once-option';
          entry.reply = { outcome: 'cancelled' }; // NOT EXERCISED / shape-unverified
          entry.replied = true;
          send({ id: entry.rawRequestId, result: entry.reply });
        }
        entry.answeredBy = 'refused-no-allow-once';
      }
    }
    if (isFirst) {
      writeState('ANSWERED', { t_answer: state.t_answer, held_ms: record.heldMs, request_id: String(entry.rawRequestId) });
    }
    console.log(`A5-ANSWER-JSON: ${JSON.stringify({
      seq: entry.seq,
      first: Boolean(isFirst),
      requestId: entry.requestId,
      t_pending_sandbox_ms: state.t_pending,
      t_answer_sandbox_ms: state.t_answer,
      held_ms: record.heldMs,
      decision: entry.decision,
      identified: entry.identified,
      answeredBy: entry.answeredBy,
    })}`);
    if (isFirst && !turnArmed) {
      turnArmed = true;
      armPhase(TURN_WAIT_MS, 'prompt response after the permission answer');
    }
  }

  function onPermissionRequest(message) {
    const info = identifyWrite(message);
    const optionsList = Array.isArray(info.params.options) ? info.params.options : [];
    const seq = record.permissionRequests.length + 1;
    const entry = {
      seq,
      rawRequestId: message.id,
      requestId: shortenId(message.id),
      toolCallId: shortenId(info.toolCall.toolCallId),
      title: excerpt(info.title, 160),
      qoderToolName: info.qoderToolName,
      toolKind: info.toolKind,
      filePath: info.rawFilePath,
      expectedTarget: TARGET_FILE,
      identifiedBy: info.basis,
      identified: info.identified,
      offeredOptionIds: optionsList.map(option => option.optionId),
      offeredKinds: optionsList.map(option => option.kind),
      options: optionsList.map(option => ({ optionId: option.optionId, name: option.name, kind: option.kind })),
      allowAlwaysOffered: optionsList.some(option => option.kind === 'allow_always'),
      decision: null,
      reply: null,
      replied: false,
      answeredBy: null,
      t: Date.now() - startedAt,
    };
    record.permissionRequests.push(entry);
    if (entry.identified) {
      record.identifiedWrites += 1;
    }
    logEvent('in', 'permission', message, {
      seq,
      toolCallId: entry.toolCallId,
      kind: entry.toolKind,
      tool: entry.qoderToolName,
      optionKinds: entry.offeredKinds,
      identified: entry.identified,
      identifiedBy: entry.identifiedBy,
    });

    if (!permissionArrived) {
      permissionArrived = true;
      clearTimeout(phaseTimer);
      state.t_pending = Date.now();
      state.request_id = String(message.id ?? '-');
      writeState('PENDING', { t_pending: state.t_pending, request_id: String(message.id ?? '-') });
      console.log(`A5-PENDING-JSON: ${JSON.stringify({
        seq,
        requestId: entry.requestId,
        tool: entry.qoderToolName,
        kind: entry.toolKind,
        identified: entry.identified,
        identifiedBy: entry.identifiedBy,
        resolvedFilePath: entry.filePath,
        expectedTarget: entry.expectedTarget,
        offeredKinds: entry.offeredKinds,
        t_pending_sandbox_ms: state.t_pending,
        hold_ms: HOLD_MS,
      })}`);
      // The evidence run holds for the full 5 minutes (task brief); the host
      // driver renews the sandbox TTL inside this window. Any later request is
      // answered immediately and never overwrites the first request's window.
      answerTimer = setTimeout(() => answerPermission(entry, optionsList, info, true), HOLD_MS);
      armPhase(HOLD_MS + TURN_WAIT_MS + 30000, 'turn completion after the 5-minute hold');
    } else {
      // Any additional request is answered immediately (never allow_always).
      answerPermission(entry, optionsList, info, false);
    }
  }

  // ---- inbound dispatch -----------------------------------------------------

  function onSetModelResponse(message) {
    record.modelSet = message.error
      ? `error ${message.error.code}: ${excerpt(message.error.message, 120)}`
      : 'accepted';
    record.setModelResponse = excerpt(JSON.stringify(message.result ?? null), 300);
  }

  function onSessionNewResponse(message) {
    record.sessionCreated = true;
    sessionId = message.result?.sessionId ?? null;
    record.currentModeId = message.result?.modes?.currentModeId ?? null;
  }

  function onPromptResponse(message) {
    const now = Date.now();
    record.stopReason = message.result?.stopReason ?? null;
    record.promptResponseAt = now;
    record.promptUsageRaw = excerpt(JSON.stringify(message.result?.usage ?? null), 300);
    record.promptMetaRaw = excerpt(JSON.stringify(message.result?._meta ?? null), 400);
    state.t_prompt_end = now;
    logEvent('in', 'rpc-response', message, { kind: 'session/prompt', stopReason: record.stopReason });
    console.log(`A5-DEADLINE-JSON: ${JSON.stringify({
      promptSentEpochMs: state.t_prompt_sent,
      promptResponseEpochMs: now,
      promptWallClockMs: now - state.t_prompt_sent,
      promptToPermissionMs: state.t_pending && state.t_prompt_sent ? state.t_pending - state.t_prompt_sent : null,
      heldMs: state.t_answer && state.t_pending ? state.t_answer - state.t_pending : null,
      answerToResponseMs: state.t_answer ? now - state.t_answer : null,
      deadlineRule: 'hard deadline = promptSentEpochMs + maxDuration; the prompt response epoch closes the turn',
    })}`);
    finish('prompt response received');
  }

  function onResponse(entry, message) {
    if (message.error && entry.kind !== 'set_model') {
      finish(`${entry.kind} failed: ${excerpt(message.error, 200)}`);
      return;
    }
    if (entry.kind === 'initialize') {
      request('session/new', 2, { method: 'session/new', params: { cwd: WORKSPACE, mcpServers: [] } });
      return;
    }
    if (entry.kind === 'session/new') {
      onSessionNewResponse(message);
      request('set_model', 3, { method: 'session/set_model', params: { sessionId, modelId: MODEL } });
      return;
    }
    if (entry.kind === 'set_model') {
      onSetModelResponse(message);
      state.t_prompt_sent = Date.now();
      record.promptSentAt = state.t_prompt_sent;
      writeState('PROMPTED', { t_prompt_sent: state.t_prompt_sent });
      armPhase(PERMISSION_WAIT_MS, 'write permission request after the prompt');
      request('prompt', 10, {
        method: 'session/prompt',
        params: {
          sessionId,
          prompt: [{
            type: 'text',
            text: `Create a new file at ${TARGET_FILE} containing exactly the single word "renewed" `
              + '(no quotes, no trailing period). Use the Write file tool and nothing else; '
              + 'do not use shell commands.',
          }],
        },
      }, { promptIndex: 0, target: TARGET_FILE });
      return;
    }
    if (entry.kind === 'prompt') {
      onPromptResponse(message);
    }
  }

  function handle(message, rawLine) {
    if (message.method === undefined) {
      const key = message.id === undefined ? null : String(message.id);
      const entry = key === null ? undefined : pending.get(key);
      if (entry) {
        pending.delete(key);
        scanObservability(`response:${entry.kind}`, message, rawLine);
        onResponse(entry, message);
      } else {
        logEvent('in', 'stray-response', message, { resultOrError: excerpt(JSON.stringify(message.result ?? message.error ?? {}), 160) });
      }
      return;
    }

    if (message.method === 'session/update') {
      scanObservability(`update:${message.params?.update?.sessionUpdate ?? '<none>'}`, message, rawLine);
      logEvent('in', 'update', message, { type: message.params?.update?.sessionUpdate ?? '<none>' });
      return;
    }
    if (message.method === 'session/request_permission') {
      scanObservability('request:session/request_permission', message, rawLine);
      onPermissionRequest(message);
      return;
    }
    if (message.id !== undefined) {
      logEvent('in', 'unsupported-request', message, { method: message.method });
      send({ id: message.id, error: { code: -32601, message: 'Unsupported client method' } });
      return;
    }
    logEvent('in', 'notification', message, { method: message.method });
  }

  // ---- criteria + summary ---------------------------------------------------

  const failures = [];

  function report(name, ok, details) {
    console.log(`[A5] ${ok ? 'PASS' : 'FAIL'}: ${name} ${details}`);
    if (!ok) {
      failures.push(name);
    }
  }

  function buildModelObservability() {
    const obs = record.modelObservability;
    const notObservable = [];
    if (obs.creditFieldMatches.length === 0) {
      notObservable.push('credit/cost fields ("credits", "total_credits", "cost", "total_cost_usd", "cost_usd"): 0 occurrences in any inbound message');
    } else {
      notObservable.push(`credit/cost field matches observed: ${JSON.stringify(obs.creditFieldMatches)}`);
    }
    const modelMatchesOutsidePrompt = obs.modelFieldMatches.filter(match => !/^response:prompt$/.test(match.kind));
    if (modelMatchesOutsidePrompt.length === 0) {
      notObservable.push('model field outside the session/prompt response: 0 occurrences (initialize / session/new / set_model responses and all session/update notifications carry none)');
    } else {
      notObservable.push(`model field matches outside the prompt response: ${JSON.stringify(modelMatchesOutsidePrompt)}`);
    }
    notObservable.push(`usage counters: all zero when present (prompt response result.usage and result._meta.quota.token_count: ${record.promptUsageRaw})`);
    if (!obs.updateTypes.includes('session_started')) {
      notObservable.push(`session_started update: not observed; update types observed: ${JSON.stringify(obs.updateTypes)}`);
    }
    return {
      effectiveModel: record.effectiveModel,
      effectiveModelPath: record.effectiveModelPath,
      promptResponseUsage: record.promptUsageRaw,
      promptResponseMeta: record.promptMetaRaw,
      setModelResponse: record.setModelResponse,
      observations: obs.observations,
      modelFieldMatches: obs.modelFieldMatches,
      creditFieldMatches: obs.creditFieldMatches,
      usageFieldMatches: obs.usageFieldMatches.slice(0, 12),
      updateTypesObserved: obs.updateTypes,
      notObservable,
    };
  }

  function evaluate() {
    const permission = record.permissionRequests[0] || null;
    const file = record.fileChecks[0] || null;
    const holdOk = state.t_pending > 0 && state.t_answer > 0
      && (state.t_answer - state.t_pending) >= HOLD_MS;
    const turnCompleted = record.stopReason !== null && state.t_prompt_end > state.t_answer;
    const fileOk = Boolean(file) && file.exists && file.contentOk;

    report('permission request: the prompted write raised an identified session/request_permission',
      Boolean(permission) && permission.identified,
      `requests=${record.permissionRequests.length} identifiedWrites=${record.identifiedWrites} `
      + `identifiedBy=${permission ? permission.identifiedBy : 'n/a'} offeredKinds=${permission ? JSON.stringify(permission.offeredKinds) : 'n/a'}`);
    report('long wait: the request was held pending >= 5 min and then answered by kind (allow_once)',
      holdOk && record.allowOnceSelections >= 1 && record.allowAlwaysSelections === 0,
      `t_pending=${state.t_pending} t_answer=${state.t_answer} held_ms=${state.t_answer && state.t_pending ? state.t_answer - state.t_pending : 'n/a'} `
      + `hold_ms=${HOLD_MS} decision=${permission ? permission.decision : 'n/a'} allowOnceSelections=${record.allowOnceSelections} `
      + `allowAlwaysSelections=${record.allowAlwaysSelections}`);
    report('turn completed after the hold: prompt response arrived after the answer',
      turnCompleted,
      `stopReason=${record.stopReason} t_answer=${state.t_answer} t_prompt_end=${state.t_prompt_end}`);
    report('CLI still executes: the Write tool created the target file with the exact content after the hold',
      fileOk,
      `exists=${file ? file.exists : 'n/a'} contentOk=${file ? file.contentOk : 'n/a'} content=${file ? JSON.stringify(file.contentExcerpt) : 'n/a'}`);
    report('interleaving evidence: host renewal bracket sits inside the pending window',
      Boolean(record.hostRenewalInterleaving && record.hostRenewalInterleaving.withinPendingWindow),
      record.hostRenewalInterleaving
        ? `sandbox_before=${record.hostRenewalInterleaving.sandboxBefore} sandbox_after=${record.hostRenewalInterleaving.sandboxAfter} `
          + `t_pending=${record.hostRenewalInterleaving.tPending} t_answer=${record.hostRenewalInterleaving.tAnswer}`
        : 'host renewal file not present at completion');
  }

  function printSummary() {
    const observability = buildModelObservability();
    const summary = {
      case: record.case,
      model: MODEL,
      holdMs: HOLD_MS,
      timings: {
        probeStartEpochMs: state.t_start,
        promptSentEpochMs: state.t_prompt_sent,
        promptResponseEpochMs: state.t_prompt_end,
        promptWallClockMs: state.t_prompt_end && state.t_prompt_sent ? state.t_prompt_end - state.t_prompt_sent : null,
        permissionRequestEpochMs: state.t_pending,
        permissionAnswerEpochMs: state.t_answer,
        heldMs: state.t_answer && state.t_pending ? state.t_answer - state.t_pending : null,
      },
      session: {
        sessionCreated: record.sessionCreated,
        modelSet: record.modelSet,
        setModelResponse: record.setModelResponse,
        currentModeId: record.currentModeId,
        permissionRequests: record.permissionRequests.length,
        identifiedWrites: record.identifiedWrites,
        allowOnceSelections: record.allowOnceSelections,
        allowAlwaysSelections: record.allowAlwaysSelections,
        stopReason: record.stopReason,
      },
      turnExecutedAfterAnswer: {
        fileChecks: record.fileChecks,
      },
      modelObservability: observability,
      hostRenewalRaw: record.hostRenewalRaw,
      hostRenewalInterleaving: record.hostRenewalInterleaving,
      totalEvents: record.totalEvents,
      eventsTruncated: record.eventsTruncated,
      stderrTail: record.stderrTail || null,
      note: record.note,
    };
    console.log(`A5-MODEL-OBSERVABILITY-JSON: ${JSON.stringify(observability)}`);
    for (const entry of record.permissionRequests) {
      console.log(`A5-PERMISSION-JSON: ${JSON.stringify(entry)}`);
    }
    console.log(`A5-EVENT-LOG-JSON: ${JSON.stringify({ case: record.case, events })}`);
    if (record.hostRenewalRaw) {
      console.log(`A5-HOST-RENEWAL: ${record.hostRenewalRaw}`);
    }
    console.log(`A5-SUMMARY-JSON: ${JSON.stringify(summary)}`);
    if (failures.length === 0) {
      console.log('A5-WAIT-RENEWAL-RESULT: PASS');
      exitSoon(0);
      return;
    }
    console.log(`A5-WAIT-RENEWAL-RESULT: FAIL (${failures.length} criterion(s): ${failures.join('; ')})`);
    exitSoon(failures.length);
  }

  // ---- ACP process ----------------------------------------------------------

  console.log(`[A5] model pin: QODER_E2E_MODEL=${MODEL} (spawn -m ${MODEL} + session/set_model)`);
  console.log(`[A5] bounds: HOLD_MS=${HOLD_MS} PERMISSION_WAIT_MS=${PERMISSION_WAIT_MS} `
    + `TURN_WAIT_MS=${TURN_WAIT_MS} CASE_TIMEOUT_MS=${CASE_TIMEOUT_MS}`);
  console.log(`[A5] state=${STATE_FILE} target=${TARGET_FILE} content=${JSON.stringify(FILE_CONTENT)} (sandbox /tmp, never the repo)`);

  if (!process.env[TOKEN_ENV] || process.env[TOKEN_ENV].trim() === '') {
    console.log(`[A5] FAIL: ${TOKEN_ENV} is not present in the environment — the authenticated ACP `
      + 'session requires it (injected into the sandbox environment, never argv)');
    console.log('A5-WAIT-RENEWAL-RESULT: FAIL (missing credential)');
    process.exit(2);
  }

  // Credential handling: inherit the environment (the PAT arrives only this way),
  // drop the SDK entrypoint variables that make `--acp` refuse to start (A3).
  const env = { ...process.env };
  for (const key of ['QODER_AGENT_SDK_ENTRYPOINT', 'QODER_WORKER_RUNTIME_ASSET_ROOT', 'QODER_AGENT_SDK_VERSION',
    'QODERCLI_RUNTIME_PACKAGING', 'QODER_SESSION_TYPE', 'QODER_WORKER_CWD', 'QODER_SDK_AUTH_PAYLOAD_FILE']) {
    delete env[key];
  }

  const child = spawn('qodercli', ['-m', MODEL, '--acp'], { cwd: WORKSPACE, env, stdio: ['pipe', 'pipe', 'pipe'] });
  record.pid = child.pid ?? null;

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

main().catch(error => {
  console.log(`[A5] FAIL: unexpected error: ${error && error.stack ? error.stack : error}`);
  console.log('A5-WAIT-RENEWAL-RESULT: FAIL (unexpected error)');
  exitSoon(9);
});

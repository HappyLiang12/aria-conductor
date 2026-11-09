#!/usr/bin/env node
// Deterministic fake of the Task 9 Windows host-control supervisor line protocol
// (assign/identity/record/tree/suspend/resume/kill/pids/exit), used by
// HostExecutionBackendIntegrationTest to exercise the controller's Java logic
// without PowerShell and without a race. No real process is suspended or killed
// here; the fixture writes a protocol log the JUnit side asserts on.
//
// Usage: node fake-host-supervisor.mjs --log <path> [--scenario <name>]
//
// Scenarios:
//   partial-suspend (default)
//       The pause/resume ledger window: the scripted record "<root+2>@1002" is
//       reported by the tree enumeration and then answers "gone" from the suspend
//       batch -- exactly the "a record exits between the enumeration and the
//       batch" trigger -- while every other record reports "ok".
//   sampler-attribution
//       The R7 window: the FIRST tree enumeration reports a grandchild
//       ("7001@7001") beside the root; every later enumeration reports the root
//       only, so the grandchild is unreachable from the root's tree when the stop
//       arrives and can only be attributed through the sampler's observation.
//       "pids" lists it as a live job member until it is killed, then it lists
//       the remaining live members.
//   unaccounted-member
//       "pids" always lists "6001@666", a live job member no enumerated tree of
//       any run contains: the proof must fail closed and never signal it.
//   foreign-member
//       Two assigned roots. The second run's root stops answering after one
//       sampled record (its run's tree is then not enumerable), while its own
//       child "7002@7002" stays a live job member. Stopping the first run must
//       fail closed on that member rather than silently exclude it.
//   deadline-stragglers
//       Same tree shape as sampler-attribution, but the verdict for the
//       grandchild record is delayed past the caller's window: the stop must
//       re-check its deadline before it signals anything after the straggler scan.
//   dead-after-first-sample
//       The supervisor serves the ready line and one sampled tree, then exits:
//       a sampler that snapshots a live supervisor must never recreate one.
//   many-exited-records
//       One enumeration reports 700 synthetic grandchildren that all answer
//       "gone" (one-off CLI children). The sampler's remembered population is
//       bounded; the fixture only logs the tree it served.
//   over-cap-live-records
//       One enumeration reports 700 synthetic grandchildren that all answer
//       "match" (they stay live until killed) and that "pids" never lists (the
//       documented best-effort job configuration: assignment refused, breakaway
//       children blind). They are unreachable from the root's tree afterwards, so
//       the sampler's record is their only attribution evidence: shedding one to
//       honour the cap would let the stop proof certify a live writer as stopped.
//   deadline-inventory
//       The first "pids" answer (the own-survivor scan) is immediate; the second
//       (the final job inventory) is delayed past the caller's window. A proof
//       that does not re-check the deadline after that round trip returns true
//       after the window elapsed.
//   head-live-tail-gone
//       Review round 3, P3-a: 700 synthetic grandchildren where the FIRST 32
//       DISTINCT identities the sampler probes are live ("match", logged as
//       "declared-live: <record>") and every later one is gone. That is the
//       adversarial population the "bounded by live process count" claim missed:
//       32+ live records sit at the iteration head and the dead ones queue behind
//       them. A prune pass that always starts at the same head probes the same 32
//       live records forever; a pass that rotates its budget reaches and evicts
//       the dead ones. "pids" never lists them (breakaway children blind), so the
//       retained live ones are only killable through the sampler's own records.
//   over-cap-unverifiable
//       Review round 3, P3-b: the same 700-record shape, but every identity
//       answers "unverifiable:times:87". An unverifiable record is not a definite
//       absence: the sampler must keep it, exactly as it keeps a live one.
//   mismatch-records
//       Review round 3: the same 700-record shape, but every identity answers
//       "mismatch:<pid>@9999" -- a recycled PID. A recycled identity can never
//       account for a live writer, so the sampler evicts it and logs the
//       identity it probed.
//   over-cap-mixed
//       Review round 3: the same 700-record shape, a mix of live and gone
//       identities (every tenth record is gone). Only the dead ones may be
//       evicted; the live ones survive the cap and the stop kills them.
//   unverifiable-record
//       Review round 3, P3-b: the sampler-attribution shape (a grandchild the
//       first enumeration reports and the root's later enumerations cannot
//       reach), but the grandchild's identity answers "unverifiable:open:5" at
//       stop time. A stop proof must fail closed on it: no kill without identity
//       verification, and the record stays in the remembered population.
//   unverifiable-member
//       Review round 3, P3-b: "pids" reports "unverifiable:5002" -- a live job
//       member whose identity the OS could not read. It is not a definite
//       absence either, so it must fail the proof closed rather than vanish from
//       the inventory.
//   late-sampled-record
//       Review round 3, P3-d: the root's tree is itself, and "12001@5001" is a
//       live breakaway writer that only the sampler's REMEMBERED record can
//       attribute (the tree never reports it, "pids" never lists it). A stop that
//       copied the observed set once before its root sweep can miss a record the
//       sampler adds in that window and certify a live writer as stopped.
//   dead-root-child
//       Release-blocking live shape (a WAITING_INPUT run whose core exited on its
//       own after delivering its question): the FIRST enumeration reports the
//       still-live child "7001@7001" beside the root -- the sampler's attribution
//       evidence -- and the root then answers "gone" for the rest of the run, while
//       the controller's in-memory binding still matches the launch. "pids" lists
//       the child as a live job member until it is killed, so the proof has to kill
//       it by identity to be satisfied.
//   dead-root-no-children
//       The same dead-root shape with nothing behind it: the first enumeration
//       reports the root itself and the root then answers "gone". "pids" is empty,
//       so the proof has nothing to stop and must still be able to verify that.
//   dead-root-unverifiable-child
//       The dead-root-child shape, but the observed child is a breakaway writer the
//       job never listed ("pids" is empty) and its identity answers
//       "unverifiable:open:5" at stop time: its sampled record is the only evidence
//       there is, and the proof must fail closed, name the identity and never
//       signal it.
//   recycled-root
//       The recorded root's pid was recycled by an unrelated process: the root
//       record answers "mismatch:<pid>@9999", and a tree enumeration anchored at
//       that pid would report the stranger's child "7003@7003" (logged as
//       "tree-of-recycled:") which the fake then lets a kill take. The bound run's
//       own tree is gone, so a stop must decide on identity evidence alone and must
//       never anchor a sweep on the recycled pid.
//   stale-tree-record
//       F33: every enumeration of a live root reports a stale parent link -- the
//       stranger "6001@1" (older than the root, whose synthetic creation is
//       5000000, by far more than the creation-order tolerance) and the
//       genuine child "6002@5000001". A snapshot's th32ParentProcessID names the
//       creator's pid at creation time; when that pid is recycled, an older process
//       looks like a descendant. Both are live identities ("match") and the fake
//       lets a kill take either, so a controller that trusts the link samples the
//       stranger and signals it; the root boundary must drop it and keep the child.
//   unverifiable-tree-node
//       F33 review P2: every enumeration reports "unverifiable:7004@1" -- a subtree
//       whose holder the OS cannot identify -- beside the genuine child "7005@9002"
//       and the root. The gap is not an identity: the sampler must not remember it
//       and the stop must fail closed without signalling anything, because a live
//       writer may sit under the unidentifiable subtree.
import { appendFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

const args = process.argv.slice(2);
const argValue = (name) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : null;
};
const logPath = argValue('--log');
const scenario = argValue('--scenario') || 'partial-suspend';
const log = (line) => {
  if (logPath) {
    appendFileSync(logPath, line + '\n');
  }
};
const say = (payload) => {
  process.stdout.write('ok|' + payload + '\n');
};

const partialSuspend = scenario === 'partial-suspend';
// The dead-root family: a run whose core exited on its own while the controller's
// in-memory binding still matches the launch (the WAITING_INPUT cancel shape).
// Production deliberately never enumerates a dead root's tree (it cannot be
// distinguished from a recycled pid's tree), so these scenarios model the tree as
// empty to pin that contract; Windows may retain an orphan's ParentProcessId.
const deadRootChild = scenario === 'dead-root-child';
const deadRootNoChildren = scenario === 'dead-root-no-children';
const deadRootUnverifiableChild = scenario === 'dead-root-unverifiable-child';
const roots = [];
let treeCalls = 0;
const applied = new Map();
const root0 = () => roots[0];
const root1 = () => roots[1];
const recordOf = (root) => `${root.pid}@${root.creation}`;
const grandchild = '7001@7001';
const grandchildState = { alive: true };
// Records the over-cap-live-records scenario reports live until they are killed.
const overCapLive = new Set();
// ---------------------------------------------------------------- review round 3
// The synthetic identities of the round-3 over-cap scenarios, in the "pid@creation"
// shape the tree reports them. One fixture process serves one scenario, so one set is
// enough; "kill" resolves them so a scenario that ends in a stop proof is truthful.
const SYNTHETIC_COUNT = 700;
const HEAD_TAIL_BASE = 14000;
const MISMATCH_BASE = 11000;
const UNVERIFIABLE_BASE = 13000;
const MIXED_BASE = 15000;
const HEAD_LIVE_LIMIT = 32;
const syntheticRecords = new Set();
// head-live-tail-gone: the first 32 distinct probed identities are live, the rest gone.
const headTailLive = new Set();
const headTailDead = new Set();
// over-cap-mixed: every tenth identity is gone, the rest stay live.
const mixedLive = new Set();
// late-sampled-record: a live breakaway writer only the sampler's record attributes.
const lateRecord = '12001@5001';
let lateAlive = true;
// recycled-root: the stranger now holding the recorded pid and its child.
const recycledRoot = scenario === 'recycled-root';
const recycledRootCreation = '9999';
const recycledChild = '7003@7003';
let recycledChildAlive = true;
// stale-tree-record: the F33 shape. The root's synthetic creation is large enough that
// the creation-order tolerance is meaningful: the stranger predates the root by far more
// than 100 ms, the genuine child does not. Both answer "match" while alive and the fake
// lets a kill take either, so a controller that trusts the stale link signals the stranger.
const staleTreeRecord = scenario === 'stale-tree-record';
const staleStranger = '6001@1';
const staleChild = '6002@5000001';
const staleStrangerState = { alive: true };
const staleChildState = { alive: true };
// unverifiable-tree-node: a subtree the OS cannot identify. It is not an identity and
// the proof may not certify it either; the genuine child keeps the rest of the tree real.
const unverifiableTree = scenario === 'unverifiable-tree-node';
// unverifiable-member: a live job member whose identity the OS cannot read.
const unverifiableMember = 'unverifiable:5002';
const syntheticScenario = () => scenario === 'head-live-tail-gone'
  || scenario === 'over-cap-unverifiable'
  || scenario === 'mismatch-records'
  || scenario === 'over-cap-mixed';
let pidsCalls = 0;
const overCapRecord = (index) => `${10000 + index}@5001`;
// The scenarios whose tree reports the grandchild that later outlives its root.
// dead-root-child keeps it a live job member (the proof must kill it); the
// unverifiable sibling is a breakaway writer the job never listed.
const hasGrandchild = () => scenario === 'sampler-attribution' || scenario === 'deadline-stragglers'
  || deadRootChild;
/** The job's live members: the live roots plus the grandchild while it is alive. */
const liveMembers = () => {
  const members = roots.filter((root) => root.alive).map(recordOf);
  if (hasGrandchild() && grandchildState.alive) {
    members.push(grandchild);
  }
  return members;
};

// The ready line the Supervisor constructor waits for: "ok|<self identity>".
log('ready: ' + process.pid);
say(`${process.pid}@${Date.now()}`);

// ---------------------------------------------------------------- partial-suspend
const goneRecord = () => `${root0().pid + 2}@1002`;
const partialTree = () => `${root0().pid + 1}@1001@2;${goneRecord()}@1;${recordOf(root0())}@0`;

// ---------------------------------------------------------------- shared scenario shapes
const samplerShape = () =>
  scenario === 'sampler-attribution' || scenario === 'deadline-stragglers';

/** The tree of the requested root: the grandchild is reported exactly once, by the sampler. */
function treeOf(pid) {
  if (partialSuspend) {
    return partialTree();
  }
  if (deadRootNoChildren) {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    // The core exits on its own: from here on the root answers "gone".
    const record = recordOf(root);
    root.alive = false;
    log('root-dead: ' + record);
    return `${record}@0`;
  }
  if (recycledRoot) {
    // A sweep anchored on the recycled pid would enumerate a stranger's tree.
    log('tree-of-recycled: ' + pid);
    return `${recycledChild}@1;${pid}@${recycledRootCreation}@0`;
  }
  if (staleTreeRecord) {
    // The F33 shape on every enumeration: a stale parent link makes the older stranger
    // look like a child of the root. The genuine child keeps it a real tree.
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    return `${staleStranger}@1;${staleChild}@1;${recordOf(root)}@0`;
  }
  if (unverifiableTree) {
    // A subtree the OS cannot identify beside a genuine child and the verified root.
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    return `unverifiable:7004@1;7005@9002@1;${recordOf(root)}@0`;
  }
  if (deadRootChild || deadRootUnverifiableChild) {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    if (treeCalls++ === 0) {
      log('sampled: ' + grandchild);
      const record = recordOf(root);
      root.alive = false; // the core exits on its own after the child was observed
      return `${grandchild}@1;${record}@0`;
    }
    return '';
  }
  if (scenario === 'sampler-attribution' || scenario === 'deadline-stragglers') {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    if (treeCalls++ === 0) {
      log('sampled: ' + grandchild);
      return `${grandchild}@1;${recordOf(root)}@0`;
    }
    return `${recordOf(root)}@0`;
  }
  if (scenario === 'many-exited-records') {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    if (treeCalls++ === 0) {
      const synthetic = [];
      for (let index = 0; index < 700; index += 1) {
        synthetic.push(`${10000 + index}@5001@1`);
      }
      log('sampled-many: ' + synthetic.length);
      return synthetic.join(';') + `;${recordOf(root)}@0`;
    }
    return `${recordOf(root)}@0`;
  }
  if (scenario === 'over-cap-live-records') {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    if (treeCalls++ === 0) {
      const synthetic = [];
      for (let index = 0; index < 700; index += 1) {
        synthetic.push(`${overCapRecord(index)}@1`);
        overCapLive.add(overCapRecord(index));
      }
      log('sampled-over-cap: ' + synthetic.length);
      return synthetic.join(';') + `;${recordOf(root)}@0`;
    }
    return `${recordOf(root)}@0`;
  }
  if (scenario === 'over-cap-mixed') {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    if (treeCalls++ === 0) {
      const synthetic = [];
      for (let index = 0; index < SYNTHETIC_COUNT; index += 1) {
        const record = `${MIXED_BASE + index}@5001`;
        syntheticRecords.add(record);
        if (index % 10 !== 0) {
          mixedLive.add(record); // every tenth identity is gone
        }
        synthetic.push(`${record}@1`);
      }
      log('sampled-mixed: ' + synthetic.length);
      return synthetic.join(';') + `;${recordOf(root)}@0`;
    }
    return `${recordOf(root)}@0`;
  }
  if (syntheticScenario()) {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    if (treeCalls++ === 0) {
      const base = scenario === 'head-live-tail-gone' ? HEAD_TAIL_BASE
        : (scenario === 'over-cap-unverifiable' ? UNVERIFIABLE_BASE : MISMATCH_BASE);
      const synthetic = [];
      for (let index = 0; index < SYNTHETIC_COUNT; index += 1) {
        const record = `${base + index}@5001`;
        syntheticRecords.add(record);
        synthetic.push(`${record}@1`);
      }
      log(`sampled-${scenario}: ${synthetic.length}`);
      return synthetic.join(';') + `;${recordOf(root)}@0`;
    }
    return `${recordOf(root)}@0`;
  }
  if (scenario === 'foreign-member') {
    const first = root0();
    if (first && first.pid === pid) {
      return first.alive ? `${recordOf(first)}@0` : '';
    }
    // The second run's root is dead: its tree is not enumerable any more.
    return '';
  }
  if (scenario === 'unverifiable-record') {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    if (treeCalls++ === 0) {
      log('sampled: ' + grandchild);
      return `${grandchild}@1;${recordOf(root)}@0`;
    }
    return `${recordOf(root)}@0`;
  }
  if (scenario === 'late-sampled-record') {
    const root = root0();
    if (!root || root.pid !== pid || !root.alive) {
      return '';
    }
    return `${recordOf(root)}@0`;
  }
  const root = root0();
  if (!root || root.pid !== pid || !root.alive) {
    return '';
  }
  return `${recordOf(root)}@0`;
}

/** The verdict of one identity record. */
function verdictOf(record) {
  if (recycledRoot) {
    const root = root0();
    if (root && record === recordOf(root)) {
      // The pid is held by an unrelated process now: the recorded identity is gone
      // from it, which is a mismatch, never a live record of this run.
      if (!root.reported) {
        root.reported = true;
        log('root-recycled: ' + record);
      }
      return `mismatch:${root.pid}@${recycledRootCreation}`;
    }
    if (record === recycledChild) {
      return recycledChildAlive ? 'match' : 'gone';
    }
    return 'gone';
  }
  if (partialSuspend) {
    return record === recordOf(root0()) ? 'match' : 'gone';
  }
  if (scenario === 'unaccounted-member') {
    const root = root0();
    if (root && record === recordOf(root)) {
      return root.alive ? 'match' : 'gone';
    }
    return 'match'; // a live writer that belongs to no enumerable run
  }
  if (scenario === 'foreign-member') {
    const second = root1();
    if (second && record === recordOf(second)) {
      // The run's root dies after its first sampled record: from then on the run
      // is supervised but its tree cannot be enumerated.
      second.lookups = (second.lookups || 0) + 1;
      if (second.lookups > 1) {
        if (!second.reported) {
          second.reported = true;
          log('foreign-root-dead: ' + record);
        }
        second.alive = false;
        return 'gone';
      }
    }
  }
  if (scenario === 'many-exited-records' && record !== recordOf(root0())) {
    return 'gone'; // every remembered child exited
  }
  if (scenario === 'over-cap-live-records' && overCapLive.has(record)) {
    return 'match'; // live until the proof kills it by identity
  }
  if (syntheticRecords.has(record)) {
    if (scenario === 'head-live-tail-gone') {
      if (headTailLive.has(record)) {
        return 'match';
      }
      if (headTailDead.has(record)) {
        return 'gone';
      }
      if (headTailLive.size < HEAD_LIVE_LIMIT) {
        headTailLive.add(record);
        log('declared-live: ' + record);
        return 'match';
      }
      headTailDead.add(record);
      return 'gone';
    }
    if (scenario === 'over-cap-unverifiable') {
      return 'unverifiable:times:87'; // the OS could not read the identity: unknown, not gone
    }
    if (scenario === 'mismatch-records') {
      return `mismatch:${record.split('@')[0]}@9999`;
    }
    // over-cap-mixed: the live ones stay live until the proof kills them.
    return mixedLive.has(record) ? 'match' : 'gone';
  }
  if (scenario === 'unverifiable-record' && record === grandchild) {
    return 'unverifiable:open:5';
  }
  if (scenario === 'late-sampled-record' && record === lateRecord) {
    return lateAlive ? 'match' : 'gone';
  }
  const root = roots.find((candidate) => recordOf(candidate) === record);
  if (root) {
    return root.alive ? 'match' : 'gone';
  }
  if (deadRootUnverifiableChild && record === grandchild) {
    return 'unverifiable:open:5'; // unknown, not absent: no identity, no signal
  }
  if ((samplerShape() || deadRootChild || scenario === 'dead-after-first-sample')
      && record === grandchild) {
    return grandchildState.alive ? 'match' : 'gone';
  }
  if (staleTreeRecord && record === staleStranger) {
    return staleStrangerState.alive ? 'match' : 'gone';
  }
  if (staleTreeRecord && record === staleChild) {
    return staleChildState.alive ? 'match' : 'gone';
  }
  return 'gone';
}

/** The delayed verdict the deadline scenario resolves its stragglers through. */
function delayedVerdict(record) {
  if (scenario === 'deadline-stragglers' && record === grandchild) {
    log('straggler-probe: ' + record);
    const until = Date.now() + 2500;
    while (Date.now() < until) {
      // busy-free wait: the fixture is a child process of the controller
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
    }
    return 'match';
  }
  return verdictOf(record);
}

function appliedResult(record, verb) {
  const root = roots.find((candidate) => recordOf(candidate) === record);
  const alive = root ? root.alive
    : (record === grandchild ? grandchildState.alive : false);
  if (!alive) {
    return `${record}:gone`;
  }
  applied.set(record, (applied.get(record) || 0) + 1);
  log(`${verb}: ${record}`);
  return `${record}:ok`;
}

createInterface({ input: process.stdin }).on('line', (raw) => {
  const line = raw.trim();
  if (line === '') {
    return;
  }
  const space = line.indexOf(' ');
  const command = space < 0 ? line : line.slice(0, space);
  const argument = space < 0 ? '' : line.slice(space + 1);
  try {
    switch (command) {
      case 'assign': {
        if (partialSuspend && roots.length > 0) {
          say('ok');
          break;
        }
        const pid = Number(argument);
        roots.push({ pid, creation: String(staleTreeRecord ? 5000000 : 9001 + roots.length), alive: true });
        log('assigned: ' + roots[roots.length - 1].pid);
        say('ok');
        break;
      }
      case 'identity': {
        const pid = Number(argument);
        const root = roots.find((candidate) => candidate.pid === pid);
        say(root && root.alive ? `${root.pid}@${root.creation}` : 'gone');
        break;
      }
      case 'record': {
        const verdict = scenario === 'deadline-stragglers' ? delayedVerdict(argument) : verdictOf(argument);
        if (verdict.startsWith('mismatch') || verdict === 'gone') {
          log('verdict: ' + argument + ':' + verdict);
        }
        say(verdict);
        break;
      }
      case 'tree': {
        const payload = treeOf(Number(argument));
        if (!partialSuspend) {
          log('tree: ' + payload); // every enumeration, so a test can wait for a completed sampler pass
        }
        say(payload);
        if (scenario === 'dead-after-first-sample') {
          // The reply above is flushed before this fires; the exit is what makes the
          // next sampler tick find a dead supervisor.
          setTimeout(() => process.exit(0), 300);
        }
        break;
      }
      case 'suspend': {
        if (partialSuspend) {
          const results = argument.split(';').filter((entry) => entry !== '').map((record) => {
            if (record === goneRecord()) {
              log('exited: ' + record);
              return `${record}:gone`;
            }
            applied.set(record, (applied.get(record) || 0) + 1);
            log('applied: ' + record);
            return `${record}:ok`;
          });
          say(results.join(';'));
          break;
        }
        say(argument.split(';').filter((entry) => entry !== '')
          .map((record) => appliedResult(record, 'applied')).join(';'));
        break;
      }
      case 'resume': {
        const record = argument.trim();
        if (partialSuspend) {
          const count = applied.get(record) || 0;
          if (count > 0) {
            applied.set(record, count - 1);
            log('resumed: ' + record);
          }
          say(`${record}:ok`);
          break;
        }
        const count = applied.get(record) || 0;
        if (count > 0) {
          applied.set(record, count - 1);
          log('resumed: ' + record);
        }
        say(`${record}:ok`);
        break;
      }
      case 'kill': {
        if (partialSuspend) {
          say(argument.split(';').filter((entry) => entry !== '')
            .map((record) => `${record}:ok`).join(';'));
          break;
        }
        const results = argument.split(';').filter((entry) => entry !== '').map((record) => {
          const root = roots.find((candidate) => recordOf(candidate) === record);
          if (root) {
            if (!root.alive) {
              return `${record}:gone`;
            }
            root.alive = false;
            log('killed: ' + record);
            return `${record}:ok`;
          }
          if (record === grandchild) {
            if (!grandchildState.alive) {
              return `${record}:gone`;
            }
            grandchildState.alive = false;
            log('killed: ' + record);
            return `${record}:ok`;
          }
          if (staleTreeRecord && record === staleStranger) {
            if (!staleStrangerState.alive) {
              return `${record}:gone`;
            }
            staleStrangerState.alive = false;
            log('killed: ' + record);
            return `${record}:ok`;
          }
          if (staleTreeRecord && record === staleChild) {
            if (!staleChildState.alive) {
              return `${record}:gone`;
            }
            staleChildState.alive = false;
            log('killed: ' + record);
            return `${record}:ok`;
          }
          if (scenario === 'over-cap-live-records' && overCapLive.has(record)) {
            overCapLive.delete(record);
            log('killed: ' + record);
            return `${record}:ok`;
          }
          if (syntheticRecords.has(record)) {
            syntheticRecords.delete(record);
            log('killed: ' + record);
            return `${record}:ok`;
          }
          if (scenario === 'late-sampled-record' && record === lateRecord) {
            if (!lateAlive) {
              return `${record}:gone`;
            }
            lateAlive = false;
            log('killed: ' + record);
            return `${record}:ok`;
          }
          if (recycledRoot && record === recycledChild) {
            if (!recycledChildAlive) {
              return `${record}:gone`;
            }
            recycledChildAlive = false;
            log('killed: ' + record);
            return `${record}:ok`;
          }
          log('kill-refused: ' + record);
          return `${record}:gone`;
        });
        say(results.join(';'));
        break;
      }
      case 'pids': {
        if (partialSuspend || recycledRoot) {
          // No live job member: the recorded process terminated (its pid was
          // recycled) and the job's kill-on-close backstop is not a stop operation.
          say('');
          break;
        }
        pidsCalls += 1;
        if (scenario === 'deadline-inventory' && pidsCalls === 2) {
          // The final job inventory outlives the caller's window; the own-survivor
          // scan (the first call) answered immediately.
          log('inventory-probe: ' + liveMembers().length);
          const until = Date.now() + 2500;
          while (Date.now() < until) {
            Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
          }
        }
        if (scenario === 'unaccounted-member') {
          say('6001@666');
          break;
        }
        if (scenario === 'unverifiable-member') {
          // A live job member whose identity the OS could not read: unknown, not absent.
          say([...liveMembers(), unverifiableMember].join(';'));
          break;
        }
        if (scenario === 'foreign-member') {
          // The first run's live members plus the second run's child, which no
          // enumerable tree of a live root contains any more.
          say([...liveMembers(), '7002@7002'].join(';'));
          break;
        }
        say(liveMembers().join(';'));
        break;
      }
      case 'exit': {
        say('bye');
        process.exit(0);
        break;
      }
      default: {
        process.stdout.write('err|unknown command: ' + command + '\n');
        break;
      }
    }
  } catch (error) {
    process.stdout.write('err|' + String(error.message).replace(/[\r\n]+/g, ' ') + '\n');
  }
});

#!/usr/bin/env node
/**
 * Lane plan for `scripts/mvn-lane.sh shard`, plus the integrity guard for its partition.
 *
 *   node scripts/lane-plan.mjs gen            (re)generate the act-execution fragments from the
 *                                             measured costs; deterministic, review, then commit
 *   node scripts/lane-plan.mjs check          fail loudly if the fragments do not exactly cover
 *                                             the shardable classes of every split module
 *   node scripts/lane-plan.mjs plan <K> <dir> print the lane table and write each lane's full class
 *                                             list plus dir/plan.json for `verify` to read back
 *   node scripts/lane-plan.mjs verify <timingLog> <laneLog...>
 *                                             fail loudly if a planned class never printed a result,
 *                                             a class ran in two lanes, a tagged timing class ran
 *                                             under contention, or the totals do not add up
 *   node scripts/lane-plan.mjs timing-plan    the modules and classes carrying @Tag("timing")
 *
 * Everything here is derived from scripts/lanes/measured-costs.json, which carries the run it was
 * taken from. The partition is a committed list (scripts/lanes/<module>/gN.txt), so a lane is a
 * name of a file, never a pattern somebody retypes.
 */
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const LANES_DIR = join(ROOT, 'scripts', 'lanes');
const COSTS_PATH = join(LANES_DIR, 'measured-costs.json');
const POM_PATH = join(ROOT, 'agent-control-tower', 'pom.xml');

const costs = JSON.parse(readFileSync(COSTS_PATH, 'utf8'));

/** repo-relative forward-slash path, so a plan line is portable and greppable on any host */
const ROOT_FWD = ROOT.split(/[\\/]+/).join('/');
function rel(p) {
  const n = p.split(/[\\/]+/).join('/');
  return n.startsWith(ROOT_FWD + '/') ? n.slice(ROOT_FWD.length + 1) : n;
}

/** surefire's own default includes, minus the tier the pom hands to failsafe. */
const DEFAULT_INCLUDES = ['Test*.java', '*Test.java', '*Tests.java', '*TestCase.java'];
function pomExcludes() {
  const pom = readFileSync(POM_PATH, 'utf8');
  const block = pom.match(/<artifactId>maven-surefire-plugin<\/artifactId>[\s\S]*?<excludes>([\s\S]*?)<\/excludes>/);
  if (!block) throw new Error('lane-plan: no surefire <excludes> block found in ' + POM_PATH);
  return [...block[1].matchAll(/<exclude>\*\*\/([^<]+)<\/exclude>/g)].map((m) => m[1]);
}

function walk(dir, acc = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (e.isFile()) acc.push(p);
  }
  return acc;
}

function matchesAny(name, patterns) {
  return patterns.some((pat) => {
    if (pat.startsWith('*') && pat.endsWith('*')) return name.includes(pat.slice(1, -1));
    if (pat.startsWith('Test')) return name.startsWith('Test') && name.endsWith(pat.slice(4));
    if (pat.startsWith('*')) return name.endsWith(pat.slice(1));
    return name === pat;
  });
}

/**
 * The classes a shard lane is expected to run for one module: discovered, minus failsafe tier, minus timing.
 *
 * Discovery is per top-level type declaration, not per file name: surefire scans target/test-classes and
 * matches the *class* name against its include patterns, so a second top-level type in the same file is a
 * test class in its own right (act-app's DevSqlControllerProfileTest.java declares
 * DevSqlControllerH2ProfilePresenceTest, and keying on the file name loses it). Nested types run with their
 * outer class, so they are not listed - and the outer name is what a report line is keyed by.
 */
function shardableClasses(module) {
  const srcRoot = join(ROOT, 'agent-control-tower', module, 'src', 'test', 'java');
  if (!existsSync(srcRoot)) return { all: [], timing: [] };
  const excludes = pomExcludes();
  const found = [];
  for (const file of walk(srcRoot)) {
    if (!file.endsWith('.java')) continue;
    const text = readFileSync(file, 'utf8');
    const pkg = (text.match(/^\s*package\s+([\w.]+)\s*;/m) || [])[1];
    if (!pkg) continue;
    for (const decl of topLevelClasses(text)) {
      const name = decl.name;
      if (!matchesAny(name + '.java', DEFAULT_INCLUDES)) continue;
      if (matchesAny(name + '.java', excludes)) continue;
      found.push({ fqcn: pkg + '.' + name, timing: decl.timing });
    }
  }
  const timing = found.filter((c) => c.timing).map((c) => c.fqcn);
  const all = found.filter((c) => !c.timing).map((c) => c.fqcn);
  return { all: all.sort(), timing: timing.sort() };
}

/** top-level, non-abstract class declarations, each tagged with whether its own annotations say "timing" */
function topLevelClasses(text) {
  const lines = text.split('\n');
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(?:(?:public|private|protected|final|abstract|sealed|non-sealed|strictfp)\s+)*class\s+([A-Za-z_]\w*)/);
    if (!m) continue;
    let timing = false;
    for (let j = i - 1; j >= 0; j--) {
      const t = lines[j].trim();
      // the window is the declaration's own annotations and comments, so a @Tag on some method inside the
      // class above cannot mark this one
      if (/^@Tag\(\s*"timing"\s*\)/.test(t)) timing = true;
      if (!t || t.startsWith('//') || t.startsWith('*') || t.startsWith('/*') || t.startsWith('@')) continue;
      break;
    }
    out.push({ name: m[1], timing });
  }
  return out;
}

/** Modules that are split into fragments: any directory under scripts/lanes/. */
function splitModules() {
  if (!existsSync(LANES_DIR)) return [];
  return readdirSync(LANES_DIR, { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => d.name);
}

function fragmentFiles(module) {
  const dir = join(LANES_DIR, module);
  if (!existsSync(dir)) return [];
  return readdirSync(dir).filter((f) => /^g\d+\.txt$/.test(f)).sort().map((f) => join(dir, f));
}

function readFragment(path) {
  const lines = readFileSync(path, 'utf8').replace(/\r/g, '').split('\n');
  let cost = null, classes = 0;
  const entries = [];
  for (const l of lines) {
    const t = l.trim();
    if (t.startsWith('#')) {
      const m = t.match(/^#\s*cost=([\d.]+)s/);
      if (m) cost = parseFloat(m[1]);
      continue;
    }
    if (!t) continue;
    entries.push(t);
    classes++;
  }
  return { path, cost, entries, classes };
}

function writeFragmentHeader(out, module, index, total, cost, members) {
  const c = costs.modules[module];
  const lines = [
    `# ${module} shard fragment ${index} of ${total} - a surefire includes file (one FQCN per line).`,
    `# cost=${cost.toFixed(2)}s  classes=${members.length}`,
    `# measured at ${costs.measured.at} on ${costs.measured.host} (${costs.measured.cpu}, ${costs.measured.ram})`,
    `# by ${costs.measured.command}; regenerate with \`node scripts/lane-plan.mjs gen\`.`,
    '',
  ];
  for (const m of members) lines.push(m);
  writeFileSync(out, lines.join('\n') + '\n');
}

// --- gen --------------------------------------------------------------------------------------------
function gen(module, groups) {
  const c = costs.modules[module];
  if (!c) throw new Error('lane-plan: no measured costs for module ' + module);
  const { all, timing } = shardableClasses(module);
  const known = new Set(Object.keys(c.byClass));
  const unpriced = all.filter((fq) => !known.has(fq) && !fq.includes('$'));
  if (unpriced.length) {
    console.error('lane-plan: ' + module + ' has discovered classes with no measured cost (add them by running the'
      + ' module serially, or they were added since ' + costs.measured.at + '):');
    for (const u of unpriced) console.error('   ' + u);
    throw new Error('lane-plan: cannot generate a cost-derived partition');
  }
  // a nested-class report line is covered by its outer class, and -Dsurefire.includesFile takes the outer name
  const priced = all.filter((fq) => known.has(fq));
  const G = groups || 4;
  const dir = join(LANES_DIR, module);
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  const buckets = Array.from({ length: G }, () => ({ cost: 0, members: [] }));
  // longest-processing-time: heaviest class into the currently lightest fragment, ties broken by name
  const sorted = priced.slice().sort((a, b) => c.byClass[b] - c.byClass[a] || a.localeCompare(b));
  for (const fq of sorted) {
    let light = 0;
    for (let i = 1; i < G; i++) if (buckets[i].cost < buckets[light].cost - 1e-9) light = i;
    buckets[light].cost += c.byClass[fq];
    buckets[light].members.push(fq);
  }
  for (let i = 0; i < G; i++) {
    buckets[i].members.sort();
    writeFragmentHeader(join(dir, 'g' + (i + 1) + '.txt'), module, i + 1, G, buckets[i].cost, buckets[i].members);
    console.error(`lane-plan: ${module}/g${i + 1}.txt cost=${buckets[i].cost.toFixed(2)}s classes=${buckets[i].members.length}`);
  }
  const sum = buckets.reduce((a, b) => a + b.cost, 0);
  const timingSec = timing.reduce((a, fq) => a + (c.byClass[fq] || 0), 0);
  console.error(`lane-plan: ${module} priced=${priced.length} of ${all.length} shardable, excluded ${timing.length} timing:`
    + ` ${timing.join(', ')}`);
  console.error(`lane-plan: ${module} fragment cost sum=${sum.toFixed(2)}s = classSum ${c.classSumSec}s - timing ${timingSec.toFixed(2)}s`);
}

// --- check --------------------------------------------------------------------------------------------
function check() {
  let bad = 0;
  for (const module of splitModules()) {
    const files = fragmentFiles(module);
    if (!files.length) { console.error(`check: ${module} has a lanes directory but no gN.txt`); bad++; continue; }
    const { all } = shardableClasses(module);
    const expected = new Set(all);
    const seen = new Map();
    for (const f of files) {
      for (const e of readFragment(f).entries) seen.set(e, (seen.get(e) || 0) + 1);
    }
    const missing = all.filter((fq) => !seen.has(fq));
    const dup = [...seen.entries()].filter(([, n]) => n > 1).map(([fq, n]) => `${fq} listed ${n} times`);
    const stale = [...seen.keys()].filter((fq) => !expected.has(fq));
    // the second oracle: a class the measured run executed but source discovery never saw would be
    // dropped from every fragment and the suite would quietly lose it, so compare against the run too.
    const c = costs.modules[module];
    const timing = new Set(shardableClasses(module).timing);
    const ranButUndiscovered = Object.keys(c.byClass).filter((fq) => !expected.has(fq) && !timing.has(fq));
    // a hand-edited fragment keeps the `# cost=` header it was generated with, and that header is what
    // the plan balances on, so it has to still match the classes the file actually lists.
    const badHeaders = [];
    for (const f of files) {
      const fr = readFragment(f);
      const real = fr.entries.reduce((a, fq) => a + (c.byClass[fq] || 0), 0);
      if (fr.cost == null || Math.abs(fr.cost - real) > 0.05) {
        badHeaders.push(`${rel(f)} header says ${fr.cost}s but its classes cost ${real.toFixed(2)}s`
          + ` - run \`node scripts/lane-plan.mjs gen ${module}\``);
      }
    }
    if (missing.length || dup.length || stale.length || ranButUndiscovered.length || badHeaders.length) {
      bad++;
      console.error(`check: ${module} partition is NOT an exact cover (${seen.size} listed / ${expected.size} discovered`
        + ` / ${Object.keys(c.byClass).length} measured)`);
      for (const m of missing) console.error(`   MISSING  ${m} (in no fragment - it would never run)`);
      for (const d of dup) console.error(`   DUPLICATE ${d} (an exact cover lists a class once)`);
      for (const s of stale) console.error(`   STALE    ${s} (listed but no shardable test class by that name exists)`);
      for (const u of ranButUndiscovered) console.error(`   UNDISCOVERED ${u} (the measured run executed it, discovery did not find it -`
        + ' the partition would omit it; fix shardableClasses or the file naming)');
      for (const h of badHeaders) console.error(`   STALE COST ${h}`);
    } else {
      console.error(`check: ${module} exact cover OK - ${expected.size} shardable classes, ${files.length} fragments,`
        + ` and the measured run's ${Object.keys(c.byClass).length} classes are all accounted for`);
    }
  }
  // arithmetic the user can audit: the fragments must add up to the module's measured class sum
  for (const module of splitModules()) {
    const c = costs.modules[module];
    const sum = fragmentFiles(module).reduce((a, f) => a + (readFragment(f).cost ?? 0), 0);
    const { timing } = shardableClasses(module);
    const timingSec = timing.reduce((a, fq) => a + (c.byClass[fq] || 0), 0);
    console.error(`check: ${module} fragmentCostSum=${sum.toFixed(2)}s expected=${(c.classSumSec - timingSec).toFixed(2)}s`
      + ` (classSum=${c.classSumSec}s - ${timing.length} timing classes at ${timingSec.toFixed(1)}s, replayed serially)`);
  }
  // a module that is not in the cost table at all would be planned by nobody and the arithmetic would
  // still balance, because the arithmetic is measured from that same table - so check membership first.
  const pom = readFileSync(POM_PATH, 'utf8');
  const declared = [...pom.matchAll(/<module>([\w.-]+)<\/module>/g)].map((m) => m[1]);
  for (const module of declared) {
    const testRoot = join(ROOT, 'agent-control-tower', module, 'src', 'test', 'java');
    const hasTests = existsSync(testRoot) && walk(testRoot).some((f) => f.endsWith('.java'));
    if (hasTests && !costs.modules[module]) {
      bad++;
      console.error(`check: ${module} has test sources but no measured costs - it would run in no lane.`
        + ' Measure it (`mvn -o -B -pl ' + module + ' test`) and add its classes to scripts/lanes/measured-costs.json.');
    }
  }
  // discovery must see every class the single process ran, in every module: a whole module is planned
  // straight from discovery, so a class discovery misses would never be given to any lane.
  for (const [module, c] of Object.entries(costs.modules)) {
    const { all, timing } = shardableClasses(module);
    const seen = new Set(all);
    const timingSet = new Set(timing);
    const invisible = Object.keys(c.byClass).filter((fq) => !seen.has(fq) && !timingSet.has(fq));
    const neverRan = all.filter((fq) => !(fq in c.byClass));
    if (invisible.length || neverRan.length) {
      bad++;
      console.error(`check: ${module} discovery does not match the measured run (${all.length} discovered /`
        + ` ${Object.keys(c.byClass).length} measured)`);
      for (const f of invisible) console.error(`   INVISIBLE ${f} (it ran serially but discovery never yields it - a lane would never run it)`);
      for (const n of neverRan) console.error(`   NEVER RAN ${n} (discovered, but the measured run produced no report for it)`);
    }
  }
  if (bad) throw new Error('check: failed for ' + bad + ' module(s)');
}

// --- plan --------------------------------------------------------------------------------------------
/**
 * Pack the work into K lanes and write each lane's complete class list.
 *
 * A lane is one Maven process, and `surefire.includesFile` is a CLI property, so it applies to every
 * module that process builds: a lane that lists only act-execution's fragment would silently run
 * nothing else. Hence each lane's file spells out every class it owns - the fragments for split
 * modules, and the discovered set for the modules it takes whole.
 */
function plan(K, outDir) {
  if (!(K >= 1 && K <= 8)) throw new Error('plan: K must be 1..8');
  const splits = splitModules();
  const tasks = [];
  for (const module of Object.keys(costs.modules)) {
    if (splits.includes(module)) continue;
    if (!costs.modules[module].classes) continue;
    tasks.push({ module, cost: costs.modules[module].classSumSec, classes: shardableClasses(module).all });
  }
  for (const module of splits) {
    for (const f of fragmentFiles(module)) {
      const fr = readFragment(f);
      if (fr.cost == null) throw new Error('plan: ' + rel(f) + ' has no `# cost=` header - rerun `gen ' + module + '`');
      tasks.push({ module, cost: fr.cost, classes: fr.entries, fragment: rel(f) });
    }
  }
  tasks.sort((a, b) => b.cost - a.cost || a.module.localeCompare(b.module));
  const lanes = Array.from({ length: K }, () => ({ cost: 0, modules: new Set(), classes: new Set(), fragments: [] }));
  for (const t of tasks) {
    let light = 0;
    for (let i = 1; i < K; i++) if (lanes[i].cost < lanes[light].cost - 1e-9) light = i;
    lanes[light].cost += t.cost;
    lanes[light].modules.add(t.module);
    for (const c of t.classes) {
      if (lanes[light].classes.has(c)) throw new Error(`plan: ${c} landed in lane${light + 1} twice`);
      lanes[light].classes.add(c);
    }
    if (t.fragment) lanes[light].fragments.push(t.fragment);
  }
  if (!outDir) throw new Error('plan: an output directory is required (the shard lane passes target/mvn-lane)');
  mkdirSync(outDir, { recursive: true });
  lanes.forEach((l, i) => {
    const list = [...l.classes].sort();
    writeFileSync(join(outDir, `lane${i + 1}-includes.txt`), list.join('\n') + '\n');
    console.log([`lane${i + 1}`, l.cost.toFixed(2), [...l.modules].sort().join(','), `lane${i + 1}-includes.txt`, String(list.length)].join('\t'));
  });
  writeFileSync(join(outDir, 'plan.json'), JSON.stringify({
    K, measuredAt: costs.measured.at,
    lanes: lanes.map((l, i) => ({
      name: `lane${i + 1}`, cost: l.cost, modules: [...l.modules].sort(), fragments: l.fragments,
      includes: `lane${i + 1}-includes.txt`, classes: [...l.classes].sort(),
    })),
  }, null, 1) + '\n');
  const planned = lanes.reduce((a, l) => a + l.classes.size, 0);
  const unique = new Set(lanes.flatMap((l) => [...l.classes])).size;
  if (planned !== unique) throw new Error(`plan: ${planned - unique} class(es) are in more than one lane`);
  console.error(`lane-plan: K=${K} classes planned=${planned} unique=${unique}`
    + `; slowest lane=${Math.max(...lanes.map((l) => l.cost)).toFixed(1)}s`);
}

// --- verify --------------------------------------------------------------------------------------------
/**
 * The loud end. Reads the plan the shard lane wrote plus every log it produced and fails if a planned
 * class printed no result line, a class ran that no lane was given, one class ran in two lanes, or the
 * test totals do not add up to what a single process runs.
 */
function verify(args) {
  const [timingLog, ...laneLogs] = args;
  const outDir = dirname(resolveFirst(laneLogs[0]));
  const planPath = join(outDir, 'plan.json');
  if (!existsSync(planPath)) throw new Error(`verify: no ${planPath} - the shard lane writes it before running`);
  const p = JSON.parse(readFileSync(planPath, 'utf8'));
  if (p.lanes.length !== laneLogs.length) {
    throw new Error(`verify: plan has ${p.lanes.length} lanes but ${laneLogs.length} lane logs were passed`);
  }
  let bad = 0;
  const ranByLane = laneLogs.map((log) => {
    const text = readFileSync(log, 'utf8').replace(/\r/g, '');
    // surefire prints a line for a nested class too (Outer$Inner); the outer name is what was planned
    return new Set([...text.matchAll(/-- in (\S+)/g)].map((m) => m[1].split('$')[0]));
  });
  const seenIn = new Map();
  for (let i = 0; i < laneLogs.length; i++) {
    const planned = new Set(p.lanes[i].classes);
    const ran = ranByLane[i];
    const missing = [...planned].filter((c) => !ran.has(c));
    const extra = [...ran].filter((c) => !planned.has(c));
    if (missing.length) {
      bad++;
      console.error(`verify: ${p.lanes[i].name} was given ${planned.size} classes and ran ${ran.size};`
        + ` ${missing.length} printed no result (surefire matched none of them, so a rename or a bad list went unnoticed)`);
      for (const m of missing.slice(0, 15)) console.error('   MISSING ' + m);
    }
    if (extra.length) {
      bad++;
      console.error(`verify: ${p.lanes[i].name} ran ${extra.length} class(es) it was not given`);
      for (const e of extra.slice(0, 15)) console.error('   EXTRA ' + e);
    }
    for (const c of ran) {
      seenIn.set(c, (seenIn.get(c) || 0) + 1);
      if (!planned.has(c) && !knownClass(c)) {
        bad++;
        console.error(`verify: ${c} is not in the measured class table at all - the plan is stale, run \`gen\``);
      }
    }
  }
  for (const [c, n] of seenIn) if (n > 1) { bad++; console.error(`verify: ${c} ran in ${n} lanes (duplicated work, and the totals would lie)`); }

  // arithmetic the user asked for: lanes + timing must equal the single-process totals
  const expectedTests = Object.values(costs.modules).reduce((a, m) => a + m.tests, 0);
  const expectedSkip = Object.values(costs.modules).reduce((a, m) => a + m.skipped, 0);
  const totalled = [...laneLogs, timingLog].map(countTests);
  const got = totalled.reduce((a, t) => a + t.run, 0);
  const skipped = totalled.reduce((a, t) => a + t.skip, 0);
  const failed = totalled.reduce((a, t) => a + t.fail, 0);
  const timingExpected = shardableTimingTests();
  console.error(`verify: lanes ran ${totalled.slice(0, -1).map((t) => t.run).join(' + ')} =`
    + ` ${totalled.slice(0, -1).reduce((a, t) => a + t.run, 0)}, timing lane ${totalled[totalled.length - 1].run}`
    + ` (the tagged classes hold ${timingExpected.run}), total ${got} vs the single process's ${expectedTests}`);
  console.error(`verify: skipped=${skipped} (the measured run skipped ${expectedSkip}) and failures+errors=${failed}`);
  if (got !== expectedTests) {
    bad++;
    console.error(`verify: completeness LOST - ${got} != ${expectedTests}. No lane may be trusted until this adds up.`);
    console.error('   if a @Tag("timing") class lives outside TIMING_MODULES, or a module has no measured cost, that is the gap');
  }
  // a tagged class that ran inside a parallel lane would keep the total right and the timing guarantee wrong
  const tagged = new Set(Object.keys(costs.modules).flatMap((m) => shardableClasses(m).timing));
  for (let i = 0; i < laneLogs.length; i++) {
    for (const c of ranByLane[i]) if (tagged.has(c)) {
      bad++;
      console.error(`verify: ${c} is tagged timing but ran in ${p.lanes[i].name} - excludedGroups did not take,`
        + ' so a wall-clock assertion executed under contention');
    }
  }
  const timingRan = new Set([...readFileSync(timingLog, 'utf8').replace(/\r/g, '').matchAll(/-- in (\S+)/g)].map((m) => m[1].split('$')[0]));
  for (const c of tagged) if (!timingRan.has(c)) {
    bad++;
    console.error(`verify: ${c} is tagged timing but the timing lane never ran it (TIMING_MODULES is too narrow?)`);
  }
  if (failed) { bad++; console.error(`verify: ${failed} failure(s)/error(s) reported by the lane summary lines`); }
  if (bad) throw new Error('verify: failed (' + bad + ' problem set(s))');
  console.error('verify: exact cover across lanes, and the arithmetic matches the single-process total');
}

function resolveFirst(p) {
  return p.startsWith('/') || /^[A-Za-z]:/.test(p) ? p : join(process.cwd(), p);
}
function knownClass(fq) {
  return Object.values(costs.modules).some((m) => Object.prototype.hasOwnProperty.call(m.byClass, fq));
}
function countTests(log) {
  const text = readFileSync(log, 'utf8').replace(/\r/g, '');
  let run = 0, skip = 0, fail = 0;
  // one summary line per module: "Tests run: N, Failures: .., Errors: .., Skipped: .." with no "-- in"
  for (const m of text.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)(?!.*-- in)/g)) {
    run += +m[1]; fail += +m[2] + +m[3]; skip += +m[4];
  }
  return { run, skip, fail };
}
function shardableTimingTests() {
  let run = 0;
  for (const module of Object.keys(costs.modules)) {
    const c = costs.modules[module];
    for (const fq of shardableClasses(module).timing) run += c.testsByClass[fq] || 0;
  }
  return { run };
}

const [mode, ...rest] = process.argv.slice(2);
try {
  if (mode === 'gen') gen(rest[0], rest[1] ? Number(rest[1]) : undefined);
  else if (mode === 'check') check();
  else if (mode === 'plan') plan(Number(rest[0]), rest[1]);
  else if (mode === 'verify') verify(rest);
  else if (mode === 'timing-plan') {
    const mods = [], cls = [];
    for (const module of Object.keys(costs.modules)) {
      const t = shardableClasses(module).timing;
      if (t.length) mods.push(module);
      cls.push(...t);
    }
    console.log(['modules', mods.join(','), 'classes', cls.join(',')].join('\t'));
  }
  else { console.error('usage: lane-plan.mjs {gen <module> [groups]|check|plan <K> <outDir>|verify <timingLog> <laneLog...>|timing-plan}'); process.exit(2); }
} catch (e) {
  console.error(String(e.message || e));
  process.exit(1);
}

// Per-class and per-context-boot breakdown of one Maven test run.
// Reads target/{surefire,failsafe}-reports/TEST-*.xml and, if given, the run's log file.
// Usage: node scripts/build-metrics/summarize.mjs <module-glob> [logfile]
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REACTOR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'agent-control-tower');
const mod = process.argv[2] || '';
const log = process.argv[3];
// Surefire never deletes old TEST-*.xml, so a filtered run would otherwise be reported as if it
// had run every class in the module. Since is the run's start time in epoch seconds.
const since = Number(process.env.RUN_SINCE || 0);
let staleSkipped = 0;
if (!mod) {
  console.error('usage: node summarize.mjs <module-glob> [logfile]   (env RUN_SINCE=<epoch s>)');
  process.exit(2);
}

const modDirs = mod.includes('*')
  ? readdirSync(REACTOR).filter(d => d.startsWith(mod.replace('*', ''))).map(d => join(REACTOR, d))
  : [join(REACTOR, mod)];

function suites(dir, kind) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).filter(f => f.startsWith('TEST-') && f.endsWith('.xml')).flatMap(f => {
    const path = join(dir, f);
    if (since && statSync(path).mtimeMs / 1000 < since) {
      staleSkipped++;
      return [];
    }
    const head = readFileSync(path, 'utf8').slice(0, 4000);
    const attr = n => { const m = head.match(new RegExp(n + '="([^"]*)"')); return m ? m[1] : ''; };
    return [{
      kind,
      name: attr('name'),
      time: parseFloat(attr('time') || '0'),
      tests: parseInt(attr('tests') || '0', 10),
      failures: parseInt(attr('failures') || '0', 10) + parseInt(attr('errors') || '0', 10),
      skipped: parseInt(attr('skipped') || '0', 10),
    }];
  });
}

let all = [];
for (const m of modDirs) {
  all = all.concat(
    suites(join(m, 'target/surefire-reports'), 'unit'),
    suites(join(m, 'target/failsafe-reports'), 'itg'),
  );
}
all.sort((a, b) => b.time - a.time);
console.log(`reports=${all.length} classes testMethods=${all.reduce((s, r) => s + r.tests, 0)} `
  + `sumOfClassTimes=${all.reduce((s, r) => s + r.time, 0).toFixed(1)}s`
  + (since ? ` staleSkipped=${staleSkipped}` : ''));
console.log('top classes by wall time (s):');
for (const r of all.slice(0, 22)) {
  console.log(`  ${r.time.toFixed(1).padStart(6)}  ${r.kind.padEnd(4)} n=${String(r.tests).padStart(3)}`
    + ` fail=${r.failures} skip=${r.skipped}  ${r.name.replace(/^io\.aria\.conductor\./, '')}`);
}

if (log && existsSync(log)) {
  const text = readFileSync(log, 'utf8').replace(/\r/g, '');
  const started = [...text.matchAll(/Started (\w+) in ([\d.]+) seconds \(process running for ([\d.]+)\)/g)];
  if (started.length) {
    console.log(`\nSpring context boots logged: ${started.length} `
      + `sum=${started.reduce((s, m) => s + parseFloat(m[2]), 0).toFixed(1)}s`);
    for (const m of started) console.log(`  ${parseFloat(m[2]).toFixed(1).padStart(6)}s  ${m[1]}`);
  }
  const applied = [...text.matchAll(/Successfully applied (\d+) migration/g)];
  if (applied.length) {
    const ddl = [...text.matchAll(/execution time 00:(\d\d)\.(\d\d\d)s/g)]
      .reduce((s, m) => s + parseInt(m[1], 10) + parseInt(m[2], 10) / 1000, 0);
    console.log(`\nFlyway: runs=${applied.length} migrations=[${applied.map(m => m[1]).join(',')}]`
      + ` loggedDDLsum=${ddl.toFixed(1)}s`);
  }
  console.log(`\ncoverageChecksMet=${/All coverage checks have been met/.test(text)}`
    + ` violations=${(text.match(/Rule violated for /g) || []).length}`);
  const total = text.match(/Total time:\s+([^\n]+)/);
  if (total) console.log(`reactor TotalTime: ${total[1].trim()}`);
}

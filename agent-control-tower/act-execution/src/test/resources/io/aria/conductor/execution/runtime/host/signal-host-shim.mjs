#!/usr/bin/env node
// Windows signal-host shim for the POSIX controller's vanishing-root test.
//
// The POSIX controller invokes its configured signal host exactly like /bin/sh:
//   <shim> -c 'kill -s <SIG> "$0" || kill-<SIG> "$0"' <pid>
// This shim implements the same contract for a win32 host: before it delivers
// the requested signal it kills the run's recorded root exactly once (the
// deterministic version of "the root dies while the sweep is in flight"), then
// delivers the requested signal for real with taskkill. SIGSTOP/SIGCONT have no
// Windows equivalent and are accepted as no-ops: the production logic under test
// is the identity-validated enumeration and the proof decision, not the signal
// delivery (the Linux integration test covers the real /bin/sh path).
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';

const hereIndex = process.argv.indexOf('--here');
if (hereIndex < 0) {
  process.stderr.write('signal-host shim requires --here <directory>\n');
  process.exit(2);
}
const here = process.argv[hereIndex + 1];
const argv = process.argv.slice(2);
// The controller's script text arrives as one argument ("kill -s KILL \"$0\" || ...").
const script = argv.find((entry) => / -s /.test(entry)) || '';
const match = / -s (KILL|STOP|CONT)\b/.exec(script);
if (!match) {
  process.stderr.write('signal-host shim could not read the signal from: ' + JSON.stringify(argv) + '\n');
  process.exit(3);
}
const signal = match[1];
const pid = Number([...argv].reverse().find((entry) => /^\d+$/.test(entry)));

const marker = join(here, 'injected');
if (!existsSync(marker)) {
  writeFileSync(marker, 'injected');
  const rootPid = Number(readFileSync(join(here, 'root.pid'), 'utf8').trim());
  spawnSync('taskkill', ['/F', '/PID', String(rootPid)], { stdio: 'ignore' });
}

if (signal === 'KILL' && Number.isInteger(pid)) {
  spawnSync('taskkill', ['/F', '/PID', String(pid)], { stdio: 'ignore' });
}
process.exit(0);

#!/usr/bin/env node
// Deterministic fake of the Task 9 Windows host-control supervisor line protocol
// (assign/identity/record/tree/suspend/resume/kill/pids/exit), used by
// HostExecutionBackendIntegrationTest to exercise the pause/resume ledger window
// without a race: the scripted record "<root+2>@1002" is reported by the tree
// enumeration and then answers "gone" from the suspend batch -- exactly the
// "a record exits between the enumeration and the batch" trigger -- while every
// other record reports "ok". No real process is suspended or killed here; the
// controller's Java logic (the ledger it keeps and the resume it sends) is what
// the test observes, through the log this fixture writes.
//
// Usage: node fake-host-supervisor.mjs --log <path>
//   "applied: <record>" is appended for every suspension this fake applies and
//   "resumed: <record>" for every resume it receives, so the JUnit assertion can
//   require that a verified resume covered every applied suspension.
import { appendFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

const args = process.argv.slice(2);
const logIndex = args.indexOf('--log');
const logPath = logIndex >= 0 ? args[logIndex + 1] : null;
const log = (line) => {
  if (logPath) {
    appendFileSync(logPath, line + '\n');
  }
};
const say = (payload) => {
  process.stdout.write('ok|' + payload + '\n');
};

let rootPid = null;
let rootCreation = null;
const applied = new Map();

const goneRecord = () => `${rootPid + 2}@1002`;
const rootRecord = () => `${rootPid}@${rootCreation}`;
const tree = () => `${rootPid + 1}@1001@2;${goneRecord()}@1;${rootPid}@${rootCreation}@0`;

// The ready line the Supervisor constructor waits for: "ok|<self identity>".
say(`${process.pid}@${Date.now()}`);

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
        if (rootPid === null) {
          rootPid = Number(argument);
          rootCreation = '9001';
        }
        say('ok');
        break;
      }
      case 'identity': {
        const pid = Number(argument);
        say(pid === rootPid ? `${pid}@${rootCreation}` : 'gone');
        break;
      }
      case 'record': {
        say(argument === rootRecord() ? 'match' : 'gone');
        break;
      }
      case 'tree': {
        say(tree());
        break;
      }
      case 'suspend': {
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
      case 'resume': {
        const record = argument.trim();
        const count = applied.get(record) || 0;
        if (count > 0) {
          applied.set(record, count - 1);
          log('resumed: ' + record);
        }
        say(`${record}:ok`);
        break;
      }
      case 'kill': {
        say(argument.split(';').filter((entry) => entry !== '').map((record) => `${record}:ok`).join(';'));
        break;
      }
      case 'pids': {
        say('');
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

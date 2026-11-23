# Live operations (Aria × Qwen realtime bridge)

Deploy, check and recover the phone's live loop on the LAN stack. The contract is
[docs/superpowers/specs/2026-11-23-aria-qwen-live-bridge-design.md](superpowers/specs/2026-11-23-aria-qwen-live-bridge-design.md);
this page is the operator half — the exact commands and what the phone should show.

Path: `ws://<LAN host>:8080/api/v1/aria/live`, subprotocol `aria-live.v1`, operator bearer only.
The backend is the same one the dashboard and the SSE chat use; the live path is one endpoint on it.

## What runs where

| Piece | Where | Notes |
|---|---|---|
| `LiveWebSocketHandler` (act-aria) | backend JVM, port 8080 | auth, limits, frame validation, tool execution, lazy live run |
| `qwen-live-bridge` (Node) | one child **per socket** | spawned on `hello`, killed when the socket closes or the backend stops |

Bridge children are **spawn-per-socket**: the backend holds no pool and no child survives its
socket. A reconnect is a new `node packages/qwen-live-bridge/dist/index.js` with a fresh DashScope
session; context is reseeded from the Aria summary, because Qwen has no resumption handles.

## Configuration

`application.yml` defaults (all off / empty):

```yaml
aria.live.enabled: false
aria.live.bridge-script: ""            # empty → packages/qwen-live-bridge/dist/index.js
aria.live.credentials-file: ""         # empty → ~/.aria-conductor/qwen-live.env
aria.live.ready-deadline-ms: 5000
aria.live.max-session-seconds: 7200
aria.live.realtime-url: ""             # empty → the child derives the SG WSS endpoint
```

A deployment turns it on from the gitignored `.env` (the start scripts export every `.env` key):

```
ARIA_LIVE_ENABLED=true
ARIA_LIVE_BRIDGE_SCRIPT=/Users/happy/Documents/project/aria-conductor/packages/qwen-live-bridge/dist/index.js
ARIA_LIVE_CREDENTIALS_FILE=/Users/happy/.aria-conductor/qwen-live.env
```

`ARIA_LIVE_REALTIME_URL` is only for overriding the endpoint (regional failover, a proxy); it is
not a secret. Keep the operator bearer, LLM keys and container settings in `.env` untouched — the
DeepSeek provider is unrelated to the live bridge.

### Credentials file (0600, never printed, never committed)

The child reads only `DASHSCOPE_API_KEY` and `DASHSCOPE_WORKSPACE` from its environment; Java reads
them from this file at spawn time and passes nothing else.

```bash
umask 077
{
  printf 'DASHSCOPE_API_KEY=%s\n' "$(tr -d '\r\n' < ~/.config/live-spike/key)"
  printf 'DASHSCOPE_WORKSPACE=%s\n' "$(tr -d '\r\n' < ~/.config/live-spike/workspace)"
} > ~/.aria-conductor/qwen-live.env
chmod 600 ~/.aria-conductor/qwen-live.env
# verify metadata only — never cat the file
ls -l ~/.aria-conductor/qwen-live.env
awk -F= '{print $1" len="length($2)}' ~/.aria-conductor/qwen-live.env
```

Expected: mode `-rw-------`, two lines, both lengths non-zero.

## Build and deploy

```bash
cd /Users/happy/Documents/project/aria-conductor
(cd packages/qwen-live-bridge && pnpm install && pnpm test && pnpm build)
```

`dist/` is gitignored, so every deploy — and every merge that touches `packages/qwen-live-bridge/src`
— needs the `pnpm build` above before the backend is restarted.

Restart the backend (this is the phone's stack; the endpoint is served by the same JVM):

```bash
# 1. stop the running backend (the script wrapper and the app JVM both go away)
lsof -nP -iTCP:8080 -sTCP:LISTEN
kill -TERM <app pid>            # graceful; the port frees before the JVM exits
for i in $(seq 1 60); do lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null || break; sleep 1; done
ps -p <app pid> >/dev/null && echo "still draining — wait for it to exit"

# 2. start it with the .env live keys already in place
nohup bash scripts/start-backend.sh > ~/.aria-conductor/prod-backend-live.log 2>&1 &
echo $! > ~/.aria-conductor/prod-backend-live.pid
```

`scripts/start-backend.sh` loads `.env`, installs the reactor and runs
`mvn spring-boot:run -pl act-app`; the live keys reach Spring as `ARIA_LIVE_*`. Stopping the whole
stack is `pwsh -NoProfile -File scripts/stop.ps1` (or the same `kill -TERM` on the app JVM).

Health on both the loopback and the LAN address — 200 on both is the deploy gate:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' http://192.168.0.112:8080/actuator/health
```

## Smoke — `hello` / `ready` (X1, the 1 s DoD)

Run from the Mac or from the phone's side of the LAN. The bearer comes from the file, never the shell
history:

```bash
export ARIA_OPERATOR_BEARER_TOKEN="$(tr -d '\r\n' < ~/.aria-conductor/phone-dev-operator-token)"
node packages/qwen-live-bridge/scripts/smoke.mjs --host 192.168.0.112 --port 8080
```

Expected, within one second, exit 0:

```
ready v=1 maxSessionSec=7200
```

A `401`/`403` upgrade means the bearer is wrong (operator vs worker); `426` means the URL was fetched
as plain HTTP. `smoke: no ready within 1000 ms` means the endpoint is up but the session did not
configure — check the backend log for the bridge stderr lines.

## Real provider probe — one text turn

The only check that proves the provider accepts the session config the bridge declares
(`turn_detection: {type: "semantic_vad_ec"}`, `voice: Kiki`, ASR `qwen3-asr-flash-realtime`). It sends
one `{"type":"text","v":1,…}` after `ready` and prints metadata only — never the text, audio, ids or
the key.

```bash
export ARIA_OPERATOR_BEARER_TOKEN="$(tr -d '\r\n' < ~/.aria-conductor/phone-dev-operator-token)"
node packages/qwen-live-bridge/scripts/smoke.mjs --host 192.168.0.112 --port 8080 --turn "而家幾點？"
```

Expected shape (a tool-using turn; a pure chat turn shows only the `output_transcript` lines), exit 0:

```
ready v=1 maxSessionSec=7200
event type=run_event
event type=tool_status
event type=tool_status
event type=run_event
event type=output_transcript seq=1 final=false
…
event type=output_transcript seq=6 final=true
```

- Exit 0 — the final `output_transcript` arrived (the phone-visible end of a turn). The bridge's
  internal `turn_done` line is consumed by Java and never crosses to the socket; a turn that called a
  tool also shows `tool_status` and the run's `run_event`.
- Exit 2 — an `error` crossed the socket; the line carries `code=<closed set> retryable=<bool>
  messageClass=<word>` and nothing else. `code=capability_mismatch` means the provider rejected the
  session config — the contract table needs a revision before L1–L4 may be claimed. Do not edit the
  bridge config to "make it pass" without recording that first.
- Exit 3 — no final transcript within 30 s; check `~/.aria-conductor/prod-backend-live.log` for the
  child's stderr metadata and the provider's reachability from this host.

## L3 — controlled outages

### Bridge only (`pkill` the children, backend stays up)

```bash
pkill -f "qwen-live-bridge/dist/index.js"
```

Expected phone evidence on the open socket:

- one `error{code:"bridge_internal",retryable:true}` then close **1011**;
- the live status shows `LIVE · 重連中`;
- the reconnect attempt opens a new socket, which spawns a **fresh** bridge child — no server action
  is needed.

To exercise the phone's retry budget, keep the bridge dead for more than 29 s: five failures with
backoff, then the spoken SSE fallback once. Restore by letting the phone reconnect (or reconnect it
by hand); the next socket spawns a new child. Killing the backend process is *not* part of this
recipe and belongs to the next one.

The children are spawn-per-socket, so `pkill` on the pattern kills exactly the live sockets' bridges
and nothing else; a socket that had no bridge (accept-then-die) shows the same frame.

### Backend down

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN     # the app JVM holds the port
kill -TERM <app pid>                 # stop the backend only, leave the phone untouched
```

Expected phone evidence: the socket closes and the reconnect is refused (connection refused, no
upgrade), so the phone falls back to the SSE chat path; the bridge children are gone with the JVM
(spawn-per-socket).

Restore:

```bash
# only if the container runtime was restarted too
podman start aria-opensandbox
cd /Users/happy/Documents/project/aria-conductor
nohup bash scripts/start-backend.sh > ~/.aria-conductor/prod-backend-live.log 2>&1 &
echo $! > ~/.aria-conductor/prod-backend-live.pid
# health 200 on 127.0.0.1:8080 and 192.168.0.112:8080, then the smoke again
```

## Logs and secrets

- Backend log: `~/.aria-conductor/prod-backend-live.log` (Hibernate SQL is verbose there; the live
  lines are `aria live …` at debug and `qwen live bridge stderr: …` for the child's metadata).
- The bridge writes metadata only (event counts, byte counts, milliseconds) to stderr; Java redacts
  before logging. No audio, image, base64, bearer, key, workspace or transcript is ever logged, and
  the smoke prints event types only.
- `.env` and `~/.aria-conductor/qwen-live.env` stay out of git (`git check-ignore -v .env`); the
  credentials file is 0600 and copied by metadata-verified commands only.

## Turning live off

Set `ARIA_LIVE_ENABLED=false` in `.env` and restart the backend. The endpoint keeps answering
`hello` but every socket then closes `1011` with `bridge_internal` (the bridge is disabled) — the
phone falls back to SSE.

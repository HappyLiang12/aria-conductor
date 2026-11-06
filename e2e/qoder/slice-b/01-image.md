# Slice B, evidence 01: the final qoder-sandbox image (CLI + bridge + plugin)

Task B4. Everything below is a raw capture from this working tree; every quoted block is an
exact substring of the terminal transcript. Commands were run from `D:\project\aria-conductor`
on Windows (Git Bash) with `pwsh` and `podman` (see `e2e/container-runtime-e2e.ps1` for the
resolution scenarios).

Contents of this evidence file:

- the image identity and config,
- the image-ensure scenarios (RED, then GREEN) in `container-runtime-e2e.ps1`,
- the untouched `.sh` scenario test,
- the real smoke transcript against the built image,
- provenance for the pinned CLI artifact,
- the fix-round 1 rebuild, mode and `/tmp` corrections, decisive auth-gate probe and plugin-flag
  probes (section 7, 2026-09-18).

## 1. Image identity

```
$ podman images --format '{{.Repository}}:{{.Tag}} {{.ID}} {{.Size}}' | grep qoder
localhost/aria-conductor/qoder-sandbox:0.1 d1312a38711d 412 MB
```

```
$ podman image inspect aria-conductor/qoder-sandbox:0.1 --format 'Id={{.Id}}{{println}}Size={{.Size}}{{println}}User={{.Config.User}}{{println}}Cmd={{json .Config.Cmd}}{{println}}ExposedPorts={{json .Config.ExposedPorts}}'
Id=d1312a38711d11cc3b1e164e57a110b95b2df828a48033c6dce8b44969d5ca7c
Size=412202883
User=node
Cmd=["node","/opt/qoder/bridge/dist/main.js"]
ExposedPorts={"4097/tcp":{}}
```

So the image (`d1312a38711d`, 412 MB / 412202883 bytes) runs as `node`, exposes 4097 and its
default command is the compiled bridge at `/opt/qoder/bridge/dist/main.js` — matching
`Dockerfile:103-106` (`USER node`, `EXPOSE 4097`, `CMD ["node", "/opt/qoder/bridge/dist/main.js"]`).

## 2. Image-ensure scenarios (`e2e/container-runtime-e2e.ps1`)

The qoder helper `Ensure-QoderSandboxImage` lives at `scripts/lib/container-runtime.ps1:167`;
its contract (mirroring `Ensure-OpencodeSandboxImage`, line 139) is: return `$false` when
`image inspect` succeeds (image already present, no build), otherwise issue the build and
return `$true`. The placeholder condition that made the early return dead code
(`scripts/lib/container-runtime.ps1:175`) was replaced by the exit-code check.

### 2a. RED (before the fix)

```
$ pwsh -NoProfile -File e2e/container-runtime-e2e.ps1
Container-runtime resolution scenarios:
  PASS: explicit docker + docker available
  PASS: explicit podman + podman available
  PASS: explicit docker + CLI missing -> hard error
  PASS: explicit podman + engine not running -> hard error with podman hint
  PASS: explicit invalid value -> hard error
  PASS: auto + docker running -> docker
  PASS: auto + only podman running -> podman
  PASS: auto + neither available -> null runtime
Qoder sandbox image ensure scenarios:
  FAIL: qoder image present -> no build (RESULT built=True | image inspect aria-conductor/qoder-sandbox:0.1
build -t aria-conductor/qoder-sandbox:0.1 D:\project\aria-conductor\agent-control-tower\qoder-sandbox
)
  PASS: qoder image absent -> build invoked from the qoder-sandbox context
Load-DotEnv scenarios:
  PASS: Load-DotEnv parses KEY=VALUE, skips comments/invalid names, preserves existing env
  PASS: Load-DotEnv missing .env is a no-op
  PASS: Load-DotEnv strips CRLF line endings

1 scenario(s) FAILED
```

The transcript shows the bug directly: even though `image inspect` was called and the image was
present, the helper went on to `build` and reported `built=True`.

### 2b. GREEN (after the fix)

```
$ pwsh -NoProfile -File e2e/container-runtime-e2e.ps1
Container-runtime resolution scenarios:
  PASS: explicit docker + docker available
  PASS: explicit podman + podman available
  PASS: explicit docker + CLI missing -> hard error
  PASS: explicit podman + engine not running -> hard error with podman hint
  PASS: explicit invalid value -> hard error
  PASS: auto + docker running -> docker
  PASS: auto + only podman running -> podman
  PASS: auto + neither available -> null runtime
Qoder sandbox image ensure scenarios:
  PASS: qoder image present -> no build
  PASS: qoder image absent -> build invoked from the qoder-sandbox context
Load-DotEnv scenarios:
  PASS: Load-DotEnv parses KEY=VALUE, skips comments/invalid names, preserves existing env
  PASS: Load-DotEnv missing .env is a no-op
  PASS: Load-DotEnv strips CRLF line endings

All scenarios PASSED
```

## 3. The `.sh` scenario test

`e2e/container-runtime-e2e.sh` is deliberately not extended with a qoder image case: the bash
helper `scripts/lib/container-runtime.sh` contains no image-ensure function at all (runtime
resolution only), and the only caller of the ensure helper in the repository is the PowerShell
start path (`scripts/start.ps1:224`). There is therefore nothing to exercise on the bash side
beyond confirming it is unaffected:

```
$ bash e2e/container-runtime-e2e.sh
Container-runtime resolution scenarios:
  PASS: explicit docker + docker available
  PASS: explicit podman + podman available
  PASS: explicit docker + CLI missing -> hard error (rc=1)
  PASS: explicit podman + engine not running -> hard error with podman hint (rc=1)
  PASS: explicit invalid value -> hard error (rc=1)
  PASS: auto + docker running -> docker
  PASS: auto + only podman running -> podman
  PASS: auto + neither available -> null runtime (rc=0)
load_dotenv scenarios:
  PASS: load_dotenv parses KEY=VALUE, preserves existing env
  PASS: load_dotenv missing .env is a no-op
  PASS: load_dotenv strips CRLF line endings

All scenarios PASSED
```

## 4. Real smoke against the built image

The container is started with a synthetic token only (no real credential is read, echoed or
referenced anywhere in this file). The bridge fails closed without a token
(`bridge/src/main.ts:27-29`), and `GET /health` is intentionally unauthenticated while every
other route requires the bearer (`bridge/src/server.ts:181-189`), so the fail-closed proof is an
anonymous request to a protected route rather than to `/health`.

```
$ podman run -d --rm --name qoder-b4-smoke -e BRIDGE_TOKEN=b4-smoke-token aria-conductor/qoder-sandbox:0.1
01f076df613a037bedc5c11d35fb55ee23dead2aaf3ed5880188e346f3cfb907
```

All probes below run inside the container (`podman exec`), which sidesteps host port mapping.
The `Authorization` header value is the synthetic `b4-smoke-token` used above.

```
$ podman exec qoder-b4-smoke node -e "fetch('http://127.0.0.1:4097/health').then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 200 {"status":"ok","cliVersion":"1.1.41"}

$ podman exec qoder-b4-smoke node -e "fetch('http://127.0.0.1:4097/sessions', {method: 'POST', headers: {'content-type': 'application/json'}, body: '{}'}).then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 401 {"error":"UNAUTHORIZED"}

$ podman exec qoder-b4-smoke node -e "fetch('http://127.0.0.1:4097/health', {headers: {Authorization: 'Bearer b4-smoke-token'}}).then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 200 {"status":"ok","cliVersion":"1.1.41"}

$ podman exec qoder-b4-smoke node -e "fetch('http://127.0.0.1:4097/sessions', {method: 'POST', headers: {'content-type': 'application/json', Authorization: 'Bearer not-the-token'}, body: '{}'}).then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 401 {"error":"UNAUTHORIZED"}
```

Reading of the four probes: `/health` answers 200 with the pinned CLI version — it is the
credential-free liveness route and the frozen C0.2 contract exempts it from the auth gate, so
probe 3 (the same route with a correct bearer) says nothing about the gate; an anonymous `POST
/sessions` is refused `401 {"error":"UNAUTHORIZED"}`; and a wrong bearer is refused identically —
i.e. the bridge is fail-closed, not merely token-aware. The decisive probe for the gate's passing
leg is the correct-bearer `POST /sessions` with an empty body (the gate passes, request validation
rejects with `400 {"error":"INVALID_REQUEST"}`); it is captured on the rebuilt image in the
fix-round section below.

Image contents and runtime metadata:

In this repository's Git Bash, MSYS rewrites container paths before `podman` sees them (`/tmp`
arrives as `C:/Users/<user>/AppData/Local/Temp`, `/opt/qoder/...` as `C:/Program Files/Git/opt/...`),
so the three commands below are only copy-paste reproducible with `MSYS_NO_PATHCONV=1` prefixed (or
with `//tmp`-style paths). The captures were taken with the prefix.

```
$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-smoke qodercli --version
1.1.41

$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-smoke stat -c '%a %U:%G' /tmp
1777 root:root

$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-smoke ls /opt/qoder/plugin
manifest.json
skills

$ podman exec qoder-b4-smoke id -un
node
```

`/tmp` still carries its standard `1777 root:root` metadata, i.e. the extraction-corruption fix
documented at `Dockerfile:59-65` holds in the shipped image. The plugin bundle is present at
`/opt/qoder/plugin` (`manifest.json`, `skills/`).

Startup log and cleanup:

```
$ podman logs qoder-b4-smoke
bridge listening on port 4097 (cli 1.1.41)
GET /health -> 200
GET /health -> 200
GET /health -> 200
POST /sessions -> 401
GET /health -> 200
POST /sessions -> 401

$ podman stop qoder-b4-smoke
qoder-b4-smoke
```

Log reading: the four probes listed above are one `GET /health` (probe 1), one anonymous `POST
/sessions` (probe 2), one `GET /health` with the correct bearer (probe 3) and one wrong-bearer `POST
/sessions` (probe 4) — i.e. only 2 of the 4 `GET /health` lines in this log. The other two are
readiness polls issued while waiting for the bridge to listen before the probes were run (they
precede the first `POST /sessions`; their outputs are not part of this capture). INFERRED: the poll
loop itself is not recorded anywhere in this evidence file — the pairing above is reconstructed from
the line order (`H H H P H P` against probes `H P H P`). The fix-round smoke in section 7 removes
the ambiguity by waiting for the bridge without polling `/health`, and logs exactly one line per
probe.

## 5. Provenance of the pinned CLI artifact

The pin is declared in the image Dockerfile (raw capture):

```
$ grep -n 'QODERCLI_VERSION\|QODERCLI_SHA256\|QODERCLI_URL\|FROM \|CMD \|EXPOSE \|USER ' agent-control-tower/qoder-sandbox/Dockerfile
37:FROM node:22-slim AS bridge-builder
50:FROM node:22-slim
52:ARG QODERCLI_VERSION=1.1.41
54:ARG QODERCLI_LINUX_X64_URL=https://qoder-ide.oss-accelerate.aliyuncs.com/qodercli/releases/${QODERCLI_VERSION}/qodercli-linux-x64.tar.gz
103:USER node
105:EXPOSE 4097
106:CMD ["node", "/opt/qoder/bridge/dist/main.js"]
```

Together with `Dockerfile:53` (`ARG QODERCLI_LINUX_X64_SHA256=1c2d174b098eb472a9ac19a80b89a664fdcb7d37dca09e64f44243faa4e3eca7`)
and the verification step `Dockerfile:70` (`sha256sum -c -`), the shipped CLI is version
`1.1.41`, sha256
`1c2d174b098eb472a9ac19a80b89a664fdcb7d37dca09e64f44243faa4e3eca7`, downloaded from the URL
above.

This evidence file does not claim any new upstream provenance: the artifact was pinned and
verified in task A1, whose provenance record is `e2e/qoder/slice-a/01-boot.md`. The
`cliVersion` reported by this image's `/health` and `qodercli --version` (both `1.1.41`) agree
with that A1 pin.

## 6. Deviations from the task brief

- The brief writes the container command as `CMD ["node","/opt/qoder/bridge/main.js"]`; the
  shipped image uses the compiled entry point `["node","/opt/qoder/bridge/dist/main.js"]`
  (`Dockerfile:106`, confirmed by `podman image inspect` above), because the bridge is compiled
  by the `bridge-builder` stage into `dist/`. `main.js` at the bridge root does not exist in the
  runtime stage.
- The `docker run --rm` smoke in the brief's step 1 was executed on the equivalent local
  runtime `podman` (the image was built into podman's store, and the container-runtime
  scenarios in `e2e/container-runtime-e2e.ps1` resolve podman here). `/health` and the bearer
  refusal were both exercised, see section 4.
- The brief's step-2 wording implies both scenario-test files; the bash file was intentionally
  left alone, see section 3.

## 7. Fix round 1 (2026-09-18)

Round naming: the heading is the one the fix-round brief prescribes (`task-B4-fix2-brief.md`,
findings I1/I2/I3/M1/M2/M5 of `review-B4-verdict.md`); the same brief's report contract also names
`task-B4-fix1-report.md`, while its "Files you own" list and the coordinator's dispatch name
`task-B4-fix2-report.md` — the delivered report is the latter. The preceding fix in this B4 sequence
is the bridge plugin-argv fix, reported in `task-B4-fix1-bridge-report.md`.

The rebuild replaced the tag: `d1312a38711d` (412 MB) → `e7a530b490cb` (387 MB). The old image id is
no longer tagged but is still in the store, so every "before" command below can be re-run against
the id instead of the tag. Host-side build/help captures were redirected to host temp files, so only
their quoted output appears here; no uncommitted path is referenced.

Capture presentation: where the same command was run once per image, the two outputs are shown as
one block and the `# <image id>` lines label which run produced the lines that follow; no captured
output line is altered. `[...]` marks omitted regions of a longer capture.

### 7.1 Rebuild, image identity and the amended bridge argv

```
$ podman build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox
[...build output trimmed to the tail...]
[2/2] STEP 9/16: RUN find /opt/qoder -type d -exec chmod 0755 {} +     && find /opt/qoder -type f -exec chmod 0644 {} +
--> a3ed64213667
[2/2] STEP 10/16: ENV HOME=/home/node
--> 056c35fda5f6
[2/2] STEP 11/16: RUN install -d -o node -g node /home/node/.qoder     && printf '{\n  "general": {\n    "enableAutoUpdate": false\n  }\n}\n' > /home/node/.qoder/settings.json     && chown node:node /home/node/.qoder/settings.json
--> 194c15509d07
[2/2] STEP 12/16: RUN install -d -o node -g node /workspace
--> 884d9df379c5
[2/2] STEP 13/16: USER node
--> e7a93d68a63f
[2/2] STEP 14/16: WORKDIR /workspace
--> e9b8abc5a416
[2/2] STEP 15/16: EXPOSE 4097
--> c460f805e7e2
[2/2] STEP 16/16: CMD ["node", "/opt/qoder/bridge/dist/main.js"]
[2/2] COMMIT aria-conductor/qoder-sandbox:0.1
--> e7a530b490cb
Successfully tagged localhost/aria-conductor/qoder-sandbox:0.1
e7a530b490cb271cbfb0db73fbd0f9dc4296155dfc07558681a4388464dcc120
build_exit=0
```

```
$ podman images --format '{{.Repository}}:{{.Tag}} {{.ID}} {{.Size}}' | grep qoder
localhost/aria-conductor/qoder-sandbox:0.1 e7a530b490cb 387 MB
```

```
$ podman image inspect localhost/aria-conductor/qoder-sandbox:0.1 --format 'Id={{.Id}}{{println}}Size={{.Size}}{{println}}User={{.Config.User}}{{println}}Cmd={{json .Config.Cmd}}'
Id=e7a530b490cb271cbfb0db73fbd0f9dc4296155dfc07558681a4388464dcc120
Size=387207863
User=node
Cmd=["node","/opt/qoder/bridge/dist/main.js"]
```

The rebuilt layer carries the amended bridge argv. Both images inspected with the same command:

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint sh <image> -c "sha256sum /opt/qoder/bridge/dist/acp-client.js; grep -c plugin-dir /opt/qoder/bridge/dist/acp-client.js; grep -a -o 'DEFAULT_ARGS[^;]*' /opt/qoder/bridge/dist/acp-client.js | head -2"

# d1312a38711d (pre-rebuild)
2a93204576a5c34b900c70cff417fd8351899733efe3e47eca5c99271ab4994e  /opt/qoder/bridge/dist/acp-client.js
0
DEFAULT_ARGS = ['--acp']

# e7a530b490cb (rebuilt)
692b7a7486a9da7c1df23449ce2624af458ec927af42bd207eb414a2bbaae455  /opt/qoder/bridge/dist/acp-client.js
3
DEFAULT_ARGS = ['--acp', '--plugin-dir', DEFAULT_PLUGIN_DIR]
```

The 412 MB → 387 MB delta is exactly the `/tmp` build leakage M5 removes:

```
$ podman history --format '{{.Size}}' d1312a38711d      $ podman history --format '{{.Size}}' e7a530b490cb
# newest first, size column only (23 entries)           # newest first, size column only (24 entries)
0B                                                      0B
0B                                                      0B
0B                                                      0B
0B                                                      0B
1.54kB                                                  1.54kB
3.58kB                                                  3.58kB
0B                                                      0B
7.17kB                                                  196kB
3.58kB                                                  7.17kB
188kB                                                   3.58kB
179MB                                                   190kB
0B                                                      154MB
0B                                                      0B
0B                                                      0B
0B                                                      0B
0B                                                      0B
3.58kB                                                  0B
7.22MB                                                  3.58kB
0B                                                      7.22MB
148MB                                                   0B
0B                                                      148MB
21.5kB                                                  0B
77.9MB                                                  21.5kB
                                                        77.9MB
```

Both columns are newest-first, but the rebuilt image has one more entry — the added 196kB
normalization layer — so below that entry the rebuilt column sits one line lower; matched
entry-for-entry across that offset, exactly two rows changed size: the CLI layer (179MB → 154MB)
and the bridge `dist/` COPY (188kB → 190kB, consistent with the recompiled `dist/` after the
amended bridge argv — section 7.1). The disappeared 25 MB is the directory M5 removes:

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --user 0 --entrypoint sh d1312a38711d -c 'du -sh /tmp/qodercli-natives-v1.1.41-unknown; find /tmp/qodercli-natives-v1.1.41-unknown -maxdepth 3 | head -20'
25M	/tmp/qodercli-natives-v1.1.41-unknown
/tmp/qodercli-natives-v1.1.41-unknown
/tmp/qodercli-natives-v1.1.41-unknown/vendor
/tmp/qodercli-natives-v1.1.41-unknown/vendor/ripgrep
/tmp/qodercli-natives-v1.1.41-unknown/vendor/ripgrep/x64-linux
/tmp/qodercli-natives-v1.1.41-unknown/node_modules
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@lydell
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@lydell/node-pty
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@lydell/node-pty-linux-x64
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@img
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@img/sharp-linux-x64
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@img/sharp-libvips-linux-x64
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@silvia-odwyer
/tmp/qodercli-natives-v1.1.41-unknown/node_modules/@silvia-odwyer/photon-node
/tmp/qodercli-natives-v1.1.41-unknown/.ready
```

(The `--user 0` above is required: the dir is root-owned 0700, so as `node` — the image user — a
sizing attempt only yields `4.0K` plus `du: cannot read directory ...: Permission denied`.)
INFERRED: this directory is the CLI's build-time native-module staging area (the observed
sub-paths are ripgrep, node-pty, sharp/libvips and photon); nothing in this fix round depends on
that reading — the captured fact is that 25 MB of root-owned build state was shipped in a shared
namespace and is now gone.

### 7.2 I1 — modes normalized, non-writability for `node`

Before (the tag still pointed at `d1312a38711d`):

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint ls localhost/aria-conductor/qoder-sandbox:0.1 -la /opt/qoder/bridge /opt/qoder/plugin
/opt/qoder/bridge:
total 16
drwxr-xr-x 1 root root 4096 Sep 17 20:48 .
drwxr-xr-x 1 root root 4096 Sep 17 20:48 ..
drwxr-xr-x 2 root root 4096 Sep 17 20:48 dist
-rwxrwxrwx 1 root root  341 Sep 17 18:55 package.json

/opt/qoder/plugin:
total 20
drwxr-xr-x 4 root root 4096 Sep 17 20:48 .
drwxr-xr-x 1 root root 4096 Sep 17 20:48 ..
drwxrwxrwx 2 root root 4096 Sep 17 16:34 .qoder-plugin
-rwxrwxrwx 1 root root  156 Sep 17 16:34 manifest.json
drwxrwxrwx 3 root root 4096 Sep 17 16:34 skills
```

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint stat localhost/aria-conductor/qoder-sandbox:0.1 -c '%a %U:%g %n' /opt/qoder/bridge/package.json /opt/qoder/plugin/manifest.json /opt/qoder/plugin/.qoder-plugin/plugin.json /opt/qoder/plugin/skills/aria-pinned/SKILL.md
777 root:0 /opt/qoder/bridge/package.json
777 root:0 /opt/qoder/plugin/manifest.json
777 root:0 /opt/qoder/plugin/.qoder-plugin/plugin.json
777 root:0 /opt/qoder/plugin/skills/aria-pinned/SKILL.md
```

After (rebuilt image; the normalization layer is `Dockerfile:97-98`):

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint ls localhost/aria-conductor/qoder-sandbox:0.1 -la /opt/qoder/bridge /opt/qoder/bridge/dist /opt/qoder/plugin /opt/qoder/plugin/skills
/opt/qoder/bridge:
total 16
drwxr-xr-x 1 root root 4096 Sep 17 21:24 .
drwxr-xr-x 1 root root 4096 Sep 17 21:24 ..
drwxr-xr-x 1 root root 4096 Sep 17 21:24 dist
-rw-r--r-- 1 root root  335 Sep 17 21:16 package.json

/opt/qoder/bridge/dist:
total 216
drwxr-xr-x 1 root root 4096 Sep 17 21:24 .
drwxr-xr-x 1 root root 4096 Sep 17 21:24 ..
-rw-r--r-- 1 root root 12426 Sep 17 21:24 acp-client.d.ts
-rw-r--r-- 1 root root  6196 Sep 17 21:24 acp-client.d.ts.map
-rw-r--r-- 1 root root 40409 Sep 17 21:24 acp-client.js
-rw-r--r-- 1 root root 26818 Sep 17 21:24 acp-client.js.map
-rw-r--r-- 1 root root  2723 Sep 17 21:24 env.d.ts
-rw-r--r-- 1 root root   596 Sep 17 21:24 env.d.ts.map
-rw-r--r-- 1 root root  3600 Sep 17 21:24 env.js
-rw-r--r-- 1 root root  1577 Sep 17 21:24 env.js.map
-rw-r--r-- 1 root root   758 Sep 17 21:24 main.d.ts
-rw-r--r-- 1 root root   364 Sep 17 21:24 main.d.ts.map
-rw-r--r-- 1 root root  2877 Sep 17 21:24 main.js
-rw-r--r-- 1 root root  2303 Sep 17 21:24 main.js.map
-rw-r--r-- 1 root root  2203 Sep 17 21:24 server.d.ts
-rw-r--r-- 1 root root  1413 Sep 17 21:24 server.d.ts.map
-rw-r--r-- 1 root root 31400 Sep 17 21:24 server.js
-rw-r--r-- 1 root root 25732 Sep 17 21:24 server.js.map
-rw-r--r-- 1 root root  2171 Sep 17 21:24 sse.d.ts
-rw-r--r-- 1 root root   873 Sep 17 21:24 sse.d.ts.map
-rw-r--r-- 1 root root  3587 Sep 17 21:24 sse.js
-rw-r--r-- 1 root root  2555 Sep 17 21:24 sse.js.map

/opt/qoder/plugin:
total 20
drwxr-xr-x 1 root root 4096 Sep 17 21:24 .
drwxr-xr-x 1 root root 4096 Sep 17 21:24 ..
drwxr-xr-x 1 root root 4096 Sep 17 16:34 .qoder-plugin
-rw-r--r-- 1 root root  156 Sep 17 16:34 manifest.json
drwxr-xr-x 1 root root 4096 Sep 17 16:34 skills

/opt/qoder/plugin/skills:
total 12
drwxr-xr-x 1 root root 4096 Sep 17 16:34 .
drwxr-xr-x 1 root root 4096 Sep 17 21:24 ..
drwxr-xr-x 1 root root 4096 Sep 17 16:34 aria-pinned
```

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint stat localhost/aria-conductor/qoder-sandbox:0.1 -c '%a %U:%g %n' /opt/qoder/bridge/package.json /opt/qoder/plugin/manifest.json /opt/qoder/plugin/.qoder-plugin/plugin.json /opt/qoder/plugin/skills/aria-pinned/SKILL.md /opt/qoder/bridge/dist/main.js /opt/qoder/bridge/dist
644 root:0 /opt/qoder/bridge/package.json
644 root:0 /opt/qoder/plugin/manifest.json
644 root:0 /opt/qoder/plugin/.qoder-plugin/plugin.json
644 root:0 /opt/qoder/plugin/skills/aria-pinned/SKILL.md
644 root:0 /opt/qoder/bridge/dist/main.js
755 root:0 /opt/qoder/bridge/dist
```

The mode fix is checked against a live write attempt as the sandbox user (the image's own `USER
node`), with `/workspace` as the control that proves the user is not simply unable to write
anywhere:

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint sh localhost/aria-conductor/qoder-sandbox:0.1 -c 'touch /opt/qoder/plugin/manifest.json; echo "plugin-manifest-touch-rc=$?"; echo x >> /opt/qoder/bridge/package.json; echo "bridge-package-json-append-rc=$?"; touch /workspace/control-write && echo "workspace-write-ok (control)"'
touch: cannot touch '/opt/qoder/plugin/manifest.json': Permission denied
plugin-manifest-touch-rc=1
bridge-package-json-append-rc=2
sh: 1: cannot create /opt/qoder/bridge/package.json: Permission denied
workspace-write-ok (control)
```

(Both write attempts are denied; the shell's own stderr line for the append is interleaved after
the `$?` echo in the capture above. `node` can still write in `/workspace`.)

### 7.3 M5 — `/tmp` cleaned in the same layer

Before (tag = `d1312a38711d`):

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint ls localhost/aria-conductor/qoder-sandbox:0.1 -la /tmp
total 12
drwxrwxrwt 1 root root 4096 Sep 17 20:48 .
dr-xr-xr-x 1 root root 4096 Sep 17 21:22 ..
drwx------ 4 root root 4096 Sep 17 20:47 qodercli-natives-v1.1.41-unknown
```

After (rebuild; the cleanup is `Dockerfile:78`, the `test` re-assert on `/tmp` is unchanged):

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint sh localhost/aria-conductor/qoder-sandbox:0.1 -c 'id -un; ls -la /tmp; stat -c "%a %U:%g" /tmp'
node
total 8
drwxrwxrwt 1 root root 4096 Sep 17 21:24 .
dr-xr-xr-x 1 root root 4096 Sep 17 21:25 ..
1777 root:0
```

No `qodercli-natives-*` and no `qodercli-smoke` remain; `/tmp` still carries its standard
`1777 root:0` metadata (in the shipping image `stat -c '%U:%g'` renders the same as `root:root`,
see section 4).

### 7.4 I2 — the two reworded claims

Auto-update: the key is present in the pinned binary; the *behavior* on the `--acp` path is not
exercised here. The count is 8 occurrences (4 matching lines) — the review reported 6, which this
run does not reproduce; the CLI binary is byte-identical in both images, so the discrepancy is in
the counting form, not in the artifact:

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint sh localhost/aria-conductor/qoder-sandbox:0.1 -c "grep -a -o general.enableAutoUpdate /usr/local/bin/qodercli | wc -l; grep -a -o general.enableAutoUpdate /usr/local/bin/qodercli | wc -c; grep -a -c general.enableAutoUpdate /usr/local/bin/qodercli"
8
200
4
```

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint sha256sum <image> /usr/local/bin/qodercli
# d1312a38711d (pre-rebuild)
ef47378ab5c6aeb32b4ec3251aad08c6a1c15970a70e8186f9e67c3906018c51  /usr/local/bin/qodercli
# e7a530b490cb (rebuilt)
ef47378ab5c6aeb32b4ec3251aad08c6a1c15970a70e8186f9e67c3906018c51  /usr/local/bin/qodercli
```

HOME: the image ENV sets `HOME=/home/node` (`Dockerfile:105`), and a direct `podman run` as the
image user prints `id -un` → `node` (section 7.3; the section-4 `podman exec` shows the same); that
the OpenSandbox bootstrap starts the sandbox with the same HOME is NOT VERIFIED here — C6 must
observe it. The Dockerfile comment now says exactly that (`Dockerfile:100-104`).

### 7.5 I3 — the decisive auth-gate probe, and the smoke on the rebuilt image

Same shape as section 4, on the rebuilt image (synthetic token only). Readiness is waited out
without polling `/health`, so the log below has exactly one line per probe:

```
$ podman run -d --rm --name qoder-b4-fix2-smoke -e BRIDGE_TOKEN=b4-smoke-token localhost/aria-conductor/qoder-sandbox:0.1
5fedf631099eb0c3b4b91315643174f7f318e079df593a05b787d63898cb1eab
```

```
$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-fix2-smoke node -e "fetch('http://127.0.0.1:4097/health').then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 200 {"status":"ok","cliVersion":"1.1.41"}

$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-fix2-smoke node -e "fetch('http://127.0.0.1:4097/sessions', {method: 'POST', headers: {'content-type': 'application/json'}, body: '{}'}).then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 401 {"error":"UNAUTHORIZED"}

$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-fix2-smoke node -e "fetch('http://127.0.0.1:4097/sessions', {method: 'POST', headers: {'content-type': 'application/json', Authorization: 'Bearer not-the-token'}, body: '{}'}).then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 401 {"error":"UNAUTHORIZED"}

$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-fix2-smoke node -e "fetch('http://127.0.0.1:4097/sessions', {method: 'POST', headers: {'content-type': 'application/json', Authorization: 'Bearer b4-smoke-token'}, body: '{}'}).then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 400 {"error":"INVALID_REQUEST"}

$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-fix2-smoke node -e "fetch('http://127.0.0.1:4097/health', {headers: {Authorization: 'Bearer b4-smoke-token'}}).then(r => r.text().then(t => console.log('HTTP', r.status, t)))"
HTTP 200 {"status":"ok","cliVersion":"1.1.41"}

$ MSYS_NO_PATHCONV=1 podman exec qoder-b4-fix2-smoke qodercli --version
1.1.41
```

```
$ podman logs qoder-b4-fix2-smoke
bridge listening on port 4097 (cli 1.1.41)
GET /health -> 200
POST /sessions -> 401
POST /sessions -> 401
POST /sessions -> 400
GET /health -> 200

$ podman stop qoder-b4-fix2-smoke
qoder-b4-fix2-smoke
```

The fourth probe is the decisive one the review asked for: the correct bearer passes the gate
(`401` becomes `400`), and the rejection is request validation — `bridge/src/server.ts:222-223`
validates before any ACP client is created, so no CLI spawn and no credits. The log confirms one
line per probe, i.e. no hidden traffic.

### 7.6 Plugin-flag probes (no model call, no network)

`--network none` is used for all three commands below; none of them reaches a model.

```
$ podman run --rm --network none --entrypoint qodercli localhost/aria-conductor/qoder-sandbox:0.1 --help
Usage: qodercli [options] [command] [query...]

Qoder CLI - Defaults to interactive mode. Use -p/--print for non-interactive
output.
[...]
  --attachment <file>                  Attach files to the initial prompt
  --plugin-dir <dir>                   Plugin directories to load
  -c, --continue                       Continue the most recent session
[...]
  mcp                                  Configure and manage MCP servers
  plugins|plugin                       Manage plugins
  skills|skill                         Manage agent skills.
[...]
  -v, --version                        output the version number
  -h, --help                           display help for command
```

(Excerpt of the 96-line capture; `[...]` marks omitted regions. The `--plugin-dir <dir>` option
exists in the pinned 1.1.41 binary, and `plugins|plugin` is a subcommand.)

```
$ MSYS_NO_PATHCONV=1 podman run --rm --network none --entrypoint qodercli localhost/aria-conductor/qoder-sandbox:0.1 plugins list --plugin-dir /opt/qoder/plugin --json
[
  {
    "id": "aria-pinned@flag",
    "name": "aria-pinned",
    "source": "aria-pinned@inline",
    "version": "0.1.0",
    "scope": "flag",
    "enabled": true,
    "canDisable": false,
    "installPath": "/opt/qoder/plugin",
    "description": "Aria Conductor pinned plugin bundle (loaded into qoder-sandbox only via --plugin-dir)",
    "resources": {
      "skills": [
        {
          "name": "aria-pinned",
          "description": "Aria Conductor pinned skill; proves --plugin-dir loads exactly the pinned bundle."
        }
      ],
      "agents": [],
      "mcpServers": [],
      "commands": [],
      "hooks": []
    }
  }
]
plugins_rc=0
```

The bundle's own text above is the plugin `name`, `version` and `description` (from
`plugin/manifest.json`, with the same values in `plugin/.qoder-plugin/plugin.json`) and the skill's
`name`/`description` (from `plugin/skills/aria-pinned/SKILL.md`); the remaining fields — `id`,
`source`, `scope`, `enabled`, `canDisable`, `installPath` — are CLI-derived (they appear in no
bundle file). `installPath` is the baked path, so in this image `--plugin-dir /opt/qoder/plugin`
resolves exactly the pinned bundle and reports its one skill.
What this does NOT show: that the plugin is loaded into an authenticated agent session — that stays
with C6/C7. The `--acp` + `--plugin-dir` combination itself is already verified in task A3
(`e2e/qoder/slice-a/03-mcp-auth.md:78`), and the shipped bridge compiles that argv (section 7.1).

Note: the `/workspace/plugin`→baked-path reword of `plugin/skills/aria-pinned/SKILL.md` is in the repo but NOT in the already-built image `e7a530b490cb`, which still carries the previous text; it is non-behavioral and takes effect on the next image build (no rebuild was run for it).

### 7.7 M2 — scenario re-runs (RED, then GREEN)

RED: the two new docker scenarios exist but the helper still writes a `podman.ps1` stub only, so
`docker` cannot resolve. One run, excerpts:

```
$ pwsh -NoProfile -File e2e/container-runtime-e2e.ps1
[...]
     | The term 'docker' is not recognized as a name of a cmdlet, function, script file, or executable program. Check
     | the spelling of the name, or if a path was included, verify that the path is correct and try again.
[...]
  FAIL: qoder image present (docker) -> no build ( | )
[...]
  FAIL: qoder image absent (docker) -> build invoked from the qoder-sandbox context ( | )
[...]

2 scenario(s) FAILED
exit=1
```

(The 13 pre-existing scenarios still passed in that run; both docker scenarios failed because the
stub CLI had to be named after the runtime — the parameterization is load-bearing, not decorative.)
This is a wiring RED. The stronger regression the docker pair is meant to catch — a helper that
went back to `image exists`, which under docker exits 1 for every tag (`container-runtime.ps1:160-165`)
and would therefore always build — was NOT executed against a mutated helper (no file outside the
fix round's own list may be modified); INFERRED: with the present stub (`exit 1` for anything but
`image inspect`/`build`) that regression would fail the "present -> no build" case.

GREEN after writing `<Runtime>.ps1`:

```
$ pwsh -NoProfile -File e2e/container-runtime-e2e.ps1
Container-runtime resolution scenarios:
  PASS: explicit docker + docker available
  PASS: explicit podman + podman available
  PASS: explicit docker + CLI missing -> hard error
  PASS: explicit podman + engine not running -> hard error with podman hint
  PASS: explicit invalid value -> hard error
  PASS: auto + docker running -> docker
  PASS: auto + only podman running -> podman
  PASS: auto + neither available -> null runtime
Qoder sandbox image ensure scenarios:
  PASS: qoder image present -> no build
  PASS: qoder image absent -> build invoked from the qoder-sandbox context
  PASS: qoder image present (docker) -> no build
  PASS: qoder image absent (docker) -> build invoked from the qoder-sandbox context
Load-DotEnv scenarios:
  PASS: Load-DotEnv parses KEY=VALUE, skips comments/invalid names, preserves existing env
  PASS: Load-DotEnv missing .env is a no-op
  PASS: Load-DotEnv strips CRLF line endings

All scenarios PASSED
ps1_exit=0
```

```
$ bash e2e/container-runtime-e2e.sh
Container-runtime resolution scenarios:
  PASS: explicit docker + docker available
  PASS: explicit podman + podman available
  PASS: explicit docker + CLI missing -> hard error (rc=1)
  PASS: explicit podman + engine not running -> hard error with podman hint (rc=1)
  PASS: explicit invalid value -> hard error (rc=1)
  PASS: auto + docker running -> docker
  PASS: auto + only podman running -> podman
  PASS: auto + neither available -> null runtime (rc=0)
load_dotenv scenarios:
  PASS: load_dotenv parses KEY=VALUE, preserves existing env
  PASS: load_dotenv missing .env is a no-op
  PASS: load_dotenv strips CRLF line endings

All scenarios PASSED
sh_exit=0
```

### 7.8 Current Dockerfile anchors

Sections 1–5 cite the pre-fix numbering and their line numbers no longer resolve; the amended file
places the touched blocks at: header auto-update comment `27-32`, extraction/natives cleanup
`68-79` (the new `rm -rf /tmp/qodercli-smoke /tmp/qodercli-natives-*` is line 78), the three COPYs
`83-90`, the mode-normalization comment + layer `92-98`, the HOME comment + `ENV HOME` `100-105`,
`USER node` `116`, `EXPOSE 4097` `118`, `CMD` `119`; the pin is unchanged (`FROM` `39`/`52`,
`ARG QODERCLI_VERSION` `54`).


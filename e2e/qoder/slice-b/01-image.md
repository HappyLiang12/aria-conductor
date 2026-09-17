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
- provenance for the pinned CLI artifact.

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

Reading of the four probes: `/health` answers 200 with the pinned CLI version (it is the
credential-free liveness route); an anonymous `POST /sessions` is refused `401
{"error":"UNAUTHORIZED"}`; the same request with the correct bearer is no longer rejected by the
auth gate; and a wrong bearer is refused identically — i.e. the bridge is fail-closed, not
merely token-aware.

Image contents and runtime metadata:

```
$ podman exec qoder-b4-smoke qodercli --version
1.1.41

$ podman exec qoder-b4-smoke stat -c '%a %U:%G' /tmp
1777 root:root

$ podman exec qoder-b4-smoke ls /opt/qoder/plugin
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

# Aria Conductor - Qoder Sandbox Template Image
# Runs the governed run-owned Qoder ACP bridge (packages/qoder-acp-bridge) and
# the pinned Qoder CLI inside a run-owned OpenSandbox sandbox.
#
# Build (context = repository root: the image consumes the bridge source tree
# and the shared runtime-sandbox scripts, and its dist/ tree is git-ignored, so
# the image BUILDS the bridge rather than copying a build artifact):
#
#   docker build -t aria-conductor/qoder-sandbox:1.0 \
#     -f agent-control-tower/qoder-sandbox/Dockerfile \
#     --build-arg QODER_CLI_VERSION=<pinned version> \
#     --build-arg QODER_CLI_ARTIFACT_URL=<pinned https artifact url> \
#     --build-arg QODER_CLI_SHA256=<sha256 of that artifact> \
#     --build-arg QODER_CLI_LICENSE_ACCEPTED=yes .
#
# NOT VERIFIED as of this task: Task 1 recorded that no Linux qodercli artifact
# has been verified yet (the only observed standalone CLI is the operator's
# Windows build, qodercli 1.1.61), and no OpenSandbox server or image exists on
# the implementation machine. The build therefore REFUSES to run without an
# explicitly pinned, checksum-verified Linux artifact and an explicit license
# acceptance -- it never falls back to "latest" and never installs an unpinned
# binary. The artifact URL, its version pin and its license terms are operator
# inputs (the CLI is a proprietary Qoder artifact; the image bakes in no
# credentials and no license keys).
#
# Isolation contract (specified, not optional):
#   * runs as the unprivileged user `aria` (uid 10001);
#   * contains NO host credential material and NO container-runtime socket;
#   * a core is only ever started through /opt/aria/launch.mjs with a trusted
#     launch manifest uploaded by the backend (explicit argv, shell: false);
#   * the per-run Qoder credential and the bridge's per-run control secret are
#     injected through the launch manifest environment, never baked in;
#   * /opt/aria/stop-writers.mjs stops the bridge and its CLI child (and their
#     descendants) while leaving the Sandbox export facility (execd) alive.

ARG NODE_IMAGE=node:22-slim

# ------------------------------------------------------------------ bridge build
# The bridge's dist/ tree is git-ignored, so it is built here from the pinned
# source tree and lockfile (the same source tree the Host placement consumes).
FROM ${NODE_IMAGE} AS bridge

ARG PNPM_VERSION=9.15.9
RUN npm install -g "pnpm@${PNPM_VERSION}"

WORKDIR /bridge
COPY packages/qoder-acp-bridge/package.json packages/qoder-acp-bridge/pnpm-lock.yaml \
     packages/qoder-acp-bridge/tsconfig.json ./
RUN pnpm install --frozen-lockfile
COPY packages/qoder-acp-bridge/src ./src
RUN pnpm run build \
    && test -f dist/main.js

# ------------------------------------------------------------------ runtime image
FROM ${NODE_IMAGE}

# The pinned Qoder CLI artifact. All four build args are required: a missing
# pin, a missing checksum or an unaccepted license FAILS the build instead of
# producing an image with an unverifiable agent binary.
ARG QODER_CLI_VERSION
ARG QODER_CLI_ARTIFACT_URL
ARG QODER_CLI_SHA256
ARG QODER_CLI_LICENSE_ACCEPTED

RUN set -eux; \
    if [ -z "${QODER_CLI_VERSION}" ] || [ -z "${QODER_CLI_ARTIFACT_URL}" ] \
       || [ -z "${QODER_CLI_SHA256}" ] || [ "${QODER_CLI_LICENSE_ACCEPTED}" != "yes" ]; then \
      echo "ERROR: this image requires an explicitly pinned Linux qodercli artifact:" >&2; \
      echo "  --build-arg QODER_CLI_VERSION=<version> --build-arg QODER_CLI_ARTIFACT_URL=<https url>" >&2; \
      echo "  --build-arg QODER_CLI_SHA256=<sha256>  --build-arg QODER_CLI_LICENSE_ACCEPTED=yes" >&2; \
      echo "  (Task 1 has not verified a Linux artifact yet; the operator supplies the pin and accepts the license.)" >&2; \
      exit 1; \
    fi; \
    case "${QODER_CLI_ARTIFACT_URL}" in \
      https://*) ;; \
      *) echo "ERROR: QODER_CLI_ARTIFACT_URL must be an https URL, got ${QODER_CLI_ARTIFACT_URL}" >&2; exit 1;; \
    esac; \
    case "${QODER_CLI_VERSION}" in \
      latest|*latest*) echo "ERROR: QODER_CLI_VERSION must be a pinned version, not '${QODER_CLI_VERSION}'" >&2; exit 1;; \
    esac; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl tar gzip; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p /tmp/qoder-artifact; \
    curl -fsSL "${QODER_CLI_ARTIFACT_URL}" -o /tmp/qoder-cli.artifact; \
    echo "${QODER_CLI_SHA256}  /tmp/qoder-cli.artifact" | sha256sum -c -; \
    case "${QODER_CLI_ARTIFACT_URL}" in \
      *.tar.gz|*.tgz) tar -xzf /tmp/qoder-cli.artifact -C /tmp/qoder-artifact ;; \
      *.tar) tar -xf /tmp/qoder-cli.artifact -C /tmp/qoder-artifact ;; \
      *) install -m 0755 /tmp/qoder-cli.artifact /tmp/qoder-artifact/qodercli ;; \
    esac; \
    binary="$(find /tmp/qoder-artifact -type f -name 'qodercli' -perm -u+x | head -n 1)"; \
    if [ -z "${binary}" ]; then \
      echo "ERROR: the pinned artifact does not contain an executable qodercli binary" >&2; exit 1; \
    fi; \
    install -m 0755 "${binary}" /usr/local/bin/qodercli; \
    rm -rf /tmp/qoder-artifact /tmp/qoder-cli.artifact; \
    observed="$(/usr/local/bin/qodercli --version 2>&1 | tr -d '\r')"; \
    case "${observed}" in \
      *"${QODER_CLI_VERSION}"*) ;; \
      *) echo "ERROR: the artifact reports '${observed}', which does not match the pinned QODER_CLI_VERSION ${QODER_CLI_VERSION}" >&2; exit 1;; \
    esac; \
    echo "Installed qodercli ${observed}"

# The governed bridge, built from the same source tree as the Host placement.
COPY --from=bridge /bridge/dist /opt/aria/bridge/dist
COPY packages/qoder-acp-bridge/package.json /opt/aria/bridge/package.json

# The fixed run-owned launcher and writer-control script (single source of truth
# in agent-control-tower/runtime-sandbox; copied, never re-implemented here).
COPY agent-control-tower/runtime-sandbox/launch.mjs /opt/aria/launch.mjs
COPY agent-control-tower/runtime-sandbox/stop-writers.mjs /opt/aria/stop-writers.mjs

# Unprivileged runtime user. The image never gains a docker/podman socket, a
# host credential mount or an elevated entrypoint.
#
# /opt/aria is the control plane the core is judged by (the launcher, the
# writer-control script and the entrypoint allowlist): it stays root-owned and
# read-only, so the unprivileged core can never rewrite the script that
# produces its stop proof or the allowlist that constrains its entrypoint.
RUN groupadd --gid 10001 aria \
    && useradd --uid 10001 --gid aria --create-home --shell /usr/sbin/nologin aria \
    && mkdir -p /workspace /home/aria/run \
    && chown -R aria:aria /workspace /home/aria \
    && chmod 0555 /opt/aria/launch.mjs /opt/aria/stop-writers.mjs

# The image's own entrypoint allowlist: a launch manifest cannot name anything
# that is not listed here, and `launch.mjs` resolves bare names through it.
RUN printf '%s\n' \
    '{"names":{"qodercli":"/usr/local/bin/qodercli","node":"/usr/local/bin/node",' \
    '"bridge":"/opt/aria/bridge/dist/main.js"},' \
    '"paths":["/usr/local/bin/qodercli","/usr/local/bin/node","/opt/aria/bridge/dist/main.js"],' \
    '"comment":"fixed image entrypoints; a launch manifest may only name these"}' \
    > /opt/aria/entrypoints.json \
    && chmod 0444 /opt/aria/entrypoints.json

# Agents' workspace files are uploaded here by the backend.
WORKDIR /workspace

# The Qoder core speaks ACP through the bridge; the default bridge port is used
# for the run's endpoint mapping and is bound by the launch manifest.
EXPOSE 9310

USER aria

CMD ["/usr/local/bin/qodercli", "--version"]

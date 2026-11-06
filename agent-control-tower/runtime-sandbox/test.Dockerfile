# Aria Conductor - mock-peer Sandbox test image (real-container lane)
# Builds the deterministic protocol peers (Task 7) plus the fixed run-owned
# launcher/control scripts into an image the sandbox-lifecycle E2E can create
# real sandboxes from. It exists so the OpenSandbox boundary (launch, endpoint
# authentication, pause, renewal, background-writer termination, export before
# kill) is verified against a real container WITHOUT a model provider, a
# credential or a live core CLI.
#
# Build (context = repository root):
#   docker build -t aria-conductor/runtime-sandbox-test:1.0 \
#     -f agent-control-tower/runtime-sandbox/test.Dockerfile .
#   podman build -t aria-conductor/runtime-sandbox-test:1.0 \
#     -f agent-control-tower/runtime-sandbox/test.Dockerfile .
#
# Then, with an OpenSandbox server reachable at OPEN_SANDBOX_URL:
#   node --test e2e/agent-core/sandbox-lifecycle.test.mjs
#
# The image is a TEST image: it must never be used as a production core image.
# It runs the peers unprivileged, exposes no container-runtime socket, mounts no
# host credential, and starts the peer only through /opt/aria/launch.mjs with a
# trusted launch manifest (explicit argv, shell: false).

FROM node:22-slim

# The fixed run-owned launcher and writer-control script (single source of truth
# in agent-control-tower/runtime-sandbox).
COPY agent-control-tower/runtime-sandbox/launch.mjs /opt/aria/launch.mjs
COPY agent-control-tower/runtime-sandbox/stop-writers.mjs /opt/aria/stop-writers.mjs

# The Task 7 deterministic peers. `peer-actions.mjs` resolves the scenario
# manifest as `join(PEER_DIR, '..', 'scenarios.json')`, so the layout below is
# part of the contract.
COPY agent-control-tower/act-app/src/test/resources/e2e/peers/ /opt/aria/e2e/peers/
COPY agent-control-tower/act-app/src/test/resources/e2e/scenarios.json /opt/aria/e2e/scenarios.json

RUN groupadd --gid 10001 aria \
    && useradd --uid 10001 --gid aria --create-home --shell /usr/sbin/nologin aria \
    && mkdir -p /workspace /home/aria/run \
    && chown -R aria:aria /workspace /home/aria

# /opt/aria is the control plane the core is judged by (the launcher, the
# writer-control script, the entrypoint allowlist and the peers): it stays
# root-owned and read-only, so the unprivileged core can never rewrite the
# script that produces its stop proof or the allowlist that constrains its
# entrypoint. The peers are only ever read by node.
RUN chmod 0555 /opt/aria/launch.mjs /opt/aria/stop-writers.mjs

# Entrypoints of this test image: node plus the two peer programs. The peer
# token (ARIA_PEER_CONTROL_TOKEN) is supplied per run through the launch
# manifest environment -- never baked into the image.
RUN printf '%s\n' \
    '{"names":{"node":"/usr/local/bin/node",' \
    '"mock-opencode":"/opt/aria/e2e/peers/mock-opencode.mjs",' \
    '"mock-qoder":"/opt/aria/e2e/peers/mock-qoder.mjs"},' \
    '"paths":["/usr/local/bin/node","/opt/aria/e2e/peers/mock-opencode.mjs",' \
    '"/opt/aria/e2e/peers/mock-qoder.mjs"],' \
    '"comment":"test image entrypoints; a launch manifest may only name these"}' \
    > /opt/aria/entrypoints.json \
    && chmod 0444 /opt/aria/entrypoints.json

WORKDIR /workspace
EXPOSE 4096
USER aria

# Smoke default: print the Node version the peers run on (the peers themselves
# are started per run by /opt/aria/launch.mjs, not by the image).
CMD ["node", "--version"]

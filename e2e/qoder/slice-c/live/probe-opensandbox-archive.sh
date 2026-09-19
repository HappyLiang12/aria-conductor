#!/usr/bin/env bash
# Cheap gate for the sandbox-creation failure class first seen in E5 and
# reproduced and repaired in E7/E8 (R79/R80): OpenSandbox injects execd into a
# fresh sandbox container through the podman docker-compat archive endpoint
# (PUT /v1.44/containers/{id}/archive?path=/). When that endpoint answers
# 500 {"cause":"broken pipe","message":"passing bulk input to subprocess:
# write |1: broken pipe"}, every run dies with SANDBOX_UNAVAILABLE at sandbox
# creation. The repair is `podman machine stop` + `start` (R80: 500 -> 200,
# reproduced outside the product stack).
#
# Run inside the VM from the repo root:
#   MSYS_NO_PATHCONV=1 podman machine ssh < e2e/qoder/slice-c/live/probe-opensandbox-archive.sh
# Exit: 0 and two "HTTP 200" lines = the archive path works.
set -e
echo "--- curl present: $(command -v curl || echo NO) ---"
podman rm -f aria-tar-probe >/dev/null 2>&1 || true
podman create --name aria-tar-probe localhost/aria-conductor/qoder-sandbox:0.1 sleep 60 >/dev/null
mkdir -p /tmp/probe/opt/opensandbox && echo probe > /tmp/probe/opt/opensandbox/x.txt
tar -C /tmp/probe -cf /tmp/probe.tar opt
echo "--- PUT path=/ (opensandbox flow) ---"
curl -s -o /tmp/resp1.txt -w "HTTP %{http_code}\n" --unix-socket /run/user/1000/podman/podman.sock \
  -X PUT "http://localhost/v1.44/containers/aria-tar-probe/archive?path=%2F" \
  -H "Content-Type: application/x-tar" --data-binary @/tmp/probe.tar
cat /tmp/resp1.txt; echo
echo "--- PUT path=/tmp (control) ---"
curl -s -o /tmp/resp2.txt -w "HTTP %{http_code}\n" --unix-socket /run/user/1000/podman/podman.sock \
  -X PUT "http://localhost/v1.44/containers/aria-tar-probe/archive?path=%2Ftmp" \
  -H "Content-Type: application/x-tar" --data-binary @/tmp/probe.tar
cat /tmp/resp2.txt; echo
echo "--- verify /opt/opensandbox in container ---"
podman start aria-tar-probe >/dev/null && podman exec aria-tar-probe ls -la /opt/opensandbox 2>&1 | tail -3
podman rm -f aria-tar-probe >/dev/null 2>&1 || true

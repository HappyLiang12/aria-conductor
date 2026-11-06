# Task 9b — `[server] eip` Config Experiment

**Date:** 2026-08-17  
**Environment:** podman 5.8.3 (WSL rootless), Windows 22H2 host  
**Worktree:** `C:\Users\User\.qoder\worktree\aria-conductor\fvHa7E`  
**Scope:** Verify whether OpenSandbox server's official `[server] eip` config option rewrites sandbox endpoint URLs' host part.

---

## 1. Grep Evidence

### Source inspected: two versions

| Version | Location | eip present? | host_ip present? |
|---------|----------|:---:|:---:|
| v0.1.0 (patched) | `opensandbox-server-src/src/` | ❌ | ✅ (patched) |
| v0.2.2 (latest) | Running container `/app/opensandbox_server/` | ✅ | ✅ |

### v0.1.0 — `[server] eip` NOT implemented

- `src/config.py` — `ServerConfig` class (lines 67-89) has **no `eip` field**. Fields: `host`, `port`, `log_level`, `api_key`.
- `src/services/docker.py` — `_resolve_public_host()` (lines 1190-1202) reads **only** `docker.host_ip` (patched) and `server.host` (bind interface). No `eip` reference.
- Grep `src/` for `eip`: **zero hits** in server source code.

### v0.2.2 — `[server] eip` IS implemented

File-by-file grep from inside the running `opensandbox/server:latest` container:

| File | Line | Usage |
|------|------|-------|
| `config.py` | 550 | `eip: Optional[str] = Field(...)` — field defined in `ServerConfig` |
| `api/lifecycle.py` | 573-575 | Reads `server.eip` for endpoint base URL |
| `api/proxy.py` | 185-187 | Reads `server.eip` for proxy external URL construction |
| `services/docker/networking.py` | 318-321 | `_resolve_public_host()` checks `server.eip` **first**, returns immediately if set |
| `services/docker/networking.py` | 326-328 | Falls back to `docker.host_ip` if `eip` not set |
| `services/docker/networking.py` | 335 | Doc comment: proxy resolution intentionally avoids `server.eip` |

**Key finding:** `_resolve_public_host()` in v0.2.2:
```python
def resolve_public_host(self) -> str:
    eip_cfg = (self.app_config.server.eip or "").strip()
    if eip_cfg:                          # eip wins
        return eip_cfg
    ...
    host_ip = self._get_docker_host_ip() # fallback to docker.host_ip
    if host_ip:
        return host_ip
    return self._resolve_bind_ip(...)    # last resort: auto-detect
```

**Both** `[server] eip` (highest priority) and `[docker] host_ip` (second priority) are implemented in v0.2.2.

---

## 2. Experiment Results

### Setup

- **Image:** `opensandbox/server:latest` (v0.2.2 — the *official* image, no custom patch)
- **Config:** `[server] eip = "127.0.0.1"`, `[docker] host_ip = "127.0.0.1"`, `network_mode = "bridge"`
- **Sandbox image:** `aria-conductor/opencode-sandbox:1.1` (local)
- **Command:** `podman run -d --name osb-eip-test -p 127.0.0.1:8090:8080 -v <podman.sock>:/var/run/docker.sock -e OPENSANDBOX_INSECURE_SERVER=YES -e SANDBOX_CONFIG_PATH=/etc/opensandbox/config.toml -v <config>:/etc/opensandbox/config.toml opensandbox/server:latest`

### Result table

| Check | Result |
|-------|--------|
| **Health** (`GET /health`) | `{"status":"healthy"}` |
| **Sandbox created** (`POST /v1/sandboxes`) | `id=22a45f18...`, state=Running |
| **Endpoint returned** (`GET .../endpoints/4096`) | `{"endpoint":"127.0.0.1:55908/proxy/4096"}` |
| **Windows reachability** (Invoke-WebRequest `:8090/proxy/4096`) | HTTP 404 (proxy path is reachable, no service listening on 4096) |

### Interpretation

The endpoint host is `127.0.0.1` — the value from `[server] eip`. Since `eip` has highest priority in v0.2.2's `resolve_public_host()`, this confirms `eip` is working correctly. Without `eip`, the default `_resolve_bind_ip()` would return the container's bridge network IP (e.g. `10.89.0.x`), unreachable from the Windows host.

---

## 3. Conclusion

**EIP_WORKS** (on `opensandbox/server:latest` / v0.2.2)

| Aspect | Verdict |
|--------|:-------:|
| v0.1.0 official `[server] eip` | ❌ Not implemented (no field in `ServerConfig`, unused in `_resolve_public_host()`) |
| v0.2.2 official `[server] eip` | ✅ Implemented, highest priority in host resolution |
| v0.1.0 `[docker] host_ip` | ❌ Not implemented upstream (patched in `aria-conductor/opensandbox-server:0.1.0-podman`) |
| v0.2.2 `[docker] host_ip` | ✅ Implemented, second priority fallback |

### Recommendation for podman-support strategy

**Upgrade to `opensandbox/server:latest` (v0.2.2+)**. This version natively supports both `[server] eip` and `[docker] host_ip`, eliminating the need for a custom patched image. The `host_ip` fix applied to the v0.1.0-podman patch is now available out-of-box in the official v0.2.2 release with even better configurability (`eip` at server scope → overrides `host_ip` at docker scope).

Aria Conductor's backend should:
1. Use `opensandbox/server:latest` (or `:v0.2.2`) instead of the patched v0.1.0-podman image.
2. Set `[server] eip = "127.0.0.1"` in the server config to ensure sandbox endpoint URLs resolve to the host (container-runtime host), reachable from Windows.
3. Optionally set `[docker] host_ip = "127.0.0.1"` as a consistent fallback.
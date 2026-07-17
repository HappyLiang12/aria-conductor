# Agent Control Tower

Java 21 + Spring Boot 3.3 modular monolith for governed AI agent execution.

## Local Startup

Local development is H2-only. The app uses a persistent H2 file database at `data/act_db.mv.db`.

### Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java | 21 | `java -version` |
| Maven | 3.9+ | `mvn --version` |
| Node.js | 20+ | `node --version` |
| pnpm | 9+ | `pnpm --version` |
| Python | 3.11+ | `python --version` |

Python is required for the LangChain ADK runtime. Every agent spawns a uvicorn process on a dedicated port (9300–9400). Without Python, the system falls back to calling the LLM API directly — functional but without LangChain features like native memory and streaming.

On Windows, `JAVA_HOME` often points at an older JDK. This repo needs JDK 21.

### Python venv (LangChain ADK runtime)

The backend spawns a Python uvicorn subprocess per agent for LangChain-based execution. Set up the venv once:

```powershell
cd ../langchain-adk
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

Verify:
```powershell
.venv\Scripts\python -c "from server import app; print('OK')"
```

**Worktree note:** The venv is per-worktree — create it in each worktree you use. Set `PYTHON_PATH` env var if the venv is at a non-default location:
```powershell
$env:PYTHON_PATH = "C:\absolute\path\to\.venv\Scripts\python.exe"
```

**LLM provider:** Configure via environment variables (auto-detected on first startup):
```powershell
$env:LLM_API_KEY = "sk-your-key-here"
$env:LLM_BASE_URL = "https://api.deepseek.com/v1"   # optional, default OpenAI
$env:LLM_MODEL = "deepseek-chat"                       # optional, default gpt-4o
```
Or configure via Dashboard: `POST /api/v1/llm-providers` to set your provider.

### 1. Prepare the shell

```powershell
.\scripts\setup-env.ps1
. .\scripts\setup-env.ps1 -Apply
```

### 2. Dry-run the backend startup

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1 -CheckOnly
```

The dry run checks `java`, checks `mvn` when a build would be needed, finds an open port starting at `8080`, and tells you whether the backend jar is already present.

### 3. Start the backend

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

What the script does:
- builds `act-app` before startup unless `-SkipBuild` is used
- keeps port auto-detection and writes the chosen port to both `data/.backend-port` and `.backend-port`
- starts the app with the `h2` profile

Useful flags:

```powershell
# start scanning from a different port
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1 -StartPort 8090

# reuse the existing jar and skip the build step
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1 -SkipBuild
```

`start-backend-isolated.ps1` is now just a thin wrapper over the same H2 startup path.

### 4. Database behavior

- Database file: `data/act_db.mv.db`
- JDBC URL: `jdbc:h2:file:./data/act_db`
- H2 console: `http://localhost:<backend-port>/h2-console`
- H2 console user: `sa`
- H2 console password: empty

Data persists across app restarts and machine reboots because the database is file-backed.

To reset local data:

```powershell
# stop the backend first
Remove-Item .\data\act_db.mv.db -ErrorAction SilentlyContinue
Remove-Item .\data\act_db.trace.db -ErrorAction SilentlyContinue
```

### 5. Start the frontend

```powershell
cd act-dashboard
pnpm install
pnpm dev
```

`start-frontend.ps1` reads `data/.backend-port`. `start-frontend-isolated.ps1` reads `.backend-port` in the repo root. `start-backend.ps1` now writes both files so either frontend script can discover the same backend port.

## Testing

Run from `agent-control-tower/`:

```powershell
mvn test -pl act-app
mvn test
```

## Notes

- `LLM_PROVIDER_API_KEY` is optional for local H2 startup. Leave it unset unless you are exercising real LLM calls.
- `start-backend.ps1` no longer forces a provider-specific LLM endpoint. If you need a non-default provider, set `LLM_PROVIDER_API_KEY`, `LLM_PROVIDER_BASE_URL`, and `LLM_PROVIDER_MODEL` in your shell before startup.
- An MCP SQL execution capability exists for local debugging. The `sql_execute` tool is always visible in the MCP server, but it only works while ACT runs with the `h2` runtime profile.
- Flyway is enabled in the H2 profile, so local startup and tests run against the same migration set.
- `archive/` is dead code and should stay untouched.

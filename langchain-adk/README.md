# LangChain ADK — Stateless Agent Runtime

Independent Python agent runtime for the Agent Control Tower.

## Architecture

- **Stateless**: No session/memory stored. Control Tower passes complete context each round.
- **SSE Streaming**: Real-time thinking process, tool calls, and responses via Server-Sent Events.
- **Independent Process**: Runs as a separate process, survives Control Tower restarts.

## Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Liveness check |
| `/status/{agent_id}` | GET | Real-time agent state |
| `/run` | POST | Execute agent, SSE stream of events |

## Quick Start

```bash
pip install -r requirements.txt
export LLM_API_KEY=sk-xxx
python src/server.py 9300
```

## SSE Event Types

| Event | Description |
|-------|-------------|
| `status` | Agent state change (running/thinking/idle) |
| `thinking` | LLM reasoning process |
| `tool_call` | LLM requests a tool execution |
| `tool_result` | Tool execution result |
| `response` | Final LLM response |
| `error` | Error occurred |
| `done` | Stream complete |

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_API_KEY` | (required) | DeepSeek API key |
| `LLM_BASE_URL` | `https://api.deepseek.com/v1` | OpenAI-compatible base URL |
| `LLM_DEFAULT_MODEL` | `deepseek-chat` | Default model name |
| `LLM_MAX_TOKENS` | `4096` | Max tokens per response |
| `HOST` | `127.0.0.1` | Bind address |
| `PORT` | `9300` | Listen port |

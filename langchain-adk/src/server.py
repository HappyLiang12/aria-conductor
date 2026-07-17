"""FastAPI server — 3 endpoints for the LangChain ADK.

GET  /health            — liveness check
GET  /status/{agent_id} — real-time agent state
POST /run               — execute agent, SSE stream of events
"""

from __future__ import annotations

import json
import sys
import os
import logging

# Allow running this script directly (python src/server.py) by adding
# the parent directory to sys.path so relative imports resolve.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from src.agent import get_state, run_agent_stream
from src.config import settings
from src.models import RunRequest

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(title="LangChain ADK", version="0.1.0")


@app.get("/health")
async def health():
    """Liveness probe — called by Control Tower health checks."""
    return {"status": "ok"}


@app.get("/status/{agent_id}")
async def status(agent_id: str):
    """Return the current state of an agent (idle/running/thinking/error)."""
    return get_state(agent_id)


@app.post("/run")
async def run(req: RunRequest):
    """
    Execute the agent for one round. Returns an SSE stream of events.

    Event types: status, thinking, tool_call, tool_result, response, error, done
    """
    async def event_stream():
        async for event in run_agent_stream(req):
            yield f"event: {event.event}\ndata: {json.dumps(event.data)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


if __name__ == "__main__":
    import uvicorn

    port = int(sys.argv[1]) if len(sys.argv) > 1 else settings.port
    logger.info("Starting LangChain ADK server on port %d", port)
    uvicorn.run(app, host=settings.host, port=port, log_level="info")

"""FastAPI endpoint tests via TestClient — SSE streaming, validation, status."""

import json

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

import src.agent as agent_mod
import src.server as server_mod
from src.models import AdkEvent
from src.server import app


@pytest.fixture
def client():
    return TestClient(app)


def parse_sse(body: str) -> list[tuple[str, dict]]:
    """Parse an SSE body into (event, data) tuples."""
    events = []
    for block in body.strip().split("\n\n"):
        event_name, data = None, None
        for line in block.splitlines():
            if line.startswith("event: "):
                event_name = line[len("event: "):]
            elif line.startswith("data: "):
                data = json.loads(line[len("data: "):])
        if event_name is not None:
            events.append((event_name, data))
    return events


# ── /health ─────────────────────────────────────────────────────────────────

class TestHealth:
    def test_health_returns_ok(self, client):
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json() == {"status": "ok"}


# ── /status/{agent_id} ──────────────────────────────────────────────────────

class TestStatus:
    def test_unknown_agent_is_idle(self, client):
        resp = client.get("/status/ghost-agent")
        assert resp.status_code == 200
        assert resp.json() == {"agent_id": "ghost-agent", "state": "idle"}

    def test_status_reflects_tracked_state(self, client):
        agent_mod._set_state("tracked-agent", "thinking", current_iteration=3)
        try:
            body = client.get("/status/tracked-agent").json()
            assert body["state"] == "thinking"
            assert body["current_iteration"] == 3
            assert body["agent_id"] == "tracked-agent"
        finally:
            agent_mod._agent_states.pop("tracked-agent", None)


# ── /run request validation ─────────────────────────────────────────────────

class TestRunValidation:
    def test_missing_agent_id_is_422(self, client):
        resp = client.post("/run", json={"model": "deepseek-chat"})
        assert resp.status_code == 422
        locs = [tuple(e["loc"]) for e in resp.json()["detail"]]
        assert ("body", "agent_id") in locs

    def test_wrong_type_max_tokens_is_422(self, client):
        resp = client.post("/run", json={"agent_id": "a1", "max_tokens": "many"})
        assert resp.status_code == 422

    def test_invalid_message_shape_is_422(self, client):
        resp = client.post(
            "/run",
            json={"agent_id": "a1", "messages": [{"content": "role missing"}]},
        )
        assert resp.status_code == 422

    def test_non_json_body_is_422(self, client):
        resp = client.post("/run", content="not json", headers={"Content-Type": "application/json"})
        assert resp.status_code == 422


# ── /run SSE streaming (agent mocked at server boundary) ────────────────────

@pytest.fixture
def stub_stream(monkeypatch):
    """Replace run_agent_stream in the server module with a canned event stream."""
    captured = {}

    def install(events):
        async def fake_stream(req):
            captured["request"] = req
            for ev in events:
                yield ev

        monkeypatch.setattr(server_mod, "run_agent_stream", fake_stream)
        return captured

    return install


class TestRunStreaming:
    CANNED = [
        AdkEvent(event="status", data={"state": "running"}),
        AdkEvent(event="thinking", data={"content": "hmm"}),
        AdkEvent(event="response", data={"content": "hi", "finish_reason": "stop"}),
        AdkEvent(event="done", data={}),
    ]

    def test_returns_sse_content_type_and_headers(self, client, stub_stream):
        stub_stream(self.CANNED)
        resp = client.post("/run", json={"agent_id": "a1"})
        assert resp.status_code == 200
        assert resp.headers["content-type"].startswith("text/event-stream")
        assert resp.headers["cache-control"] == "no-cache"
        assert resp.headers["x-accel-buffering"] == "no"

    def test_events_serialized_in_order_with_json_data(self, client, stub_stream):
        stub_stream(self.CANNED)
        resp = client.post("/run", json={"agent_id": "a1"})
        events = parse_sse(resp.text)
        assert [name for name, _ in events] == ["status", "thinking", "response", "done"]
        assert events[0][1] == {"state": "running"}
        assert events[2][1]["content"] == "hi"
        assert events[3][1] == {}

    def test_request_body_parsed_into_run_request(self, client, stub_stream):
        captured = stub_stream([AdkEvent(event="done", data={})])
        payload = {
            "agent_id": "agent-7",
            "session_id": "s-9",
            "model": "gpt-4o",
            "messages": [{"role": "user", "content": "hello"}],
            "temperature": 0.2,
        }
        client.post("/run", json=payload)
        req = captured["request"]
        assert req.agent_id == "agent-7"
        assert req.session_id == "s-9"
        assert req.model == "gpt-4o"
        assert req.messages[0].content == "hello"
        assert req.temperature == 0.2
        # defaults applied for omitted fields
        assert req.max_tokens == 1024

    def test_error_events_streamed_not_http_error(self, client, stub_stream):
        stub_stream([
            AdkEvent(event="error", data={"message": "llm down"}),
            AdkEvent(event="done", data={}),
        ])
        resp = client.post("/run", json={"agent_id": "a1"})
        # transport stays 200; the failure travels inside the stream
        assert resp.status_code == 200
        events = parse_sse(resp.text)
        assert events[0] == ("error", {"message": "llm down"})
        assert events[-1][0] == "done"


# ── /run end-to-end through the real agent (only ChatOpenAI faked) ──────────

class TestRunEndToEnd:
    def test_full_pipeline_with_fake_llm(self, client, monkeypatch):
        class FakeLLM:
            def bind_tools(self, tools):
                return self

            async def ainvoke(self, messages):
                return AIMessage(
                    content="final answer",
                    tool_calls=[{"id": "c1", "name": "search", "args": {"q": "x"}}],
                )

        monkeypatch.setattr(agent_mod, "ChatOpenAI", lambda **kw: FakeLLM())

        resp = client.post(
            "/run",
            json={
                "agent_id": "e2e-agent",
                "messages": [{"role": "user", "content": "go"}],
                "tools": [{"name": "search", "description": "d", "parameters": {}}],
            },
        )
        assert resp.status_code == 200
        events = parse_sse(resp.text)
        names = [name for name, _ in events]
        assert names == ["status", "status", "thinking", "tool_call", "response", "done"]
        tool_call = dict(events)["tool_call"]
        assert tool_call["name"] == "search"
        assert json.loads(tool_call["arguments"]) == {"q": "x"}
        response = dict(events)["response"]
        assert response["content"] == "final answer"

    def test_full_pipeline_llm_failure_streams_error(self, client, monkeypatch):
        class ExplodingLLM:
            async def ainvoke(self, messages):
                raise RuntimeError("connection refused")

        monkeypatch.setattr(agent_mod, "ChatOpenAI", lambda **kw: ExplodingLLM())

        resp = client.post("/run", json={"agent_id": "e2e-fail"})
        assert resp.status_code == 200
        events = parse_sse(resp.text)
        names = [name for name, _ in events]
        assert "error" in names
        assert names[-1] == "done"
        assert "connection refused" in dict(events)["error"]["message"]

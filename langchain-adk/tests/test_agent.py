"""Agent logic tests — message conversion, tool parsing, streaming with a fake LLM."""

import asyncio
import json
from types import SimpleNamespace

import pytest
from langchain_core.messages import (
    AIMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)

import src.agent as agent_mod
from src.agent import (
    get_state,
    parse_tool_calls,
    run_agent_stream,
    to_langchain_messages,
    to_langchain_tools,
)
from src.models import Message, RunRequest, ToolDefinition


# ── Fake LLM plumbing (no real network) ─────────────────────────────────────

class FakeLLM:
    """Stands in for ChatOpenAI: records constructor kwargs and bound tools."""

    def __init__(self, response=None, error=None):
        self.response = response if response is not None else AIMessage(content="ok")
        self.error = error
        self.init_kwargs = {}
        self.bound_tools = None

    def bind_tools(self, tools):
        self.bound_tools = tools
        return self

    async def ainvoke(self, messages):
        self.invoked_messages = messages
        if self.error:
            raise self.error
        return self.response


@pytest.fixture
def fake_llm(monkeypatch):
    fake = FakeLLM()

    def factory(**kwargs):
        fake.init_kwargs = kwargs
        return fake

    monkeypatch.setattr(agent_mod, "ChatOpenAI", factory)
    return fake


def collect_events(req: RunRequest) -> list:
    async def _collect():
        return [ev async for ev in run_agent_stream(req)]

    return asyncio.run(_collect())


# ── to_langchain_messages ───────────────────────────────────────────────────

class TestToLangchainMessages:
    def test_role_mapping(self):
        result = to_langchain_messages([
            Message(role="system", content="sys"),
            Message(role="user", content="usr"),
            Message(role="assistant", content="ast"),
            Message(role="tool", content="42", tool_call_id="c1"),
        ])
        assert [type(m) for m in result] == [SystemMessage, HumanMessage, AIMessage, ToolMessage]
        assert result[0].content == "sys"
        assert result[3].tool_call_id == "c1"

    def test_unknown_role_falls_back_to_human(self):
        result = to_langchain_messages([Message(role="wizard", content="abracadabra")])
        assert isinstance(result[0], HumanMessage)
        assert result[0].content == "abracadabra"

    def test_none_content_becomes_empty_string(self):
        result = to_langchain_messages([
            Message(role="user"),
            Message(role="assistant"),
            Message(role="tool", tool_call_id="c1"),
        ])
        assert all(m.content == "" for m in result)

    def test_invalid_json_tool_arguments_become_empty_args(self):
        msg = Message(
            role="assistant",
            content="",
            tool_calls=[{"id": "c1", "name": "t", "arguments": "not json {"}],
        )
        result = to_langchain_messages([msg])
        assert result[0].tool_calls[0]["args"] == {}

    def test_empty_arguments_string_becomes_empty_args(self):
        msg = Message(
            role="assistant",
            tool_calls=[{"id": "c1", "name": "t", "arguments": ""}],
        )
        result = to_langchain_messages([msg])
        assert result[0].tool_calls[0]["args"] == {}

    def test_empty_input_yields_empty_output(self):
        assert to_langchain_messages([]) == []


# ── parse_tool_calls ────────────────────────────────────────────────────────

class TestParseToolCalls:
    def test_modern_tool_calls_format(self):
        response = AIMessage(
            content="",
            tool_calls=[{"id": "c1", "name": "web_search", "args": {"q": "cats"}}],
        )
        result = parse_tool_calls(response)
        assert len(result) == 1
        assert result[0].id == "c1"
        assert result[0].name == "web_search"
        assert json.loads(result[0].arguments) == {"q": "cats"}

    def test_legacy_additional_kwargs_format(self):
        # AIMessage normalizes additional_kwargs into modern tool_calls, so use a
        # bare stub to genuinely exercise the legacy OpenAI-format branch.
        response = SimpleNamespace(
            tool_calls=[],
            additional_kwargs={
                "tool_calls": [
                    {"id": "c9", "function": {"name": "calc", "arguments": '{"x":1}'}},
                ]
            },
        )
        result = parse_tool_calls(response)
        assert len(result) == 1
        assert result[0].id == "c9"
        assert result[0].name == "calc"
        assert result[0].arguments == '{"x":1}'

    def test_legacy_format_missing_function_fields_defaults(self):
        response = SimpleNamespace(tool_calls=[], additional_kwargs={"tool_calls": [{"id": "c2"}]})
        result = parse_tool_calls(response)
        assert result[0].name == ""
        assert result[0].arguments == "{}"

    def test_no_tool_calls_returns_empty(self):
        assert parse_tool_calls(AIMessage(content="plain answer")) == []


# ── to_langchain_tools ──────────────────────────────────────────────────────

class TestToLangchainTools:
    def test_openai_wrapper_format_unwrapped(self):
        tools = [{
            "type": "function",
            "function": {"name": "t1", "description": "d1", "parameters": {"type": "object"}},
        }]
        result = to_langchain_tools(tools)
        assert result == [{
            "type": "function",
            "function": {"name": "t1", "description": "d1", "parameters": {"type": "object"}},
        }]

    def test_flat_dict_format_wrapped(self):
        result = to_langchain_tools([{"name": "t2", "description": "d2"}])
        assert result[0]["type"] == "function"
        assert result[0]["function"]["name"] == "t2"
        assert result[0]["function"]["parameters"] == {}

    def test_pydantic_tool_definition_converted(self):
        td = ToolDefinition(name="t3", description="d3", parameters={"type": "object"})
        result = to_langchain_tools([td])
        assert result[0]["function"]["name"] == "t3"
        assert result[0]["function"]["parameters"] == {"type": "object"}

    def test_mixed_formats_in_one_call(self):
        tools = [
            {"name": "flat", "description": "f"},
            {"type": "function", "function": {"name": "wrapped", "description": "w"}},
            ToolDefinition(name="model", description="m"),
        ]
        names = [t["function"]["name"] for t in to_langchain_tools(tools)]
        assert names == ["flat", "wrapped", "model"]

    def test_missing_fields_default_to_empty(self):
        result = to_langchain_tools([{"function": {}}])
        assert result[0]["function"] == {"name": "", "description": "", "parameters": {}}


# ── state tracking ──────────────────────────────────────────────────────────

class TestStateTracking:
    def test_unknown_agent_reports_idle(self):
        assert get_state("never-seen") == {"agent_id": "never-seen", "state": "idle"}

    def test_run_updates_state_to_idle_after_success(self, fake_llm):
        collect_events(RunRequest(agent_id="agent-state-ok"))
        state = get_state("agent-state-ok")
        assert state["state"] == "idle"
        assert state["current_iteration"] == 0
        assert "last_activity" in state

    def test_run_updates_state_to_error_on_failure(self, fake_llm):
        fake_llm.error = RuntimeError("llm exploded")
        collect_events(RunRequest(agent_id="agent-state-err"))
        state = get_state("agent-state-err")
        assert state["state"] == "error"
        assert state["error"] == "llm exploded"


# ── run_agent_stream ────────────────────────────────────────────────────────

class TestRunAgentStream:
    def test_happy_path_event_sequence(self, fake_llm):
        fake_llm.response = AIMessage(content="the answer")
        events = collect_events(RunRequest(agent_id="a1"))
        assert [e.event for e in events] == ["status", "status", "thinking", "response", "done"]
        assert events[0].data["state"] == "running"
        assert events[1].data["state"] == "thinking"
        assert events[2].data["content"] == "the answer"
        assert events[3].data["content"] == "the answer"
        assert events[3].data["finish_reason"] == "stop"
        assert events[3].data["tool_calls"] == []

    def test_empty_content_skips_thinking_event(self, fake_llm):
        fake_llm.response = AIMessage(content="")
        events = collect_events(RunRequest(agent_id="a2"))
        assert "thinking" not in [e.event for e in events]
        assert events[-2].event == "response"
        assert events[-1].event == "done"

    def test_tool_calls_emitted_as_events(self, fake_llm):
        fake_llm.response = AIMessage(
            content="using tools",
            tool_calls=[
                {"id": "c1", "name": "search", "args": {"q": "a"}},
                {"id": "c2", "name": "calc", "args": {"x": 1}},
            ],
        )
        events = collect_events(RunRequest(agent_id="a3"))
        tool_events = [e for e in events if e.event == "tool_call"]
        assert [e.data["name"] for e in tool_events] == ["search", "calc"]
        assert json.loads(tool_events[1].data["arguments"]) == {"x": 1}
        response = next(e for e in events if e.event == "response")
        assert [tc["id"] for tc in response.data["tool_calls"]] == ["c1", "c2"]

    def test_tools_bound_when_provided(self, fake_llm):
        req = RunRequest(
            agent_id="a4",
            tools=[{"name": "web_search", "description": "d", "parameters": {}}],
        )
        collect_events(req)
        assert fake_llm.bound_tools is not None
        assert fake_llm.bound_tools[0]["function"]["name"] == "web_search"

    def test_no_tools_means_no_binding(self, fake_llm):
        collect_events(RunRequest(agent_id="a5"))
        assert fake_llm.bound_tools is None

    def test_per_request_llm_credentials_override_settings(self, fake_llm):
        req = RunRequest(
            agent_id="a6",
            model="custom-model",
            max_tokens=77,
            llm_api_key="sk-per-request",
            llm_base_url="http://per-request:1/v1",
        )
        collect_events(req)
        assert fake_llm.init_kwargs["model"] == "custom-model"
        assert fake_llm.init_kwargs["api_key"] == "sk-per-request"
        assert fake_llm.init_kwargs["base_url"] == "http://per-request:1/v1"
        assert fake_llm.init_kwargs["max_tokens"] == 77

    def test_settings_used_when_no_per_request_credentials(self, fake_llm, monkeypatch):
        monkeypatch.setattr(agent_mod.settings, "llm_api_key", "sk-env")
        monkeypatch.setattr(agent_mod.settings, "llm_base_url", "http://env:2/v1")
        collect_events(RunRequest(agent_id="a7"))
        assert fake_llm.init_kwargs["api_key"] == "sk-env"
        assert fake_llm.init_kwargs["base_url"] == "http://env:2/v1"

    def test_messages_converted_before_invoke(self, fake_llm):
        req = RunRequest(
            agent_id="a8",
            messages=[
                {"role": "system", "content": "be nice"},
                {"role": "user", "content": "hello"},
            ],
        )
        collect_events(req)
        assert isinstance(fake_llm.invoked_messages[0], SystemMessage)
        assert isinstance(fake_llm.invoked_messages[1], HumanMessage)
        assert fake_llm.invoked_messages[1].content == "hello"

    def test_usage_metadata_propagated_to_response(self, fake_llm):
        fake_llm.response = AIMessage(
            content="counted",
            usage_metadata={"input_tokens": 11, "output_tokens": 22, "total_tokens": 33},
        )
        events = collect_events(RunRequest(agent_id="a9"))
        response = next(e for e in events if e.event == "response")
        assert response.data["usage"] == {"input_tokens": 11, "output_tokens": 22}

    def test_llm_failure_yields_error_then_done(self, fake_llm):
        fake_llm.error = ValueError("bad key")
        events = collect_events(RunRequest(agent_id="a10"))
        kinds = [e.event for e in events]
        assert kinds == ["status", "status", "error", "done"]
        error_event = events[2]
        assert "bad key" in error_event.data["message"]
        assert "timestamp" in error_event.data

    def test_done_is_always_last_event(self, fake_llm):
        ok_events = collect_events(RunRequest(agent_id="a11"))
        fake_llm.error = RuntimeError("boom")
        err_events = collect_events(RunRequest(agent_id="a12"))
        assert ok_events[-1].event == "done"
        assert err_events[-1].event == "done"
        assert ok_events[-1].data == {}

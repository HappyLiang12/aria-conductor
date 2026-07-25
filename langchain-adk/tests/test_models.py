"""Pydantic model validation tests for the ADK protocol."""

import pytest
from pydantic import ValidationError

from src.models import (
    AdkEvent,
    Context,
    Message,
    RunRequest,
    RunResponse,
    StatusResponse,
    ToolCall,
    ToolDefinition,
)


# ── ToolDefinition ──────────────────────────────────────────────────────────

class TestToolDefinition:
    def test_minimal_construction_applies_defaults(self):
        td = ToolDefinition(name="search", description="Search the web")
        assert td.parameters == {}
        assert td.strict is False

    def test_missing_name_rejected(self):
        with pytest.raises(ValidationError) as exc:
            ToolDefinition(description="no name")
        assert any(e["loc"] == ("name",) for e in exc.value.errors())

    def test_missing_description_rejected(self):
        with pytest.raises(ValidationError):
            ToolDefinition(name="only-name")

    def test_parameters_must_be_dict(self):
        with pytest.raises(ValidationError):
            ToolDefinition(name="t", description="d", parameters=["not", "a", "dict"])

    def test_serialization_round_trip(self):
        td = ToolDefinition(
            name="calc",
            description="Calculator",
            parameters={"type": "object", "properties": {"expr": {"type": "string"}}},
            strict=True,
        )
        restored = ToolDefinition.model_validate(td.model_dump())
        assert restored == td
        assert restored.parameters["properties"]["expr"] == {"type": "string"}


# ── Message / ToolCall ──────────────────────────────────────────────────────

class TestMessage:
    def test_role_required(self):
        with pytest.raises(ValidationError) as exc:
            Message(content="hi")
        assert any(e["loc"] == ("role",) for e in exc.value.errors())

    def test_optional_fields_default_to_none(self):
        msg = Message(role="user")
        assert msg.content is None
        assert msg.tool_call_id is None
        assert msg.tool_calls is None

    def test_nested_tool_calls_coerced_from_dicts(self):
        msg = Message(
            role="assistant",
            content="calling",
            tool_calls=[{"id": "c1", "name": "search", "arguments": '{"q":"x"}'}],
        )
        assert isinstance(msg.tool_calls[0], ToolCall)
        assert msg.tool_calls[0].name == "search"

    def test_invalid_nested_tool_call_rejected(self):
        with pytest.raises(ValidationError):
            Message(role="assistant", tool_calls=[{"id": "c1"}])  # missing name/arguments

    def test_round_trip_preserves_tool_calls(self):
        msg = Message(
            role="assistant",
            tool_calls=[ToolCall(id="c1", name="t", arguments="{}")],
        )
        restored = Message.model_validate(msg.model_dump())
        assert restored.tool_calls[0].id == "c1"
        assert restored == msg


class TestToolCall:
    def test_all_fields_required(self):
        with pytest.raises(ValidationError) as exc:
            ToolCall(id="c1", name="t")
        assert any(e["loc"] == ("arguments",) for e in exc.value.errors())

    def test_arguments_is_string_not_dict(self):
        # protocol carries arguments as a JSON *string*; dicts must be rejected
        with pytest.raises(ValidationError):
            ToolCall(id="c1", name="t", arguments={"q": "x"})


# ── Context ─────────────────────────────────────────────────────────────────

class TestContext:
    def test_defaults(self):
        ctx = Context()
        assert ctx.allowed_tools == []
        assert ctx.access_level == "operator"
        assert ctx.session_carryover == {}

    def test_default_factories_are_not_shared(self):
        a, b = Context(), Context()
        a.allowed_tools.append("web_search")
        assert b.allowed_tools == []

    def test_accepts_empty_object_from_control_tower(self):
        ctx = Context.model_validate({})
        assert ctx.access_level == "operator"


# ── RunRequest ──────────────────────────────────────────────────────────────

class TestRunRequest:
    def test_minimal_request_defaults(self):
        req = RunRequest(agent_id="a1")
        assert req.session_id == ""
        assert req.model == "deepseek-chat"
        assert req.messages == []
        assert req.tools == []
        assert req.max_tokens == 1024
        assert req.temperature == 0.7
        assert isinstance(req.context, Context)
        assert req.llm_api_key is None
        assert req.llm_base_url is None

    def test_agent_id_required(self):
        with pytest.raises(ValidationError) as exc:
            RunRequest()
        assert any(e["loc"] == ("agent_id",) for e in exc.value.errors())

    def test_non_numeric_max_tokens_rejected(self):
        with pytest.raises(ValidationError):
            RunRequest(agent_id="a1", max_tokens="lots")

    def test_messages_validated_as_message_models(self):
        req = RunRequest(agent_id="a1", messages=[{"role": "user", "content": "hi"}])
        assert isinstance(req.messages[0], Message)
        with pytest.raises(ValidationError):
            RunRequest(agent_id="a1", messages=[{"content": "no role"}])

    def test_tools_accept_openai_wrapper_dicts(self):
        # tools is intentionally list[dict] to accept both formats
        wrapper = {"type": "function", "function": {"name": "t", "description": "d", "parameters": {}}}
        req = RunRequest(agent_id="a1", tools=[wrapper])
        assert req.tools[0]["function"]["name"] == "t"

    def test_full_round_trip(self):
        req = RunRequest(
            agent_id="a1",
            session_id="s1",
            model="gpt-4o",
            messages=[Message(role="user", content="hi")],
            max_tokens=99,
            temperature=0.1,
            context=Context(allowed_tools=["x"], access_level="admin"),
            llm_api_key="sk-test",
            llm_base_url="http://localhost:9999/v1",
        )
        restored = RunRequest.model_validate(req.model_dump())
        assert restored == req
        assert restored.context.access_level == "admin"


# ── RunResponse / StatusResponse / AdkEvent ─────────────────────────────────

class TestRunResponse:
    def test_defaults(self):
        resp = RunResponse(content="done")
        assert resp.tool_calls == []
        assert resp.finish_reason == "stop"
        assert resp.usage == {}

    def test_content_required(self):
        with pytest.raises(ValidationError):
            RunResponse()

    def test_usage_values_must_be_ints(self):
        with pytest.raises(ValidationError):
            RunResponse(content="x", usage={"input_tokens": "many"})


class TestStatusResponse:
    def test_defaults(self):
        sr = StatusResponse(agent_id="a1")
        assert sr.state == "idle"
        assert sr.current_iteration == 0
        assert sr.last_activity is None
        assert sr.metrics == {}

    def test_agent_id_required(self):
        with pytest.raises(ValidationError):
            StatusResponse()


class TestAdkEvent:
    def test_event_and_data_required(self):
        with pytest.raises(ValidationError) as exc:
            AdkEvent(event="status")
        assert any(e["loc"] == ("data",) for e in exc.value.errors())

    def test_data_must_be_dict(self):
        with pytest.raises(ValidationError):
            AdkEvent(event="status", data="running")

    def test_round_trip(self):
        ev = AdkEvent(event="tool_call", data={"id": "c1", "name": "t"})
        restored = AdkEvent.model_validate(ev.model_dump())
        assert restored == ev

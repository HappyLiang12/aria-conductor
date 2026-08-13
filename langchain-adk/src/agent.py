"""Core agent logic — pure function, fully stateless.

Control Tower passes complete messages + context each round.
The agent process stores no session/memory state.
"""

from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timezone
from typing import AsyncIterator

from langchain_core.messages import (
    AIMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_openai import ChatOpenAI

from .config import settings
from .models import AdkEvent, Message, RunRequest, ToolCall, ToolDefinition

logger = logging.getLogger(__name__)

# ── F16 fallback parser: precompiled patterns (never compiled per-call) ─────
_FALLBACK_MAX_BYTES = 50 * 1024
_FALLBACK_MAX_CALLS = 50

_RE_TOOL_CALL_TAG = re.compile(
    r'<tool_call\b[^>]*?\bname\s*=\s*["\']([^"\']+)["\'][^>]*?>(.*?)</tool_call\s*>',
    re.IGNORECASE | re.DOTALL,
)
_RE_INVOKE_TAG = re.compile(
    r'<invoke\b[^>]*?\bname\s*=\s*["\']([^"\']+)["\'][^>]*?>(.*?)</invoke\s*>',
    re.IGNORECASE | re.DOTALL,
)
_RE_MARKDOWN_JSON_FENCE = re.compile(
    r'```(?:json|js)\s*\n(.*?)```',
    re.IGNORECASE | re.DOTALL,
)


# ── In-memory status tracking (not persisted, per-process only) ─────────────
_agent_states: dict[str, dict] = {}


def _set_state(agent_id: str, state: str, **extra):
    _agent_states[agent_id] = {
        "agent_id": agent_id,
        "state": state,
        "last_activity": datetime.now(timezone.utc).isoformat(),
        **extra,
    }


def get_state(agent_id: str) -> dict:
    return _agent_states.get(agent_id, {"agent_id": agent_id, "state": "idle"})


# ── Message conversion ──────────────────────────────────────────────────────

def to_langchain_messages(messages: list[Message]):
    """Convert protocol messages to LangChain message objects."""
    result = []
    for msg in messages:
        if msg.role == "system":
            result.append(SystemMessage(content=msg.content or ""))
        elif msg.role == "user":
            result.append(HumanMessage(content=msg.content or ""))
        elif msg.role == "assistant":
            # Build AIMessage with tool_calls if present (required by DeepSeek ordering)
            kwargs = {"content": msg.content or ""}
            if msg.tool_calls:
                lc_tool_calls = []
                for tc in msg.tool_calls:
                    try:
                        args = json.loads(tc.arguments) if tc.arguments else {}
                    except (json.JSONDecodeError, TypeError):
                        args = {}
                    lc_tool_calls.append({
                        "id": tc.id,
                        "name": tc.name,
                        "args": args,
                    })
                kwargs["tool_calls"] = lc_tool_calls
            result.append(AIMessage(**kwargs))
        elif msg.role == "tool":
            result.append(ToolMessage(content=msg.content or "", tool_call_id=msg.tool_call_id or ""))
        else:
            # Fallback — treat as human message
            result.append(HumanMessage(content=msg.content or ""))
    return result


def parse_tool_calls(response: AIMessage) -> list[ToolCall]:
    """Extract tool calls from a LangChain AIMessage."""
    tool_calls = []
    if hasattr(response, "tool_calls") and response.tool_calls:
        for tc in response.tool_calls:
            tool_calls.append(ToolCall(
                id=tc.get("id", ""),
                name=tc.get("name", ""),
                arguments=json.dumps(tc.get("args", {})),
            ))
    elif hasattr(response, "additional_kwargs") and response.additional_kwargs.get("tool_calls"):
        # Legacy OpenAI format
        for tc in response.additional_kwargs["tool_calls"]:
            fn = tc.get("function", {})
            tool_calls.append(ToolCall(
                id=tc.get("id", ""),
                name=fn.get("name", ""),
                arguments=fn.get("arguments", "{}"),
            ))
    return tool_calls




def _normalize_call(name, arguments):
    """Coerce a (name, arguments) pair into a valid name + JSON argument string."""
    name = (name or "").strip()
    if isinstance(arguments, str):
        args_str = arguments.strip()
        if not args_str:
            args_str = "{}"
        else:
            try:
                json.loads(args_str)
            except (json.JSONDecodeError, TypeError):
                args_str = json.dumps(arguments)
    elif arguments is None:
        args_str = "{}"
    else:
        args_str = json.dumps(arguments)
    return name, args_str


def _extract_from_json(data):
    """Yield (name, arguments) pairs from a parsed JSON dict or list of dicts."""
    calls = []
    if isinstance(data, dict):
        name = data.get("name")
        if name:
            calls.append((name, data.get("arguments", {})))
    elif isinstance(data, list):
        for item in data:
            if isinstance(item, dict) and item.get("name"):
                calls.append((item["name"], item.get("arguments", {})))
    return calls


def _iter_json_object_spans(text):
    """Yield balanced {...} spans (string-aware) for direct-call JSON detection."""
    idx = 0
    length = len(text)
    while idx < length:
        start = text.find("{", idx)
        if start == -1:
            return
        depth = 0
        in_string = False
        escape = False
        end = -1
        for i in range(start, length):
            ch = text[i]
            if in_string:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_string = False
            else:
                if ch == '"':
                    in_string = True
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        end = i
                        break
        if end == -1:
            return
        yield text[start : end + 1]
        idx = end + 1


def _parse_xml_dsml(content):
    """Extract tool calls from XML/DSML forms (<tool_call> and <invoke>)."""
    calls = []
    for m in _RE_TOOL_CALL_TAG.finditer(content):
        calls.append((m.group(1), m.group(2)))
    for m in _RE_INVOKE_TAG.finditer(content):
        calls.append((m.group(1), m.group(2)))
    return calls


def _parse_markdown_json(content):
    """Extract tool calls from ```json fenced blocks containing name/arguments."""
    calls = []
    for m in _RE_MARKDOWN_JSON_FENCE.finditer(content):
        raw = m.group(1).strip()
        try:
            data = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        calls.extend(_extract_from_json(data))
    return calls


def _parse_direct_call(content):
    """Extract tool calls from bare JSON objects embedded in free text."""
    calls = []
    for raw in _iter_json_object_spans(content):
        try:
            data = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        calls.extend(_extract_from_json(data))
    return calls


def parse_tool_calls_from_text(content, bound_tool_names=None) -> list[ToolCall]:
    """Fallback parser: recover tool calls from plain-text model output.

    Weak models sometimes emit tool calls as XML/DSML or Markdown JSON instead
    of native `tool_calls`. This mirrors the structured parser for those cases.
    """
    if not content or len(content) > _FALLBACK_MAX_BYTES:
        return []

    whitelist = None
    if bound_tool_names is not None:
        whitelist = {getattr(n, "name", n) for n in bound_tool_names if getattr(n, "name", n)}

    raw = _parse_xml_dsml(content) + _parse_markdown_json(content)
    if not raw:
        raw = _parse_direct_call(content)

    result = []
    for name, arguments in raw:
        name, arguments = _normalize_call(name, arguments)
        if not name:
            continue
        if whitelist is not None and name not in whitelist:
            continue
        result.append(ToolCall(id=f"fallback-{len(result)}", name=name, arguments=arguments))
        if len(result) >= _FALLBACK_MAX_CALLS:
            break

    if result:
        logger.warning(
            "Fallback parser recovered %d tool call(s) from text output", len(result)
        )

    return result


def to_langchain_tools(tools: list[dict]) -> list[dict]:
    """Convert tool definitions to LangChain bind_tools format.

    Accepts Pydantic ToolDefinition objects, flat dicts {name, description, parameters},
    AND OpenAI wrapper dicts {type, function: {name, description, parameters}}.
    """
    result = []
    for t in tools:
        # Handle Pydantic ToolDefinition objects (convert to dict first)
        if hasattr(t, 'name') and hasattr(t, 'description') and not isinstance(t, dict):
            t = {"name": t.name, "description": t.description, "parameters": getattr(t, 'parameters', {})}

        if "function" in t:
            # OpenAI wrapper format — extract the inner function
            fn = t["function"]
            result.append({
                "type": "function",
                "function": {
                    "name": fn.get("name", ""),
                    "description": fn.get("description", ""),
                    "parameters": fn.get("parameters", {}),
                }
            })
        else:
            # Flat ToolDefinition format
            result.append({
                "type": "function",
                "function": {
                    "name": t.get("name", ""),
                    "description": t.get("description", ""),
                    "parameters": t.get("parameters", {}),
                }
            })
    return result

# ── Core agent function ─────────────────────────────────────────────────────

async def run_agent_stream(req: RunRequest) -> AsyncIterator[AdkEvent]:
    """
    Pure function — no state stored between calls.
    Control Tower passes complete messages + context each round.

    Yields SSE events:
      status → thinking → [tool_call ...] → response → done
    """
    _set_state(req.agent_id, "running", current_iteration=1)

    yield AdkEvent(
        event="status",
        data={
            "state": "running",
            "iteration": 1,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        },
    )

    try:
        # Build the LLM client — fresh each call (stateless)
        llm = ChatOpenAI(
            model=req.model,
            api_key=req.llm_api_key or settings.llm_api_key,
            base_url=req.llm_base_url or settings.llm_base_url,
            max_tokens=req.max_tokens,
            timeout=settings.llm_timeout,
        )

        # Convert messages to LangChain format
        lc_messages = to_langchain_messages(req.messages)

        _set_state(req.agent_id, "thinking", current_iteration=1)

        yield AdkEvent(
            event="status",
            data={"state": "thinking", "timestamp": datetime.now(timezone.utc).isoformat()},
        )

        # Bind tools to LLM if tools are provided
        effective_llm = llm
        if req.tools:
            langchain_tools = to_langchain_tools(req.tools)
            effective_llm = llm.bind_tools(langchain_tools)
            logger.info(f"Bound {len(langchain_tools)} tools to LLM call")

        # Call the LLM (non-streaming for simplicity; can be upgraded to streaming)
        response: AIMessage = await effective_llm.ainvoke(lc_messages)

        # Parse tool calls
        tool_calls = parse_tool_calls(response)

        content = response.content if isinstance(response.content, str) else str(response.content)

        # F16: weak models may emit tool calls as plain text (XML/DSML or JSON)
        # instead of native tool_calls. When structured parsing finds nothing but
        # tools are bound and the model produced text, attempt the fallback parser.
        if not tool_calls and req.tools and content:
            bound_tool_names = [t["function"]["name"] for t in to_langchain_tools(req.tools)]
            fallback = parse_tool_calls_from_text(content, bound_tool_names=bound_tool_names)
            if fallback:
                tool_calls = fallback
                logger.info("Fallback parser recovered %d tool call(s)", len(tool_calls))

        # Yield thinking event with the LLM's reasoning (if available)
        if content:
            yield AdkEvent(
                event="thinking",
                data={"content": content, "timestamp": datetime.now(timezone.utc).isoformat()},
            )

        # Yield tool_call events
        for tc in tool_calls:
            yield AdkEvent(
                event="tool_call",
                data={
                    "id": tc.id,
                    "name": tc.name,
                    "arguments": tc.arguments,
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                },
            )

        # Build usage info
        usage = {}
        if hasattr(response, "usage_metadata") and response.usage_metadata:
            usage = {
                "input_tokens": response.usage_metadata.get("input_tokens", 0),
                "output_tokens": response.usage_metadata.get("output_tokens", 0),
            }

        # Yield the final response
        yield AdkEvent(
            event="response",
            data={
                "content": content,
                "tool_calls": [tc.model_dump() for tc in tool_calls],
                "finish_reason": "stop",
                "usage": usage,
            },
        )

        _set_state(req.agent_id, "idle", current_iteration=0, metrics=usage)

    except Exception as e:
        logger.exception("Agent execution failed for agent %s", req.agent_id)
        _set_state(req.agent_id, "error", error=str(e))
        yield AdkEvent(
            event="error",
            data={
                "message": str(e),
                "timestamp": datetime.now(timezone.utc).isoformat(),
            },
        )

    finally:
        yield AdkEvent(event="done", data={})

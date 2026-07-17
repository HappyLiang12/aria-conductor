"""Pydantic models for the LangChain ADK protocol."""

from __future__ import annotations

from pydantic import BaseModel, Field
from typing import Any, Optional


class ToolDefinition(BaseModel):
    """OpenAI function-calling tool definition."""
    name: str
    description: str
    parameters: dict[str, Any] = Field(default_factory=dict)
    strict: bool = False


class Message(BaseModel):
    """A single message in the conversation history."""

    role: str = Field(description="system | user | assistant | tool")
    content: Optional[str] = Field(default=None, description="Message text content")
    tool_call_id: Optional[str] = Field(default=None, description="ID of the tool call this message responds to")
    tool_calls: Optional[list[ToolCall]] = Field(default=None, description="Tool calls made by the assistant")


class ToolCall(BaseModel):
    """A tool/function call made by the LLM."""

    id: str
    name: str
    arguments: str  # JSON string of arguments


class Context(BaseModel):
    """RBAC and session context -- passed fresh each round by Control Tower.

    This is the extension point for future RBAC enforcement.
    Currently passed as an empty object by Control Tower.
    """

    allowed_tools: list[str] = Field(default_factory=list)
    access_level: str = Field(default="operator")
    session_carryover: dict[str, Any] = Field(default_factory=dict)


class RunRequest(BaseModel):
    """POST /run request body — fully self-contained, no server-side state."""

    agent_id: str
    session_id: str = ""
    model: str = "deepseek-chat"
    messages: list[Message] = Field(default_factory=list)
    tools: list[dict[str, Any]] = Field(default_factory=list)  # Accepts both ToolDefinition and OpenAI wrapper format
    max_tokens: int = 1024
    temperature: float = 0.7
    context: Context = Field(default_factory=Context)
    llm_api_key: Optional[str] = Field(default=None, description="Per-request LLM API key (overrides env)")
    llm_base_url: Optional[str] = Field(default=None, description="Per-request LLM base URL (overrides env)")


class RunResponse(BaseModel):
    """Final response after SSE stream completes (optional summary)."""

    content: str
    tool_calls: list[ToolCall] = Field(default_factory=list)
    finish_reason: str = Field(default="stop")
    usage: dict[str, int] = Field(default_factory=dict)


class StatusResponse(BaseModel):
    """GET /status/{agent_id} response."""

    agent_id: str
    state: str = Field(default="idle", description="idle | running | thinking | error")
    current_iteration: int = Field(default=0)
    last_activity: Optional[str] = Field(default=None)
    metrics: dict[str, Any] = Field(default_factory=dict)


class AdkEvent(BaseModel):
    """A single SSE event emitted during /run streaming."""

    event: str = Field(description="status | thinking | tool_call | tool_result | response | error | done")
    data: dict[str, Any]

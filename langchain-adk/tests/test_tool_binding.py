from src.models import ToolDefinition, Message, ToolCall
from src.agent import to_langchain_tools, to_langchain_messages


def test_to_langchain_tools_format():
    tools = [
        ToolDefinition(
            name="web_search",
            description="Search the web",
            parameters={"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}
        )
    ]
    result = to_langchain_tools(tools)
    assert len(result) == 1
    assert result[0]["type"] == "function"
    assert result[0]["function"]["name"] == "web_search"


def test_empty_tools():
    assert to_langchain_tools([]) == []


def test_to_langchain_messages_preserves_tool_calls():
    """Assistant messages with tool_calls must carry them into AIMessage for DeepSeek ordering."""
    messages = [
        Message(role="system", content="You are helpful."),
        Message(role="user", content="Search for cats."),
        Message(
            role="assistant",
            content="Let me search.",
            tool_calls=[
                ToolCall(id="call_1", name="web_search", arguments='{"query":"cats"}')
            ]
        ),
    ]
    result = to_langchain_messages(messages)
    assert len(result) == 3
    # Third message is the assistant with tool_calls
    assistant_msg = result[2]
    assert hasattr(assistant_msg, "tool_calls")
    assert assistant_msg.tool_calls is not None
    assert len(assistant_msg.tool_calls) == 1
    assert assistant_msg.tool_calls[0]["id"] == "call_1"
    assert assistant_msg.tool_calls[0]["name"] == "web_search"
    assert assistant_msg.tool_calls[0]["args"] == {"query": "cats"}


def test_to_langchain_messages_assistant_without_tool_calls():
    """Assistant messages without tool_calls should still work."""
    messages = [
        Message(role="assistant", content="Hello, how can I help?"),
    ]
    result = to_langchain_messages(messages)
    assert len(result) == 1
    assert result[0].content == "Hello, how can I help?"


def test_to_langchain_messages_preserves_tool_call_id():
    """Tool messages must carry tool_call_id."""
    messages = [
        Message(role="tool", content="result: 42", tool_call_id="call_abc"),
    ]
    result = to_langchain_messages(messages)
    assert len(result) == 1
    assert result[0].tool_call_id == "call_abc"

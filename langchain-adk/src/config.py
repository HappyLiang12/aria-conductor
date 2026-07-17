"""Configuration for LangChain ADK — reads from environment variables."""

import os
from dataclasses import dataclass


@dataclass
class Settings:
    """Runtime configuration loaded from environment variables."""

    # LLM settings — DeepSeek is OpenAI-compatible
    llm_api_key: str = os.getenv("LLM_API_KEY", "")
    llm_base_url: str = os.getenv("LLM_BASE_URL", "https://api.deepseek.com/v1")
    llm_default_model: str = os.getenv("LLM_DEFAULT_MODEL", "deepseek-chat")
    llm_max_tokens: int = int(os.getenv("LLM_MAX_TOKENS", "4096"))
    llm_timeout: int = int(os.getenv("LLM_TIMEOUT", "60"))

    # Server settings
    host: str = os.getenv("HOST", "127.0.0.1")
    port: int = int(os.getenv("PORT", "9300"))


settings = Settings()

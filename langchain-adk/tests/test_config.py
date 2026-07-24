"""Config loading tests — Settings reads env vars at module import time."""

import importlib

import pytest

import src.config


ENV_VARS = [
    "LLM_API_KEY",
    "LLM_BASE_URL",
    "LLM_DEFAULT_MODEL",
    "LLM_MAX_TOKENS",
    "LLM_TIMEOUT",
    "HOST",
    "PORT",
]


@pytest.fixture
def clean_env(monkeypatch):
    """Remove all ADK env vars, and reload config after the test to undo changes."""
    for var in ENV_VARS:
        monkeypatch.delenv(var, raising=False)
    yield monkeypatch
    monkeypatch.undo()
    importlib.reload(src.config)


def reload_settings():
    return importlib.reload(src.config).settings


class TestDefaults:
    def test_defaults_when_env_unset(self, clean_env):
        s = reload_settings()
        assert s.llm_api_key == ""
        assert s.llm_base_url == "https://api.deepseek.com/v1"
        assert s.llm_default_model == "deepseek-chat"
        assert s.llm_max_tokens == 4096
        assert s.llm_timeout == 60
        assert s.host == "127.0.0.1"
        assert s.port == 9300

    def test_int_fields_are_ints_not_strings(self, clean_env):
        s = reload_settings()
        assert isinstance(s.llm_max_tokens, int)
        assert isinstance(s.llm_timeout, int)
        assert isinstance(s.port, int)


class TestEnvOverrides:
    def test_llm_api_key_override(self, clean_env):
        clean_env.setenv("LLM_API_KEY", "sk-from-env")
        assert reload_settings().llm_api_key == "sk-from-env"

    def test_llm_base_url_override(self, clean_env):
        clean_env.setenv("LLM_BASE_URL", "http://localhost:1234/v1")
        assert reload_settings().llm_base_url == "http://localhost:1234/v1"

    def test_llm_default_model_override(self, clean_env):
        clean_env.setenv("LLM_DEFAULT_MODEL", "deepseek-reasoner")
        assert reload_settings().llm_default_model == "deepseek-reasoner"

    def test_numeric_overrides_parsed_as_int(self, clean_env):
        clean_env.setenv("LLM_MAX_TOKENS", "2048")
        clean_env.setenv("LLM_TIMEOUT", "120")
        clean_env.setenv("PORT", "9400")
        s = reload_settings()
        assert s.llm_max_tokens == 2048
        assert s.llm_timeout == 120
        assert s.port == 9400

    def test_host_override(self, clean_env):
        clean_env.setenv("HOST", "0.0.0.0")
        assert reload_settings().host == "0.0.0.0"

    def test_non_numeric_port_fails_fast(self, clean_env):
        clean_env.setenv("PORT", "not-a-port")
        with pytest.raises(ValueError):
            reload_settings()

    def test_non_numeric_max_tokens_fails_fast(self, clean_env):
        clean_env.setenv("LLM_MAX_TOKENS", "unlimited")
        with pytest.raises(ValueError):
            reload_settings()


class TestSingleton:
    def test_module_exposes_settings_instance(self):
        assert isinstance(src.config.settings, src.config.Settings)

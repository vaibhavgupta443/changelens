"""Settings from environment variables. Nothing secret is ever logged."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    provider: str = "mock"                      # mock | openai (any OpenAI-compatible endpoint, e.g. OpenRouter, Ollama)
    base_url: str = "https://openrouter.ai/api/v1"
    api_key: str = ""
    model: str = "openai/gpt-4o-mini"
    timeout_s: float = 45.0
    max_prompt_chars: int = 24_000              # hard cap on what is sent to a model
    max_calls: int = 0                          # 0 = unlimited; set a small number to protect a paid key
    max_tokens: int = 1500
    require_api_token: str = ""                 # if set, callers must send X-Internal-Token

    @staticmethod
    def from_env() -> "Settings":
        e = os.environ.get
        return Settings(
            provider=e("CHANGELENS_LLM_PROVIDER", "mock").lower(),
            base_url=e("CHANGELENS_LLM_BASE_URL", "https://openrouter.ai/api/v1").rstrip("/"),
            api_key=e("CHANGELENS_LLM_API_KEY", ""),
            model=e("CHANGELENS_LLM_MODEL", "openai/gpt-4o-mini"),
            timeout_s=float(e("CHANGELENS_LLM_TIMEOUT_S", "45")),
            max_prompt_chars=int(e("CHANGELENS_MAX_PROMPT_CHARS", "24000")),
            max_calls=int(e("CHANGELENS_LLM_MAX_CALLS", "0")),
            max_tokens=int(e("CHANGELENS_LLM_MAX_TOKENS", "1500")),
            require_api_token=e("CHANGELENS_INTERNAL_TOKEN", ""),
        )

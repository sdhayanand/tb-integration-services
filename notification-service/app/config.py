"""Settings from environment variables (ARCHITECTURE §6: PUSH_AUDIENCE, NOTIFY_MODE)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    # OIDC audience Pub/Sub puts in the push token (the Cloud Run service URL). Empty = no verification (local only).
    push_audience: str = field(default_factory=lambda: os.getenv("PUSH_AUDIENCE", "").strip())
    # Optional: the Pub/Sub push service account that must appear as the token's `email` claim.
    push_service_account: str = field(default_factory=lambda: os.getenv("PUSH_SERVICE_ACCOUNT", "").strip())
    # log (default) | sendgrid | twilio
    notify_mode: str = field(default_factory=lambda: os.getenv("NOTIFY_MODE", "log").strip().lower() or "log")
    brand_name: str = field(default_factory=lambda: os.getenv("BRAND_NAME", "Men's Wearhouse"))
    sendgrid_api_key: str = field(default_factory=lambda: os.getenv("SENDGRID_API_KEY", ""))
    twilio_account_sid: str = field(default_factory=lambda: os.getenv("TWILIO_ACCOUNT_SID", ""))
    twilio_auth_token: str = field(default_factory=lambda: os.getenv("TWILIO_AUTH_TOKEN", ""))
    log_json: bool = field(default_factory=lambda: os.getenv("LOG_JSON", "false").lower() == "true")

    @property
    def verify_push_token(self) -> bool:
        return bool(self.push_audience)


def load_settings() -> Settings:
    return Settings()

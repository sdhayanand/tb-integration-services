"""OIDC verification of the Pub/Sub push `Authorization: Bearer <token>` header.

Pub/Sub signs the token with the push subscription's service account; the audience is the push
endpoint (Cloud Run URL). google-auth fetches Google's certificates and validates signature, expiry,
issuer and audience.
"""
from __future__ import annotations

import logging
from typing import Protocol

from google.auth.transport import requests as google_requests
from google.oauth2 import id_token

log = logging.getLogger("notification.auth")


class PushAuthError(Exception):
    """Raised when the push request is not authenticated."""


class TokenVerifier(Protocol):
    def __call__(self, token: str) -> dict: ...


def google_token_verifier(audience: str) -> TokenVerifier:
    """Default verifier: google.oauth2.id_token.verify_oauth2_token against the configured audience."""

    request = google_requests.Request()

    def verify(token: str) -> dict:
        return id_token.verify_oauth2_token(token, request, audience=audience)

    return verify


def authenticate_push(authorization_header: str | None, *, verifier: TokenVerifier | None,
                      required_email: str = "") -> dict | None:
    """Returns the token claims, or None when verification is disabled (verifier is None).

    Raises PushAuthError on a missing/invalid token or when the `email` claim does not match the
    expected push service account.
    """
    if verifier is None:
        return None
    if not authorization_header or not authorization_header.lower().startswith("bearer "):
        raise PushAuthError("missing bearer token")
    token = authorization_header[7:].strip()
    if not token:
        raise PushAuthError("empty bearer token")
    try:
        claims = verifier(token)
    except Exception as exc:  # google-auth raises ValueError/GoogleAuthError subclasses
        log.warning("push token rejected: %s", exc)
        raise PushAuthError(f"invalid token: {exc}") from exc
    if required_email:
        email = claims.get("email", "")
        if email != required_email or not claims.get("email_verified", False):
            raise PushAuthError(f"token email '{email}' is not the expected push service account")
    return claims

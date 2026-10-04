"""notification-service (Cloud Run, Python 3.12 / FastAPI).

POST /push/shipments  Pub/Sub push endpoint for subscription `shipments-notification` (topic shipments-v1).
                      Verifies the OIDC bearer token (when PUSH_AUDIENCE is set), decodes the
                      ShipmentEvent, renders a customer notification, sends it via NOTIFY_MODE, 204.
GET  /healthz         liveness/readiness.
"""
from __future__ import annotations

import json
import logging
import sys
from collections import OrderedDict
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Header, Request, Response, status
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from .auth import PushAuthError, TokenVerifier, authenticate_push, google_token_verifier
from .config import Settings, load_settings
from .models import PushEnvelope, ShipmentEvent
from .notifier import Sender, build_sender, render

log = logging.getLogger("notification")


class _JsonFormatter(logging.Formatter):
    """Cloud Logging friendly one-line JSON (severity/message)."""

    def format(self, record: logging.LogRecord) -> str:  # noqa: D401
        payload: dict[str, Any] = {
            "severity": record.levelname,
            "message": record.getMessage(),
            "logger": record.name,
        }
        for key in ("correlationId", "orderId", "eventId"):
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload)


def configure_logging(json_logs: bool) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(_JsonFormatter() if json_logs
                         else logging.Formatter("%(asctime)s %(levelname)s %(name)s - %(message)s"))
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(logging.INFO)


class _RecentEventIds:
    """Small in-memory dedupe of Pub/Sub at-least-once redeliveries (per instance, best effort)."""

    def __init__(self, capacity: int = 10_000) -> None:
        self._capacity = capacity
        self._seen: OrderedDict[str, None] = OrderedDict()

    def seen_before(self, event_id: str) -> bool:
        if event_id in self._seen:
            self._seen.move_to_end(event_id)
            return True
        self._seen[event_id] = None
        if len(self._seen) > self._capacity:
            self._seen.popitem(last=False)
        return False


def create_app(settings: Settings | None = None, *, verifier: TokenVerifier | None = None,
               sender: Sender | None = None) -> FastAPI:
    """App factory (tests inject a fake verifier / sender)."""
    settings = settings or load_settings()
    configure_logging(settings.log_json)

    if verifier is None and settings.verify_push_token:
        verifier = google_token_verifier(settings.push_audience)
    sender = sender or build_sender(
        settings.notify_mode,
        sendgrid_api_key=settings.sendgrid_api_key,
        twilio_account_sid=settings.twilio_account_sid,
        twilio_auth_token=settings.twilio_auth_token,
    )
    recent = _RecentEventIds()

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        log.info("notification-service starting: notify_mode=%s verify_push_token=%s audience=%s",
                 sender.name, verifier is not None, settings.push_audience or "(none)")
        if verifier is None:
            log.warning("PUSH_AUDIENCE not set: push requests are NOT authenticated (local/dev only)")
        yield
        log.info("notification-service stopped")

    app = FastAPI(title="Tailored Brands OTD - notification-service", version="1.0.0", lifespan=lifespan,
                  docs_url="/docs", openapi_url="/openapi.json")
    app.state.settings = settings
    app.state.sender = sender

    @app.exception_handler(PushAuthError)
    async def _auth_error(_: Request, exc: PushAuthError) -> JSONResponse:
        return _problem(status.HTTP_401_UNAUTHORIZED, "Unauthorized", str(exc))

    @app.get("/healthz", tags=["ops"])
    async def healthz() -> dict[str, str]:
        return {"status": "UP", "notifyMode": sender.name, "pushAuth": "oidc" if verifier else "disabled"}

    @app.post("/push/shipments", status_code=status.HTTP_204_NO_CONTENT, tags=["push"],
              summary="Pub/Sub push endpoint for shipments-v1")
    async def push_shipments(request: Request, authorization: str | None = Header(default=None),
                             x_correlation_id: str | None = Header(default=None)) -> Response:
        authenticate_push(authorization, verifier=verifier, required_email=settings.push_service_account)

        try:
            envelope = PushEnvelope.model_validate(await request.json())
        except (ValueError, ValidationError) as exc:  # json decode error or shape error
            return _problem(status.HTTP_400_BAD_REQUEST, "Invalid push envelope", _short(exc))

        try:
            event = ShipmentEvent.model_validate_json(envelope.message.decoded_data())
        except ValidationError as exc:
            # a 4xx tells Pub/Sub not to keep retrying forever; the subscription's dead-letter policy
            # moves the message to events-dlq after the configured attempts
            return _problem(status.HTTP_400_BAD_REQUEST, "Invalid ShipmentEvent", _short(exc))

        correlation_id = event.correlation_id or envelope.message.attributes.get("correlationId") \
            or x_correlation_id or ""
        extra = {"correlationId": correlation_id, "orderId": event.order_id, "eventId": event.event_id}

        if recent.seen_before(event.event_id):
            log.info("duplicate shipment event ignored (messageId=%s)", envelope.message.message_id, extra=extra)
            return Response(status_code=status.HTTP_204_NO_CONTENT)

        notification = render(event, brand=settings.brand_name)
        sender.send(notification)
        log.info("notified customer: status=%s carrier=%s tracking=%s messageId=%s", event.status, event.carrier,
                 event.tracking_number, envelope.message.message_id, extra=extra)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    return app


def _problem(code: int, title: str, detail: str) -> JSONResponse:
    """RFC 7807 problem details, consistent with the Java services."""
    return JSONResponse(
        status_code=code,
        media_type="application/problem+json",
        content={"type": "https://tailoredbrands.com/otd/problems/" + title.lower().replace(" ", "-"),
                 "title": title, "status": code, "detail": detail},
    )


def _short(exc: Exception) -> str:
    text = str(exc)
    return text if len(text) <= 500 else text[:500] + "..."


app = create_app()

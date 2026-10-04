"""Renders the customer-facing text for a ShipmentEvent and "sends" it according to NOTIFY_MODE."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Protocol

from .models import ShipmentEvent

log = logging.getLogger("notification.notifier")


@dataclass(frozen=True)
class Notification:
    order_id: str
    subject: str
    body: str
    channel: str  # email | sms | log


_TEMPLATES: dict[str, tuple[str, str]] = {
    # status -> (subject, body with {brand}, {order_id}, {carrier}, {tracking}, {location}, {when})
    "LABEL_CREATED": (
        "Your {brand} order {order_id} is getting ready",
        "Good news! Your {brand} order {order_id} has a shipping label with {carrier} (tracking {tracking}). "
        "We'll let you know as soon as it is on the way.",
    ),
    "IN_TRANSIT": (
        "Your {brand} order {order_id} is on its way",
        "Your {brand} order {order_id} is in transit with {carrier}{location_phrase}. Tracking: {tracking}.",
    ),
    "OUT_FOR_DELIVERY": (
        "Your {brand} order {order_id} is out for delivery",
        "Your {brand} order {order_id} is out for delivery today with {carrier}{location_phrase}. "
        "Someone may need to be available to receive it. Tracking: {tracking}.",
    ),
    "DELIVERED": (
        "Your {brand} order {order_id} has been delivered",
        "Your {brand} order {order_id} was delivered by {carrier}{location_phrase}. Enjoy your new look! "
        "Need alterations? Visit any {brand} store.",
    ),
    "EXCEPTION": (
        "An update on your {brand} order {order_id}",
        "There is a delivery exception on your {brand} order {order_id} with {carrier}{location_phrase}. "
        "We are on it; track the package with {tracking} or call customer care.",
    ),
}


def render(event: ShipmentEvent, brand: str = "Men's Wearhouse") -> Notification:
    subject_t, body_t = _TEMPLATES[event.status]
    location_phrase = f" ({event.location})" if event.location else ""
    values = {
        "brand": brand,
        "order_id": event.order_id,
        "carrier": event.carrier or "the carrier",
        "tracking": event.tracking_number or "n/a",
        "location_phrase": location_phrase,
    }
    return Notification(
        order_id=event.order_id,
        subject=subject_t.format(**values),
        body=body_t.format(**values),
        channel="log",
    )


class Sender(Protocol):
    name: str

    def send(self, notification: Notification) -> None: ...


class LogSender:
    """Default (NOTIFY_MODE=log): writes the notification to the log. Safe for demos."""

    name = "log"

    def send(self, notification: Notification) -> None:
        log.info("NOTIFY order=%s subject=%r body=%r", notification.order_id, notification.subject, notification.body)


class SendGridSender:
    """STUB (NOTIFY_MODE=sendgrid): where an email would be sent through SendGrid's v3 Mail Send API.

    Deliberately not wired to the network in this learning project: it validates configuration and
    logs what would be sent. Replace `send` with a `requests.post("https://api.sendgrid.com/v3/mail/send", ...)`.
    """

    name = "sendgrid"

    def __init__(self, api_key: str) -> None:
        if not api_key:
            raise ValueError("NOTIFY_MODE=sendgrid requires SENDGRID_API_KEY")
        self._api_key = api_key

    def send(self, notification: Notification) -> None:
        log.info("[sendgrid STUB] would email order=%s subject=%r", notification.order_id, notification.subject)


class TwilioSender:
    """STUB (NOTIFY_MODE=twilio): where an SMS would be sent through Twilio's Messages API.

    Same approach as SendGridSender: validates configuration, logs instead of calling Twilio.
    """

    name = "twilio"

    def __init__(self, account_sid: str, auth_token: str) -> None:
        if not account_sid or not auth_token:
            raise ValueError("NOTIFY_MODE=twilio requires TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN")
        self._account_sid = account_sid

    def send(self, notification: Notification) -> None:
        log.info("[twilio STUB] would text order=%s body=%r", notification.order_id, notification.body[:160])


def build_sender(mode: str, *, sendgrid_api_key: str = "", twilio_account_sid: str = "",
                 twilio_auth_token: str = "") -> Sender:
    if mode == "log":
        return LogSender()
    if mode == "sendgrid":
        return SendGridSender(sendgrid_api_key)
    if mode == "twilio":
        return TwilioSender(twilio_account_sid, twilio_auth_token)
    raise ValueError(f"unknown NOTIFY_MODE '{mode}' (log | sendgrid | twilio)")

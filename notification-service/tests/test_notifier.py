import pytest

from app.models import ShipmentEvent
from app.notifier import LogSender, SendGridSender, TwilioSender, build_sender, render


def _event(status: str, **overrides) -> ShipmentEvent:
    base = {"eventId": "e1", "orderId": "ORD-2026-000123", "trackingNumber": "1Z1", "carrier": "UPS",
            "status": status, "location": "Oakland, CA"}
    base.update(overrides)
    return ShipmentEvent.model_validate(base)


def test_out_for_delivery_text_mentions_brand_and_order():
    n = render(_event("OUT_FOR_DELIVERY"))
    assert n.subject == "Your Men's Wearhouse order ORD-2026-000123 is out for delivery"
    assert "Your Men's Wearhouse order ORD-2026-000123 is out for delivery today with UPS (Oakland, CA)" in n.body
    assert "1Z1" in n.body


@pytest.mark.parametrize("status,fragment", [
    ("LABEL_CREATED", "getting ready"),
    ("IN_TRANSIT", "on its way"),
    ("OUT_FOR_DELIVERY", "out for delivery"),
    ("DELIVERED", "has been delivered"),
    ("EXCEPTION", "An update on your"),
])
def test_every_status_has_a_template(status, fragment):
    assert fragment in render(_event(status)).subject


def test_missing_optional_fields_render_gracefully():
    n = render(_event("IN_TRANSIT", trackingNumber=None, carrier=None, location=None), brand="Jos. A. Bank")
    assert n.body == "Your Jos. A. Bank order ORD-2026-000123 is in transit with the carrier. Tracking: n/a."


def test_build_sender_modes():
    assert isinstance(build_sender("log"), LogSender)
    assert isinstance(build_sender("sendgrid", sendgrid_api_key="SG.x"), SendGridSender)
    assert isinstance(build_sender("twilio", twilio_account_sid="AC1", twilio_auth_token="t"), TwilioSender)
    with pytest.raises(ValueError):
        build_sender("sendgrid")
    with pytest.raises(ValueError):
        build_sender("twilio", twilio_account_sid="AC1")
    with pytest.raises(ValueError):
        build_sender("carrier-pigeon")


def test_unknown_status_is_rejected_by_model():
    with pytest.raises(Exception):
        _event("LOST")

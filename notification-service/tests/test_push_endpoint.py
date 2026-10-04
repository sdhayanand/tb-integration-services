import base64

import pytest
from fastapi.testclient import TestClient

from app.auth import PushAuthError
from app.config import Settings
from app.main import create_app
from app.notifier import Notification

from .conftest import envelope


class RecordingSender:
    name = "recording"

    def __init__(self) -> None:
        self.sent: list[Notification] = []

    def send(self, notification: Notification) -> None:
        self.sent.append(notification)


def _client(*, audience: str = "", verifier=None, required_email: str = "") -> tuple[TestClient, RecordingSender]:
    settings = Settings(push_audience=audience, push_service_account=required_email, notify_mode="log")
    sender = RecordingSender()
    app = create_app(settings, verifier=verifier, sender=sender)
    return TestClient(app), sender


def test_healthz():
    client, _ = _client()
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.json()["pushAuth"] == "disabled"


def test_push_without_audience_skips_auth_and_notifies(shipment_event):
    client, sender = _client()
    response = client.post("/push/shipments", json=envelope(shipment_event))
    assert response.status_code == 204
    assert len(sender.sent) == 1
    assert sender.sent[0].order_id == "ORD-2026-000123"
    assert "out for delivery" in sender.sent[0].subject


def test_duplicate_event_is_acknowledged_but_not_resent(shipment_event):
    client, sender = _client()
    assert client.post("/push/shipments", json=envelope(shipment_event, message_id="m-1")).status_code == 204
    assert client.post("/push/shipments", json=envelope(shipment_event, message_id="m-2")).status_code == 204
    assert len(sender.sent) == 1


def test_push_with_audience_requires_valid_bearer_token(shipment_event):
    accepted = {"aud": "https://notify.example.run.app", "email": "pubsub-push@p.iam.gserviceaccount.com",
                "email_verified": True}

    def verifier(token: str) -> dict:
        if token == "good":
            return accepted
        raise ValueError("Token expired")

    client, sender = _client(audience="https://notify.example.run.app", verifier=verifier,
                             required_email="pubsub-push@p.iam.gserviceaccount.com")

    assert client.post("/push/shipments", json=envelope(shipment_event)).status_code == 401
    bad = client.post("/push/shipments", json=envelope(shipment_event), headers={"Authorization": "Bearer nope"})
    assert bad.status_code == 401
    assert bad.headers["content-type"].startswith("application/problem+json")
    assert "Token expired" in bad.json()["detail"]
    assert sender.sent == []

    good = client.post("/push/shipments", json=envelope(shipment_event), headers={"Authorization": "Bearer good"})
    assert good.status_code == 204
    assert len(sender.sent) == 1


def test_wrong_service_account_email_is_rejected(shipment_event):
    def verifier(token: str) -> dict:
        return {"email": "someone-else@p.iam.gserviceaccount.com", "email_verified": True}

    client, sender = _client(audience="https://x", verifier=verifier, required_email="pubsub-push@p.iam.gserviceaccount.com")
    response = client.post("/push/shipments", json=envelope(shipment_event), headers={"Authorization": "Bearer t"})
    assert response.status_code == 401
    assert "not the expected push service account" in response.json()["detail"]
    assert sender.sent == []


def test_invalid_envelope_and_invalid_event_are_400(shipment_event):
    client, sender = _client()
    assert client.post("/push/shipments", json={"nope": 1}).status_code == 400
    assert client.post("/push/shipments", json={"message": {"data": "!!not-base64!!"}}).status_code == 400

    broken = dict(shipment_event)
    broken["status"] = "TELEPORTED"
    response = client.post("/push/shipments", json=envelope(broken))
    assert response.status_code == 400
    assert response.json()["title"] == "Invalid ShipmentEvent"

    not_json = {"message": {"data": base64.b64encode(b"<xml/>").decode()}}
    assert client.post("/push/shipments", json=not_json).status_code == 400
    assert sender.sent == []


def test_auth_helper_rejects_malformed_headers():
    with pytest.raises(PushAuthError):
        from app.auth import authenticate_push
        authenticate_push("Basic abc", verifier=lambda t: {}, required_email="")

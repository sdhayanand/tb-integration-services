import base64
import json
import os

import pytest

os.environ.setdefault("NOTIFY_MODE", "log")


def envelope(event: dict, *, attributes: dict | None = None, message_id: str = "m-1") -> dict:
    data = base64.b64encode(json.dumps(event).encode()).decode()
    return {
        "message": {"data": data, "attributes": attributes or {}, "messageId": message_id,
                    "publishTime": "2026-10-04T15:00:01Z"},
        "subscription": "projects/tb-otd/subscriptions/shipments-notification",
    }


@pytest.fixture
def shipment_event() -> dict:
    return {
        "eventId": "9d2c2c6e-1f6f-4d2d-9a1f-1234567890ab",
        "eventType": "SHIPMENT_UPDATED",
        "eventTime": "2026-10-04T15:00:00Z",
        "schemaVersion": "1",
        "source": "SHIPMENT_WEBHOOK",
        "correlationId": "corr-ups-1",
        "orderId": "ORD-2026-000123",
        "trackingNumber": "1Z999AA10123456784",
        "carrier": "UPS",
        "status": "OUT_FOR_DELIVERY",
        "statusTime": "2026-10-04T14:58:00Z",
        "location": "Oakland, CA",
    }

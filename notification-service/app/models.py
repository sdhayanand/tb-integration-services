"""Pydantic models: the Pub/Sub push envelope and the canonical ShipmentEvent (ARCHITECTURE §3.3)."""
from __future__ import annotations

import base64
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

ShipmentStatus = Literal["LABEL_CREATED", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION"]


class ShipmentEvent(BaseModel):
    """Canonical shipment event as published by shipment-webhook (unknown fields ignored)."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    event_id: str = Field(alias="eventId")
    event_type: str = Field(alias="eventType", default="SHIPMENT_UPDATED")
    event_time: datetime | None = Field(alias="eventTime", default=None)
    schema_version: str = Field(alias="schemaVersion", default="1")
    source: str | None = None
    correlation_id: str | None = Field(alias="correlationId", default=None)
    order_id: str = Field(alias="orderId")
    tracking_number: str | None = Field(alias="trackingNumber", default=None)
    carrier: str | None = None
    status: ShipmentStatus
    status_time: datetime | None = Field(alias="statusTime", default=None)
    location: str | None = None


class PushMessage(BaseModel):
    """`message` part of a Pub/Sub push envelope."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    data: str = ""
    attributes: dict[str, str] = Field(default_factory=dict)
    message_id: str | None = Field(alias="messageId", default=None)
    publish_time: str | None = Field(alias="publishTime", default=None)
    ordering_key: str | None = Field(alias="orderingKey", default=None)

    @field_validator("data")
    @classmethod
    def _must_be_base64(cls, value: str) -> str:
        if value:
            try:
                base64.b64decode(value, validate=True)
            except Exception as exc:  # noqa: BLE001 - we only care that it is not decodable
                raise ValueError("message.data is not valid base64") from exc
        return value

    def decoded_data(self) -> bytes:
        return base64.b64decode(self.data) if self.data else b""


class PushEnvelope(BaseModel):
    """Pub/Sub push envelope: {"message": {...}, "subscription": "projects/p/subscriptions/s"}."""

    model_config = ConfigDict(extra="ignore")

    message: PushMessage
    subscription: str | None = None

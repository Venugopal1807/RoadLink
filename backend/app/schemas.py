"""Pydantic request/response models.

The wire body is an ENVELOPE:

    { "packet": {...}, "delivery": {...} }

packet   - created once on the rider's device, signed, immutable thereafter.
           Byte-identical whether it arrives direct or via a BLE relay.
delivery - metadata about THIS submission attempt. Differs per path, and is
           deliberately outside the signature.

Keeping them separate is what lets the same signed packet be submitted by the
rider and by one or more relays without any signature mismatch.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

DeliveryPath = Literal["direct", "ble_relay"]


class SosPacket(BaseModel):
    model_config = ConfigDict(extra="forbid")

    v: int = Field(default=1, ge=1, le=255, description="schema_version")
    event_id: str = Field(min_length=8, max_length=64, description="UUIDv4 generated on-device")
    rider_id: str = Field(min_length=1, max_length=64, description="Opaque. Never a name/phone/email")
    created_at: int = Field(ge=0, description="Epoch millis, DEVICE clock. Never used for ordering")
    lat: float | None = Field(default=None, ge=-90.0, le=90.0)
    lng: float | None = Field(default=None, ge=-180.0, le=180.0)
    acc_m: int | None = Field(default=None, ge=0, le=100000, description="GPS accuracy, metres")
    conf: int = Field(ge=0, le=100, description="Crash confidence. Evidence, not a diagnosis")
    trigger: list[str] = Field(default_factory=list, max_length=8)
    simulated: bool = Field(description="NEVER omitted. Propagates to the responder UI")
    sig: str | None = Field(default=None, max_length=64)


class Delivery(BaseModel):
    model_config = ConfigDict(extra="forbid")

    path: DeliveryPath = "direct"
    relay_id: str | None = Field(default=None, max_length=64)
    relay_received_at: int | None = Field(default=None, ge=0, description="Epoch ms on the RELAY's clock")


class SosSubmission(BaseModel):
    model_config = ConfigDict(extra="forbid")

    packet: SosPacket
    delivery: Delivery = Field(default_factory=Delivery)


class IngestResponse(BaseModel):
    event_id: str
    duplicate: bool
    divergent: bool = Field(description="Same event_id arrived carrying different signed content")
    state: str
    sig_valid: bool
    audit_entries: int


class StatusUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    state: Literal["ACKNOWLEDGED", "CLOSED"]
    note: str | None = Field(default=None, max_length=500)

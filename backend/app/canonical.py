"""Canonical serialization and packet signing for RoadLink SOS packets.

WHY NOT CANONICAL JSON:
Signing over JSON across Kotlin and Python is a classic source of silent
signature mismatches, because the two languages do not agree on float
repr (17.4401 may serialize as "17.4401" or "17.440100000000001"), on key
ordering, or on unicode escaping.

Instead we sign a fixed, field-ordered, explicitly-formatted string. Every
field has one and only one textual representation:

  - integers      decimal, no padding
  - lat / lng     "%.7f"  (7 dp = ~1.1 cm; deterministic in both languages)
  - booleans      "1" / "0"
  - null          empty string
  - trigger list  sorted ascending, comma-joined

The Kotlin implementation must produce byte-identical output. Any change to
this function is a wire-protocol break and requires a schema_version bump.
"""

from __future__ import annotations

import base64
import hmac
import hashlib
from typing import Any, Iterable

# Spike-grade pre-shared key. This is NOT key management: anyone holding the
# APK holds this key. Production design is a per-device Ed25519 keypair with
# the private key in the Android Keystore. See docs/phase1-spike-plan.md G.1.
SPIKE_HMAC_KEY = b"roadlink-phase1-spike-key-not-for-production"

SIG_BYTES = 16  # HMAC-SHA256 truncated to 128 bits


def _fmt_coord(value: float | None) -> str:
    return "" if value is None else "%.7f" % value


def _fmt_int(value: int | None) -> str:
    return "" if value is None else str(int(value))


def _fmt_bool(value: bool) -> str:
    return "1" if value else "0"


def _fmt_triggers(triggers: Iterable[str] | None) -> str:
    return "" if not triggers else ",".join(sorted(triggers))


def canonical_string(packet: dict[str, Any]) -> str:
    """Build the exact string that gets signed. Field order is fixed."""
    return "|".join(
        [
            "v=" + _fmt_int(packet.get("v")),
            "event_id=" + (packet.get("event_id") or ""),
            "rider_id=" + (packet.get("rider_id") or ""),
            "created_at=" + _fmt_int(packet.get("created_at")),
            "lat=" + _fmt_coord(packet.get("lat")),
            "lng=" + _fmt_coord(packet.get("lng")),
            "acc_m=" + _fmt_int(packet.get("acc_m")),
            "conf=" + _fmt_int(packet.get("conf")),
            "trigger=" + _fmt_triggers(packet.get("trigger")),
            "simulated=" + _fmt_bool(bool(packet.get("simulated"))),
        ]
    )


def sign(packet: dict[str, Any], key: bytes = SPIKE_HMAC_KEY) -> str:
    """Return the base64 signature for a packet (the 'sig' field)."""
    mac = hmac.new(key, canonical_string(packet).encode("utf-8"), hashlib.sha256)
    return base64.b64encode(mac.digest()[:SIG_BYTES]).decode("ascii")


def verify(packet: dict[str, Any], key: bytes = SPIKE_HMAC_KEY) -> bool:
    """Constant-time check of the packet's 'sig' field."""
    provided = packet.get("sig")
    if not provided:
        return False
    return hmac.compare_digest(sign(packet, key), provided)


def packet_hash(packet: dict[str, Any]) -> str:
    """Content hash used to detect a duplicate event_id carrying DIFFERENT data.

    Covers the signed fields only, so a benign difference in delivery metadata
    does not register as divergence.
    """
    digest = hashlib.sha256(canonical_string(packet).encode("utf-8")).digest()
    return digest.hex()


def event_ref(event_id: str) -> str:
    """First 8 bytes of SHA-256(event_id), hex.

    This is what the BLE advertisement broadcasts instead of the raw event_id,
    so a passive eavesdropper cannot correlate a broadcast with a backend
    record. Mirrors ble/Beacon.kt. See docs/phase1-spike-plan.md F.3.
    """
    return hashlib.sha256(event_id.encode("utf-8")).digest()[:8].hex()

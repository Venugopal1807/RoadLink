"""RoadLink Phase 1 spike backend.

Single idempotent ingestion endpoint for SOS events, regardless of whether
they arrived direct from the rider or forwarded by a BLE relay.

Run:  python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
      (from the backend/ directory)

Bind to 0.0.0.0 so the phones can reach it over the LAN during the spike.
"""

from __future__ import annotations

import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Response, status

from . import db
from .canonical import event_ref, packet_hash, verify
from .schemas import IngestResponse, SosSubmission, StatusUpdate

# When set to "1" the backend REJECTS packets that fail signature verification.
# Default is OFF for the spike: during Phase 1 a canonicalisation mismatch
# between Kotlin and Python is far more likely than an attacker, and silently
# dropping emergency events while debugging that would be the worse failure.
# Every event records sig_valid either way, and the audit trail keeps it.
REQUIRE_SIG = os.environ.get("ROADLINK_REQUIRE_SIG", "0") == "1"


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    yield


app = FastAPI(
    title="RoadLink Spike Backend",
    version="0.1.0-phase1",
    summary="Idempotent SOS ingestion for the Phase 1 BLE spike",
    lifespan=lifespan,
)


@app.get("/healthz", tags=["ops"])
def healthz() -> dict[str, object]:
    """Liveness probe. The device uses this to decide 'am I actually online'."""
    return {"ok": True, "service": "roadlink-spike", "require_sig": REQUIRE_SIG}


@app.post(
    "/api/v1/sos",
    response_model=IngestResponse,
    tags=["ingest"],
    status_code=status.HTTP_201_CREATED,
)
def ingest_sos(submission: SosSubmission, response: Response) -> IngestResponse:
    """Ingest an emergency event. Idempotent on event_id.

    201 -> this event_id was recorded for the first time
    200 -> already known; the submission is a no-op that still appends audit

    A duplicate is NOT an error. The rider's own phone and one or more relays
    are all expected to submit the same event independently; that redundancy
    is the design, and idempotency here is what makes it safe.
    """
    packet = submission.packet.model_dump()
    delivery = submission.delivery.model_dump()

    sig_valid = verify(packet)
    if REQUIRE_SIG and not sig_valid:
        raise HTTPException(status_code=403, detail="packet signature verification failed")

    result = db.ingest(packet, delivery, sig_valid, packet_hash(packet))

    response.status_code = (
        status.HTTP_200_OK if result["duplicate"] else status.HTTP_201_CREATED
    )
    return IngestResponse(
        event_id=packet["event_id"],
        duplicate=result["duplicate"],
        divergent=result["divergent"],
        state=result["state"],
        sig_valid=sig_valid,
        audit_entries=result["audit_entries"],
    )


@app.get("/api/v1/sos/active", tags=["responder"])
def list_active() -> dict[str, object]:
    events = db.list_active()
    return {"count": len(events), "events": events}


@app.get("/api/v1/sos/{event_id}", tags=["responder"])
def get_event(event_id: str) -> dict[str, object]:
    event = db.get_event(event_id)
    if event is None:
        raise HTTPException(status_code=404, detail="unknown event_id")
    # Included so a spike operator can match a backend record against the
    # 8-byte reference actually seen in a BLE advertisement.
    event["event_ref"] = event_ref(event_id)
    return event


@app.patch("/api/v1/sos/{event_id}/status", tags=["responder"])
def update_status(event_id: str, update: StatusUpdate) -> dict[str, object]:
    """Responder-side status. Separate from the rider-side delivery state."""
    if not db.set_state(event_id, update.state, update.note):
        raise HTTPException(status_code=404, detail="unknown event_id")
    return {"event_id": event_id, "state": update.state}


@app.get("/api/v1/debug/audit", tags=["spike"])
def debug_audit() -> dict[str, object]:
    """Spike-only: raw audit rows plus table counts, for test verification."""
    return {"counts": db.counts(), "audit": db.all_audit()}


@app.post("/api/v1/debug/reset", tags=["spike"])
def debug_reset() -> dict[str, object]:
    """Spike-only: wipe the database so a test run starts clean.

    Guarded by ROADLINK_ALLOW_RESET so it cannot be hit by accident on
    anything that outlives the spike.
    """
    if os.environ.get("ROADLINK_ALLOW_RESET", "0") != "1":
        raise HTTPException(status_code=403, detail="reset disabled; set ROADLINK_ALLOW_RESET=1")
    db.reset_db()
    return {"ok": True, "counts": db.counts()}

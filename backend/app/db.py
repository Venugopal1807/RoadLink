"""SQLite persistence for the Phase 1 spike.

Deliberately stdlib sqlite3 with a thin repository layer rather than an ORM:
the spike needs zero install risk, and this module is the single swap point
for PostgreSQL in Phase 3.

CONCURRENCY: WAL journal + BEGIN IMMEDIATE + busy_timeout. Ingest must stay
correct when a rider and one or more relays submit the same event at the
same instant.
"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
from datetime import datetime, timezone
from typing import Any

DB_PATH = os.environ.get(
    "ROADLINK_DB",
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "roadlink_spike.db"),
)

_init_lock = threading.Lock()
_initialised = False

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    event_id            TEXT    PRIMARY KEY,
    schema_version      INTEGER NOT NULL,
    rider_id            TEXT    NOT NULL,
    created_at_device   INTEGER NOT NULL,
    received_at         TEXT    NOT NULL,
    lat                 REAL,
    lng                 REAL,
    acc_m               INTEGER,
    conf                INTEGER NOT NULL,
    trigger             TEXT    NOT NULL,
    simulated           INTEGER NOT NULL,
    state               TEXT    NOT NULL,
    first_delivery_path TEXT    NOT NULL,
    first_relay_id      TEXT,
    sig                 TEXT,
    sig_valid           INTEGER NOT NULL,
    packet_hash         TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS audit (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id      TEXT    NOT NULL,
    at            TEXT    NOT NULL,
    actor         TEXT    NOT NULL,
    action        TEXT    NOT NULL,
    delivery_path TEXT,
    relay_id      TEXT,
    detail        TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_event ON audit(event_id);
CREATE INDEX IF NOT EXISTS idx_events_state ON events(state);
"""

# Terminal/active states held at the backend. Distinct from the rider-side
# delivery state machine, per architecture doc section 6.
STATE_RECEIVED = "RECEIVED"
STATE_ACKNOWLEDGED = "ACKNOWLEDGED"
STATE_CLOSED = "CLOSED"
ACTIVE_STATES = (STATE_RECEIVED, STATE_ACKNOWLEDGED)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=10.0, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=10000")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db() -> None:
    global _initialised
    with _init_lock:
        if _initialised:
            return
        conn = connect()
        try:
            conn.executescript(SCHEMA)
        finally:
            conn.close()
        _initialised = True


def reset_db() -> None:
    """Test-only: drop everything and recreate."""
    global _initialised
    conn = connect()
    try:
        conn.executescript("DROP TABLE IF EXISTS audit; DROP TABLE IF EXISTS events;")
        conn.executescript(SCHEMA)
    finally:
        conn.close()
    _initialised = True


def append_audit(
    conn: sqlite3.Connection,
    event_id: str,
    actor: str,
    action: str,
    delivery_path: str | None = None,
    relay_id: str | None = None,
    detail: str | None = None,
) -> None:
    """Append an audit row. This is NEVER conditional and NEVER deleted."""
    conn.execute(
        "INSERT INTO audit (event_id, at, actor, action, delivery_path, relay_id, detail)"
        " VALUES (?,?,?,?,?,?,?)",
        (event_id, now_iso(), actor, action, delivery_path, relay_id, detail),
    )


def ingest(packet: dict[str, Any], delivery: dict[str, Any], sig_valid: bool, phash: str) -> dict[str, Any]:
    """Idempotent ingest keyed on event_id.

    Returns {"duplicate": bool, "divergent": bool, "state": str, "audit_entries": int}.

    The INSERT and the audit append happen in one IMMEDIATE transaction so a
    concurrent duplicate cannot produce two event rows or lose an audit row.
    """
    event_id = packet["event_id"]
    path = delivery.get("path", "direct")
    relay_id = delivery.get("relay_id")
    actor = "relay" if path == "ble_relay" else "rider"

    conn = connect()
    try:
        conn.execute("BEGIN IMMEDIATE")

        cur = conn.execute(
            "INSERT OR IGNORE INTO events ("
            " event_id, schema_version, rider_id, created_at_device, received_at,"
            " lat, lng, acc_m, conf, trigger, simulated, state,"
            " first_delivery_path, first_relay_id, sig, sig_valid, packet_hash"
            ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                event_id,
                packet.get("v", 1),
                packet["rider_id"],
                packet["created_at"],
                now_iso(),
                packet.get("lat"),
                packet.get("lng"),
                packet.get("acc_m"),
                packet["conf"],
                json.dumps(sorted(packet.get("trigger") or [])),
                1 if packet.get("simulated") else 0,
                STATE_RECEIVED,
                path,
                relay_id,
                packet.get("sig"),
                1 if sig_valid else 0,
                phash,
            ),
        )
        inserted = cur.rowcount == 1

        divergent = False
        if inserted:
            append_audit(
                conn, event_id, actor, "ingest_accepted", path, relay_id,
                json.dumps({"sig_valid": sig_valid}),
            )
        else:
            row = conn.execute(
                "SELECT packet_hash FROM events WHERE event_id=?", (event_id,)
            ).fetchone()
            divergent = row is not None and row["packet_hash"] != phash
            # A duplicate is NOT an error. It is a second legitimate delivery
            # path. First write wins; we never overwrite a stored event.
            append_audit(
                conn,
                event_id,
                actor,
                "ingest_duplicate_divergent" if divergent else "ingest_duplicate",
                path,
                relay_id,
                json.dumps({"sig_valid": sig_valid, "incoming_hash": phash}) if divergent else None,
            )

        state_row = conn.execute(
            "SELECT state FROM events WHERE event_id=?", (event_id,)
        ).fetchone()
        count_row = conn.execute(
            "SELECT COUNT(*) AS n FROM audit WHERE event_id=?", (event_id,)
        ).fetchone()

        conn.execute("COMMIT")

        return {
            "duplicate": not inserted,
            "divergent": divergent,
            "state": state_row["state"] if state_row else STATE_RECEIVED,
            "audit_entries": count_row["n"],
        }
    except Exception:
        try:
            conn.execute("ROLLBACK")
        except sqlite3.Error:
            pass
        raise
    finally:
        conn.close()


def _event_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    d = dict(row)
    d["trigger"] = json.loads(d["trigger"])
    d["simulated"] = bool(d["simulated"])
    d["sig_valid"] = bool(d["sig_valid"])
    return d


def get_event(event_id: str) -> dict[str, Any] | None:
    conn = connect()
    try:
        row = conn.execute("SELECT * FROM events WHERE event_id=?", (event_id,)).fetchone()
        if row is None:
            return None
        event = _event_to_dict(row)
        event["audit"] = [
            dict(r)
            for r in conn.execute(
                "SELECT * FROM audit WHERE event_id=? ORDER BY id ASC", (event_id,)
            )
        ]
        return event
    finally:
        conn.close()


def list_active() -> list[dict[str, Any]]:
    conn = connect()
    try:
        placeholders = ",".join("?" for _ in ACTIVE_STATES)
        rows = conn.execute(
            f"SELECT * FROM events WHERE state IN ({placeholders}) ORDER BY received_at DESC",
            ACTIVE_STATES,
        ).fetchall()
        return [_event_to_dict(r) for r in rows]
    finally:
        conn.close()


def set_state(event_id: str, state: str, note: str | None) -> bool:
    conn = connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        cur = conn.execute("UPDATE events SET state=? WHERE event_id=?", (state, event_id))
        if cur.rowcount == 0:
            conn.execute("ROLLBACK")
            return False
        append_audit(conn, event_id, "responder", "status_change", detail=json.dumps({"to": state, "note": note}))
        conn.execute("COMMIT")
        return True
    finally:
        conn.close()


def all_audit() -> list[dict[str, Any]]:
    conn = connect()
    try:
        return [dict(r) for r in conn.execute("SELECT * FROM audit ORDER BY id ASC")]
    finally:
        conn.close()


def counts() -> dict[str, int]:
    conn = connect()
    try:
        e = conn.execute("SELECT COUNT(*) AS n FROM events").fetchone()["n"]
        a = conn.execute("SELECT COUNT(*) AS n FROM audit").fetchone()["n"]
        return {"events": e, "audit": a}
    finally:
        conn.close()

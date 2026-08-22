"""Phase 1 backend test suite — test T10 and its supporting invariants.

Runs against a REAL uvicorn server over REAL HTTP (stdlib urllib only, no
pytest/httpx dependency). Testing over the wire rather than with an in-process
client is deliberate: it exercises the actual status codes, the actual JSON
codec, and lets us fire genuinely concurrent duplicate submissions, which is
where idempotency is most likely to break.

Run:  python tests/test_idempotency.py        (from the backend/ directory)
Exit code 0 = all passed.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.dirname(HERE)
sys.path.insert(0, BACKEND)

from app.canonical import event_ref, sign  # noqa: E402

PORT = int(os.environ.get("ROADLINK_TEST_PORT", "8077"))
BASE = f"http://127.0.0.1:{PORT}"

_passed = 0
_failed = 0
_failures: list[str] = []


# ----------------------------------------------------------------- assertions

def check(name: str, condition: bool, detail: str = "") -> None:
    global _passed, _failed
    if condition:
        _passed += 1
        print(f"  PASS  {name}")
    else:
        _failed += 1
        _failures.append(f"{name} :: {detail}")
        print(f"  FAIL  {name}   {detail}")


def check_eq(name: str, actual, expected) -> None:
    check(name, actual == expected, f"expected {expected!r}, got {actual!r}")


# ----------------------------------------------------------------- http helper

def request(method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            return exc.code, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return exc.code, {"raw": raw}


# ----------------------------------------------------------------- fixtures

def make_packet(**overrides) -> dict:
    packet = {
        "v": 1,
        "event_id": str(uuid.uuid4()),
        "rider_id": "rl_7f3a91c4",
        "created_at": 1755763200123,
        "lat": 17.4401,
        "lng": 78.3489,
        "acc_m": 12,
        "conf": 82,
        "trigger": ["peak_g", "inactivity"],
        "simulated": True,
    }
    packet.update(overrides)
    packet["sig"] = sign(packet)
    return packet


def submit(packet: dict, path: str = "direct", relay_id: str | None = None) -> tuple[int, dict]:
    delivery: dict = {"path": path}
    if relay_id:
        delivery["relay_id"] = relay_id
    return request("POST", "/api/v1/sos", {"packet": packet, "delivery": delivery})


# ----------------------------------------------------------------- tests

def test_health() -> None:
    print("\n[1] Health / liveness")
    code, body = request("GET", "/healthz")
    check_eq("healthz returns 200", code, 200)
    check_eq("healthz ok flag", body.get("ok"), True)


def test_first_ingest() -> str:
    print("\n[2] First ingest of a new event")
    packet = make_packet()
    code, body = submit(packet, "ble_relay", "rl_relay_b")
    check_eq("first submission returns 201", code, 201)
    check_eq("duplicate flag is false", body.get("duplicate"), False)
    check_eq("signature verified", body.get("sig_valid"), True)
    check_eq("state is RECEIVED", body.get("state"), "RECEIVED")
    check_eq("audit_entries == 1", body.get("audit_entries"), 1)
    return packet["event_id"]


def test_t10_duplicate_submission() -> None:
    """T10: relay POSTs 3x, then rider POSTs directly.
    Expect exactly 1 event row and 4 audit rows."""
    print("\n[3] T10 - duplicate submission (3x relay + 1x direct)")
    packet = make_packet()
    eid = packet["event_id"]

    code1, b1 = submit(packet, "ble_relay", "rl_relay_b")
    code2, b2 = submit(packet, "ble_relay", "rl_relay_b")
    code3, b3 = submit(packet, "ble_relay", "rl_relay_c")
    code4, b4 = submit(packet, "direct")

    check_eq("submission 1 -> 201", code1, 201)
    check_eq("submission 2 -> 200", code2, 200)
    check_eq("submission 3 -> 200", code3, 200)
    check_eq("submission 4 (direct) -> 200", code4, 200)
    check_eq("sub 2 duplicate=true", b2.get("duplicate"), True)
    check_eq("sub 4 duplicate=true", b4.get("duplicate"), True)
    check("no divergence reported", not any(b.get("divergent") for b in (b1, b2, b3, b4)))
    check_eq("audit_entries reaches 4", b4.get("audit_entries"), 4)

    code, event = request("GET", f"/api/v1/sos/{eid}")
    check_eq("event fetch 200", code, 200)
    check_eq("exactly 4 audit rows stored", len(event.get("audit", [])), 4)
    check_eq(
        "first_delivery_path preserved as ble_relay",
        event.get("first_delivery_path"),
        "ble_relay",
    )
    check_eq("first_relay_id preserved", event.get("first_relay_id"), "rl_relay_b")
    actions = [a["action"] for a in event["audit"]]
    check_eq("audit action sequence", actions,
             ["ingest_accepted", "ingest_duplicate", "ingest_duplicate", "ingest_duplicate"])


def test_concurrent_duplicates() -> None:
    """The real race: rider and several relays submit at the same instant."""
    print("\n[4] Concurrent duplicate submission (8 simultaneous POSTs)")
    packet = make_packet()
    eid = packet["event_id"]
    results: list[tuple[int, dict]] = []
    lock = threading.Lock()
    barrier = threading.Barrier(8)

    def worker(i: int) -> None:
        barrier.wait()  # release all threads at the same moment
        r = submit(packet, "ble_relay" if i else "direct", f"rl_relay_{i}" if i else None)
        with lock:
            results.append(r)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    created = [r for r in results if r[0] == 201]
    duplicates = [r for r in results if r[0] == 200]
    check_eq("all 8 requests answered", len(results), 8)
    check_eq("exactly one 201 CREATED", len(created), 1)
    check_eq("the other seven are 200 duplicate", len(duplicates), 7)

    code, event = request("GET", f"/api/v1/sos/{eid}")
    check_eq("event exists once", code, 200)
    check_eq("exactly 8 audit rows (none lost under race)", len(event.get("audit", [])), 8)
    accepted = [a for a in event["audit"] if a["action"] == "ingest_accepted"]
    check_eq("exactly one ingest_accepted audit row", len(accepted), 1)


def test_divergent_duplicate() -> None:
    """Same event_id, different signed content. Must not overwrite."""
    print("\n[5] Divergent duplicate (same event_id, different payload)")
    packet = make_packet(conf=82)
    eid = packet["event_id"]
    submit(packet, "direct")

    tampered = make_packet(event_id=eid, conf=17)  # re-signed, so sig is valid
    code, body = submit(tampered, "ble_relay", "rl_relay_x")
    check_eq("divergent duplicate still 200", code, 200)
    check_eq("divergent flag raised", body.get("divergent"), True)

    _, event = request("GET", f"/api/v1/sos/{eid}")
    check_eq("stored conf NOT overwritten (first write wins)", event.get("conf"), 82)
    actions = [a["action"] for a in event["audit"]]
    check("divergence recorded in audit", "ingest_duplicate_divergent" in actions, str(actions))


def test_signature() -> None:
    print("\n[6] Signature verification")
    good = make_packet()
    _, body = submit(good, "direct")
    check_eq("valid signature -> sig_valid true", body.get("sig_valid"), True)

    tampered = make_packet()
    tampered["conf"] = 99          # mutate AFTER signing
    code, body = submit(tampered, "direct")
    check_eq("tampered packet still accepted (spike default)", code, 201)
    check_eq("but flagged sig_valid=false", body.get("sig_valid"), False)

    unsigned = make_packet()
    unsigned.pop("sig")
    _, body = submit(unsigned, "direct")
    check_eq("unsigned packet flagged sig_valid=false", body.get("sig_valid"), False)


def test_never_deleted_invariant() -> None:
    print("\n[7] Invariant - events and audit rows are never removed")
    before = request("GET", "/api/v1/debug/audit")[1]["counts"]
    packet = make_packet()
    submit(packet, "direct")
    submit(packet, "ble_relay", "rl_relay_z")
    after = request("GET", "/api/v1/debug/audit")[1]["counts"]
    check_eq("event count grew by exactly 1", after["events"] - before["events"], 1)
    check_eq("audit count grew by exactly 2", after["audit"] - before["audit"], 2)


def test_responder_flow() -> None:
    print("\n[8] Responder list and status transitions")
    packet = make_packet()
    eid = packet["event_id"]
    submit(packet, "direct")

    _, active = request("GET", "/api/v1/sos/active")
    ids = [e["event_id"] for e in active["events"]]
    check("new event appears in active list", eid in ids)

    code, _ = request("PATCH", f"/api/v1/sos/{eid}/status", {"state": "CLOSED", "note": "spike test"})
    check_eq("PATCH status -> 200", code, 200)

    _, active2 = request("GET", "/api/v1/sos/active")
    ids2 = [e["event_id"] for e in active2["events"]]
    check("closed event drops out of active list", eid not in ids2)

    _, event = request("GET", f"/api/v1/sos/{eid}")
    check("event itself still exists after close", event.get("event_id") == eid)
    check("status_change recorded in audit",
          "status_change" in [a["action"] for a in event["audit"]])


def test_validation() -> None:
    print("\n[9] Input validation")
    bad = make_packet()
    bad["conf"] = 250
    code, _ = submit(bad, "direct")
    check_eq("conf > 100 rejected with 422", code, 422)

    extra = make_packet()
    extra["rider_phone"] = "+919999999999"
    code, _ = submit(extra, "direct")
    check_eq("unexpected PII field rejected with 422", code, 422)

    bad_lat = make_packet()
    bad_lat["lat"] = 999.0
    code, _ = submit(bad_lat, "direct")
    check_eq("out-of-range latitude rejected with 422", code, 422)

    code, _ = request("GET", "/api/v1/sos/does-not-exist")
    check_eq("unknown event_id -> 404", code, 404)


def test_event_ref() -> None:
    print("\n[10] event_ref matches the BLE beacon reference")
    packet = make_packet()
    eid = packet["event_id"]
    submit(packet, "direct")
    _, event = request("GET", f"/api/v1/sos/{eid}")
    check_eq("event_ref is 8 bytes (16 hex chars)", len(event.get("event_ref", "")), 16)
    check_eq("event_ref matches canonical implementation", event.get("event_ref"), event_ref(eid))
    check("raw event_id is not recoverable from event_ref", event["event_ref"] not in eid)


# ----------------------------------------------------------------- runner

def wait_for_server(timeout: float = 45.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/healthz", timeout=2):
                return True
        except Exception:
            time.sleep(0.4)
    return False


def main() -> int:
    db_path = os.path.join(tempfile.gettempdir(), f"roadlink_test_{uuid.uuid4().hex}.db")
    env = dict(os.environ, ROADLINK_DB=db_path, ROADLINK_ALLOW_RESET="1", PYTHONUNBUFFERED="1")

    print(f"Starting uvicorn on port {PORT} (db: {db_path})")
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1",
         "--port", str(PORT), "--log-level", "warning"],
        cwd=BACKEND, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    try:
        if not wait_for_server():
            print("\nSERVER FAILED TO START. Output:")
            proc.terminate()
            print(proc.communicate(timeout=10)[0])
            return 2

        print("Server up.\n" + "=" * 62)
        test_health()
        test_first_ingest()
        test_t10_duplicate_submission()
        test_concurrent_duplicates()
        test_divergent_duplicate()
        test_signature()
        test_never_deleted_invariant()
        test_responder_flow()
        test_validation()
        test_event_ref()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()

    print("=" * 62)
    print(f"RESULT: {_passed} passed, {_failed} failed")
    if _failures:
        print("\nFailures:")
        for f in _failures:
            print("  - " + f)
    return 0 if _failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

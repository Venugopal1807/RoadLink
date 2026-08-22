"""Cross-language wire check.

Takes the envelope produced by the KOTLIN client (bytes written by
EnvelopeTest) and POSTs it verbatim at a real running backend. If the server
answers sig_valid=true, the Kotlin and Python canonicalisations agree over
real HTTP - which is the thing that cannot be proven by either side alone.
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(REPO, "backend")
FIXTURE = os.path.join(REPO, "android", "app", "build", "wire", "signed-envelope.json")
PORT = int(os.environ.get("ROADLINK_WIRE_PORT", "8099"))
BASE = f"http://127.0.0.1:{PORT}"


def request(method, path, raw_body=None):
    data = raw_body.encode("utf-8") if raw_body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8")
        try:
            return exc.code, json.loads(body)
        except json.JSONDecodeError:
            return exc.code, {"raw": body}


def main():
    if not os.path.exists(FIXTURE):
        print(f"FIXTURE MISSING: {FIXTURE}")
        print("Generate it first:")
        print("  cd android && ./gradlew :app:testDebugUnitTest --tests '*EnvelopeTest*'")
        return 2

    envelope = open(FIXTURE, encoding="utf-8").read()
    print("Kotlin-produced envelope:")
    print(envelope)
    print()

    db = os.path.join(tempfile.gettempdir(), f"roadlink_wire_{uuid.uuid4().hex}.db")
    env = dict(os.environ, ROADLINK_DB=db, PYTHONUNBUFFERED="1")
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app",
         "--host", "127.0.0.1", "--port", str(PORT), "--log-level", "warning"],
        cwd=BACKEND, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    try:
        deadline = time.time() + 45
        up = False
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(BASE + "/healthz", timeout=2):
                    up = True
                    break
            except Exception:
                time.sleep(0.4)
        if not up:
            proc.terminate()
            print("SERVER FAILED TO START:", proc.communicate(timeout=10)[0])
            return 2

        failures = []

        # POST the Kotlin bytes verbatim.
        status, body = request("POST", "/api/v1/sos", envelope)
        print(f"POST /api/v1/sos -> HTTP {status}")
        print(json.dumps(body, indent=2))
        print()

        if status != 201:
            failures.append(f"expected 201, got {status}")
        if body.get("sig_valid") is not True:
            failures.append(
                "sig_valid was not true - the Kotlin and Python canonicalisations DISAGREE"
            )
        if body.get("duplicate") is not False:
            failures.append("first submission should not be a duplicate")

        event_id = body.get("event_id")

        # The simulated path must be recorded as such.
        status2, event = request("GET", f"/api/v1/sos/{event_id}")
        print(f"GET /api/v1/sos/{event_id} -> HTTP {status2}")
        print(json.dumps({k: event.get(k) for k in
                          ("event_id", "simulated", "sig_valid", "first_delivery_path",
                           "first_relay_id", "lat", "lng", "conf", "trigger", "state")},
                         indent=2))
        print()

        if event.get("first_delivery_path") != "simulated_relay":
            failures.append(f"delivery path was {event.get('first_delivery_path')!r}")
        if event.get("simulated") is not True:
            failures.append("simulated flag did not survive the wire")
        if event.get("sig_valid") is not True:
            failures.append("stored sig_valid is not true")
        if event.get("lat") != 17.4401 or event.get("lng") != 78.3489:
            failures.append(f"coordinates round-tripped wrong: {event.get('lat')}, {event.get('lng')}")

        # Same bytes again: must be idempotent.
        status3, body3 = request("POST", "/api/v1/sos", envelope)
        print(f"POST again (same bytes) -> HTTP {status3}, duplicate={body3.get('duplicate')}")
        if status3 != 200 or body3.get("duplicate") is not True:
            failures.append("resubmitting identical bytes was not treated as a duplicate")

        _, event2 = request("GET", f"/api/v1/sos/{event_id}")
        if len(event2.get("audit", [])) != 2:
            failures.append(f"expected 2 audit rows, got {len(event2.get('audit', []))}")

        print()
        print("=" * 62)
        if failures:
            print("RESULT: FAILED")
            for f in failures:
                print("  - " + f)
            return 1
        print("RESULT: PASSED - Kotlin-signed packet verified by the Python backend")
        print("  sig_valid=true over real HTTP")
        print("  delivery path recorded as simulated_relay, not ble_relay")
        print("  identical resubmission -> 1 event, 2 audit rows")
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())

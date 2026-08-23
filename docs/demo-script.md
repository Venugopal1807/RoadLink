# Demo script

Two versions of the same demonstration. Setup is in [`setup.md`](setup.md).

| | When to use | BLE |
|---|---|---|
| **DEMO B** | Default. Use unless the hardware ladder has passed | Not used |
| **DEMO A** | Only if `physical-test-results.md` records T5 and T6 passing | Shown as an addition |

**DEMO B is the baseline and it is complete on its own.** DEMO A is DEMO B plus
one extra scene. Neither depends on BLE working, and the core story is identical:

> RoadLink does not assume connectivity. It preserves the emergency first, then
> attempts delivery through whatever transport is available.

The demo never stages a real crash. **CREATE TEST SOS** drives the same pipeline
a sensor trigger will use (signing, persistence, state machine, transport,
backend, audit); only the trigger is simulated, and the app labels it
`SIMULATED TRIGGER` on screen.

---

## Before you start

```bash
# terminal 1 - backend, bound so the phone can reach it
cd backend && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# terminal 2 - the log is the evidence, keep it on screen
adb logcat -c && adb logcat -s RLINK
```

Build the app against the demo network, not the emulator loopback:

```bash
cd android && ./gradlew :app:assembleDebug -Proadlink.backendUrl=http://<LAN-IP>:8000
```

Starting state on the Rider tab:

| Control | Set to |
|---|---|
| Delivery path | **AUTO** |
| Simulated relay in range | **off** |
| Force rider offline | **off** |
| Scripted relay failures | **0** |
| Arm physical BLE transport | **off** (DEMO B) / **on** (DEMO A, Phone A only) |

Press **Reset script**. Emergencies left from an earlier run are fine; nothing
deletes them, which is the point.

> Say what is real and what is not. Every label on screen already does this, and
> the demo is stronger for matching it.

---

# DEMO B — the guaranteed path (no BLE)

Runs on one phone. Nothing here can fail because of a radio.

### 2-minute sequence

| # | Do | Say / show |
|---|---|---|
| 1 | Nothing yet | "A rider crashes on a highway. They may be unconscious, and they are often exactly where there is no signal. An app that needs a network at the moment of the crash does not solve the case that matters." |
| 2 | Rider tab → **Force rider offline** ON | "First I take the network away, so you can see what happens when delivery cannot succeed." |
| 3 | **CREATE TEST SOS** | Point at the `SIMULATED TRIGGER` badge. The trigger is a button; everything after it is the real pipeline. |
| 4 | Log tab, top line | `PERSISTED <id> ... safe on disk before any delivery attempt` — "On disk before any delivery is attempted. The store has no delete, no purge and no expiry, so no code path can drop an emergency because delivery failed." |
| 5 | Log tab, next lines | Each transport reports unavailable. Event sits at `QUEUED`. |
| 6 | Responder tab | Empty. "That absence is honest. Nothing reached the backend, and the responder view reads the server, not this phone. The emergency is not lost, it is waiting." |
| 7 | **Force rider offline** OFF. Touch nothing else | Within seconds: `ATTEMPT ... via DIRECT NETWORK (REAL)` → `HTTP 201` → `DELIVERED`. |
| 8 | Responder tab | The emergency appears with its delivery path. "Nobody pressed anything. It delivered itself when a path existed." |

That is the whole claim, demonstrated. Steps 3–8 map to the required sequence:
create → persist → no connectivity → stays queued → connectivity returns →
automatic delivery → backend receives it → audit visible on the Rider card.

**Close (15s):**

> RoadLink stores the emergency locally before transmission and retries when a
> transport becomes available. A nearby phone can also carry it over BLE. What is
> proven end to end today is the durable offline path. The radio hop is
> implemented but not yet measured on hardware, and the app refuses to report it
> as working until it is.

### Extending DEMO B to 4–5 minutes

Add any of these after step 8. Pick two; do not run all four.

**Idempotency.** On the delivered event press **Submit again over direct
network**. The log shows `duplicate=true`. Then:

```bash
curl http://localhost:8000/api/v1/debug/audit
```

One event row, two audit rows, `first_delivery_path` unchanged. "The rider and
any number of relays may each submit the same emergency. The backend keeps one
record and every delivery attempt. That redundancy is the design."

**Durability.** Force-stop the app, reopen it. Queued emergencies are still
there and the retry loop resumes on launch.

**Retry with backoff.** Set **Scripted relay failures** to 2, pin the path to
**SIMULATED**, create an SOS. It fails twice, logs `event retained, still
queued` both times, succeeds on the third attempt.

**Architecture (30s, no app).** Show the layer diagram from
[`architecture.md`](architecture.md) and make one point: nothing above
`Transport.kt` imports a Bluetooth class, so BLE is a replaceable transport
rather than the product.

**The simulated relay, if asked about BLE.** Rider tab → **Simulated relay in
range** ON, pin the path to **SIMULATED**, create an SOS. The log says
`(no radio involved)` and the backend records the path as `simulated_relay`,
never `ble_relay`.

> "The BLE transport is implemented and tested at the protocol and orchestration
> layer. Physical validation is pending, so this hop is simulated, and the system
> labels it as simulated everywhere down to the wire path in the database."

---

# DEMO A — with physically validated BLE

**Only run this if [`physical-test-results.md`](physical-test-results.md)
records T5 and T6 passing on your hardware.** If it does not, run DEMO B and say
so. Do not present a simulated relay as a radio hop.

Run DEMO B steps 1–8 first, then add the scene below. Two phones, one APK.

**Setup:** Phone A — **Arm physical BLE transport** ON, **Force rider offline**
ON, delivery path pinned to **BLE**. Phone B — **Relay mode** ON, left online.

| # | Do | Show |
|---|---|---|
| 9 | Phone A: **CREATE TEST SOS** | A: `BLE advertising ...` |
| 10 | Wait | B: `RELAY found emergency ...` → `RELAY read verified packet` → `RELAY took custody ...` |
| 11 | Phone A screen | Reaches **RELAYED**, not DELIVERED |
| 12 | Phone B, still online | `ATTEMPT ... via DIRECT NETWORK` → `DELIVERED` |
| 13 | Responder tab | Emergency present, delivery path `ble_relay` |

> "Phone A says RELAYED, not DELIVERED. A relay's acknowledgement means another
> phone has custody, and that phone may never regain connectivity. So the rider
> keeps trying independently, which is safe because the backend is idempotent."

Two details worth one sentence each if there is time:

- Phone B verified the signature before storing, so a relay cannot be used to
  inject emergencies into the backend.
- Phone B withheld the acknowledgement until the write succeeded, because the
  rider may stop advertising once acknowledged.

**Quote the measured relay success rate only with its sample size and the phone
models it came from.** If T6 has not been run 30 times, do not quote a rate.

---

## Timing

| | DEMO B | DEMO A |
|---|---|---|
| Problem statement | 0:20 | 0:20 |
| Offline → queued | 0:50 | 0:50 |
| Reconnect → delivered | 0:35 | 0:35 |
| Close | 0:15 | 0:15 |
| **Core total** | **2:00** | **2:00** |
| Extensions | +1:30 | — |
| BLE relay scene | — | +1:30 |
| Architecture | +0:30 | +0:30 |
| **Full** | **~4:00** | **~4:00** |

The 2-minute core is the priority. Everything else is optional.

## If something goes wrong

| Symptom | Do this |
|---|---|
| Delivery does not resume after re-enabling network | Press **Deliver now** instead of waiting for the 3s loop |
| Backend unreachable | The Responder tab reports it. Restart uvicorn; queued emergencies are still on the phone and deliver themselves |
| Phone cannot reach the backend | The APK was probably built with the emulator default. Rebuild with `-Proadlink.backendUrl=http://<LAN-IP>:8000` |
| Anything BLE misbehaves | Pin the delivery path to **NETWORK** and continue. That the product works without BLE is the argument, not a retreat |
| Wrong tab state after a rehearsal | **Reset script**, then set the starting-state table above |

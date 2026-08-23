# Demo script

Two demonstrations of the same system. Setup is in [`setup.md`](setup.md);
backend options are in [`deployment.md`](deployment.md).

| | When to use | Depends on BLE |
|---|---|---|
| **Primary demo** | Always. This is the submission demo | No |
| **BLE demo** | Only if the hardware ladder has passed | Yes |

**The primary demo is complete on its own and needs no radio.** The BLE demo is
an addition, not a dependency. Both carry the same claim:

> RoadLink does not assume connectivity. It preserves the emergency first, then
> delivers it through whatever transport becomes available.

No real crash is staged. **CREATE TEST SOS** drives the same pipeline a sensor
would (signing, persistence, state machine, transport, backend, audit). Only the
trigger is simulated, and the app labels it `SIMULATED TRIGGER` on screen.

---

## Before you start

```bash
# terminal 1 - backend
cd backend && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# terminal 2 - the log is the evidence. Keep it visible throughout.
adb logcat -c && adb logcat -s RLINK
```

Build against the backend you are actually using:

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
| Arm physical BLE transport | **off** |

Press **Reset script**. Emergencies from an earlier run are fine — nothing
deletes them, which is the point.

> Two sentences worth saying once, early: the trigger is a button, and BLE has
> not been validated on hardware. Volunteering both makes everything else
> credible.

---

# Primary demo (2 minutes, no BLE)

Twelve steps. `<id>` is the first 8 characters of the event id.

### 1–2. Open RoadLink, show the Rider screen

Three tabs: **Rider**, **Responder**, **Log**. The Rider tab lists what this
phone is holding.

> "This is the rider's phone. Everything it has recorded is on this screen."

### 3. Force connectivity unavailable

Rider tab → **Force rider offline** ON.

```
rider network forced offline = true
```

> "A switch rather than aeroplane mode, so this is reproducible in front of you.
> It blocks the direct upload path exactly as losing signal would."

### 4. Create a simulated emergency

Press **CREATE TEST SOS**. Point at the `SIMULATED TRIGGER` badge.

```
CREATE TEST SOS pressed - trigger is SIMULATED, pipeline is real
```

### 5. Show persistence

```
PERSISTED <id> origin=SIMULATED - safe on disk before any delivery attempt
event <id> confirmed and persisted, now QUEUED
```

> "On disk before anything is transmitted. The store has no delete method, no
> purge and no expiry, so no code path is able to drop an emergency because
> delivery failed."

### 6. Show delivery attempts fail

```
DIRECT NETWORK unavailable for <id>
BLE RELAY unavailable for <id>
SIMULATED RELAY unavailable for <id>
```

> "Every transport reports itself unavailable and says why. Nothing claims
> success."

**Expect these lines once, not repeating.** A transport that reports itself
unavailable is never actually tried, so it does not consume a retry; the reason
is recorded once and repeats are suppressed until it changes. The loop is still
re-checking every three seconds.

### 7. Show the event remains queued

Rider tab. The card shows status **QUEUED**, with its creation time,
confidence, triggers and attempt count.

Responder tab: **"No active emergencies at the backend."**

> "That absence is honest. Nothing has reached the backend, and the responder
> view reads the server, not this phone. The emergency is not lost — it is
> waiting."

### 8. Restore connectivity

**Force rider offline** OFF. Touch nothing else.

```
rider network forced offline = false
```

### 9. Show automatic delivery

Within about three seconds, unprompted:

```
ATTEMPT <id> via DIRECT NETWORK (REAL) attempt=1
direct upload of <id> accepted, HTTP 201
DELIVERED <id> via DIRECT NETWORK
```

The Rider card flips to **DELIVERED**, with `Delivered via: DIRECT NETWORK`.

> "Nobody pressed anything. The retry loop found a path and delivered it."

### 10–11. Open Responder, show the emergency received

The emergency appears, with two separate badges: **trigger fidelity** and
**delivery path**.

> "These are deliberately not collapsed into one flag. A real sensor event
> delivered by a simulator is still not evidence that the relay works."

Location reads `no fix reported` when there is no GPS fix, rather than a
placeholder coordinate.

### 12. Show the audit trail

On the delivered event press **Submit again over direct network (idempotency
check)**.

```
REDELIVER <id> via DIRECT NETWORK -> direct upload; backend HTTP 200, sig_valid=true duplicate=true
```

```bash
curl http://<LAN-IP>:8000/api/v1/debug/audit
```

One event row, two audit rows, `first_delivery_path` unchanged.

> "The rider and any number of relays may each submit the same emergency. The
> backend keeps one record and every delivery attempt. That redundancy is the
> design, and idempotency is what makes it safe."

### Close (15 seconds)

> "RoadLink stores the emergency before transmitting and retries when a path
> appears. A nearby phone can also carry it over Bluetooth. What is proven end to
> end is the durable offline path. The radio hop is implemented but not measured
> on hardware, and the app refuses to report it as working until it is."

---

# BLE demo (optional)

**Run only if [`physical-test-results.md`](physical-test-results.md) records T5
and T6 passing on your hardware.** If it does not, skip this and say so. Do not
present a simulated relay as a radio hop.

Two phones, one APK, roles chosen at runtime.

**Setup** — Phone A: **Arm physical BLE transport** ON, **Force rider offline**
ON, delivery path pinned to **BLE**. Phone B: **Relay mode** ON, left online.

| Step | Phone A | Phone B |
|---|---|---|
| Press CREATE TEST SOS on A | `PERSISTED <id> ...`<br>`ATTEMPT <id> via BLE RELAY (REAL) attempt=1`<br>`BLE advertising <id> ref=<8 bytes> simulated=true` | `RELAY scanning for riders in range` |
| Discovery | `BLE relay connected: <addr>` | `RELAY found emergency ref=<ref> rssi=-NN` |
| Transfer | `BLE packet read by <addr> (NNN B)` | `RELAY read verified packet <id> (NNN B) in NNNms` |
| Custody | `BLE ACK for <id> from relay <relay_id>` | `RELAY took custody of <id> from rider <rider>; stored and queued for upload` |
| Result | `RELAYED <id> to <relay_id> via BLE RELAY - NOT yet confirmed at the backend` | `ATTEMPT <id> via DIRECT NETWORK (REAL) attempt=1`<br>`DELIVERED <id> via DIRECT NETWORK` |

Responder tab then shows the emergency with delivery path `ble_relay`.

> "Phone A says RELAYED, not DELIVERED. The acknowledgement means another phone
> has custody, and that phone may never regain connectivity, so the rider keeps
> trying independently."

Two details worth one sentence each if there is time:

- Phone B verified the signature **before** storing, so a relay cannot be used to
  inject emergencies into the backend.
- Phone B withheld its acknowledgement until the write succeeded, because the
  rider may stop advertising once acknowledged.

**Quote a relay success rate only with its sample size and the handset models.**
If T6 has not been run 30 times, quote nothing.

---

# Backup demo, if BLE fails or is unavailable

Nothing to switch to: **the primary demo above is the backup.** It never touches
the radio. If BLE misbehaves mid-demonstration, pin the delivery path to
**NETWORK** and continue.

> "That the product works without BLE is the argument, not a retreat. BLE is one
> transport behind an interface — the offline queue and the delivery guarantee
> are unchanged whether or not the radio works."

If someone asks to see the relay path anyway, use the simulator and label it:
Rider tab → **Simulated relay in range** ON, path pinned to **SIMULATED**, then
CREATE TEST SOS.

```
SIMULATED relay rl_sim_relay_01 discovered <id> (no radio involved)
SIMULATED relay accepted <id> and is holding it
DELIVERED <id> via SIMULATED RELAY
```

The backend records the path as `simulated_relay`, never `ble_relay`.

> "The BLE transport is implemented and tested at the protocol layer. Physical
> validation is pending, so this hop is simulated — and the system labels it as
> simulated everywhere, down to the wire path stored in the database."

---

## Timing

| Section | Primary | With BLE |
|---|---|---|
| Problem and Rider screen | 0:20 | 0:20 |
| Offline, create, persist, queued (3–7) | 0:50 | 0:50 |
| Restore, deliver, responder (8–11) | 0:35 | 0:35 |
| Audit trail (12) | 0:20 | 0:20 |
| BLE relay | — | +1:30 |
| Close | 0:15 | 0:15 |
| **Total** | **2:20** | **3:50** |

For a hard two minutes, drop step 12.

## If something goes wrong

| Symptom | Do this |
|---|---|
| Delivery does not resume after re-enabling the network | Press **Deliver now** rather than waiting for the loop |
| Backend unreachable | The Responder tab reports it. Restart uvicorn; queued emergencies are still on the phone and deliver themselves |
| Phone cannot reach the backend | The APK was probably built with the emulator default. Rebuild with `-Proadlink.backendUrl=http://<LAN-IP>:8000` |
| `Cleartext HTTP ... not permitted` | You are running a release build. Use the debug APK |
| Deployed backend is slow to answer | Free tiers sleep. Hit `/healthz` a minute beforehand |
| Anything BLE misbehaves | Pin the path to **NETWORK** and continue |
| Rehearsal left odd state | **Reset script**, then re-apply the starting-state table |

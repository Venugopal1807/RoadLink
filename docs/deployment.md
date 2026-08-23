# Deploying the backend

Three options, in the order worth trying. The LAN option is the one that has
actually been verified end to end and is the guaranteed demo path.

| Option | HTTPS | Verified | Effort |
|---|---|---|---|
| A. Render blueprint | Yes | Start command verified locally; **not deployed** | ~5 min, needs a Render account |
| B. Docker image | Depends on host | **NOT VERIFIED** — no Docker daemon available here | ~10 min |
| C. Local / LAN | No | **VERIFIED** end to end on an emulator | ~1 min |

No option requires a secret, an API key or a credential of any kind. Nothing in
this repository needs to be edited to deploy it.

---

## A. Render blueprint (recommended for a public URL)

`render.yaml` at the repository root is a complete Render blueprint. It pins the
build and start commands, sets `/healthz` as the health check, and disables the
destructive reset endpoint.

1. Push the repository to GitHub (already done:
   `github.com/Venugopal1807/RoadLink`).
2. Render dashboard → **New** → **Blueprint** → select the repository.
3. Render reads `render.yaml`, provisions the service, and assigns an HTTPS URL
   of the form `https://roadlink-backend-XXXX.onrender.com`.

**What has been verified here:** the exact start command in the blueprint,
`uvicorn app.main:app --host 0.0.0.0 --port $PORT`, was run locally against
`PORT=8123` and served `GET /healthz` → `200 {"ok":true,...}` and
`GET /api/v1/sos/active` → `200 {"count":0,"events":[]}`.

**What has NOT been verified:** the deployment itself. No Render account was
used and no service was created, so the URL, cold-start behaviour and TLS are
UNKNOWN until you deploy.

### Free-plan caveats, which matter for a live demo

- **The instance sleeps after inactivity.** The first request after a sleep can
  take 30–60 seconds. Hit `/healthz` a minute before demonstrating.
- **The filesystem is ephemeral.** SQLite is wiped on restart, so submitted
  emergencies do not survive one. This is fine for a demo and is exactly the
  prototype limitation recorded in [`limitations.md`](limitations.md). Attach a
  persistent disk, or swap `backend/app/db.py` to PostgreSQL, before anything is
  expected to persist.
- **There is no authentication.** Anyone with the URL can submit or read
  emergencies. See [`security.md`](security.md). Do not leave a public instance
  running after the submission.

## B. Docker (portable)

`backend/Dockerfile` targets any container host — Railway, Fly.io, Cloud Run —
and honours `$PORT`.

```bash
docker build -t roadlink-backend ./backend
docker run -p 8000:8000 roadlink-backend
```

**NOT VERIFIED.** The Docker CLI is present on this machine but the daemon is
not running, so the image was never built or run. Treat the Dockerfile as
untested until you build it.

## C. Local / LAN (the guaranteed path)

This is the configuration every recorded result in
[`verification-log.md`](verification-log.md) was produced against.

```bash
cd backend
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Bind `0.0.0.0`, not `127.0.0.1`, or the phone cannot reach it. Then find the
machine's LAN address:

```bash
ipconfig                 # Windows: the IPv4 Address, e.g. 192.168.1.23
ip addr                  # Linux
ipconfig getifaddr en0   # macOS
```

Phone and laptop must be on the same network. Guest and client-isolated Wi-Fi
silently block this; a phone hotspot that the laptop joins is the reliable
fallback.

Confirm from the phone's browser before building anything:
`http://<LAN-IP>:8000/healthz`.

### Cleartext HTTP on a phone

Debug builds permit cleartext to any host
(`app/src/debug/res/xml/network_security_config.xml`), because the prototype
backend has no TLS certificate and the LAN address is not known at build time.
Release builds still deny it and can only reach an HTTPS endpoint.

An earlier version of the release config tried to allow private-LAN ranges with
CIDR entries such as `192.168.0.0/16`. Android's `<domain>` element matches
hostnames only and does not parse CIDR, so those entries silently matched
nothing. Every recorded run had reached the literal `10.0.2.2` entry, so the gap
went unnoticed. If you see `Cleartext HTTP traffic to ... not permitted` on a
phone, you are running a release build.

---

## Building the app against whichever backend you chose

The URL is a build property. Nothing machine-specific is ever committed.

```bash
cd android

# deployed HTTPS backend
./gradlew :app:assembleDebug -Proadlink.backendUrl=https://roadlink-backend-XXXX.onrender.com

# LAN backend
./gradlew :app:assembleDebug -Proadlink.backendUrl=http://192.168.1.23:8000
```

Omitting the property builds against the emulator loopback `http://10.0.2.2:8000`
and prints a warning. Such an APK reaches nothing from a physical phone.

Confirm the URL actually reached the binary:

```bash
python - <<'PY'
import zipfile, re
z = zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk')
for n in (x for x in z.namelist() if x.endswith('.dex')):
    for m in re.finditer(rb'https?://[0-9A-Za-z.:/-]{6,60}', z.read(n)):
        s = m.group().decode()
        if 'w3.org' not in s:
            print(n, '->', s)
PY
```

## Post-deploy verification

Run these against whatever base URL you ended up with.

```bash
BASE=https://roadlink-backend-XXXX.onrender.com

curl -s $BASE/healthz
# {"ok":true,"service":"roadlink-spike","require_sig":false}

curl -s $BASE/api/v1/sos/active
# {"count":0,"events":[]}
```

Then create an emergency from the app and confirm it appears:

```bash
curl -s $BASE/api/v1/sos/active
curl -s $BASE/api/v1/sos/<event_id>
```

Idempotency is covered by `backend/tests/test_idempotency.py` (61 assertions
against a live server). Re-pointing that suite at a deployed instance is not
supported — it starts and stops its own uvicorn on a throwaway database.

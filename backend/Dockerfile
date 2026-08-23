# RoadLink prototype backend.
#
# Portable across Render, Railway, Fly.io and Cloud Run: every one of them
# injects the listening port as $PORT, which the CMD below honours.
#
# NOTE ON STORAGE: this image writes SQLite to the container filesystem, which
# on a free tier is ephemeral - events are lost when the instance restarts or
# sleeps. That is acceptable for a demo backend and is documented in
# docs/deployment.md. Attach a persistent volume, or move db.py to PostgreSQL,
# before anything is expected to survive.

FROM python:3.12-slim

WORKDIR /app

# Dependencies first, so a code change does not re-run the install layer.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Where SQLite lives. Override to a mounted volume path to make data survive.
ENV ROADLINK_DB=/app/roadlink.db

# Signature verification stays advisory by default, matching local behaviour:
# a canonicalisation mismatch is recorded as sig_valid=false rather than
# silently dropping an emergency. Set to 1 to reject instead.
ENV ROADLINK_REQUIRE_SIG=0

EXPOSE 8000

# $PORT when the platform sets one, 8000 when run locally.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]

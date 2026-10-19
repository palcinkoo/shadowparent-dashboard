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
CREATE INDEX IF NOT EXISTS idx_logs_device ON logs(device_id, ts DESC);
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


def latest_location(device_id=None):
    conn = _connect()
    cur = conn.cursor()
    if device_id:
        cur.execute(_q("SELECT * FROM locations WHERE device_id = %s ORDER BY ts DESC LIMIT 1"),
                    (device_id,))
    else:
        cur.execute(_q("SELECT * FROM locations ORDER BY ts DESC LIMIT 1"))
    row = cur.fetchone()
    out = dict(zip([d[0] for d in cur.description], row)) if row else None
    conn.close()
    return out


def location_track(device_id, limit=500):
    conn = _connect()
    cur = conn.cursor()
    cur.execute(_q("SELECT * FROM locations WHERE device_id = %s ORDER BY ts DESC LIMIT %s"),
                (device_id, limit))
    out = _fetchall(cur)
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

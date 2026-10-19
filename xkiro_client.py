import os
import json
import urllib.request

def _load_env(path=".env"):
    if not os.path.exists(path):
        return
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())

_load_env()

BASE = os.getenv("XKIRO_BASE", "https://api.xkiro.com/v1")
KEY = os.getenv("XKIRO_KEY")
MODEL = os.getenv("XKIRO_MODEL", "qwen/qwen3.8-omni-flash:free")

if not KEY:
    raise RuntimeError("XKIRO_KEY not set in .env")

def chat(message: str, model: str = MODEL) -> str:
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": message}]
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {KEY}",
            "Content-Type": "application/json",
            "User-Agent": "fox-xkiro-client/1.0",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]

if __name__ == "__main__":
    import sys
    msg = " ".join(sys.argv[1:]) or "Hello!"
    print(chat(msg))

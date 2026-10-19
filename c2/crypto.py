"""Server-side crypto — mirrors android/app/src/main/java/com/sp/core/util/Crypto.kt

Android encrypts:  base64url(iv(12) || AES-256-GCM(plaintext))
Plaintext layout: "<device_id>|<kind>|<payload>"
"""
import base64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Must stay in sync with tools/encrypt_payload.py + native-lib.cpp
F1 = [0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x70, 0x81, 0x92, 0xA3, 0xB4]
F2 = [0xFE, 0xDC, 0xBA, 0x98, 0x76, 0x54, 0x32, 0x10, 0x0F, 0x1E, 0x2D]
F3 = [0xC5, 0xD6, 0xE7, 0xF8, 0x09, 0x3C, 0x4B, 0x5A, 0x69, 0x78, 0x11]


def _unmask(arr, seed, n):
    return [arr[i] ^ ((seed + i) & 0xFF) for i in range(n)]


def derive_key() -> bytes:
    b1 = _unmask(F1, 0x5A, 11)
    b2 = _unmask(F2, 0xA5, 11)
    b3 = _unmask(F3, 0x33, 11)
    out = bytearray(32)
    for i in range(32):
        if i % 3 == 0:
            out[i] = b1[i % len(b1)]
        elif i % 3 == 1:
            out[i] = b2[i % len(b2)]
        else:
            out[i] = b3[i % len(b3)]
    return bytes(out)


KEY = derive_key()


def decrypt(blob: str) -> tuple[str, str, str]:
    """Returns (device_id, kind, payload). Raises ValueError on bad data."""
    raw = base64.urlsafe_b64decode(blob + "=" * (-len(blob) % 4))
    if len(raw) < 13:
        raise ValueError("ciphertext too short")
    iv, ct = raw[:12], raw[12:]
    plain = AESGCM(KEY).decrypt(iv, ct, None).decode("utf-8", "replace")
    parts = plain.split("|", 2)
    if len(parts) != 3:
        raise ValueError("malformed payload")
    return parts[0], parts[1], parts[2]

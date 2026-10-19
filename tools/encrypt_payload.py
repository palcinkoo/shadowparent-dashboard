#!/usr/bin/env python3
"""
encrypt_payload.py — AES/GCM encryption of payload classes.dex

Key derivation mirrors native-lib.cpp + NativeKey.kt exactly:
  1. F1/F2/F3 are XOR-unmasked in native: out[i] = in[i] ^ (seed + i)
  2. NativeKey.key() interleaves the three fragments into 32 bytes
  3. Output layout = 12-byte IV || AES-256-GCM ciphertext
"""
import sys
import os
import secrets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Must stay in sync with F1/F2/F3 + seeds in android/app/src/main/cpp/native-lib.cpp
F1 = [0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x70, 0x81, 0x92, 0xA3, 0xB4]
F2 = [0xFE, 0xDC, 0xBA, 0x98, 0x76, 0x54, 0x32, 0x10, 0x0F, 0x1E, 0x2D]
F3 = [0xC5, 0xD6, 0xE7, 0xF8, 0x09, 0x3C, 0x4B, 0x5A, 0x69, 0x78, 0x11]


def unmask(arr, seed, n):
    return [arr[i] ^ ((seed + i) & 0xFF) for i in range(n)]


def derive_key() -> bytes:
    b1 = unmask(F1, 0x5A, 11)
    b2 = unmask(F2, 0xA5, 11)
    b3 = unmask(F3, 0x33, 11)
    out = bytearray(32)
    for i in range(32):
        if i % 3 == 0:
            out[i] = b1[i % len(b1)]
        elif i % 3 == 1:
            out[i] = b2[i % len(b2)]
        else:
            out[i] = b3[i % len(b3)]
    return bytes(out)


NATIVE_KEY = derive_key()


def encrypt(input_path: str, output_path: str):
    with open(input_path, "rb") as f:
        plaintext = f.read()

    iv = secrets.token_bytes(12)
    ciphertext = AESGCM(NATIVE_KEY).encrypt(iv, plaintext, None)

    parent = os.path.dirname(output_path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(iv + ciphertext)

    print(f"[+] Encrypted {input_path} -> {output_path}")
    print(f"    {len(plaintext)} bytes plaintext, key {NATIVE_KEY.hex()}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.dex> <output.dex.enc>")
        sys.exit(1)
    encrypt(sys.argv[1], sys.argv[2])

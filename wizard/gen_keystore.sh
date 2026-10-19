#!/bin/bash
keytool -genkeypair -v -keystore release.keystore -alias sp -keyalg RSA -keysize 2048 -validity 10000

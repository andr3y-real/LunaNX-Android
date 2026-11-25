#!/bin/sh
set -eu
cd "$(dirname "$0")"
umask 077

mkdir -p build/generated build/classes build/dex build/assets/licenses
cp ../LICENSE ../NOTICE ../THIRD_PARTY_NOTICES.md build/assets/
cp build/deps/curl-8.17.0/COPYING build/assets/licenses/curl.txt
cp build/deps/jansson-2.14.1/LICENSE build/assets/licenses/jansson.txt
cp build/deps/opus-1.5.2/COPYING build/assets/licenses/opus.txt
cp -R ../licenses/. build/assets/licenses/
jar=${ANDROID_JAR:-build/android.jar}
aapt2 compile --dir res -o build/resources.zip
aapt2 link -I "$jar" --manifest AndroidManifest.xml --java build/generated \
    -A build/assets -o build/resources.apk build/resources.zip

javac -encoding UTF-8 --release 8 -proc:none -cp "$jar" -d build/classes \
    build/generated/org/lunanx/android/R.java src/org/lunanx/android/MainActivity.java \
    src/org/lunanx/android/PlayerFraming.java src/org/lunanx/android/PlaybackReport.java
jar cf build/java-classes.jar -C build/classes .
d8 --release --min-api 28 --lib "$jar" --output build/dex build/java-classes.jar
llvm-strip --strip-unneeded -o build/liblunanx.so build/native/liblunanx.so

LUNANX_LIBCXX=${LUNANX_LIBCXX:-${PREFIX:-}/lib/libc++_shared.so} python3 - <<'PY'
import os
import shutil
from zipfile import ZIP_DEFLATED, ZipFile

shutil.copyfile("build/resources.apk", "build/unsigned.apk")
with ZipFile("build/unsigned.apk", "a", compression=ZIP_DEFLATED) as apk:
    apk.write("build/dex/classes.dex", "classes.dex")
    apk.write("build/liblunanx.so", "lib/arm64-v8a/liblunanx.so")
    apk.write(os.environ["LUNANX_LIBCXX"],
              "lib/arm64-v8a/libc++_shared.so")
PY

key=${LUNANX_KEYSTORE:-build/lunanx-development.p12}
if [ -z "${LUNANX_KEYSTORE:-}" ] && [ -f signing/lunanx-development.p12 ]; then
    key=signing/lunanx-development.p12
fi
if [ ! -f "$key" ]; then
    keytool -genkeypair -keystore "$key" -storepass android -alias lunanx \
        -dname 'CN=LunaNX Android Development' -keyalg RSA -keysize 2048 -validity 3650
fi
apksigner sign --ks "$key" --ks-key-alias lunanx --ks-pass pass:android \
    --out build/LunaNX-Android.apk build/unsigned.apk
apksigner verify --verbose build/LunaNX-Android.apk

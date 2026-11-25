#!/bin/sh
set -eu
cd "$(dirname "$0")"

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    : "${sdk:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
    sdk=$(cd "$sdk" && pwd)
    ANDROID_NDK_HOME=$(cd "$ANDROID_NDK_HOME" && pwd)
    ndk_host=$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    build_tools=${ANDROID_BUILD_TOOLS:-$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)}
    ANDROID_JAR=$sdk/platforms/android-35/android.jar
    LUNANX_LIBCXX=$ndk_host/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so
    test -f "$ANDROID_JAR" && test -f "$LUNANX_LIBCXX" && test -x "$build_tools/aapt2" || {
        echo "Install Android platform 35, SDK build-tools and the Android NDK." >&2
        exit 1
    }
    PATH="$build_tools:$ndk_host/bin:$PATH"
    export PATH ANDROID_JAR LUNANX_LIBCXX ANDROID_NDK_HOME
    set -- "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared
else
    if [ "$(uname -m)" != aarch64 ] || [ -z "${PREFIX:-}" ]; then
        echo "Set ANDROID_SDK_ROOT and ANDROID_NDK_HOME, or build in arm64 Termux." >&2
        exit 1
    fi
    set --
fi

python3 fetch-dependencies.py
./build-dependencies.sh
cmake -S . -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Release "$@"
cmake --build build/native --parallel "${JOBS:-2}"
./package-apk.sh

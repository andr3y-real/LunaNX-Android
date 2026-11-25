#!/bin/sh
set -eu
cd "$(dirname "$0")"
root=$PWD
deps=$root/build/deps
stage=$root/build/native-deps
jobs=${JOBS:-2}
build_dep() {
    name=$1
    source=$2
    shift 2
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        set -- "$@" "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared
    fi
    cmake -S "$source" -B "$deps/$name-build" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$stage" -DCMAKE_INSTALL_LIBDIR=lib \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 "$@"
    cmake --build "$deps/$name-build" --parallel "$jobs"
    cmake --install "$deps/$name-build"
}
build_dep mbedtls ../extern/libpeer/third_party/mbedtls \
    -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF -DMBEDTLS_FATAL_WARNINGS=OFF \
    "-DCMAKE_C_FLAGS=-I$root -DMBEDTLS_USER_CONFIG_FILE=\\\"lunanx_mbedtls_config.h\\\""
build_dep srtp ../extern/libpeer/third_party/libsrtp -DTEST_APPS=OFF
build_dep jansson "$deps/jansson-2.14.1" -DJANSSON_BUILD_DOCS=OFF -DJANSSON_WITHOUT_TESTS=ON
build_dep opus "$deps/opus-1.5.2" -DOPUS_BUILD_TESTING=OFF -DOPUS_BUILD_PROGRAMS=OFF
build_dep curl "$deps/curl-8.17.0" -DCMAKE_PREFIX_PATH="$stage" \
    "-DMBEDTLS_INCLUDE_DIR=$stage/include" "-DMBEDTLS_LIBRARY=$stage/lib/libmbedtls.a" \
    "-DMBEDX509_LIBRARY=$stage/lib/libmbedx509.a" "-DMBEDCRYPTO_LIBRARY=$stage/lib/libmbedcrypto.a" \
    -DCURL_USE_PKGCONFIG=OFF -DCURL_USE_MBEDTLS=ON -DCURL_USE_OPENSSL=OFF -DCURL_USE_LIBPSL=OFF \
    -DCURL_DISABLE_LDAP=ON -DCURL_DISABLE_LDAPS=ON -DCURL_USE_LIBSSH2=OFF \
    -DUSE_LIBIDN2=OFF -DUSE_NGHTTP2=OFF -DUSE_NGTCP2=OFF \
    -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF -DCURL_ZLIB=OFF -DBUILD_CURL_EXE=OFF \
    -DBUILD_TESTING=OFF -DHTTP_ONLY=ON -DCURL_CA_BUNDLE=none -DCURL_CA_PATH=none

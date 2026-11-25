cmake_minimum_required(VERSION 3.20)
project(LunaNXAndroid LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(ROOT "${CMAKE_CURRENT_LIST_DIR}/..")
set(APP "${ROOT}/app/src")
set(PEER "${ROOT}/extern/libpeer/src")
set(DEPS "${CMAKE_CURRENT_LIST_DIR}/build/native-deps")
file(GLOB PEER_SOURCES CONFIGURE_DEPENDS "${PEER}/*.c")
list(REMOVE_ITEM PEER_SOURCES "${PEER}/peer_signaling.c" "${PEER}/ssl_transport.c")

add_library(lunanx SHARED
    native/bridge.cpp
    native/android_video_decoder.cpp
    native/android_microphone.cpp
    native/android_audio_pipeline.cpp
    native/android_rumble.cpp
    "${APP}/core/app_paths.cpp"
    "${APP}/core/http_client.cpp"
    "${APP}/core/i18n.cpp"
    "${APP}/core/runtime_journal.cpp"
    "${APP}/core/stream_settings.cpp"
    "${APP}/luna/amazon_auth.cpp"
    "${APP}/luna/cookie_jar.cpp"
    "${APP}/luna/luna_client.cpp"
    "${APP}/luna/luna_sdp.cpp"
    "${APP}/luna/recent_games.cpp"
    "${APP}/input/luna_client_messages.cpp"
    "${APP}/input/luna_input_encoder.cpp"
    "${APP}/stream/stream_diagnostics.cpp"
    "${APP}/webrtc/shared.cpp"
    "${APP}/webrtc/luna_stream_session.cpp"
    "${ROOT}/extern/qrcodegen/qrcodegen.c"
    ${PEER_SOURCES}
)
target_include_directories(lunanx PRIVATE
    "${APP}" "${PEER}" "${DEPS}/include" "${DEPS}/include/opus"
    "${ROOT}/extern/qrcodegen" native .
)
target_compile_definitions(lunanx PRIVATE
    CONFIG_USE_USRSCTP=0 HAS_SOCKLEN_T=1 _GNU_SOURCE
    MBEDTLS_USER_CONFIG_FILE="lunanx_mbedtls_config.h"
)
target_compile_options(lunanx PRIVATE -fvisibility=hidden)
target_link_options(lunanx PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)
if(CMAKE_CROSSCOMPILING)
    set(AAUDIO_LIBRARY aaudio)
else()
    target_compile_options(lunanx PRIVATE --target=aarch64-linux-android28)
    target_link_options(lunanx PRIVATE --target=aarch64-linux-android28 -fno-termux-rpath)
    set(AAUDIO_LIBRARY /system/lib64/libaaudio.so)
endif()
target_link_libraries(lunanx PRIVATE
    "${DEPS}/lib/libcurl.a"
    "${DEPS}/lib/libjansson.a"
    "${DEPS}/lib/libopus.a"
    "${DEPS}/lib/libsrtp2.a"
    "${DEPS}/lib/libmbedtls.a"
    "${DEPS}/lib/libmbedx509.a"
    "${DEPS}/lib/libmbedcrypto.a"
    ${AAUDIO_LIBRARY} android mediandk log m dl
)

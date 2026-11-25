#pragma once

#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include "video_presentation_clock.hpp"

class AndroidVideoDecoder
{
public:
    struct Signals
    {
        bool input;
        bool output;
        bool error;
    };

    AndroidVideoDecoder(ANativeWindow* window, int width, int height, int fps,
                        std::function<void()> wake = {});
    ~AndroidVideoDecoder();

    bool ready() const { return started_; }
    Signals signals();
    bool submit_decode_unit(const uint8_t* data, int size, uint32_t timestamp, uint64_t generation);
    int drain_output(uint64_t generation);
    bool set_output_window(ANativeWindow* window, uint64_t generation);
    bool reset_stream(uint64_t generation);
    void cleanup();

private:
    struct Output
    {
        int32_t index;
        AMediaCodecBufferInfo info;
    };

    static void on_input(AMediaCodec*, void*, int32_t);
    static void on_output(AMediaCodec*, void*, int32_t, AMediaCodecBufferInfo*);
    static void on_format(AMediaCodec*, void*, AMediaFormat*);
    static void on_error(AMediaCodec*, void*, media_status_t, int32_t, const char*);

    bool open();
    void close_locked();

    std::mutex mutex_;
    const int width_;
    const int height_;
    const int fps_;
    const std::function<void()> wake_;
    VideoPresentationClock presentation_clock_;
    uint64_t output_generation_ = 0;
    std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)> window_{nullptr, ANativeWindow_release};
    AMediaCodec* codec_ = nullptr;
    bool started_ = false;
    uint32_t last_timestamp_ = 0;
    int64_t presentation_ticks_ = 0;
    bool have_timestamp_ = false;

    std::mutex callback_mutex_;
    AMediaCodec* callback_codec_ = nullptr;
    std::deque<int32_t> inputs_;
    std::deque<Output> outputs_;
    bool codec_error_ = false;
};

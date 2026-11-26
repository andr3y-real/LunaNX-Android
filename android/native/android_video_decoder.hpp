#pragma once

#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <utility>
#include "codec_queue.hpp"
#include "video_presentation_clock.hpp"

class AndroidVideoDecoder
{
public:
    struct Signals
    {
        bool input;
        bool output;
        bool error;
        uint64_t presentation_retry_us;
    };

    enum class SubmitStatus
    {
        Submitted,
        Retry,
        Error,
    };

    AndroidVideoDecoder(ANativeWindow* window, int width, int height, int fps,
                        std::function<void()> wake = {});
    ~AndroidVideoDecoder();

    bool ready() const { return started_; }
    Signals signals();
    size_t codec_pending() const { return codec_queue_.count(); }
    uint64_t slow_codec_outputs() const { return codec_queue_.slow_outputs(); }
    uint64_t peak_playout_reserve_us() const { return peak_playout_reserve_us_.load(std::memory_order_relaxed); }
    uint64_t peak_codec_delay_us() const { return codec_queue_.peak_delay_us(); }
    uint64_t submit_retries() const { return submit_retries_.load(std::memory_order_relaxed); }
    uint64_t deferred_frames_presented() const { return deferred_frames_presented_.load(std::memory_order_relaxed); }
    uint64_t stale_frames_dropped() const { return stale_frames_dropped_.load(std::memory_order_relaxed); }
    std::pair<int, int> video_size() const
    {
        return {decoded_width_.load(std::memory_order_relaxed),
                decoded_height_.load(std::memory_order_relaxed)};
    }
    SubmitStatus submit_decode_unit(const uint8_t* data, int size, uint32_t timestamp, uint64_t generation);
    struct OutputProgress
    {
        int decoded = 0;
        int scheduled = 0;
        bool error = false;
    };
    OutputProgress drain_output(uint64_t generation);
    bool set_output_window(ANativeWindow* window, uint64_t generation);
    bool reset_stream(uint64_t generation);
    void cleanup();

private:
    struct Output
    {
        int32_t index;
        AMediaCodecBufferInfo info;
        uint64_t available_us;
    };

    static void on_input(AMediaCodec*, void*, int32_t);
    static void on_output(AMediaCodec*, void*, int32_t, AMediaCodecBufferInfo*);
    static void on_format(AMediaCodec*, void*, AMediaFormat*);
    static void on_error(AMediaCodec*, void*, media_status_t, int32_t, const char*);

    bool open();
    bool configure_codec(bool low_latency);
    void apply_window_frame_rate();
    void close_locked();

    std::mutex mutex_;
    const int width_;
    const int height_;
    const int fps_;
    const std::function<void()> wake_;
    VideoPresentationClock presentation_clock_;
    CodecQueue codec_queue_;
    std::optional<Output> deferred_output_;
    std::atomic<uint64_t> presentation_retry_us_ {0};
    uint64_t output_generation_ = 0;
    std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)> window_{nullptr, ANativeWindow_release};
    AMediaCodec* codec_ = nullptr;
    bool started_ = false;
    uint32_t last_timestamp_ = 0;
    int64_t presentation_ticks_ = 0;
    bool have_timestamp_ = false;
    std::atomic<int> decoded_width_ {0};
    std::atomic<int> decoded_height_ {0};
    std::atomic<uint64_t> peak_playout_reserve_us_ {0};
    std::atomic<uint64_t> submit_retries_ {0};
    std::atomic<uint64_t> deferred_frames_presented_ {0};
    std::atomic<uint64_t> stale_frames_dropped_ {0};

    std::mutex callback_mutex_;
    AMediaCodec* callback_codec_ = nullptr;
    std::deque<int32_t> inputs_;
    std::deque<Output> outputs_;
    bool codec_error_ = false;
};

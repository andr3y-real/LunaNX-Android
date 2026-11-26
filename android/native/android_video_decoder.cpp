#include "android_video_decoder.hpp"

#include <dlfcn.h>
#include <media/NdkMediaFormat.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <utility>

using Clock = std::chrono::steady_clock;

namespace
{
uint64_t monotonic_us()
{
    return std::chrono::duration_cast<std::chrono::microseconds>(
        Clock::now().time_since_epoch()).count();
}

bool is_config_only_access_unit(const uint8_t* data, int size)
{
    if (!data || size < 4)
        return false;
    bool has_vcl = false;
    bool has_nal = false;
    for (int i = 0; i + 3 < size; ++i)
    {
        int header = -1;
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1)
            header = i + 3;
        else if (i + 4 < size && data[i] == 0 && data[i + 1] == 0 &&
                 data[i + 2] == 0 && data[i + 3] == 1)
            header = i + 4;
        if (header >= 0 && header < size)
        {
            has_nal = true;
            const uint8_t type = data[header] & 0x1f;
            if (type >= 1 && type <= 5)
            {
                has_vcl = true;
                break;
            }
        }
    }
    return has_nal && !has_vcl;
}
} // namespace

AndroidVideoDecoder::AndroidVideoDecoder(ANativeWindow* window, int width, int height, int fps,
                                         std::function<void()> wake)
    : width_(width), height_(height), fps_(fps), wake_(std::move(wake)), presentation_clock_(fps),
      codec_queue_(fps), decoded_width_(width), decoded_height_(height)
{
    set_output_window(window, 0);
}

AndroidVideoDecoder::~AndroidVideoDecoder()
{
    cleanup();
}

void AndroidVideoDecoder::apply_window_frame_rate()
{
    if (!window_)
        return;
    using SetFrameRateFn = int32_t (*)(ANativeWindow*, float, int8_t);
    static const auto set_frame_rate =
        reinterpret_cast<SetFrameRateFn>(dlsym(RTLD_DEFAULT, "ANativeWindow_setFrameRate"));
    if (set_frame_rate)
    {
        constexpr int8_t kFixedSourceCompatibility = 1;
        set_frame_rate(window_.get(), static_cast<float>(std::max(1, fps_)), kFixedSourceCompatibility);
    }
}

bool AndroidVideoDecoder::configure_codec(bool low_latency)
{
    const std::unique_ptr<AMediaFormat, decltype(&AMediaFormat_delete)> format(
        AMediaFormat_new(), AMediaFormat_delete);
    if (!codec_ || !format)
        return false;
    AMediaFormat_setString(format.get(), "mime", "video/avc");
    AMediaFormat_setInt32(format.get(), "width", width_);
    AMediaFormat_setInt32(format.get(), "height", height_);
    AMediaFormat_setInt32(format.get(), "max-input-size", 4 * 1024 * 1024);
    AMediaFormat_setInt32(format.get(), "priority", 0);
    if (low_latency)
    {
        AMediaFormat_setInt32(format.get(), "low-latency", 1);
        AMediaFormat_setInt32(format.get(), "max-bframes", 0);
        AMediaFormat_setFloat(format.get(), "operating-rate",
                              static_cast<float>(std::max(fps_ * 2, 120)));
        AMediaFormat_setInt32(format.get(), "vendor.qti-ext-dec-low-latency.enable", 1);
        AMediaFormat_setInt32(format.get(), "vendor.rtc-ext-dec-low-latency.enable", 1);
    }
    return AMediaCodec_configure(codec_, format.get(), window_.get(), nullptr, 0) == AMEDIA_OK;
}

bool AndroidVideoDecoder::open()
{
    if (!window_)
        return false;

    apply_window_frame_rate();
    for (int attempt = 0; attempt < 2; ++attempt)
    {
        codec_ = AMediaCodec_createDecoderByType("video/avc");
        if (!codec_)
            break;
        {
            std::lock_guard lock(callback_mutex_);
            callback_codec_ = codec_;
        }
        const AMediaCodecOnAsyncNotifyCallback callbacks {on_input, on_output, on_format, on_error};
        const bool try_low_latency = (attempt == 0);
        if (AMediaCodec_setAsyncNotifyCallback(codec_, callbacks, this) == AMEDIA_OK &&
            configure_codec(try_low_latency))
        {
            started_ = AMediaCodec_start(codec_) == AMEDIA_OK;
            if (started_)
                return true;
        }
        close_locked();
    }
    return false;
}

void AndroidVideoDecoder::on_input(AMediaCodec* codec, void* context, int32_t index)
{
    auto& decoder = *static_cast<AndroidVideoDecoder*>(context);
    {
        std::lock_guard lock(decoder.callback_mutex_);
        if (codec != decoder.callback_codec_)
            return;
        decoder.inputs_.push_back(index);
    }
    if (decoder.wake_)
        decoder.wake_();
}

void AndroidVideoDecoder::on_output(AMediaCodec* codec, void* context, int32_t index, AMediaCodecBufferInfo* info)
{
    const auto available_us = monotonic_us();
    auto& decoder = *static_cast<AndroidVideoDecoder*>(context);
    {
        std::lock_guard lock(decoder.callback_mutex_);
        if (codec != decoder.callback_codec_)
            return;
        decoder.outputs_.push_back({index, *info, available_us});
    }
    if (decoder.wake_)
        decoder.wake_();
}

void AndroidVideoDecoder::on_format(AMediaCodec* codec, void* context, AMediaFormat* format)
{
    if (!format)
        return;
    auto& decoder = *static_cast<AndroidVideoDecoder*>(context);
    int32_t w = 0, h = 0;
    int32_t left = 0, right = 0, top = 0, bottom = 0;
    if (AMediaFormat_getInt32(format, "crop-left", &left) &&
        AMediaFormat_getInt32(format, "crop-right", &right) &&
        AMediaFormat_getInt32(format, "crop-top", &top) &&
        AMediaFormat_getInt32(format, "crop-bottom", &bottom) &&
        right >= left && bottom >= top)
    {
        w = right - left + 1;
        h = bottom - top + 1;
    }
    else
    {
        AMediaFormat_getInt32(format, "width", &w);
        AMediaFormat_getInt32(format, "height", &h);
    }
    if (w > 0 && h > 0)
    {
        std::lock_guard lock(decoder.callback_mutex_);
        if (codec == decoder.callback_codec_)
        {
            decoder.decoded_width_.store(w, std::memory_order_relaxed);
            decoder.decoded_height_.store(h, std::memory_order_relaxed);
        }
    }
    AMediaFormat_delete(format);
}

void AndroidVideoDecoder::on_error(AMediaCodec* codec, void* context, media_status_t, int32_t action, const char*)
{
    if (AMediaCodecActionCode_isTransient(action))
        return;
    auto& decoder = *static_cast<AndroidVideoDecoder*>(context);
    {
        std::lock_guard lock(decoder.callback_mutex_);
        if (codec != decoder.callback_codec_)
            return;
        decoder.codec_error_ = true;
    }
    if (decoder.wake_)
        decoder.wake_();
}

AndroidVideoDecoder::Signals AndroidVideoDecoder::signals()
{
    std::lock_guard lock(callback_mutex_);
    return {!inputs_.empty(), !outputs_.empty(), codec_error_};
}

AndroidVideoDecoder::OutputProgress AndroidVideoDecoder::drain_output(uint64_t generation)
{
    std::lock_guard lock(mutex_);
    OutputProgress progress;
    if (!started_ || generation != output_generation_)
    {
        progress.error = true;
        return progress;
    }

    std::array<Output, 32> frames;
    size_t count = 0;
    {
        std::lock_guard callback_lock(callback_mutex_);
        while (!outputs_.empty() && count < frames.size())
        {
            frames[count++] = outputs_.front();
            outputs_.pop_front();
        }
    }

    size_t pictures = 0;
    for (size_t i = 0; i < count; ++i)
    {
        const auto& output = frames[i];
        if (!(output.info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG))
            frames[pictures++] = output;
        else if (AMediaCodec_releaseOutputBuffer(codec_, output.index, false) != AMEDIA_OK)
        {
            progress.error = true;
            return progress;
        }
    }

    // Preserve the compressed reference chain and catch up by dropping output
    // pictures. Keep only the two newest pictures eligible for the Surface.
    const auto now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
        Clock::now().time_since_epoch()).count();
    for (size_t i = 0; i < pictures; ++i)
    {
        const auto& output = frames[i];
        const bool stale = pictures - i > 2;
        const auto when_ns = stale ? std::nullopt : presentation_clock_.schedule(
            output.info.presentationTimeUs * 1000LL, now_ns, i + 1 < pictures);
        const auto released = when_ns
            ? AMediaCodec_releaseOutputBufferAtTime(codec_, output.index, *when_ns)
            : AMediaCodec_releaseOutputBuffer(codec_, output.index, false);
        if (released != AMEDIA_OK)
        {
            progress.error = true;
            break;
        }
        if (stale)
            stale_frames_dropped_.fetch_add(1, std::memory_order_relaxed);
        codec_queue_.produced(output.info.presentationTimeUs, output.available_us);
        ++progress.decoded;
        if (when_ns)
            ++progress.scheduled;
    }
    return progress;
}

AndroidVideoDecoder::SubmitStatus AndroidVideoDecoder::submit_decode_unit(
    const uint8_t* data, int size, uint32_t timestamp, uint64_t generation)
{
    std::lock_guard lock(mutex_);
    if (!started_ || generation != output_generation_ || !data || size <= 0)
        return SubmitStatus::Error;

    int32_t index;
    {
        std::lock_guard callback_lock(callback_mutex_);
        if (codec_error_)
            return SubmitStatus::Error;
        if (inputs_.empty())
        {
            submit_retries_.fetch_add(1, std::memory_order_relaxed);
            return SubmitStatus::Retry;
        }
        index = inputs_.front();
        inputs_.pop_front();
    }
    size_t capacity = 0;
    auto* buffer = AMediaCodec_getInputBuffer(codec_, index, &capacity);
    if (!buffer || static_cast<size_t>(size) > capacity)
    {
        std::lock_guard callback_lock(callback_mutex_);
        if (codec_ == callback_codec_)
            inputs_.push_front(index);
        return SubmitStatus::Error;
    }

    if (have_timestamp_)
        presentation_ticks_ += static_cast<int32_t>(timestamp - last_timestamp_);
    last_timestamp_ = timestamp;
    have_timestamp_ = true;
    std::memcpy(buffer, data, size);
    const auto timestamp_us = presentation_ticks_ > 0 ? presentation_ticks_ * 100 / 9 : 0;
    const uint32_t flags = is_config_only_access_unit(data, size)
        ? AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
        : 0u;
    const auto submitted_us = monotonic_us();
    if (AMediaCodec_queueInputBuffer(codec_, index, 0, size, timestamp_us, flags) != AMEDIA_OK)
        return SubmitStatus::Error;
    if (flags == 0)
        codec_queue_.submitted(timestamp_us, submitted_us);
    return SubmitStatus::Submitted;
}

bool AndroidVideoDecoder::set_output_window(ANativeWindow* window, uint64_t generation)
{
    std::lock_guard lock(mutex_);
    if (window)
        ANativeWindow_acquire(window);
    close_locked();
    window_.reset(window);
    output_generation_ = generation;
    return !window || open();
}

bool AndroidVideoDecoder::reset_stream(uint64_t generation)
{
    std::lock_guard lock(mutex_);
    if (generation != output_generation_)
        return false;
    close_locked();
    return open();
}

void AndroidVideoDecoder::cleanup()
{
    std::lock_guard lock(mutex_);
    close_locked();
    window_.reset();
}

void AndroidVideoDecoder::close_locked()
{
    {
        std::lock_guard lock(callback_mutex_);
        callback_codec_ = nullptr;
        inputs_.clear();
        outputs_.clear();
        codec_error_ = false;
    }
    if (codec_)
    {
        AMediaCodec_setAsyncNotifyCallback(codec_, {}, nullptr);
        if (started_)
            AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
    }
    codec_ = nullptr;
    started_ = false;
    have_timestamp_ = false;
    presentation_ticks_ = 0;
    presentation_clock_.reset();
    codec_queue_.reset();
}

#include "android_video_decoder.hpp"

#include <media/NdkMediaFormat.h>
#include <chrono>
#include <cstring>
#include <utility>

using Clock = std::chrono::steady_clock;

static uint64_t monotonic_us()
{
    return std::chrono::duration_cast<std::chrono::microseconds>(
        Clock::now().time_since_epoch()).count();
}

AndroidVideoDecoder::AndroidVideoDecoder(ANativeWindow* window, int width, int height, int fps,
                                         std::function<void()> wake)
    : width_(width), height_(height), fps_(fps), wake_(std::move(wake)), presentation_clock_(fps),
      codec_queue_(fps)
{
    set_output_window(window, 0);
}

AndroidVideoDecoder::~AndroidVideoDecoder()
{
    cleanup();
}

bool AndroidVideoDecoder::open()
{
    if (!window_)
        return false;

    codec_ = AMediaCodec_createDecoderByType("video/avc");
    const std::unique_ptr<AMediaFormat, decltype(&AMediaFormat_delete)> format(
        AMediaFormat_new(), AMediaFormat_delete);
    if (codec_ && format)
    {
        AMediaFormat_setString(format.get(), "mime", "video/avc");
        AMediaFormat_setInt32(format.get(), "width", width_);
        AMediaFormat_setInt32(format.get(), "height", height_);
        AMediaFormat_setInt32(format.get(), "max-input-size", 2 * 1024 * 1024);
        AMediaFormat_setInt32(format.get(), "low-latency", 1);
        AMediaFormat_setInt32(format.get(), "priority", 0);
        AMediaFormat_setFloat(format.get(), "operating-rate", static_cast<float>(fps_));
        {
            std::lock_guard lock(callback_mutex_);
            callback_codec_ = codec_;
        }
        const AMediaCodecOnAsyncNotifyCallback callbacks {on_input, on_output, on_format, on_error};
        if (AMediaCodec_setAsyncNotifyCallback(codec_, callbacks, this) == AMEDIA_OK &&
            AMediaCodec_configure(codec_, format.get(), window_.get(), nullptr, 0) == AMEDIA_OK)
            started_ = AMediaCodec_start(codec_) == AMEDIA_OK;
    }
    if (!started_)
        close_locked();
    return started_;
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
    auto& decoder = *static_cast<AndroidVideoDecoder*>(context);
    {
        std::lock_guard lock(decoder.callback_mutex_);
        if (codec != decoder.callback_codec_)
            return;
        decoder.outputs_.push_back({index, *info});
    }
    if (decoder.wake_)
        decoder.wake_();
}

void AndroidVideoDecoder::on_format(AMediaCodec*, void*, AMediaFormat* format)
{
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

    for (int count = 0; count < 16; ++count)
    {
        Output output {};
        bool output_waiting;
        {
            std::lock_guard callback_lock(callback_mutex_);
            if (outputs_.empty())
                break;
            output = outputs_.front();
            outputs_.pop_front();
            output_waiting = !outputs_.empty();
        }
        if (output.info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG)
        {
            if (AMediaCodec_releaseOutputBuffer(codec_, output.index, false) != AMEDIA_OK)
            {
                progress.error = true;
                break;
            }
            continue;
        }

        const auto now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            Clock::now().time_since_epoch()).count();
        const auto when_ns = presentation_clock_.schedule(output.info.presentationTimeUs * 1000, now_ns, output_waiting);
        const auto released = when_ns ? AMediaCodec_releaseOutputBufferAtTime(codec_, output.index, *when_ns)
                                      : AMediaCodec_releaseOutputBuffer(codec_, output.index, false);
        if (released != AMEDIA_OK)
        {
            progress.error = true;
            break;
        }
        codec_queue_.produced(output.info.presentationTimeUs, static_cast<uint64_t>(now_ns / 1000));
        ++progress.decoded;
        if (when_ns)
            ++progress.scheduled;
    }
    return progress;
}

bool AndroidVideoDecoder::submit_decode_unit(
    const uint8_t* data, int size, uint32_t timestamp, uint64_t generation)
{
    std::lock_guard lock(mutex_);
    if (!started_ || generation != output_generation_)
        return false;

    int32_t index;
    {
        std::lock_guard callback_lock(callback_mutex_);
        if (inputs_.empty() || codec_error_)
            return false;
        index = inputs_.front();
        inputs_.pop_front();
    }
    size_t capacity = 0;
    auto* buffer = AMediaCodec_getInputBuffer(codec_, index, &capacity);
    if (!buffer || size <= 0 || static_cast<size_t>(size) > capacity)
        return false;

    if (have_timestamp_)
        presentation_ticks_ += static_cast<int32_t>(timestamp - last_timestamp_);
    last_timestamp_ = timestamp;
    have_timestamp_ = true;
    std::memcpy(buffer, data, size);
    const auto timestamp_us = presentation_ticks_ > 0 ? presentation_ticks_ * 100 / 9 : 0;
    if (AMediaCodec_queueInputBuffer(codec_, index, 0, size, timestamp_us, 0) != AMEDIA_OK)
        return false;
    codec_queue_.submitted(timestamp_us, monotonic_us());
    return true;
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

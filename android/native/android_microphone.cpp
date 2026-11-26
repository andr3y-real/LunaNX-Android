#include "android_microphone.hpp"

#include <aaudio/AAudio.h>
#include <opus.h>
#include <array>

AndroidMicrophone::~AndroidMicrophone() { stop(); }

bool AndroidMicrophone::start(std::function<void(const uint8_t*, size_t)> send)
{
    if (active())
        return true;
    stop();
    int error;
    encoder_ = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder_)
        return false;
    opus_encoder_ctl(encoder_, OPUS_SET_BITRATE(24000));
    opus_encoder_ctl(encoder_, OPUS_SET_INBAND_FEC(1));
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
    {
        stop();
        return false;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setInputPreset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    error = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (error != AAUDIO_OK || AAudioStream_getSampleRate(stream_) != 48000 ||
        AAudioStream_getChannelCount(stream_) != 1 ||
        AAudioStream_getFormat(stream_) != AAUDIO_FORMAT_PCM_I16 ||
        AAudioStream_requestStart(stream_) != AAUDIO_OK)
    {
        stop();
        return false;
    }
    send_ = std::move(send);
    running_.store(true);
    worker_ = std::thread([this] { capture(); });
    return true;
}

void AndroidMicrophone::capture()
{
    // One Opus packet spans 20 ms, matching libpeer's outgoing RTP clock.
    std::array<int16_t, 960> pcm{};
    std::array<uint8_t, 1275> packet{};
    int filled = 0;
    while (running_.load())
    {
        const int read = AAudioStream_read(stream_, pcm.data() + filled,
                                          pcm.size() - filled, 20'000'000);
        if (read < 0)
            break;
        filled += read;
        if (filled != pcm.size())
            continue;
        filled = 0;
        const int bytes = opus_encode(encoder_, pcm.data(), pcm.size(),
                                      packet.data(), packet.size());
        if (bytes < 0)
            break;
        if (running_.load())
            send_(packet.data(), bytes);
    }
    running_.store(false);
}

void AndroidMicrophone::stop()
{
    running_.store(false);
    if (stream_)
        AAudioStream_requestStop(stream_);
    if (worker_.joinable())
        worker_.join();
    if (stream_)
    {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
    if (encoder_)
    {
        opus_encoder_destroy(encoder_);
        encoder_ = nullptr;
    }
    send_ = {};
}

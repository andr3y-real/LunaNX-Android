#include "stream/audio/AudioPipeline.hpp"
#include "stream/audio/AudioRtpUtils.hpp"

#include "peer_connection.h"
#include <aaudio/AAudio.h>
#include <opus.h>

#include <algorithm>
#include <array>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

namespace
{
constexpr int sample_rate = 48000;
constexpr int channels = 2;
constexpr int maximum_opus_frames = 5760;

struct Packet
{
    uint32_t ssrc;
    uint16_t sequence;
    std::vector<uint8_t> payload;
};
}

struct AudioPipeline::Impl
{
    std::mutex mutex;
    std::condition_variable available;
    std::deque<Packet> packets;
    std::thread worker;
    std::atomic<bool> running {false};
    size_t queue_limit = 8;
    int volume = 100;

    void play()
    {
        int error = OPUS_OK;
        OpusDecoder* decoder = opus_decoder_create(sample_rate, channels, &error);
        if (!decoder)
            return;

        AAudioStreamBuilder* builder = nullptr;
        AAudioStream* stream = nullptr;
        if (AAudio_createStreamBuilder(&builder) == AAUDIO_OK)
        {
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setSampleRate(builder, sample_rate);
            AAudioStreamBuilder_setChannelCount(builder, channels);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_openStream(builder, &stream);
            AAudioStreamBuilder_delete(builder);
        }

        if (stream && AAudioStream_requestStart(stream) == AAUDIO_OK)
        {
            std::array<int16_t, maximum_opus_frames * channels> pcm {};
            uint32_t ssrc = 0;
            uint16_t sequence = 0;
            while (running.load())
            {
                std::unique_lock lock(mutex);
                available.wait(lock, [this] { return !running.load() || !packets.empty(); });
                if (!running.load())
                    break;
                Packet packet = std::move(packets.front());
                packets.pop_front();
                lock.unlock();

                if (ssrc != packet.ssrc)
                    opus_decoder_ctl(decoder, OPUS_RESET_STATE);
                else if (static_cast<int16_t>(packet.sequence - sequence) <= 0)
                    continue;
                ssrc = packet.ssrc;
                sequence = packet.sequence;

                const int frames = opus_decode(decoder, packet.payload.data(), packet.payload.size(),
                                               pcm.data(), maximum_opus_frames, 0);
                if (frames <= 0)
                    continue;
                if (volume != 100)
                    for (int i = 0; i < frames * channels; ++i)
                        pcm[i] = static_cast<int16_t>(pcm[i] * volume / 100);
                int written = 0;
                while (written < frames && running.load())
                {
                    const auto result = AAudioStream_write(stream, pcm.data() + written * channels,
                                                          frames - written, 15'000'000);
                    if (result <= 0)
                        break;
                    written += result;
                }
            }
            AAudioStream_requestStop(stream);
        }
        if (stream)
            AAudioStream_close(stream);
        opus_decoder_destroy(decoder);
    }
};

AudioPipeline::AudioPipeline() : impl_(std::make_unique<Impl>()) {}
AudioPipeline::~AudioPipeline() { stop(); }

bool AudioPipeline::start()
{
    if (impl_->running.exchange(true))
        return true;
    impl_->worker = std::thread([this] { impl_->play(); });
    return true;
}

void AudioPipeline::configure(int volume_percent, int target_buffer_ms)
{
    impl_->volume = std::clamp(volume_percent, 0, 100);
    impl_->queue_limit = std::clamp(target_buffer_ms / 10, 4, 24);
}

void AudioPipeline::stop()
{
    {
        std::lock_guard lock(impl_->mutex);
        impl_->running.store(false);
        impl_->packets.clear();
    }
    impl_->available.notify_all();
    if (impl_->worker.joinable())
        impl_->worker.join();
}

void AudioPipeline::submit(const PeerAudioPacket& packet)
{
    if (!impl_->running.load())
        return;
    const auto payload = lunanx::audio::ParseRedPrimary(packet.data, packet.size, packet.payload_type);
    if (!payload.data || !payload.size)
        return;

    std::lock_guard lock(impl_->mutex);
    if (!impl_->running.load())
        return;
    if (impl_->packets.size() >= impl_->queue_limit)
        impl_->packets.pop_front();
    impl_->packets.push_back({packet.ssrc, packet.sequence,
                             {payload.data, payload.data + payload.size}});
    impl_->available.notify_one();
}

void AudioPipeline::set_sender_report(uint32_t, uint64_t, uint32_t) {}
std::string AudioPipeline::debug_info() const { return "AAudio"; }
int64_t AudioPipeline::playback_ntp_us() const { return -1; }

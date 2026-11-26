// LunaNX-Origin: KazaR-RooT | urn:uuid:57e9877e-564c-4275-ae2a-a70f276bfbed | unit=f73c2f77115f46bff6eb
// Project origin: https://github.com/kazar-root/LunaNX (third-party notices remain applicable).

#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace lunanx::video
{

// Keep roughly 80 ms of complete pictures. The extra frame absorbs short
// retransmission/decode bursts without building a latency-heavy backlog.
constexpr std::size_t MaximumQueuedAccessUnits(int frames_per_second)
{
    const int safe_fps = std::max(1, frames_per_second);
    return static_cast<std::size_t>(
        std::clamp((safe_fps + 11) / 12, 2, 6));
}

template<class Queue>
std::size_t PicturesBeforeNewestKeyframe(const Queue& queue)
{
    for (std::size_t i = queue.size(); i > 0; --i)
        if (queue[i - 1].idr)
            return i - 1;
    return queue.size();
}

class AndroidDecodeRecovery
{
public:
    enum class Action { Wait, RequestKeyframe, RestartCodec };
    static constexpr std::uint64_t kNoOutputUs = 650'000;

    struct Progress
    {
        std::uint64_t last_output_us;
        std::uint64_t video_run_start_us;
        std::uint64_t last_picture_us;
        std::uint64_t last_input_us;
        std::uint64_t last_idr_input_us;
        std::uint64_t queued_since_us;
        bool input_available;
    };

    Action poll(std::uint64_t now_us, const Progress& progress)
    {
        constexpr std::uint64_t recent_video_us = 250'000;
        const auto live_since = std::max(progress.last_output_us, progress.video_run_start_us);
        if (probe_started_us_ != 0 && live_since > probe_started_us_)
            reset();
        if (live_since == 0 || !elapsed(now_us, live_since, kNoOutputUs) ||
            progress.last_picture_us <= live_since || progress.last_picture_us > now_us ||
            now_us - progress.last_picture_us > recent_video_us)
        {
            reset();
            return Action::Wait;
        }

        // A short starvation event can leave timestamp records behind inside
        // the codec. Look for successful input and output activity instead.
        if (probe_started_us_ == 0)
        {
            probe_started_us_ = now_us;
            last_probe_us_ = now_us;
            return Action::RequestKeyframe;
        }

        const bool submitted_keyframe = progress.last_idr_input_us >= probe_started_us_ &&
            elapsed(now_us, progress.last_idr_input_us, 450'000);
        const bool blocked_input = !progress.input_available && progress.queued_since_us != 0 &&
            elapsed(now_us, std::max(progress.queued_since_us, progress.last_input_us), 700'000);
        if (elapsed(now_us, probe_started_us_, 700'000) && (submitted_keyframe || blocked_input))
        {
            reset();
            return Action::RestartCodec;
        }
        if (elapsed(now_us, last_probe_us_, 1'200'000))
        {
            last_probe_us_ = now_us;
            return Action::RequestKeyframe;
        }
        return Action::Wait;
    }

    void reset() { probe_started_us_ = 0; last_probe_us_ = 0; }

private:
    static constexpr bool elapsed(std::uint64_t now, std::uint64_t since, std::uint64_t duration)
    {
        return now >= since && now - since >= duration;
    }

    std::uint64_t probe_started_us_ = 0;
    std::uint64_t last_probe_us_ = 0;
};

constexpr std::size_t ReadyFrameQueueCapacity()
{
    return 3;
}

constexpr std::size_t StartupReadyFrameDepth()
{
    return 2;
}

// Wait only when the ready queue is empty. This adaptive micro-reserve catches
// a decoder completion racing the UI frame without stalling input/render for a
// large part of the refresh interval. Runtime prediction may shorten or skip it.
constexpr int FrameWaitBudgetMicroseconds(int frames_per_second)
{
    const int safe_fps = std::max(1, frames_per_second);
    return std::clamp((1000000 / safe_fps) * 2 / 5, 2500, 6500);
}

} // namespace lunanx::video

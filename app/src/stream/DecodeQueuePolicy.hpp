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

constexpr bool AndroidCodecOutputStalled(std::uint64_t now_us, std::uint64_t last_output_us,
                                         std::uint64_t oldest_input_us, std::uint64_t last_picture_us,
                                         std::size_t pending)
{
    constexpr std::uint64_t stall_us = 350'000;
    return pending >= 3 && oldest_input_us != 0 &&
           now_us >= oldest_input_us && now_us - oldest_input_us >= stall_us &&
           now_us >= last_output_us && now_us - last_output_us >= stall_us &&
           last_picture_us > oldest_input_us && now_us >= last_picture_us &&
           now_us - last_picture_us <= 250'000;
}

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

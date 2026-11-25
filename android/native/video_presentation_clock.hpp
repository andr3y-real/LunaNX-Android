#pragma once

#include <algorithm>
#include <cstdint>
#include <optional>

class VideoPresentationClock
{
public:
    explicit VideoPresentationClock(int fps)
        : frame_ns_((1'000'000'000LL + std::max(1, fps) - 1) / std::max(1, fps)) {}

    std::optional<int64_t> schedule(int64_t media_ns, int64_t now_ns, bool output_waiting = false)
    {
        const int64_t latest = now_ns + 2 * frame_ns_;
        const int64_t earliest = anchored_ ? std::max(now_ns, last_output_ns_ + frame_ns_) : now_ns;
        // SurfaceView can collapse frames scheduled for the same VSYNC. If all
        // slots within the latency budget are claimed, return the buffer unused.
        if (earliest > latest)
            return std::nullopt;

        const int64_t target = local_anchor_ns_ + (media_ns - media_anchor_ns_);
        const bool restart = !anchored_ || media_ns <= last_media_ns_ || target < now_ns;
        // When more decoded output is ready, use the first available slot
        // instead of adding a frame of startup delay to the whole burst.
        const int64_t presentation = restart ? std::max(earliest, now_ns + (output_waiting ? 0 : frame_ns_))
                                             : std::clamp(target, earliest, latest);
        if (restart || target > latest)
        {
            anchored_ = true;
            media_anchor_ns_ = media_ns;
            local_anchor_ns_ = presentation;
        }
        last_media_ns_ = media_ns;
        last_output_ns_ = presentation;
        return presentation;
    }

    void reset() { anchored_ = false; last_output_ns_ = 0; }

private:
    const int64_t frame_ns_;
    bool anchored_ = false;
    int64_t media_anchor_ns_ = 0;
    int64_t local_anchor_ns_ = 0;
    int64_t last_media_ns_ = 0;
    int64_t last_output_ns_ = 0;
};

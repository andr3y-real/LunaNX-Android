#pragma once

#include <algorithm>
#include <cstdint>

class VideoPresentationClock
{
public:
    explicit VideoPresentationClock(int fps)
        : frame_ns_(1'000'000'000 / std::max(1, fps)) {}

    int64_t schedule(int64_t media_ns, int64_t now_ns)
    {
        const int64_t target = local_anchor_ns_ + (media_ns - media_anchor_ns_);
        const int64_t latest = now_ns + 2 * frame_ns_;
        const bool restart = !anchored_ || media_ns <= last_media_ns_ || target < now_ns;
        last_media_ns_ = media_ns;
        if (!restart && target <= latest)
            return last_output_ns_ = target;

        const int64_t presentation = restart
            ? std::max(now_ns + frame_ns_, std::min(last_output_ns_, latest))
            : latest;
        anchored_ = true;
        media_anchor_ns_ = media_ns;
        local_anchor_ns_ = presentation;
        return last_output_ns_ = presentation;
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

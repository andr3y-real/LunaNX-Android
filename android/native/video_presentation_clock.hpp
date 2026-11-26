#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>

class VideoPresentationClock
{
public:
    struct Picture
    {
        int64_t media_ns;
        int64_t ready_ns;
    };

    explicit VideoPresentationClock(int fps)
        : frame_ns_((1'000'000'000LL + std::max(1, fps) - 1) / std::max(1, fps)),
          spacing_ns_(fps > 30 ? frame_ns_ : frame_ns_ * 7 / 8),
          lookahead_ns_(fps > 30 ? frame_ns_ * 7 / 4 : 2 * frame_ns_),
          reserve_cap_ns_(fps > 30 ? frame_ns_ * 3 / 4 : frame_ns_),
          catch_up_ns_(fps > 30 ? frame_ns_ / 16 : 0) {}

    std::optional<int64_t> schedule(Picture picture, int64_t now_ns)
    {
        const int64_t media_gap = picture.media_ns - last_media_ns_;
        int64_t target = observe(picture);
        const int64_t earliest = has_output_ ? std::max(now_ns, next_slot_ns(now_ns)) : now_ns;
        const int64_t latest = now_ns + lookahead_ns_;
        if (earliest > latest)
            return std::nullopt;

        // While the Surface already has future work, keep adjacent pictures
        // near their earliest eligible slots. A moving jitter estimate must
        // not repeatedly push an already buffered slot further into the future.
        if (has_output_ && last_output_ns_ >= now_ns)
            target = std::min(target, earliest + std::max<int64_t>(0, media_gap - frame_ns_));

        const int64_t presentation = std::clamp(target, earliest, latest);
        last_output_ns_ = presentation;
        has_output_ = true;
        return presentation;
    }

    std::array<std::optional<int64_t>, 2> schedule_pair(Picture first, Picture second, int64_t now_ns)
    {
        auto trial = *this;
        std::array times{trial.schedule(first, now_ns), trial.schedule(second, now_ns)};
        if (times[0] && !times[1])
        {
            // If the Surface has room for only one picture from the batch,
            // show the newest one. The older picture still informs jitter.
            observe(first);
            times = {std::nullopt, schedule(second, now_ns)};
        }
        else
        {
            *this = trial;
        }
        return times;
    }

    std::optional<int64_t> retry_at_ns(Picture picture, int64_t now_ns) const
    {
        if (!has_output_)
            return std::nullopt;
        const int64_t retry = next_slot_ns(now_ns) - lookahead_ns_;
        // Bound the time a decoded buffer occupies a codec slot while waiting.
        // Account for time since its callback, including delays on the worker.
        if (retry <= now_ns || retry - picture.ready_ns > frame_ns_ / 4)
            return std::nullopt;
        return retry;
    }

    void reset() { have_arrival_ = false; has_output_ = false; variation_ns_ = 0; }
    int64_t reserve_ns() const { return std::min(reserve_cap_ns_, 2 * variation_ns_); }

private:
    int64_t next_slot_ns(int64_t now_ns) const
    {
        const int64_t nominal = last_output_ns_ + spacing_ns_;
        // Use nominal spacing during steady playback. If the Surface has more
        // than one and a half frames of lead, recover one millisecond at a time.
        return nominal - (nominal - now_ns > frame_ns_ * 3 / 2 ? catch_up_ns_ : 0);
    }

    int64_t observe(Picture picture)
    {
        const int64_t offset = picture.ready_ns - picture.media_ns;
        const int64_t shift = offset - last_offset_ns_;
        // A jump in RTP or a long interruption starts a fresh estimate, while
        // normal callback jitter stays on the same local timeline.
        if (!have_arrival_ || picture.media_ns < last_media_ns_ ||
            shift > 2 * frame_ns_ || shift < -2 * frame_ns_)
        {
            have_arrival_ = true;
            baseline_ns_ = offset;
            variation_ns_ = 0;
        }
        else if (picture.media_ns > last_media_ns_)
        {
            const int64_t deviation = shift < 0 ? -shift : shift;
            variation_ns_ += (deviation - variation_ns_) / (deviation > variation_ns_ ? 4 : 32);
            baseline_ns_ = std::min(offset, baseline_ns_ + frame_ns_ / 256);
        }
        last_offset_ns_ = offset;
        last_media_ns_ = picture.media_ns;
        return picture.media_ns + baseline_ns_ + reserve_ns();
    }

    int64_t frame_ns_;
    int64_t spacing_ns_;
    int64_t lookahead_ns_;
    int64_t reserve_cap_ns_;
    int64_t catch_up_ns_;
    bool have_arrival_ = false;
    bool has_output_ = false;
    int64_t baseline_ns_ = 0;
    int64_t variation_ns_ = 0;
    int64_t last_media_ns_ = 0;
    int64_t last_offset_ns_ = 0;
    int64_t last_output_ns_ = 0;
};

#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>

// Adapts the bounded priming window in OpenNOW Android's SurfaceDecoderQueuePolicy
// (Zortos, MIT). The decoder worker owns the queue. Polling reads only published counters.
class CodecQueue
{
public:
    explicit CodecQueue(int fps) : frame_limit_(std::max(32, (std::max(1, fps) + 1) / 2)) {}

    bool can_submit(size_t bytes) const
    {
        constexpr size_t max_bytes = 16 * 1024 * 1024;
        return pending_.size() < frame_limit_ && bytes <= max_bytes - bytes_;
    }

    void submitted(int64_t timestamp, size_t bytes, uint64_t now)
    {
        pending_.push_back({timestamp, bytes, now});
        bytes_ += bytes;
        publish();
    }

    void produced(int64_t timestamp, uint64_t now)
    {
        const auto frame = std::find_if(pending_.begin(), pending_.end(),
            [timestamp](const auto& entry) { return entry.timestamp == timestamp; });
        if (frame == pending_.end())
            return;
        bytes_ -= frame->bytes;
        pending_.erase(frame);
        if (first_output_ == 0)
            first_output_ = now;
        publish();
    }

    void reset()
    {
        pending_.clear();
        bytes_ = 0;
        first_output_ = 0;
        publish();
    }

    size_t count() const { return count_.load(std::memory_order_relaxed); }

    uint64_t age_us(uint64_t now) const
    {
        const auto since = oldest_.load(std::memory_order_relaxed);
        return since != 0 && now > since ? now - since : 0;
    }

    bool stalled(uint64_t now) const
    {
        const auto deadline = stall_deadline_.load(std::memory_order_acquire);
        return deadline != 0 && now >= deadline;
    }

private:
    void publish()
    {
        const auto since = pending_.empty() ? 0 : pending_.front().since;
        oldest_.store(since, std::memory_order_relaxed);
        count_.store(pending_.size(), std::memory_order_relaxed);
        const uint64_t deadline = pending_.empty() ? 0 : first_output_ == 0
            ? since + 1'000'000 : std::max(since, first_output_) + 250'000;
        stall_deadline_.store(deadline, std::memory_order_release);
    }

    struct Entry
    {
        int64_t timestamp;
        size_t bytes;
        uint64_t since;
    };

    const size_t frame_limit_;
    std::deque<Entry> pending_;
    size_t bytes_ = 0;
    uint64_t first_output_ = 0;
    std::atomic<size_t> count_ {0};
    std::atomic<uint64_t> oldest_ {0};
    std::atomic<uint64_t> stall_deadline_ {0};
};

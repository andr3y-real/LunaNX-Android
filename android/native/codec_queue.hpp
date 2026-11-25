#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>

// The decoder worker owns timestamp matching. Polling reads only published counters.
class CodecQueue
{
public:
    explicit CodecQueue(int fps) : frame_limit_(std::max(32, (std::max(1, fps) + 1) / 2)) {}

    void submitted(int64_t timestamp, uint64_t now)
    {
        // A vendor codec may omit an output or rewrite its timestamp. Keep diagnostic
        // bookkeeping bounded without treating unmatched timestamps as decoder errors.
        if (pending_.size() == frame_limit_)
            pending_.pop_front();
        pending_.push_back({timestamp, now});
        publish();
    }

    void produced(int64_t timestamp, uint64_t now)
    {
        const auto frame = std::find_if(pending_.begin(), pending_.end(),
            [timestamp](const auto& entry) { return entry.timestamp == timestamp; });
        if (frame == pending_.end())
            return;
        const auto delay = now >= frame->since ? now - frame->since : 0;
        if (delay > peak_delay_.load(std::memory_order_relaxed))
            peak_delay_.store(delay, std::memory_order_relaxed);
        pending_.erase(frame);
        publish();
    }

    void reset()
    {
        pending_.clear();
        publish();
    }

    size_t count() const { return count_.load(std::memory_order_relaxed); }

    uint64_t peak_delay_us() const { return peak_delay_.load(std::memory_order_relaxed); }

private:
    void publish()
    {
        count_.store(pending_.size(), std::memory_order_relaxed);
    }

    struct Entry
    {
        int64_t timestamp;
        uint64_t since;
    };

    const size_t frame_limit_;
    std::deque<Entry> pending_;
    std::atomic<size_t> count_ {0};
    std::atomic<uint64_t> peak_delay_ {0};
};

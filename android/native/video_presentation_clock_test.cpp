#include "video_presentation_clock.hpp"

#include <cassert>

int main()
{
    constexpr int64_t frame = 16'666'667;
    constexpr int64_t start = 2'000'000'000;
    VideoPresentationClock clock(60);
    for (int i = 0; i < 120; ++i)
    {
        const int64_t time = start + i * frame;
        assert(clock.schedule({i * frame, time}, time) == time);
    }
    assert(clock.reserve_ns() == 0);

    // After a large RTP jump, the first available frame takes the next slot.
    clock.reset();
    assert(clock.schedule({0, start}, start) == start);
    assert(clock.schedule({9 * frame, start + frame}, start + frame) == start + frame);
    const auto next = clock.schedule({10 * frame, start + frame + frame / 2}, start + frame + frame / 2);
    assert(next && *next >= start + 2 * frame && *next <= start + 3 * frame);

    // When only one slot remains, retain the newest decoded picture.
    clock.reset();
    assert(clock.schedule({0, start}, start) == start);
    assert(clock.schedule({frame, start}, start));
    const int64_t wake = start + frame / 2;
    const auto batch = clock.schedule_pair({2 * frame, start}, {3 * frame, start}, wake);
    assert(!batch[0] && batch[1] == start + 2 * frame);
    const VideoPresentationClock::Picture waiting {4 * frame, wake};
    assert(!clock.schedule(waiting, wake));
    const auto retry = clock.retry_at_ns();
    assert(retry > wake && retry - wake < frame);
    assert(!clock.schedule(waiting, retry - 1));
    assert(clock.schedule(waiting, retry) == start + 3 * frame);
    const auto resumed = clock.schedule({5 * frame, start + 2'000'000'000}, start + 2'000'000'000);
    assert(resumed == start + 2'000'000'000 && clock.reserve_ns() == 0);

    // Both stream profiles absorb recurring jitter within two frame periods.
    for (const int fps : {30, 60})
    {
        VideoPresentationClock noisy(fps);
        const int64_t interval = (1'000'000'000LL + fps - 1) / fps;
        constexpr int64_t jitter[] = {-9'000'000, -8'000'000, 23'000'000, 7'000'000, -9'000'000};
        int64_t previous = 0;
        int64_t max_reserve = 0;
        int scheduled_count = 0;
        for (int i = 0; i < fps * 1200; ++i)
        {
            const int64_t media = i * interval + (i >= fps * 600 ? 2'000'000'000LL : 0);
            const int64_t ready = start + media + jitter[i % 5];
            const auto scheduled = noisy.schedule({media, ready}, ready);
            if (scheduled)
            {
                const int64_t minimum_spacing = fps > 30 ? interval : interval * 7 / 8;
                assert(*scheduled >= previous + minimum_spacing);
                assert(*scheduled >= ready && *scheduled <= ready + 2 * interval);
                previous = *scheduled;
                ++scheduled_count;
            }
            max_reserve = std::max(max_reserve, noisy.reserve_ns());
        }
        assert(max_reserve > 0 && max_reserve <= interval);
        assert(scheduled_count >= fps * 1200 * 3 / 4);
    }
}

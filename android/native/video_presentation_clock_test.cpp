#include "video_presentation_clock.hpp"

#include <cassert>

int main()
{
    constexpr int64_t frame = 16'666'667;
    constexpr int64_t start = 2'000'000'000;
    VideoPresentationClock clock(60);
    // Single startup frame presents immediately without adding an extra frame of delay.
    assert(clock.schedule(0, start) == start);
    assert(clock.schedule(frame, start + frame / 2) == start + frame);
    assert(clock.schedule(2 * frame, start + frame / 2) == start + 2 * frame);
    // A fourth burst frame within the same half-frame exceeds the 2-frame lookahead.
    assert(!clock.schedule(3 * frame, start + frame / 2));
    // Once a frame passes, the next slot opens cleanly.
    assert(clock.schedule(3 * frame, start + frame) == start + 3 * frame);
    const auto after_pause = clock.schedule(4 * frame, start + 30 * frame);
    assert(after_pause == start + 30 * frame);
    clock.reset();
    assert(clock.schedule(0, start) == start);

    // A source running slower than the negotiated maximum retains its cadence without stalling.
    clock.reset();
    assert(clock.schedule(0, start) == start);
    assert(clock.schedule(2 * frame, start + 2 * frame) == start + 2 * frame);
    assert(clock.schedule(4 * frame, start + 4 * frame) == start + 4 * frame);

    VideoPresentationClock burst(60);
    assert(burst.schedule(0, start) == start);
    assert(burst.schedule(frame, start) == start + frame);
    assert(burst.schedule(2 * frame, start) == start + 2 * frame);
    assert(!burst.schedule(3 * frame, start));

    // Repeated or non-monotonic timestamps re-anchor without discarding the frame.
    clock.reset();
    assert(clock.schedule(2 * frame, start) == start);
    assert(clock.schedule(2 * frame, start + 2 * frame) == start + 2 * frame);

    // A timestamp restart during a burst must wait behind scheduled output.
    clock.reset();
    assert(clock.schedule(0, start) == start);
    assert(clock.schedule(frame, start) == start + frame);
    assert(clock.schedule(0, start) == start + 2 * frame);
    assert(!clock.schedule(frame, start));

    // After a sequence gap, a recovered burst can jump forward in media time
    // even though its pictures reach the decoder close together. Re-anchor
    // to the nearest slot so the first picture leaves room for the next one.
    clock.reset();
    assert(clock.schedule(0, start) == start);
    assert(clock.schedule(9 * frame, start + frame) == start + frame);
    assert(clock.schedule(10 * frame, start + frame + frame / 2) == start + 2 * frame);

    // Repeated late deliveries followed by catch-up should fit in the two-frame
    // budget across the whole match, including after a pause.
    clock.reset();
    constexpr int64_t jitter[] = {-9'000'000, -8'000'000, 23'000'000, 7'000'000, -9'000'000};
    int64_t previous = 0;
    int64_t paused_ns = 0;
    for (int i = 0; i < 12000; ++i)
    {
        if (i == 6000)
            paused_ns = 2'000'000'000;
        const int64_t media = i * frame + paused_ns;
        const int64_t arrival = start + media + jitter[i % 5];
        const auto scheduled = clock.schedule(media, arrival);
        assert(scheduled && *scheduled > previous);
        assert(*scheduled >= arrival && *scheduled <= arrival + 2 * frame);
        previous = *scheduled;
    }
}

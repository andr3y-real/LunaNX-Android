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
    assert(burst.schedule(0, start, true) == start);
    assert(burst.schedule(frame, start, true) == start + frame);
    assert(burst.schedule(2 * frame, start, true) == start + 2 * frame);
    assert(!burst.schedule(3 * frame, start, true));

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
}

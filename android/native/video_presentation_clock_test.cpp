#include "video_presentation_clock.hpp"

#include <cassert>

int main()
{
    constexpr int64_t frame = 16'666'667;
    constexpr int64_t start = 2'000'000'000;
    VideoPresentationClock clock(60);
    assert(clock.schedule(0, start) == start + frame);
    assert(clock.schedule(frame, start + frame / 2) == start + 2 * frame);
    // A burst cannot replace a frame already scheduled for that display interval.
    assert(!clock.schedule(2 * frame, start + frame / 2));
    assert(!clock.schedule(frame, start + frame / 2));
    assert(clock.schedule(3 * frame, start + frame) == start + 3 * frame);
    const auto after_pause = clock.schedule(4 * frame, start + 30 * frame);
    assert(after_pause == start + 31 * frame);
    clock.reset();
    assert(clock.schedule(0, start) == start + frame);

    // A source running slower than the negotiated maximum retains its cadence.
    clock.reset();
    assert(clock.schedule(0, start) == start + frame);
    assert(clock.schedule(2 * frame, start + 2 * frame) == start + 3 * frame);
    assert(clock.schedule(4 * frame, start + 4 * frame) == start + 5 * frame);

    // Repeated timestamps restart the media anchor without a shared slot.
    assert(clock.schedule(4 * frame, start + 5 * frame) == start + 6 * frame);
}

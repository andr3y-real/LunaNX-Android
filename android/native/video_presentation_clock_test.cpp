#include "video_presentation_clock.hpp"

#include <cassert>

int main()
{
    constexpr int64_t frame = 1'000'000'000 / 60;
    constexpr int64_t start = 2'000'000'000;
    VideoPresentationClock clock(60);
    assert(clock.schedule(0, start) == start + frame);
    assert(clock.schedule(frame, start + frame / 2) == start + 2 * frame);
    const auto burst = clock.schedule(2 * frame, start + frame / 2);
    assert(burst == start + frame / 2 + 2 * frame);
    const auto reordered = clock.schedule(frame, start + frame / 2);
    assert(reordered == burst);
    const auto after_pause = clock.schedule(3 * frame, start + 30 * frame);
    assert(after_pause == start + 31 * frame);
    clock.reset();
    assert(clock.schedule(0, start) == start + frame);
}

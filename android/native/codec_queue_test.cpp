#include "codec_queue.hpp"

#include <cassert>

int main()
{
    CodecQueue queue(60);
    assert(!queue.stalled(2'000'000));
    queue.submitted(10, 1024, 10'000);
    queue.submitted(20, 1024, 20'000);
    assert(queue.count() == 2 && queue.age_us(30'000) == 20'000);
    assert(!queue.stalled(1'009'999));
    assert(queue.stalled(1'010'000));

    // The oldest frame can remain pending if a later timestamp is output first.
    queue.produced(20, 1'011'000);
    assert(queue.count() == 1 && queue.age_us(1'015'000) == 1'005'000);
    assert(!queue.stalled(1'260'999));
    assert(queue.stalled(1'261'000));
    queue.produced(999, 1'262'000);
    assert(queue.count() == 1);
    queue.produced(10, 1'263'000);
    assert(queue.count() == 0 && !queue.stalled(2'000'000));

    queue.submitted(30, 1, 2'000'000);
    assert(!queue.stalled(2'249'999));
    assert(queue.stalled(2'250'000));

    queue.reset();
    constexpr size_t limit = 16 * 1024 * 1024;
    assert(queue.can_submit(limit));
    queue.submitted(31, limit, 3'000'000);
    assert(!queue.can_submit(1));
    queue.produced(31, 3'010'000);
    assert(queue.can_submit(limit));
    for (int frame = 0; frame < 32; ++frame)
    {
        assert(queue.can_submit(1));
        queue.submitted(frame, 1, 3'020'000 + frame);
    }
    assert(!queue.can_submit(1));
    queue.reset();
    assert(queue.count() == 0 && queue.age_us(4'000'000) == 0);
}

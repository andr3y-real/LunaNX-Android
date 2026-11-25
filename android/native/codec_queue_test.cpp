#include "codec_queue.hpp"

#include <cassert>

int main()
{
    CodecQueue queue(60);
    queue.submitted(10, 10'000);
    queue.submitted(20, 20'000);
    assert(queue.count() == 2 && queue.age_us(30'000) == 20'000);

    queue.produced(20);
    assert(queue.count() == 1 && queue.age_us(40'000) == 30'000);
    queue.produced(999);
    assert(queue.count() == 1);
    queue.produced(10);
    assert(queue.count() == 0 && queue.age_us(50'000) == 0);

    // Missing output timestamps cannot grow the telemetry or prevent taking new samples.
    for (int frame = 0; frame < 40; ++frame)
        queue.submitted(frame, 100'000 + frame);
    assert(queue.count() == 32 && queue.age_us(100'040) == 32);
    queue.produced(7);
    assert(queue.count() == 32);
    queue.produced(8);
    assert(queue.count() == 31);
    queue.reset();
    assert(queue.count() == 0 && queue.age_us(200'000) == 0);
}

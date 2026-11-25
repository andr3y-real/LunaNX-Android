#include "codec_queue.hpp"

#include <cassert>

int main()
{
    CodecQueue queue(60);
    queue.submitted(10, 10'000);
    queue.submitted(20, 20'000);
    queue.produced(20, 25'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);

    queue.produced(999, 4'336'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);
    queue.submitted(30, 4'336'000);
    queue.produced(30, 4'340'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);
    queue.produced(10, 4'340'000);
    assert(queue.count() == 0 && queue.peak_delay_us() == 4'330'000);

    for (int frame = 0; frame < 40; ++frame)
        queue.submitted(frame, 5'000'000 + frame);
    assert(queue.count() == 32);
    queue.produced(7, 5'000'050);
    assert(queue.count() == 32);
    queue.produced(8, 5'000'051);
    assert(queue.count() == 31);
    queue.reset();
    assert(queue.count() == 0 && queue.peak_delay_us() == 4'330'000);
}

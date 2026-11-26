#include "codec_queue.hpp"
#include "stream/DecodeQueuePolicy.hpp"

#include <array>

#include <cassert>

int main()
{
    CodecQueue queue(60);
    queue.submitted(10, 10'000);
    queue.submitted(20, 20'000);
    queue.produced(20, 25'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);
    assert(queue.oldest_pending_us() == 10'000 && queue.slow_outputs() == 0);

    queue.produced(999, 4'336'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);
    queue.submitted(30, 4'336'000);
    queue.produced(30, 4'340'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);
    queue.produced(10, 4'340'000);
    assert(queue.count() == 0 && queue.peak_delay_us() == 4'330'000);
    assert(queue.oldest_pending_us() == 0 && queue.slow_outputs() == 1);

    for (int frame = 0; frame < 40; ++frame)
        queue.submitted(frame, 5'000'000 + frame);
    assert(queue.count() == 32);
    queue.produced(7, 5'000'050);
    assert(queue.count() == 32);
    queue.produced(8, 5'000'051);
    assert(queue.count() == 31);
    queue.reset();
    assert(queue.count() == 0 && queue.peak_delay_us() == 4'330'000);
    assert(queue.oldest_pending_us() == 0 && queue.slow_outputs() == 1);

    using lunanx::video::AndroidCodecOutputStalled;
    assert(!AndroidCodecOutputStalled(1'000'000, 900'000, 100'000, 990'000, 4));
    assert(!AndroidCodecOutputStalled(1'000'000, 200'000, 400'000, 600'000, 4));
    assert(!AndroidCodecOutputStalled(1'000'000, 200'000, 400'000, 990'000, 2));
    assert(!AndroidCodecOutputStalled(1'000'000, 200'000, 750'000, 990'000, 4));
    assert(AndroidCodecOutputStalled(1'000'000, 200'000, 400'000, 990'000, 4));

    struct Picture { bool idr; };
    const std::array<Picture, 5> two_keys{{{true}, {false}, {false}, {true}, {false}}};
    const std::array<Picture, 2> no_keys{{{false}, {false}}};
    assert(lunanx::video::PicturesBeforeNewestKeyframe(two_keys) == 3);
    assert(lunanx::video::PicturesBeforeNewestKeyframe(no_keys) == no_keys.size());
}

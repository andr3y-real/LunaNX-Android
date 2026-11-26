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
    assert(queue.slow_outputs() == 0);

    queue.produced(999, 4'336'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);
    queue.submitted(30, 4'336'000);
    queue.produced(30, 4'340'000);
    assert(queue.count() == 1 && queue.peak_delay_us() == 5'000);
    queue.produced(10, 4'340'000);
    assert(queue.count() == 0 && queue.peak_delay_us() == 4'330'000);
    assert(queue.slow_outputs() == 1);

    for (int frame = 0; frame < 40; ++frame)
        queue.submitted(frame, 5'000'000 + frame);
    assert(queue.count() == 32);
    queue.produced(7, 5'000'050);
    assert(queue.count() == 32);
    queue.produced(8, 5'000'051);
    assert(queue.count() == 31);
    queue.reset();
    assert(queue.count() == 0 && queue.peak_delay_us() == 4'330'000);
    assert(queue.slow_outputs() == 1);

    using Recovery = lunanx::video::AndroidDecodeRecovery;
    using Action = Recovery::Action;
    Recovery recovery;
    Recovery::Progress progress {100'000, 20'000, 485'000, 340'000, 30'000, 0, true};
    // A brief network pause after stale submissions would have reset the old
    // policy at 350 ms. Output that returns on its own needs no intervention.
    assert(recovery.poll(500'000, progress) == Action::Wait);
    progress.last_output_us = 520'000;
    progress.last_picture_us = 525'000;
    assert(recovery.poll(530'000, progress) == Action::Wait);

    // A continuing stream gets an IDR request, followed by time for the codec
    // to handle the IDR actually submitted by the decoder worker.
    progress.last_picture_us = 1'180'000;
    assert(recovery.poll(1'200'000, progress) == Action::RequestKeyframe);
    progress.last_picture_us = 1'880'000;
    progress.last_idr_input_us = progress.last_input_us = 1'650'000;
    assert(recovery.poll(1'900'000, progress) == Action::Wait);
    progress.last_picture_us = 2'080'000;
    assert(recovery.poll(2'100'000, progress) == Action::RestartCodec);

    // If the codec accepts no inputs, accumulated fresh pictures provide the
    // separate signal for a reset after the soft attempt.
    progress = {3'000'000, 3'000'000, 3'660'000, 3'100'000, 3'100'000, 3'200'000, false};
    assert(recovery.poll(3'670'000, progress) == Action::RequestKeyframe);
    progress.last_picture_us = 4'350'000;
    assert(recovery.poll(4'370'000, progress) == Action::RestartCodec);

    // A source can keep sending P-frames without returning a usable IDR.
    // Refresh the request while the codec can keep taking input.
    progress = {5'000'000, 5'000'000, 5'680'000, 5'600'000, 5'300'000, 0, true};
    assert(recovery.poll(5'700'000, progress) == Action::RequestKeyframe);
    progress.last_picture_us = progress.last_input_us = 6'480'000;
    assert(recovery.poll(6'500'000, progress) == Action::Wait);
    progress.last_picture_us = progress.last_input_us = 6'880'000;
    assert(recovery.poll(6'900'000, progress) == Action::RequestKeyframe);
    recovery.reset();

    // A resumed output or a new run of incoming video retires a previous probe.
    progress = {5'000'000, 5'000'000, 5'680'000, 5'600'000, 5'300'000, 0, true};
    assert(recovery.poll(5'700'000, progress) == Action::RequestKeyframe);
    progress.last_output_us = 5'900'000;
    progress.last_picture_us = 6'580'000;
    assert(recovery.poll(6'600'000, progress) == Action::RequestKeyframe);
    progress.last_picture_us = 7'010'000;
    progress.video_run_start_us = 7'000'000;
    assert(recovery.poll(7'020'000, progress) == Action::Wait);
    progress.last_picture_us = 7'100'000;
    assert(recovery.poll(7'700'000, progress) == Action::Wait);

    struct Picture { bool idr; };
    const std::array<Picture, 5> two_keys{{{true}, {false}, {false}, {true}, {false}}};
    const std::array<Picture, 2> no_keys{{{false}, {false}}};
    assert(lunanx::video::PicturesBeforeNewestKeyframe(two_keys) == 3);
    assert(lunanx::video::PicturesBeforeNewestKeyframe(no_keys) == no_keys.size());
}

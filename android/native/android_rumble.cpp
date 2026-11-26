#include "android_rumble.hpp"
#include "input/switch_rumble.hpp"

#include <atomic>

namespace lunanx::input
{
namespace
{
std::atomic<std::uint32_t> vibration {};
}

std::uint32_t AndroidRumbleState()
{
    return vibration.load(std::memory_order_relaxed);
}

void SwitchRumble::Send(std::uint8_t heavy, std::uint8_t light)
{
    std::uint32_t expected = vibration.load(std::memory_order_relaxed);
    for (;;)
    {
        const auto sequence = (expected >> 16) + 1;
        const std::uint32_t next = (sequence << 16) | (static_cast<std::uint32_t>(heavy) << 8) | light;
        if (vibration.compare_exchange_weak(expected, next, std::memory_order_relaxed))
            break;
    }
}
}

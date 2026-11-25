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
    const auto sequence = (vibration.load(std::memory_order_relaxed) >> 16) + 1;
    vibration.store((sequence << 16) | (static_cast<std::uint32_t>(heavy) << 8) | light,
                    std::memory_order_relaxed);
}
}

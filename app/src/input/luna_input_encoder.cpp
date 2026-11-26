// LunaNX-Origin: KazaR-RooT | urn:uuid:57e9877e-564c-4275-ae2a-a70f276bfbed | unit=fec918ce70e83a8d46cc
// Project origin: https://github.com/kazar-root/LunaNX (third-party notices remain applicable).
#include "core/source_origin.hpp"
LUNANX_SOURCE_ORIGIN(ufec918ce70e83a8d46cc, "fec918ce70e83a8d46cc")

#include "luna_input_encoder.hpp"

#include "luna_proto.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace lunanx::input
{
namespace
{

constexpr std::uint32_t kGamepadStateField = 104;
constexpr std::uint32_t kInputReadyField = 36;

// XInput [-32768, 32767] → échelle Luna [0, 65534], 32767 au repos.
std::uint64_t AxisToLuna(int value)
{
    return static_cast<std::uint64_t>(std::clamp(value + 32767, 0, 65534));
}

std::uint64_t TriggerToLuna(std::uint8_t value)
{
    return static_cast<std::uint64_t>(value) * 65534u / 255u;
}

} // namespace

std::int16_t AxisToXInput(float value)
{
    if (!std::isfinite(value))
        return 0;
    const float clamped = std::clamp(value, -1.0f, 1.0f);
    return static_cast<std::int16_t>(std::lround(clamped * 32767.0f));
}

std::uint64_t WallClockMilliseconds()
{
    return static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}

std::vector<std::uint8_t> LunaInputEncoder::EncodeGamepad(const GamepadState& state)
{
    const std::uint64_t now = WallClockMilliseconds();
    ProtoWriter timing;
    timing.Varint(1, now).Varint(3, 4).Varint(8, now);

    ProtoWriter pad;
    pad.Varint(2, ++sequence_)
        .Varint(3, state.buttons)
        .Varint(4, TriggerToLuna(state.left_trigger))
        .Varint(5, TriggerToLuna(state.right_trigger))
        .VarintAlways(6, AxisToLuna(state.left_x))
        .VarintAlways(7, AxisToLuna(state.left_y))
        .VarintAlways(8, AxisToLuna(state.right_x))
        .VarintAlways(9, AxisToLuna(state.right_y))
        .Message(12, timing);

    ProtoWriter message;
    message.Message(kGamepadStateField, pad);
    return message.data();
}

std::vector<std::uint8_t> LunaInputEncoder::EncodeKeyEvent(const std::string& key, bool pressed)
{
    const std::uint64_t now = WallClockMilliseconds();
    ProtoWriter timing;
    timing.Varint(1, now).Varint(3, 2);
    if (!pressed)
        timing.Varint(4, 1);
    timing.Varint(8, now);

    ProtoWriter ev;
    if (!key.empty())
        ev.String(1, key);
    ev.Varint(3, 1).Message(8, timing);

    ProtoWriter message;
    message.Message(pressed ? 100 : 101, ev);
    return message.data();
}

std::vector<std::uint8_t> LunaInputEncoder::EncodePointerMove(double x_percent, double y_percent)
{
    if (!std::isfinite(x_percent)) x_percent = 50.0;
    if (!std::isfinite(y_percent)) y_percent = 50.0;
    const std::uint64_t now = WallClockMilliseconds();
    const std::uint64_t prev = pointer_seq_++;
    const std::uint64_t curr = pointer_seq_;

    ProtoWriter header;
    header.Varint(10, 2).Varint(20, curr).Varint(30, prev).Varint(40, 0xffffffffu).Varint(50, 3);

    ProtoWriter timing;
    timing.Varint(1, now).Varint(3, 1).Varint(4, 2).Varint(8, now);

    ProtoWriter move;
    move.Varint(3, 1).Varint(4, 1)
        .Double(5, std::clamp(x_percent, 0.0, 100.0))
        .Double(6, std::clamp(y_percent, 0.0, 100.0))
        .Message(8, timing);

    ProtoWriter message;
    message.Message(1, header).Message(35, move);
    return message.data();
}

std::vector<std::uint8_t> LunaInputEncoder::EncodePointerButton(bool pressed)
{
    const std::uint64_t now = WallClockMilliseconds();
    const std::uint64_t prev = pointer_seq_++;
    const std::uint64_t curr = pointer_seq_;

    ProtoWriter header;
    header.Varint(10, 1).Varint(20, curr).Varint(30, prev).Varint(40, 0xffffffffu).Varint(50, 3);

    ProtoWriter timing;
    timing.Varint(1, now).Varint(3, 1);
    if (!pressed)
        timing.Varint(4, 1);
    timing.Varint(8, now);

    ProtoWriter btn;
    btn.Varint(1, 1).Message(8, timing);

    ProtoWriter message;
    message.Message(1, header).Message(pressed ? 50 : 51, btn);
    return message.data();
}

std::vector<std::uint8_t> LunaInputEncoder::EncodeInputReady()
{
    ProtoWriter message;
    message.Message(kInputReadyField, ProtoWriter());
    return message.data();
}

const std::string& LunaInputEncoder::InputChannelLabel()
{
    static const std::string label = "inputChannel";
    return label;
}

const std::string& LunaInputEncoder::ClientMessageChannelLabel()
{
    static const std::string label = "clientMessageChannel";
    return label;
}

const std::vector<std::string>& LunaInputEncoder::RequestedChannels()
{
    // « display » est confirmé par la capture ; les trois autres noms restent à confirmer.
    static const std::vector<std::string> channels = {"display", "input", "audio", "control"};
    return channels;
}

} // namespace lunanx::input

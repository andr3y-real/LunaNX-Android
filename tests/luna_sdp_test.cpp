#include "luna/luna_sdp.hpp"

#include <cassert>
#include <string>

int main()
{
    using namespace lunanx::luna;
    const LocalTransport transport;
    assert(BuildLunaOffer(transport).find("profile-level-id=42e01f\r\n") != std::string::npos);

    struct Mode { int height; int fps; const char* level; };
    constexpr Mode modes[] = {
        {720, 30, "42e01f"},
        {720, 60, "42e020"},
        {1080, 30, "42e028"},
        {1080, 60, "42e02a"},
    };
    for (const auto& mode : modes)
    {
        const std::string offer = BuildLunaOffer(transport, 6, H264ReceiveLevelFor(mode.height, mode.fps));
        assert(offer.find(std::string("profile-level-id=") + mode.level + "\r\n") != std::string::npos);
        assert(offer.find("a=rtcp-fb:96 nack\r\n") != std::string::npos);
        assert(offer.find("a=fmtp:97 apt=96\r\n") != std::string::npos);
    }
}

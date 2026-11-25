#include <assert.h>

// Exercise the production access-unit flush without opening a network connection.
#include "../extern/libpeer/src/rtp.c"

static void count_picture(const PeerVideoPacket* packet, void* context) {
  assert(packet->size > 0);
  ++*(int*)context;
}

static void test_stats(void) {
  uint8_t data[] = {0, 0, 0, 1, 0x06};
  int pictures = 0;
  RtpDecoder decoder = {0};
  decoder.au_buf = data;
  decoder.user_data = &pictures;
  decoder.on_video_packet = count_picture;

  decoder.au_started = 1;
  decoder.au_offset = sizeof(data);
  rtp_decoder_flush_access_unit(&decoder);
  assert(decoder.non_picture_access_units == 1);
  assert(decoder.access_units_dropped == 0);
  assert(pictures == 0);

  data[4] = 0x65;
  decoder.au_started = 1;
  decoder.au_offset = sizeof(data);
  decoder.au_has_vcl = 1;
  rtp_decoder_flush_access_unit(&decoder);
  assert(decoder.access_units_completed == 1 && pictures == 1);

  decoder.au_started = 1;
  decoder.au_offset = sizeof(data);
  decoder.au_has_vcl = 1;
  decoder.au_damaged = 1;
  rtp_decoder_flush_access_unit(&decoder);
  assert(decoder.non_picture_access_units == 1);
  assert(decoder.access_units_dropped == 1 && pictures == 1);
}

struct Pictures {
  uint32_t timestamps[4];
  uint32_t sources[4];
  size_t count;
};

static void record_picture(const PeerVideoPacket* packet, void* context) {
  struct Pictures* pictures = context;
  assert(pictures->count < 4);
  pictures->sources[pictures->count] = packet->ssrc;
  pictures->timestamps[pictures->count++] = packet->timestamp;
}

static void receive(RtpDecoder* decoder, uint16_t seq, uint32_t timestamp, int marker,
                    const uint8_t* payload, size_t size) {
  uint8_t packet[20] = {0x80, 96};
  assert(size <= sizeof(packet) - 12);
  if (marker)
    packet[1] |= 0x80;
  write_be16(packet + 2, seq);
  write_be32(packet + 4, timestamp);
  write_be32(packet + 8, 0x12345678);
  memcpy(packet + 12, payload, size);
  rtp_decode_h264(decoder, packet, 12 + size);
}

static void test_fragment_boundaries(void) {
  struct Pictures pictures = {0};
  uint8_t au[64];
  uint8_t nalu[64];
  RtpDecoder decoder = {0};
  decoder.user_data = &pictures;
  decoder.on_video_packet = record_picture;
  decoder.au_buf = au;
  decoder.au_capacity = sizeof(au);
  decoder.nalu_buf = nalu;
  decoder.nalu_capacity = sizeof(nalu);
  const uint8_t picture[] = {0x65, 1};
  const uint8_t first[] = {0x7c, 0x85, 2};
  const uint8_t last[] = {0x7c, 0x45, 3};

  // A sender can omit a marker. Changing timestamp must retain the SSRC.
  receive(&decoder, 1, 1000, 0, picture, sizeof(picture));
  receive(&decoder, 2, 2000, 1, picture, sizeof(picture));
  assert(pictures.count == 2 && pictures.timestamps[1] == 2000);
  assert(pictures.sources[0] == 0x12345678 && pictures.sources[1] == 0x12345678);

  // A fragment end for a new timestamp cannot complete the previous picture.
  receive(&decoder, 3, 3000, 0, first, sizeof(first));
  receive(&decoder, 4, 4000, 1, last, sizeof(last));
  assert(pictures.count == 2 && decoder.access_units_dropped == 2);
  assert(!decoder.au_started && !decoder.fragment_started);

  // Marker without a final fragment closes and discards the incomplete picture.
  receive(&decoder, 5, 5000, 1, first, sizeof(first));
  assert(pictures.count == 2 && decoder.access_units_dropped == 3);
  assert(!decoder.au_started && !decoder.fragment_started);

  receive(&decoder, 6, 6000, 0, first, sizeof(first));
  receive(&decoder, 7, 6000, 1, last, sizeof(last));
  assert(pictures.count == 3 && pictures.timestamps[2] == 6000);
  assert(pictures.sources[2] == 0x12345678 && decoder.access_units_dropped == 3);
}

int main(void) {
  test_stats();
  test_fragment_boundaries();
}

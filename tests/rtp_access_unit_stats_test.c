#include <assert.h>

// Exercise the production access-unit flush without opening a network connection.
#include "../extern/libpeer/src/rtp.c"

static void count_picture(const PeerVideoPacket* packet, void* context) {
  assert(packet->size > 0);
  ++*(int*)context;
}

int main(void) {
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

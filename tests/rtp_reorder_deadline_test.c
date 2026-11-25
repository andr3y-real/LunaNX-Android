#include <assert.h>

// Run the production reorder buffer against a deterministic clock, without sockets.
#include "../extern/libpeer/src/rtp.c"

static uint32_t now_ms;
uint32_t ports_get_monotonic_time_ms(void) { return now_ms; }

struct Output {
  uint16_t sequences[8];
  size_t count;
};

static int record_sequence(RtpDecoder* decoder, uint8_t* packet, size_t size) {
  struct Output* output = decoder->user_data;
  assert(output->count < sizeof(output->sequences) / sizeof(output->sequences[0]));
  output->sequences[output->count++] = read_be16(packet + 2);
  return (int)size;
}

static void receive(RtpDecoder* decoder, uint16_t sequence, uint32_t arrival_ms) {
  const uint8_t packet[] = {0, 0, (uint8_t)(sequence >> 8), (uint8_t)sequence};
  now_ms = arrival_ms;
  rtp_decoder_decode_reordered(decoder, packet, sizeof(packet));
}

int main(void) {
  struct Output output = {0};
  RtpDecoder decoder = {0};
  decoder.user_data = &output;
  decoder.decode_func = record_sequence;
  decoder.reorder_buf = calloc(RTP_REORDER_WINDOW, RTP_REORDER_PACKET_CAPACITY);
  assert(decoder.reorder_buf);
  decoder.reorder_gap_hold_ms = 40;
  decoder.reorder_burst_gap_hold_ms = 64;

  receive(&decoder, 65534, UINT32_MAX - 20);
  receive(&decoder, 0, UINT32_MAX - 15);
  assert(output.count == 1 && decoder.reorder_buffered_packets == 1);
  rtp_decoder_expire_gap(&decoder, 23);
  assert(output.count == 1);
  rtp_decoder_expire_gap(&decoder, 24);
  assert(output.count == 2 && output.sequences[1] == 0);
  assert(decoder.reorder_buffered_packets == 0);
  assert(decoder.forced_sequence_skips == 1 && decoder.reorder_gap_expirations == 1);
  receive(&decoder, 65535, 25);
  assert(decoder.late_packets_dropped == 1);

  receive(&decoder, 2, 60);
  receive(&decoder, 2, 62);
  assert(decoder.reorder_buffered_packets == 1 && output.count == 2);
  receive(&decoder, 1, 65);
  assert(output.count == 4 && output.sequences[2] == 1 && output.sequences[3] == 2);
  assert(decoder.reorder_buffered_packets == 0 && decoder.reorder_gap_recoveries == 1);
  rtp_decoder_expire_gap(&decoder, 140);
  assert(decoder.reorder_gap_expirations == 1);

  receive(&decoder, 68, 200);
  rtp_decoder_expire_gap(&decoder, 240);
  assert(output.count == 4);
  rtp_decoder_expire_gap(&decoder, 264);
  assert(output.count == 5 && output.sequences[4] == 68);
  assert(decoder.reorder_gap_expirations == 2);
  free(decoder.reorder_buf);
}

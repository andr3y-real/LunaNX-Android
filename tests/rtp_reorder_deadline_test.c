#include <assert.h>

// Run the production reorder buffer against a deterministic clock, without sockets.
#include "../extern/libpeer/src/rtp.c"

static uint32_t now_ms;
uint32_t ports_get_monotonic_time_ms(void) { return now_ms; }

struct Output {
  uint16_t sequences[16];
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
  rtp_decoder_decode(decoder, packet, sizeof(packet));
}

static void test_expiration_and_reorder(void) {
  struct Output output = {0};
  RtpDecoder decoder = {0};
  decoder.type = PT_H264;
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

  // Large keyframe bursts (>128 packets) must hold until NACK recovery rather
  // than fast-forwarding prematurely inside the reorder window.
  receive(&decoder, 228, 300);
  assert(decoder.reorder_buffered_packets == 1);
  assert(decoder.forced_sequence_skips == 66);
  receive(&decoder, 70, 310);
  assert(output.count == 5 && decoder.reorder_buffered_packets == 2);
  receive(&decoder, 69, 315);
  assert(output.count == 7 && output.sequences[5] == 69 && output.sequences[6] == 70);
  rtp_decoder_expire_gap(&decoder, 350);
  assert(output.count == 7);
  rtp_decoder_expire_gap(&decoder, 390);
  assert(output.count == 8 && output.sequences[7] == 228);
  free(decoder.reorder_buf);
}

static int retransmit(RtpDecoder* decoder, uint16_t sequence, uint32_t arrival_ms) {
  const uint8_t packet[] = {0, 0, (uint8_t)(sequence >> 8), (uint8_t)sequence};
  now_ms = arrival_ms;
  return rtp_decoder_decode_retransmission(decoder, packet, sizeof(packet));
}

static void test_nack_deadlines(void) {
  struct Output output = {0};
  RtpDecoder decoder = {0};
  decoder.type = PT_H264;
  decoder.user_data = &output;
  decoder.decode_func = record_sequence;
  decoder.reorder_buf = calloc(RTP_REORDER_WINDOW, RTP_REORDER_PACKET_CAPACITY);
  assert(decoder.reorder_buf);
  decoder.reorder_gap_hold_ms = 111;
  decoder.reorder_burst_gap_hold_ms = 120;

  // A 92 ms session has time for one request. Packet arrival can advance the
  // PID before the response, but overlapping sequence numbers stay throttled.
  uint16_t packet_id = 0, bitmask = 0;
  receive(&decoder, 65534, UINT32_MAX - 10);
  receive(&decoder, 3, UINT32_MAX - 3);
  assert(!rtp_decoder_get_nack(&decoder, 0, 92, &packet_id, &bitmask));
  assert(rtp_decoder_get_nack(&decoder, 1, 92, &packet_id, &bitmask));
  assert(packet_id == 65535 && bitmask == 7);
  assert(rtp_decoder_note_nack(&decoder, 1, packet_id, bitmask) == 0);
  assert(!retransmit(&decoder, 4, 2)); // Unrequested RTX cannot extend the window.
  receive(&decoder, 65535, 3);
  assert(!rtp_decoder_get_nack(&decoder, 8, 92, &packet_id, &bitmask));
  assert(retransmit(&decoder, 1, 90));
  assert(!retransmit(&decoder, 1, 91)); // Duplicate buffered RTX.
  assert(retransmit(&decoder, 0, 92));
  assert(!retransmit(&decoder, 0, 93)); // Late duplicate.
  assert(!rtp_decoder_get_nack(&decoder, 94, 92, &packet_id, &bitmask));
  assert(retransmit(&decoder, 2, 96));
  assert(output.count == 6 && output.sequences[5] == 3);

  // More than one active hole retains the age of its original evidence.
  receive(&decoder, 5, 100);
  receive(&decoder, 7, 102);
  rtp_decoder_expire_gap(&decoder, 211);
  assert(output.count == 7 && output.sequences[6] == 5);
  rtp_decoder_expire_gap(&decoder, 212);
  assert(output.count == 7);
  rtp_decoder_expire_gap(&decoder, 213);
  assert(output.count == 8 && output.sequences[7] == 7);

  // In a large loss, the second request covers new sequence numbers, and
  // a short-RTT connection can retry once the first response was due.
  receive(&decoder, 45, 250);
  assert(rtp_decoder_get_nack(&decoder, 255, 24, &packet_id, &bitmask));
  assert(packet_id == 8 && bitmask == 65535);
  assert(rtp_decoder_note_nack(&decoder, 255, packet_id, bitmask) == 0);
  assert(rtp_decoder_get_nack(&decoder, 260, 24, &packet_id, &bitmask));
  assert(packet_id == 25 && bitmask == 65535);
  assert(rtp_decoder_note_nack(&decoder, 260, packet_id, bitmask) == 0);
  assert(rtp_decoder_get_nack(&decoder, 279, 24, &packet_id, &bitmask));
  assert(packet_id == 8 && bitmask == 65535);
  assert(rtp_decoder_note_nack(&decoder, 279, packet_id, bitmask) == 17);
  free(decoder.reorder_buf);
}

int main(void) {
  test_expiration_and_reorder();
  test_nack_deadlines();
}

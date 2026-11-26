#include <assert.h>

// Run the production reorder buffer against a deterministic clock, without sockets.
#include "../extern/libpeer/src/rtp.c"
#include "../extern/libpeer/src/rtcp.c"

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
  RtcpNack entries[RTCP_MAX_NACK_ENTRIES];
  receive(&decoder, 65534, UINT32_MAX - 10);
  receive(&decoder, 3, UINT32_MAX - 3);
  assert(!rtp_decoder_get_nacks(&decoder, 0, 92, entries));
  assert(rtp_decoder_get_nacks(&decoder, 1, 92, entries) == 1);
  assert(entries[0].packet_id == 65535 && entries[0].bitmask == 7);
  assert(rtp_decoder_note_nack(&decoder, 1, 92, entries[0].packet_id, entries[0].bitmask) == 0);
  assert(!retransmit(&decoder, 4, 2)); // Unrequested RTX cannot extend the window.
  receive(&decoder, 65535, 3);
  assert(!rtp_decoder_get_nacks(&decoder, 8, 92, entries));
  assert(retransmit(&decoder, 1, 90));
  assert(!retransmit(&decoder, 1, 91)); // Duplicate buffered RTX.
  assert(retransmit(&decoder, 0, 92));
  assert(!retransmit(&decoder, 0, 93)); // Late duplicate.
  assert(!rtp_decoder_get_nacks(&decoder, 94, 92, entries));
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

  // Pack all ranges for this loss into one feedback message. A short-RTT
  // connection can retry once the first response was due.
  receive(&decoder, 45, 250);
  size_t count = rtp_decoder_get_nacks(&decoder, 255, 24, entries);
  assert(count == 3);
  assert(entries[0].packet_id == 8 && entries[0].bitmask == 65535);
  assert(entries[1].packet_id == 25 && entries[1].bitmask == 65535);
  assert(entries[2].packet_id == 42 && entries[2].bitmask == 3);
  for (size_t i = 0; i < count; ++i)
    assert(rtp_decoder_note_nack(&decoder, 255, 24, entries[i].packet_id, entries[i].bitmask) == 0);
  assert(rtp_decoder_get_nacks(&decoder, 260, 24, entries) == 0);
  assert(rtp_decoder_get_nacks(&decoder, 279, 24, entries) == 3);
  assert(rtp_decoder_note_nack(&decoder, 279, 24, entries[0].packet_id, entries[0].bitmask) == 17);

  rtp_decoder_expire_gap(&decoder, 361);
  assert(output.count == 9);
  decoder.access_units_completed = RTP_RECOVERY_STARTUP_ACCESS_UNITS;
  decoder.reorder_burst_gap_hold_ms = rtp_recovery_burst_gap_hold_ms(140, 132, 3);
  // With 132 ms RTT, request distant packets on the first feedback and
  // retain enough time for their retransmission to arrive.
  receive(&decoder, 446, 400);
  assert(output.count == 9 && decoder.reorder_buffered_packets == 1);
  count = rtp_decoder_get_nacks(&decoder, 405, 132, entries);
  assert(count == RTCP_MAX_NACK_ENTRIES);
  assert(entries[0].packet_id == 46 && entries[11].packet_id == 233 &&
         entries[11].bitmask == 65535);
  for (size_t i = 0; i < count; ++i)
    assert(rtp_decoder_note_nack(&decoder, 405, 132, entries[i].packet_id, entries[i].bitmask) == 0);
  assert(rtp_decoder_get_nacks(&decoder, 410, 132, entries) == 0);
  rtp_decoder_expire_gap(&decoder, 550);
  assert(output.count == 9);
  assert(retransmit(&decoder, 249, 550));
  assert(!retransmit(&decoder, 250, 551));
  rtp_decoder_expire_gap(&decoder, 565);
  assert(output.count == 10 && output.sequences[9] == 249);
  free(decoder.reorder_buf);
}

static void test_gap_retains_rtx_deadline(void) {
  struct Output output = {0};
  RtpDecoder decoder = {0};
  decoder.type = PT_H264;
  decoder.user_data = &output;
  decoder.decode_func = record_sequence;
  decoder.reorder_buf = calloc(RTP_REORDER_WINDOW, RTP_REORDER_PACKET_CAPACITY);
  assert(decoder.reorder_buf);

  rtp_decoder_set_reorder_grace_ms(&decoder, 140);
  rtp_decoder_set_reorder_burst_grace_ms(&decoder, 165);
  receive(&decoder, 100, 1000);
  receive(&decoder, 171, 1001);
  RtcpNack entries[RTCP_MAX_NACK_ENTRIES];
  assert(rtp_decoder_get_nacks(&decoder, 1006, 132, entries) == 5);
  for (size_t i = 0; i < 5; ++i)
    assert(rtp_decoder_note_nack(&decoder, 1006, 132, entries[i].packet_id, entries[i].bitmask) == 0);
  // The new RTT is lower, but packets requested at the old RTT are still in flight.
  assert(rtp_decoder_get_nacks(&decoder, 1075, 40, entries) == 0);
  rtp_decoder_set_reorder_grace_ms(&decoder, 95);
  rtp_decoder_set_reorder_burst_grace_ms(&decoder, 112);
  rtp_decoder_expire_gap(&decoder, 1138);
  assert(output.count == 1);
  assert(retransmit(&decoder, 101, 1139));
  assert(output.count == 2 && output.sequences[1] == 101);
  rtp_decoder_expire_gap(&decoder, 1166);
  assert(output.count == 3 && output.sequences[2] == 171);

  receive(&decoder, 173, 1200);
  rtp_decoder_expire_gap(&decoder, 1294);
  assert(output.count == 3);
  rtp_decoder_expire_gap(&decoder, 1295);
  assert(output.count == 4 && output.sequences[3] == 173);
  free(decoder.reorder_buf);
}

static void test_large_burst_keeps_inflight_rtx(void) {
  struct Output output = {0};
  RtpDecoder decoder = {0};
  decoder.type = PT_H264;
  decoder.user_data = &output;
  decoder.decode_func = record_sequence;
  decoder.reorder_buf = calloc(RTP_REORDER_WINDOW, RTP_REORDER_PACKET_CAPACITY);
  assert(decoder.reorder_buf);
  decoder.access_units_completed = RTP_RECOVERY_STARTUP_ACCESS_UNITS;
  rtp_decoder_set_reorder_grace_ms(&decoder, 112);
  rtp_decoder_set_reorder_burst_grace_ms(&decoder, 130);
  receive(&decoder, 1000, 100);
  receive(&decoder, 1205, 105);
  RtcpNack entries[RTCP_MAX_NACK_ENTRIES];
  assert(rtp_decoder_get_nacks(&decoder, 110, 90, entries) == RTCP_MAX_NACK_ENTRIES);
  assert(rtp_decoder_note_nack(&decoder, 110, 90, entries[0].packet_id, entries[0].bitmask) == 0);

  // Far-ahead data used to force the receiver to skip an RTX already in flight.
  receive(&decoder, 1620, 145);
  assert(decoder.forced_sequence_skips == 0);
  assert(retransmit(&decoder, 1001, 200));
  assert(output.count == 2 && output.sequences[1] == 1001);
  rtp_decoder_expire_gap(&decoder, 234);
  assert(output.count == 2);
  rtp_decoder_expire_gap(&decoder, 235);
  assert(output.count == 3 && output.sequences[2] == 1205);
  free(decoder.reorder_buf);
}

static void test_nack_wire_format(void) {
  const RtcpNack entries[] = {{65535, 3}, {50, 0xff00}};
  const uint8_t expected[] = {
      0x81, 205, 0, 4,  0x12, 0x34, 0x56, 0x78,
      0x87, 0x65, 0x43, 0x21,  0xff, 0xff, 0, 3,  0, 50, 0xff, 0,
  };
  uint8_t packet[sizeof(expected)];
  assert(rtcp_get_nack(packet, sizeof(packet) - 1, 1, 2, entries, 2) == -1);
  assert(rtcp_get_nack(packet, sizeof(packet), 1, 2, entries, 0) == -1);
  assert(rtcp_get_nack(packet, sizeof(packet), 0x12345678, 0x87654321, entries, 2) == sizeof(packet));
  assert(memcmp(packet, expected, sizeof(packet)) == 0);
}

int main(void) {
  test_expiration_and_reorder();
  test_nack_deadlines();
  test_nack_wire_format();
  test_gap_retains_rtx_deadline();
  test_large_burst_keeps_inflight_rtx();
}

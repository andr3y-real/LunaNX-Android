// LunaNX-Origin: KazaR-RooT | urn:uuid:57e9877e-564c-4275-ae2a-a70f276bfbed | unit=4a7aaa68fd82f6c67b9e
// Project origin: https://github.com/kazar-root/LunaNX (third-party notices remain applicable).

#pragma once

#include "core/stream_settings.hpp"
#include "core/i18n.hpp"
#include "input/luna_input_encoder.hpp"
#include "input/switch_rumble.hpp"
#include "luna/luna_client.hpp"
#include "stream/live_metrics.hpp"
#include "stream/audio/AudioPipeline.hpp"
#include "stream/DecodeQueuePolicy.hpp"
#ifdef __ANDROID__
#include "android_video_decoder.hpp"
#include "android_microphone.hpp"
#else
#include "stream/IVideoRenderer.hpp"
#include "stream/ffmpeg/FFmpegVideoDecoder.hpp"
#endif

#include "peer.h"
#include "peer_connection.h"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace lunanx
{

// Une partie Luna : réservation de la machine distante (API HTTP), connexion WebRTC
// (libpeer), décodage H.264 (NVDEC ou logiciel), audio Opus et envoi de la manette.
class LunaStreamSession
{
  public:
    enum class Phase
    {
        Preparing,
        WaitingForServer,
        Connecting,
        Streaming,
        Ended,
        Failed,
    };

    LunaStreamSession(std::shared_ptr<const luna::LunaClient> client, luna::Game game
#ifdef __ANDROID__
                      , ANativeWindow* window, bool prefer_pt_br, int height, int fps, int bitrate_mbps
#endif
    );
    ~LunaStreamSession();

    void start();
    void request_stop();
    void stop();
    // À appeler à chaque image depuis le thread d'interface.
    void poll();

    bool send_gamepad(const input::GamepadState& state);
    bool send_key(const std::string& key, bool pressed);
    bool send_pointer_move(double x_percent, double y_percent);
    bool send_pointer_button(bool pressed);
#ifndef __ANDROID__
    void draw(NVGcontext* vg, int width, int height, AVFrame* frame, uint64_t generation);
    int64_t video_target_rtp_timestamp() const;
#endif

    Phase phase() const { return phase_.load(std::memory_order_acquire); }
    std::string status_text() const;
    std::string debug_text() const;
    StreamCounters stream_counters() const;
#ifdef __ANDROID__
    struct LaunchQueueStatus
    {
        bool queued = false;
        int position = -1;
        int wait_seconds = -1;
    };
    LaunchQueueStatus launch_queue_status() const;

    struct AndroidVideoStats
    {
        uint64_t received = 0;
        uint64_t decoded = 0;
        uint64_t discarded = 0;
        uint64_t resets = 0;
        uint64_t codec_resets = 0;
        uint64_t queue_recoveries = 0;
        uint64_t queue_ms = 0;
        uint64_t arrival_jitter_ms = 0;
        uint64_t codec_pending = 0;
        uint64_t peak_codec_delay_ms = 0;
        uint64_t non_picture_units = 0;
        uint64_t waiting_for_keyframe_drops = 0;
        uint64_t rtp_gaps_recovered = 0;
        uint64_t rtp_gaps_expired = 0;
        int rendered_width = 0;
        int rendered_height = 0;
        int rtt_current_ms = -1;
        int rtt_min_ms = -1;
        int rtt_p95_ms = -1;
        int rtt_jitter_ms = -1;
        uint64_t rtp_packets_received = 0;
        uint64_t rtp_sequence_gaps = 0;
        uint64_t rtp_reordered_packets = 0;
        uint64_t rtp_late_packets_dropped = 0;
        uint64_t rtp_forced_skips = 0;
        uint64_t rtp_burst_fast_forwards = 0;
        uint64_t nack_requests = 0;
        uint64_t nack_packets_requested = 0;
        uint64_t rtx_packets_received = 0;
        uint64_t rtx_packets_recovered = 0;
        uint64_t codec_submit_retries = 0;
        uint64_t codec_stale_drops = 0;
        uint64_t keyframe_requests = 0;
        uint64_t peak_queue_ms = 0;
        uint64_t codec_watchdog_resets = 0;
        uint64_t slow_codec_outputs = 0;
        uint64_t peak_playout_reserve_ms = 0;
        uint64_t deferred_frames_presented = 0;
        uint32_t nack_rtt_ms = 0;
        uint32_t rtx_response_ms = 0;
    };
    AndroidVideoStats android_video_stats();
    bool set_android_surface(ANativeWindow* window);
    bool set_microphone_enabled(bool enabled);
    bool microphone_active() const { return microphone_.active(); }
#endif
    bool input_ready() const { return input_sid_ >= 0; }
    const StreamSettings& settings() const { return settings_; }

    // Points d'entrée des callbacks C de libpeer.
    void on_peer_state_change(PeerConnectionState state);
    void on_datachannel_message(const char* msg, size_t len, uint16_t sid);
    void on_datachannel_open();
    void on_datachannel_close();
    void on_video_packet(const PeerVideoPacket& packet);
    void on_audio_packet(const PeerAudioPacket& packet);
    void on_rtp_sender_report(uint32_t ssrc, uint64_t ntp_us, uint32_t rtp_timestamp);

  private:
#ifdef __ANDROID__
    AndroidMicrophone microphone_;
#endif
    struct DecodeUnit
    {
        std::vector<uint8_t> data;
        bool idr = false;
        uint32_t rtp_timestamp = 0;
        std::chrono::steady_clock::time_point enqueued_at {};
    };

    struct SenderReport
    {
        uint32_t ssrc = 0;
        uint64_t ntp_us = 0;
        uint32_t rtp_timestamp = 0;
    };

#ifndef __ANDROID__
    void setup_video_decoder();
#endif
    bool setup_peer_connection();
    void signaling_loop();
    void network_loop();
    void decoder_loop();
    void set_status(const std::string& status);
    void fail(const std::string& reason);
    void open_datachannels();
    bool send_on_channel(int sid, const std::vector<uint8_t>& message);
    void send_client_startup_messages();
    void maybe_send_ping();
    void enqueue_decode_unit(const uint8_t* data, size_t size, uint32_t rtp_timestamp, bool idr);
    void discard_decode_prefix_locked(size_t count);
    void recycle_decode_buffer_locked(std::vector<uint8_t>&& buffer);
    void clear_decoder_queue_locked();
#ifdef __ANDROID__
    uint64_t observe_decoder_queue_age_locked();
#endif
    void request_keyframe(const char* reason);
    void maybe_recover_decode_stall();
    void maybe_recover_rtp_damage(bool incoming_idr = false);
    bool sleep_unless_stopped(std::chrono::milliseconds duration);

    std::shared_ptr<const luna::LunaClient> client_;
    luna::Game game_;
    StreamSettings settings_;
    input::LunaInputEncoder input_encoder_;
    input::SwitchRumble rumble_;
    int rumble_events_logged_ = 0;

    std::atomic<Phase> phase_ {Phase::Preparing};
    mutable std::mutex status_mutex_;
    std::string status_ = Tr("Préparation…");
    std::string failure_;
    std::string server_region_;
#ifdef __ANDROID__
    LaunchQueueStatus launch_queue_status_;
#endif

    PeerConnection* pc_ = nullptr;
    mutable std::recursive_mutex peer_mutex_;
    std::atomic<bool> remote_description_set_ {false};
    std::atomic<bool> stop_requested_ {false};
    std::atomic<bool> interactive_input_pending_ {false};
    PeerConnectionState peer_state_ = PEER_CONNECTION_NEW;
    bool peer_ever_completed_ = false;
    bool datachannels_requested_ = false;
    int datachannel_open_attempts_ = 0;
    uint32_t datachannel_opened_mask_ = 0;
    std::chrono::steady_clock::time_point last_datachannel_attempt_at_ {};
    std::atomic<int> input_sid_ {-1};
    int client_message_sid_ = -1;
    std::chrono::steady_clock::time_point last_ping_at_ {};
    std::array<int, 16> datachannel_rx_logged_ {};

    std::thread signaling_thread_;
    std::thread network_thread_;
    std::thread decoder_thread_;
    std::atomic<bool> network_running_ {false};
    std::atomic<bool> decoder_running_ {false};
    std::mutex stop_mutex_;
    std::condition_variable stop_cv_;

#ifdef __ANDROID__
    std::unique_ptr<AndroidVideoDecoder> decoder_;
    std::string launch_locale_;
    uint64_t video_received_ = 0;
    uint64_t video_run_start_us_ = 0;
    uint64_t last_video_arrival_us_ = 0;
    uint32_t last_video_rtp_timestamp_ = 0;
    int64_t arrival_jitter_us_ = 0;
    std::atomic<uint64_t> decoder_resets_ {0};
    std::atomic<uint64_t> codec_resets_ {0};
    std::atomic<uint64_t> codec_watchdog_resets_ {0};
    std::atomic<uint64_t> queue_recoveries_ {0};
    uint64_t peak_decoder_queue_age_ms_ = 0; // Guarded by decoder_queue_mutex_.
    uint64_t waiting_for_keyframe_drops_ = 0; // Guarded by decoder_queue_mutex_.
    // Guarded by decoder_queue_mutex_.
    video::AndroidDecodeRecovery codec_recovery_;
    uint64_t last_codec_input_us_ = 0;
    uint64_t last_codec_idr_input_us_ = 0;
    std::atomic<bool> video_surface_attached_ {true};
    std::atomic<uint64_t> video_surface_generation_ {0};
#else
    std::unique_ptr<FFmpegVideoDecoder> decoder_;
    std::unique_ptr<IVideoRenderer> renderer_;
#endif
    std::unique_ptr<AudioPipeline> audio_;
    std::string video_backend_name_ = Tr("non initialisé");

    mutable std::mutex decoder_queue_mutex_;
    std::condition_variable decoder_queue_cv_;
    std::deque<DecodeUnit> decoder_queue_;
    std::deque<std::vector<uint8_t>> decoder_buffer_pool_;
    std::atomic<bool> decoder_resync_required_ {true};
    std::atomic<bool> decoder_reset_requested_ {false};
    std::atomic<bool> keyframe_needed_ {false};
    std::atomic<int> packets_received_ {0};
    std::atomic<int> frames_decoded_ {0};
    std::atomic<int> decode_errors_ {0};
    std::atomic<int> decoder_queue_drops_ {0};
    std::atomic<uint64_t> last_decoded_frame_at_us_ {0};
    uint32_t last_rtp_access_units_dropped_ = 0;
    int keyframe_request_attempts_ = 0;
    std::chrono::steady_clock::time_point last_keyframe_request_at_ {};
    bool last_keyframe_request_sent_ = false;
#ifdef __ANDROID__
    uint64_t first_pending_pli_at_us_ = 0;
#endif
    std::chrono::steady_clock::time_point last_decode_stall_request_at_ {};
    std::chrono::steady_clock::time_point streaming_since_ {};

    std::vector<SenderReport> sender_reports_;
    uint32_t video_ssrc_ = 0;
    uint32_t audio_ssrc_ = 0;
    uint64_t video_sr_ntp_us_ = 0;
    uint32_t video_sr_rtp_timestamp_ = 0;
    bool have_video_sender_report_ = false;
    std::atomic<int> rendered_width_ {0};
    std::atomic<int> rendered_height_ {0};
    std::atomic<uint64_t> gamepad_reports_sent_ {0};
    std::atomic<uint64_t> received_media_bytes_ {0};
    std::atomic<uint64_t> presented_frames_ {0};
    uint64_t last_presented_generation_ = 0;
    bool have_presented_frame_ = false;
};

} // namespace lunanx

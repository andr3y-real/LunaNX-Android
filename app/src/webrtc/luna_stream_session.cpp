// LunaNX-Origin: KazaR-RooT | urn:uuid:57e9877e-564c-4275-ae2a-a70f276bfbed | unit=86d0cb31882f7e48e9fb
// Project origin: https://github.com/kazar-root/LunaNX (third-party notices remain applicable).
#include "core/source_origin.hpp"
LUNANX_SOURCE_ORIGIN(u86d0cb31882f7e48e9fb, "86d0cb31882f7e48e9fb")

#include "luna_stream_session.hpp"

#include "core/app_paths.hpp"
#include "core/i18n.hpp"
#include "core/runtime_journal.hpp"
#include "input/luna_client_messages.hpp"
#include "internal.hpp"
#include "luna/luna_sdp.hpp"
#include "stream/audio/AudioRtpUtils.hpp"
#ifndef __ANDROID__
#include "stream/deko3d/DKVideoRenderer.hpp"
#include "stream/ffmpeg/AVFrameHolder.hpp"
#endif
#include "stream/network_loop_policy.hpp"
#include "stream/stream_diagnostics.hpp"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>

using namespace lunanx::webrtc::internal;

namespace lunanx
{
namespace
{

#ifndef __ANDROID__
// Présent pendant qu'un flux NVDEC est actif : s'il reste après un plantage, le
// prochain lancement passe automatiquement en décodage logiciel.
std::string NvdecMarkerPath()
{
    return AppHomePath() + "/nvdec_active.marker";
}

bool ConsumeNvdecCrashMarker()
{
    std::ifstream marker(NvdecMarkerPath(), std::ios::binary);
    const bool exists = marker.good();
    marker.close();
    if (exists)
        std::remove(NvdecMarkerPath().c_str());
    return exists;
}

void CreateNvdecMarker()
{
    std::ofstream marker(NvdecMarkerPath(), std::ios::trunc);
    marker << "NVDEC actif\n";
}

#endif

LunaStreamSession* Self(void* userdata)
{
    return static_cast<LunaStreamSession*>(userdata);
}

extern "C" {
void on_peer_state_change_cb(PeerConnectionState state, void* userdata)
{
    Self(userdata)->on_peer_state_change(state);
}
void on_datachannel_message_cb(char* msg, size_t len, void* userdata, uint16_t sid)
{
    Self(userdata)->on_datachannel_message(msg, len, sid);
}
void on_datachannel_open_cb(void* userdata)
{
    Self(userdata)->on_datachannel_open();
}
void on_datachannel_close_cb(void* userdata)
{
    Self(userdata)->on_datachannel_close();
}
void on_video_packet_cb(const PeerVideoPacket* packet, void* userdata)
{
    if (packet)
        Self(userdata)->on_video_packet(*packet);
}
void on_audio_packet_cb(const PeerAudioPacket* packet, void* userdata)
{
    if (packet)
        Self(userdata)->on_audio_packet(*packet);
}
void on_rtp_sender_report_cb(uint32_t ssrc, uint64_t ntp_us, uint32_t rtp_timestamp, void* userdata)
{
    Self(userdata)->on_rtp_sender_report(ssrc, ntp_us, rtp_timestamp);
}
}

} // namespace

LunaStreamSession::LunaStreamSession(std::shared_ptr<const luna::LunaClient> client, luna::Game game
#ifdef __ANDROID__
                                     , ANativeWindow* window, bool prefer_pt_br, int height, int fps, int bitrate_mbps
#endif
)
    : client_(std::move(client)),
      game_(std::move(game)),
      settings_(LoadStreamSettings())
{
    SetStreamDiagnosticsEnabled(settings_.debug_diagnostics);
    peer_init();
#ifndef __ANDROID__
    renderer_ = std::make_unique<DKVideoRenderer>();
#endif
    audio_ = std::make_unique<AudioPipeline>();
    audio_->configure(settings_.audio_volume, settings_.audio_buffer_ms);
#ifdef __ANDROID__
    settings_.height = height == 1080 ? 1080 : 720;
    settings_.width = settings_.height == 1080 ? 1920 : 1280;
    settings_.fps = fps == 30 ? 30 : 60;
    settings_.bitrate_kbps = std::clamp(bitrate_mbps, 3, 20) * 1000;
    settings_.label = std::to_string(settings_.height) + "p" + std::to_string(settings_.fps);
    settings_.preset_id = settings_.label;
    if (prefer_pt_br)
        launch_locale_ = "pt_BR";
    decoder_ = std::make_unique<AndroidVideoDecoder>(
        window, settings_.width, settings_.height, settings_.fps, [this] {
            std::lock_guard lock(decoder_queue_mutex_);
            decoder_queue_cv_.notify_one();
        });
    video_backend_name_ = "Android MediaCodec (assíncrono)";
#else
    setup_video_decoder();
#endif
}

LunaStreamSession::~LunaStreamSession()
{
    stop();
    peer_deinit();
}

#ifndef __ANDROID__
void LunaStreamSession::setup_video_decoder()
{
    const bool previous_crash = settings_.video_backend == "Auto" && ConsumeNvdecCrashMarker();
    const bool force_software = settings_.video_backend == "Software" || previous_crash;
    const bool require_hardware = settings_.video_backend == "NVDEC";
    const int flags = force_software ? VIDEO_DECODER_FORCE_SOFTWARE
                    : require_hardware ? VIDEO_DECODER_REQUIRE_HARDWARE
                                       : VIDEO_DECODER_PREFER_HARDWARE;

    decoder_ = std::make_unique<FFmpegVideoDecoder>();
    int result = decoder_->setup(VIDEO_FORMAT_H264, settings_.width, settings_.height,
                                 settings_.fps, nullptr, flags);
    if (result != 0 && !force_software && !require_hardware)
    {
        AppendStreamLog("VIDEO nvdec_failed " + decoder_->setup_diagnostics());
        decoder_->cleanup();
        decoder_ = std::make_unique<FFmpegVideoDecoder>();
        result = decoder_->setup(VIDEO_FORMAT_H264, settings_.width, settings_.height,
                                 settings_.fps, nullptr, VIDEO_DECODER_FORCE_SOFTWARE);
    }

    if (result != 0)
        video_backend_name_ = Tr("échec d'initialisation du décodeur");
    else if (decoder_->uses_hardware_frames())
        video_backend_name_ = Tr("NVDEC (matériel)");
    else
        video_backend_name_ = previous_crash ? Tr("logiciel (mode sans échec)") : Tr("logiciel");

    if (result == 0 && decoder_->uses_hardware_frames())
        CreateNvdecMarker();
}

#endif

bool LunaStreamSession::setup_peer_connection()
{
    PeerConfiguration config = {};
    config.video_codec = CODEC_H264;
    config.audio_codec = CODEC_OPUS;
    config.datachannel = DATA_CHANNEL_BINARY;
    config.onvideopacket = on_video_packet_cb;
    config.onaudiopacket = on_audio_packet_cb;
    config.onrtpsenderreport = on_rtp_sender_report_cb;
    config.user_data = this;
    // Candidat réflexif pour les box qui filtrent l'UDP entrant ; le serveur Luna
    // annonce de toute façon un candidat public direct dans sa réponse.
    config.ice_servers[0].urls = "stun:stun.l.google.com:19302";

    pc_ = peer_connection_create(&config);
    if (!pc_)
        return false;
    peer_connection_oniceconnectionstatechange(pc_, on_peer_state_change_cb);
    peer_connection_ondatachannel(pc_, on_datachannel_message_cb, on_datachannel_open_cb,
                                  on_datachannel_close_cb);
    return true;
}

void LunaStreamSession::start()
{
#ifdef __ANDROID__
    if (!decoder_ || !decoder_->ready())
    {
        fail("Não foi possível iniciar o decodificador H.264 do Android.");
        return;
    }
#endif
    ResetStreamLog();
    AppendStreamLog("SESSION start game=" + game_.title + " preset=" + settings_.label +
                    " decoder=" + video_backend_name_);
    stop_requested_.store(false);

    if (settings_.audio_enabled)
        audio_->start();

    decoder_running_.store(true);
    decoder_thread_ = std::thread(&LunaStreamSession::decoder_loop, this);
    network_running_.store(true);
    network_thread_ = std::thread(&LunaStreamSession::network_loop, this);
    signaling_thread_ = std::thread(&LunaStreamSession::signaling_loop, this);
}

bool LunaStreamSession::sleep_unless_stopped(std::chrono::milliseconds duration)
{
    std::unique_lock<std::mutex> lock(stop_mutex_);
    return !stop_cv_.wait_for(lock, duration, [this] { return stop_requested_.load(); });
}

// Texte de l'écran d'attente : position et durée estimée, quand Luna les communique.
static std::string QueueStatusText(const luna::SessionJob& job)
{
    std::string text = job.position_in_line > 0
        ? TrFormat("Position dans la file : {}", {std::to_string(job.position_in_line)})
        : Tr("En file d'attente…");
    if (job.expected_wait_seconds > 0)
        text += "\n" + TrFormat("Attente estimée : environ {} min",
                                {std::to_string((job.expected_wait_seconds + 59) / 60)});
    return text;
}

void LunaStreamSession::set_status(const std::string& status)
{
    std::lock_guard<std::mutex> lock(status_mutex_);
    status_ = status;
}

void LunaStreamSession::fail(const std::string& reason)
{
    {
        std::lock_guard<std::mutex> lock(status_mutex_);
        failure_ = reason;
        status_ = reason;
    }
    AppendStreamLog("SESSION failed " + reason);
    LogRuntimeEvent("stream", "failed", reason);
    phase_.store(Phase::Failed, std::memory_order_release);
}

void LunaStreamSession::signaling_loop()
{
    try
    {
        set_status(TrFormat("Préparation de « {} »…", {game_.title}));
        if (game_.play_token.empty())
            game_ = client_->FetchGameDetail(game_);

        set_status(Tr("Recherche du serveur le plus proche…"));
        const auto regions = client_->MeasureRegions();
        for (const auto& region : regions)
            AppendStreamLog("REGION " + region.region + " " + std::to_string(region.latency_ms) + " ms");

        try
        {
            if (client_->IsPinChallengeRequired())
                AppendStreamLog("PIN contrôle parental demandé : le lancement peut être refusé");
        }
        catch (const luna::LunaError& ex)
        {
            AppendStreamLog(std::string("PIN vérification impossible : ") + ex.what());
        }

        std::string offer;
        {
            std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
            if (!setup_peer_connection())
                throw luna::LunaError(Tr("Impossible de créer la connexion WebRTC"));
            const char* local = peer_connection_create_offer(pc_);
            const auto transport = luna::ParseLocalTransport(local ? local : "");
            if (transport.ice_ufrag.empty() || transport.fingerprint.empty())
                throw luna::LunaError(Tr("Offre WebRTC locale incomplète"));
#ifdef __ANDROID__
            // Match libpeer's Opus sender SSRC to the advertised audio track.
            const auto h264_level = luna::H264ReceiveLevelFor(settings_.height, settings_.fps);
            offer = luna::BuildLunaOffer(transport, 6, h264_level);
#else
            offer = luna::BuildLunaOffer(transport);
#endif
        }
        AppendStreamLog("SDP offer\n" + offer);
        if (stop_requested_.load())
            return;

        set_status(Tr("Réservation d'une machine Luna…"));
        phase_.store(Phase::WaitingForServer);
        const std::string job_id = client_->CreateGameSessionJob(
            game_, offer, input::LunaInputEncoder::RequestedChannels(), regions, settings_.region
#ifdef __ANDROID__
            , launch_locale_
#endif
        );
        AppendStreamLog("JOB " + job_id);

        if (!sleep_unless_stopped(std::chrono::milliseconds(1500)))
            return;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::minutes(15);
        luna::SessionJob job;
        while (!stop_requested_.load())
        {
            job = client_->GetGameSessionJob(job_id);
            AppendStreamLog("JOB status=" + job.raw_status + " position=" + std::to_string(job.position_in_line) +
                            " wait_s=" + std::to_string(job.expected_wait_seconds));
            if (job.status != luna::SessionJobStatus::Pending)
                break;
            const bool queued = job.raw_status.find("QUEUE") != std::string::npos ||
                job.position_in_line > 0;
            {
                std::lock_guard<std::mutex> lock(status_mutex_);
                status_ = queued ? QueueStatusText(job) : Tr("Démarrage de la machine distante…");
#ifdef __ANDROID__
                launch_queue_status_ = {queued, job.position_in_line, job.expected_wait_seconds};
#endif
            }
            if (std::chrono::steady_clock::now() > deadline)
                throw luna::LunaError(Tr("Le serveur Luna ne répond pas (délai dépassé)"));
            if (!sleep_unless_stopped(std::chrono::milliseconds(std::clamp(job.retry_after_ms, 500, 5000))))
                return;
        }
        if (stop_requested_.load())
            return;
        if (job.status == luna::SessionJobStatus::Failed)
            throw luna::LunaError(TrFormat("Luna a refusé la session : {}", {job.error}));

        const std::string answer = luna::NormalizeLunaAnswer(job.answer_sdp);
        AppendStreamLog("SDP answer region=" + job.region + " routing=" + job.routing +
                        " h264pt=" + std::to_string(luna::AnsweredH264PayloadType(answer)) + "\n" + answer);
        {
            std::lock_guard<std::mutex> lock(status_mutex_);
            server_region_ = job.region;
        }
        {
            std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
            peer_connection_set_remote_description(pc_, answer.c_str(), SDP_TYPE_ANSWER);
            // Rapports RTCP + REMB : sans eux le serveur reste bloqué à bas débit.
            const uint32_t max_bitrate = static_cast<uint32_t>(std::max(settings_.bitrate_kbps, 3000)) * 1000u;
            peer_connection_set_max_receive_bitrate(pc_, max_bitrate);
            AppendStreamLog("REMB max=" + std::to_string(max_bitrate / 1000) + " kbit/s");
            remote_description_set_.store(true, std::memory_order_release);
        }
        phase_.store(Phase::Connecting);
        set_status(Tr("Connexion au flux vidéo…"));
    }
    catch (const luna::SignInRequired& ex)
    {
        fail(TrFormat("Session Amazon expirée : reconnecte-toi. ({})", {ex.what()}));
    }
    catch (const std::exception& ex)
    {
        fail(ex.what());
    }
}

void LunaStreamSession::network_loop()
{
    while (network_running_.load(std::memory_order_acquire))
    {
        int batch = 0;
        bool can_run = false;
        {
            std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
            can_run = pc_ && remote_description_set_.load(std::memory_order_acquire);
            if (can_run)
            {
                constexpr int kMaximumDatagramsPerBatch = 24;
                for (int i = 0; i < kMaximumDatagramsPerBatch; ++i)
                {
                    const int processed = i == 0 ? peer_connection_loop(pc_)
                                                 : peer_connection_loop_nonblocking(pc_);
                    if (processed == 0)
                        break;
                    ++batch;
                    // Laisse passer immédiatement un rapport manette en attente.
                    if (interactive_input_pending_.load(std::memory_order_acquire))
                        break;
                }
            }
        }
        const int backoff = network::LoopBackoffMilliseconds(can_run, batch);
        if (backoff > 0)
            std::this_thread::sleep_for(std::chrono::milliseconds(backoff));
        else
            std::this_thread::yield();
    }
}

void LunaStreamSession::poll()
{
    if (stop_requested_.load() || !remote_description_set_.load(std::memory_order_acquire))
        return;

    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    if (!pc_)
        return;
    const PeerConnectionState state = peer_connection_get_state(pc_);
    if (state == PEER_CONNECTION_COMPLETED)
    {
        if (!peer_ever_completed_)
        {
            peer_ever_completed_ = true;
            streaming_since_ = std::chrono::steady_clock::now();
            AppendStreamLog("PEER completed");
            request_keyframe("startup");
        }
        open_datachannels();
        maybe_send_ping();
        if (frames_decoded_.load() > 0 && phase() == Phase::Connecting)
        {
            phase_.store(Phase::Streaming);
            set_status(Tr("En jeu"));
        }
#ifdef __ANDROID__
        if (video_surface_attached_.load())
#endif
        {
            if (keyframe_needed_.exchange(false))
                request_keyframe("decoder_resync");
            maybe_recover_rtp_damage();
            maybe_recover_decode_stall();
        }
    }
    else if (state == PEER_CONNECTION_FAILED || state == PEER_CONNECTION_CLOSED ||
             state == PEER_CONNECTION_DISCONNECTED)
    {
        if (phase() != Phase::Failed && phase() != Phase::Ended)
        {
            if (peer_ever_completed_)
            {
                phase_.store(Phase::Ended);
                set_status(Tr("La partie est terminée ou la connexion a été perdue."));
                AppendStreamLog("PEER ended state=" + std::string(peer_connection_state_to_string(state)));
            }
            else
            {
                fail(TrFormat("Connexion WebRTC impossible ({})", {peer_connection_state_to_string(state)}));
            }
        }
    }
}

void LunaStreamSession::open_datachannels()
{
    // Les deux canaux du client web : entrées (SCTP 1) et messages client (SCTP 3).
    const std::string labels[] = {input::LunaInputEncoder::InputChannelLabel(),
                                  input::LunaInputEncoder::ClientMessageChannelLabel()};
    if (datachannels_requested_ || datachannel_open_attempts_ >= 60)
        return;
    // L'association SCTP s'établit après DTLS : on réessaie toutes les 750 ms.
    const auto now = std::chrono::steady_clock::now();
    if (now - last_datachannel_attempt_at_ < std::chrono::milliseconds(750))
        return;
    last_datachannel_attempt_at_ = now;
    ++datachannel_open_attempts_;

    bool all_open = true;
    char protocol[] = "";
    for (size_t i = 0; i < std::size(labels); ++i)
    {
        if (datachannel_opened_mask_ & (1u << i))
            continue;
        // Nous sommes serveur DTLS : les identifiants SCTP impairs nous reviennent (RFC 8832).
        const uint16_t sid = static_cast<uint16_t>(1 + 2 * i);
        std::vector<char> name(labels[i].begin(), labels[i].end());
        name.push_back('\0');
        // Le SCTP embarqué ne négocie pas Forward-TSN : canaux fiables uniquement.
        const int result = peer_connection_create_datachannel_sid(
            pc_, DATA_CHANNEL_RELIABLE, 0, 0, name.data(), protocol, sid);
        AppendStreamLog("DC open label=" + labels[i] + " sid=" + std::to_string(sid) +
                        " attempt=" + std::to_string(datachannel_open_attempts_) +
                        " result=" + std::to_string(result));
        if (result < 0)
        {
            all_open = false;
            continue;
        }
        datachannel_opened_mask_ |= (1u << i);
        if (labels[i] == input::LunaInputEncoder::InputChannelLabel())
        {
            input_sid_ = sid;
            send_on_channel(input_sid_, input::LunaInputEncoder::EncodeInputReady());
        }
        else
        {
            client_message_sid_ = sid;
            send_client_startup_messages();
        }
    }
    datachannels_requested_ = all_open;
}

bool LunaStreamSession::send_on_channel(int sid, const std::vector<uint8_t>& message)
{
    if (!pc_ || sid < 0)
        return false;
    std::vector<uint8_t> copy = message;
    return peer_connection_datachannel_send_binary_sid(
               pc_, reinterpret_cast<char*>(copy.data()), copy.size(), static_cast<uint16_t>(sid)) >= 0;
}

void LunaStreamSession::send_client_startup_messages()
{
    // Sans la configuration d'affichage, le serveur n'envoie que le son.
    input::DisplayRequest display;
    display.width = settings_.width;
    display.height = settings_.height;
    display.fps = settings_.fps;
    if (settings_.bitrate_kbps > 0)
    {
        display.start_bitrate = static_cast<uint32_t>(settings_.bitrate_kbps) * 1000u;
#ifdef __ANDROID__
        display.max_bitrate = display.start_bitrate;
        display.min_bitrate = std::min(display.min_bitrate, display.max_bitrate);
#endif
    }
    if (display.max_bitrate < display.start_bitrate)
        display.max_bitrate = display.start_bitrate;

    const std::vector<uint8_t> messages[] = {
        input::EncodeSessionHello(),
        input::EncodeDisplayRequest(display),
        input::EncodeClientInfo(),
    };
    const char* names[] = {"hello", "display", "client_info"};
    for (size_t i = 0; i < std::size(messages); ++i)
    {
        const bool sent = send_on_channel(client_message_sid_, messages[i]);
        AppendStreamLog(std::string("CLIENT send ") + names[i] + " ok=" + (sent ? "1" : "0") +
                        " hex=" + HexPreview(messages[i].data(), messages[i].size(), 72));
    }
    AppendStreamLog("CLIENT display " + std::to_string(display.width) + "x" +
                    std::to_string(display.height) + "@" + std::to_string(display.fps));
    last_ping_at_ = std::chrono::steady_clock::now();
}

void LunaStreamSession::maybe_send_ping()
{
    if (client_message_sid_ < 0)
        return;
    const auto now = std::chrono::steady_clock::now();
    if (now - last_ping_at_ < std::chrono::seconds(1))
        return;
    last_ping_at_ = now;
    send_on_channel(client_message_sid_, input::EncodePing(input::WallClockMilliseconds()));
}

void LunaStreamSession::on_peer_state_change(PeerConnectionState state)
{
    peer_state_ = state;
    AppendStreamLog(std::string("PEER state=") + peer_connection_state_to_string(state));
}

void LunaStreamSession::on_datachannel_message(const char* msg, size_t len, uint16_t sid)
{
    // Journalise les premiers messages de chaque canal : c'est ce qui permettra
    // d'ajuster le protocole d'entrée et de contrôle de Luna.
    int& logged = datachannel_rx_logged_[sid % datachannel_rx_logged_.size()];
    if (logged < 40)
    {
        ++logged;
        const char* label = pc_ ? peer_connection_lookup_sid_label(pc_, sid) : nullptr;
        const auto* bytes = reinterpret_cast<const uint8_t*>(msg);
        AppendStreamLog("DC rx sid=" + std::to_string(sid) + " label=" + (label ? label : "?") +
                        " len=" + std::to_string(len) +
                        " field=" + std::to_string(input::ServerMessageField(bytes, len)) +
                        " hex=" + HexPreview(bytes, len, 64));
    }
    input::RumbleCommand rumble;
    if (input::ParseRumble(reinterpret_cast<const uint8_t*>(msg), len, rumble))
    {
        rumble_.Send(rumble.heavy, rumble.light);
        if ((rumble.heavy || rumble.light) && rumble_events_logged_ < 5)
        {
            ++rumble_events_logged_;
            AppendStreamLog("RUMBLE heavy=" + std::to_string(rumble.heavy) +
                            " light=" + std::to_string(rumble.light));
        }
        return;
    }
    // Le serveur peut ouvrir lui-même le canal d'entrée : on s'y rattache.
    if (input_sid_ < 0 && pc_)
    {
        const char* label = peer_connection_lookup_sid_label(pc_, sid);
        if (label && input::LunaInputEncoder::InputChannelLabel() == label)
            input_sid_ = sid;
    }
}

void LunaStreamSession::on_datachannel_open()
{
    AppendStreamLog("DC sctp_open");
}

void LunaStreamSession::on_datachannel_close()
{
    AppendStreamLog("DC sctp_closed");
    rumble_.Stop();
    input_sid_ = -1;
    client_message_sid_ = -1;
}

bool LunaStreamSession::send_gamepad(const input::GamepadState& state)
{
    if (input_sid_ < 0)
        return false;
    interactive_input_pending_.store(true, std::memory_order_release);
    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    interactive_input_pending_.store(false, std::memory_order_release);
    if (!pc_ || input_sid_ < 0)
        return false;

    std::vector<uint8_t> report = input_encoder_.EncodeGamepad(state);
    const int sent = peer_connection_datachannel_send_binary_sid(
        pc_, reinterpret_cast<char*>(report.data()), report.size(), static_cast<uint16_t>(input_sid_));
    if (sent < 0)
        return false;
    const uint64_t count = gamepad_reports_sent_.fetch_add(1) + 1;
    if (count <= 3)
        AppendStreamLog("INPUT gamepad hex=" + HexPreview(report.data(), report.size(), 48));
    return true;
}

bool LunaStreamSession::send_key(const std::string& key, bool pressed)
{
    if (input_sid_ < 0 || key.empty())
        return false;
    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    if (!pc_ || input_sid_ < 0)
        return false;
    std::vector<uint8_t> report = input_encoder_.EncodeKeyEvent(key, pressed);
    return peer_connection_datachannel_send_binary_sid(
        pc_, reinterpret_cast<char*>(report.data()), report.size(), static_cast<uint16_t>(input_sid_)) >= 0;
}

bool LunaStreamSession::send_pointer_move(double x_percent, double y_percent)
{
    if (input_sid_ < 0)
        return false;
    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    if (!pc_ || input_sid_ < 0)
        return false;
    std::vector<uint8_t> report = input_encoder_.EncodePointerMove(x_percent, y_percent);
    return peer_connection_datachannel_send_binary_sid(
        pc_, reinterpret_cast<char*>(report.data()), report.size(), static_cast<uint16_t>(input_sid_)) >= 0;
}

bool LunaStreamSession::send_pointer_button(bool pressed)
{
    if (input_sid_ < 0)
        return false;
    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    if (!pc_ || input_sid_ < 0)
        return false;
    std::vector<uint8_t> report = input_encoder_.EncodePointerButton(pressed);
    return peer_connection_datachannel_send_binary_sid(
        pc_, reinterpret_cast<char*>(report.data()), report.size(), static_cast<uint16_t>(input_sid_)) >= 0;
}

void LunaStreamSession::request_stop()
{
    if (stop_requested_.exchange(true))
        return;
    stop_cv_.notify_all();
    network_running_.store(false);
    {
        std::lock_guard lock(decoder_queue_mutex_);
        decoder_running_.store(false);
        decoder_queue_cv_.notify_all();
    }
}

void LunaStreamSession::stop()
{
    request_stop();
#ifdef __ANDROID__
    microphone_.stop();
#endif
    if (signaling_thread_.joinable())
        signaling_thread_.join();
    if (network_thread_.joinable())
        network_thread_.join();
    // Après l'arrêt du réseau, pour qu'aucun message tardif ne relance la vibration.
    rumble_.Stop();
    if (audio_)
        audio_->stop();
    if (decoder_thread_.joinable())
        decoder_thread_.join();
    {
        std::lock_guard<std::mutex> lock(decoder_queue_mutex_);
        clear_decoder_queue_locked();
        decoder_buffer_pool_.clear();
    }

    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    if (pc_)
    {
        peer_connection_close(pc_);
        peer_connection_destroy(pc_);
        pc_ = nullptr;
    }
    // Les surfaces deko3d référencent la mémoire NVDEC : libérer le rendu avant le décodeur.
#ifndef __ANDROID__
    renderer_.reset();
#endif
    if (decoder_)
    {
#ifndef __ANDROID__
        const bool hardware = decoder_->uses_hardware_frames();
#endif
        decoder_->cleanup();
        decoder_.reset();
#ifndef __ANDROID__
        if (hardware)
            std::remove(NvdecMarkerPath().c_str());
#endif
        AppendStreamLog("SESSION stopped packets=" + std::to_string(packets_received_.load()) +
                        " frames=" + std::to_string(frames_decoded_.load()) +
                        " gamepadReports=" + std::to_string(gamepad_reports_sent_.load()));
    }
}

void LunaStreamSession::decoder_loop()
{
#ifdef __ANDROID__
    unsigned resets_without_output = 0;
    uint64_t previous_generation = 0;
    for (;;)
    {
        uint64_t generation = 0;
        bool reset_requested = false;
        AndroidVideoDecoder::Signals signals {};
        {
            std::unique_lock lock(decoder_queue_mutex_);
            while (decoder_running_.load())
            {
                if (video_surface_attached_.load())
                {
                    signals = decoder_->signals();
                    if (signals.output || signals.error || decoder_reset_requested_.load() ||
                        (signals.input && !decoder_queue_.empty()))
                        break;
                    if (signals.presentation_retry_us)
                    {
                        const auto retry_at = std::chrono::steady_clock::time_point(
                            std::chrono::microseconds(signals.presentation_retry_us));
                        decoder_queue_cv_.wait_until(lock, retry_at);
                        continue;
                    }
                }
                decoder_queue_cv_.wait(lock);
            }
            if (!decoder_running_.load())
                break;

            generation = video_surface_generation_.load();
            reset_requested = decoder_reset_requested_.exchange(false);
        }

        if (!video_surface_attached_.load() || generation != video_surface_generation_.load())
            continue;
        if (generation != previous_generation)
        {
            previous_generation = generation;
            resets_without_output = 0;
        }

        const auto output = !signals.error && signals.output
            ? decoder_->drain_output(generation) : AndroidVideoDecoder::OutputProgress {};
        if (generation != video_surface_generation_.load())
            continue;
        if (output.decoded > 0)
        {
            last_decoded_frame_at_us_.store(NowUs(), std::memory_order_release);
            const auto [vw, vh] = decoder_->video_size();
            if (vw > 0 && vh > 0)
            {
                rendered_width_.store(vw, std::memory_order_relaxed);
                rendered_height_.store(vh, std::memory_order_relaxed);
            }
            if (frames_decoded_.fetch_add(output.decoded) == 0)
                AppendStreamLog("DECODE first_frame");
            resets_without_output = 0;
            // Output may arrive between the watchdog decision and this wakeup.
            reset_requested = false;
        }
        if (output.scheduled > 0)
            presented_frames_.fetch_add(output.scheduled, std::memory_order_relaxed);

        bool submit_error = false;
        if (!reset_requested && !signals.error && !output.error)
        {
            // Give ready output priority so an input burst cannot hold all
            // codec buffers while decoded frames are waiting to be released.
            for (int fed = 0; fed < 8; ++fed)
            {
                const auto ready = decoder_->signals();
                if (!ready.input || ready.output || ready.error ||
                    !video_surface_attached_.load() || generation != video_surface_generation_.load() ||
                    decoder_reset_requested_.load())
                    break;
                DecodeUnit unit;
                {
                    std::lock_guard lock(decoder_queue_mutex_);
                    if (decoder_queue_.empty() || !decoder_running_.load())
                        break;
                    observe_decoder_queue_age_locked();
                    unit = std::move(decoder_queue_.front());
                    decoder_queue_.pop_front();
                }
                const auto status = decoder_->submit_decode_unit(
                    unit.data.data(), static_cast<int>(unit.data.size()), unit.rtp_timestamp, generation);
                if (status == AndroidVideoDecoder::SubmitStatus::Retry)
                {
                    std::lock_guard lock(decoder_queue_mutex_);
                    if (generation == video_surface_generation_.load() && decoder_running_.load() &&
                        !decoder_resync_required_.load() &&
                        (decoder_queue_.empty() || !decoder_queue_.front().idr))
                        decoder_queue_.push_front(std::move(unit));
                    else
                        recycle_decode_buffer_locked(std::move(unit.data));
                    break;
                }
                {
                    std::lock_guard lock(decoder_queue_mutex_);
                    if (status == AndroidVideoDecoder::SubmitStatus::Submitted &&
                        generation == video_surface_generation_.load() && video_surface_attached_.load())
                    {
                        last_codec_input_us_ = NowUs();
                        if (unit.idr)
                            last_codec_idr_input_us_ = last_codec_input_us_;
                    }
                    recycle_decode_buffer_locked(std::move(unit.data));
                }
                if (status == AndroidVideoDecoder::SubmitStatus::Error)
                {
                    submit_error = true;
                    break;
                }
            }
        }

        if (generation != video_surface_generation_.load())
            continue;

        const bool codec_error = signals.error || output.error || submit_error;
        if (codec_error || reset_requested)
        {
            {
                std::lock_guard lock(decoder_queue_mutex_);
                if (generation != video_surface_generation_.load())
                    continue;
                codec_recovery_.reset();
                last_codec_input_us_ = 0;
                last_codec_idr_input_us_ = 0;
                const auto skip = video::PicturesBeforeNewestKeyframe(decoder_queue_);
                decoder_queue_drops_ += static_cast<int>(skip);
                discard_decode_prefix_locked(skip);
                decoder_resync_required_.store(decoder_queue_.empty());
                if (!decoder_queue_.empty())
                    keyframe_needed_.store(false);
            }
            if (codec_error)
            {
                ++decode_errors_;
                ++codec_resets_;
            }
            else
            {
                ++codec_watchdog_resets_;
            }
            ++decoder_resets_;
            const bool recovered = ++resets_without_output <= 4 && decoder_->reset_stream(generation);
            if (generation != video_surface_generation_.load())
                continue;
            if (!recovered)
            {
                request_stop();
                fail("Não foi possível recuperar o decodificador de vídeo.");
                break;
            }
            std::lock_guard lock(decoder_queue_mutex_);
            if (decoder_resync_required_.load())
                keyframe_needed_.store(true);
        }
    }
#else
    for (;;)
    {
        DecodeUnit unit;
        {
            std::unique_lock<std::mutex> lock(decoder_queue_mutex_);
            decoder_queue_cv_.wait(lock, [this] {
                return !decoder_running_.load(std::memory_order_acquire) || !decoder_queue_.empty();
            });
            if (!decoder_running_.load(std::memory_order_acquire))
                break;
            unit = std::move(decoder_queue_.front());
            decoder_queue_.pop_front();
        }

        if (decoder_reset_requested_.exchange(false) && decoder_)
            decoder_->reset_stream();
        const int decoded = decoder_
            ? decoder_->submit_decode_unit(unit.data.data(), static_cast<int>(unit.data.size()),
                                           unit.rtp_timestamp)
            : -1;
        if (decoded > 0)
        {
            last_decoded_frame_at_us_.store(NowUs(), std::memory_order_release);
            if (frames_decoded_.fetch_add(decoded) == 0)
                AppendStreamLog("DECODE first_frame");
        }
        else if (decoded < 0)
        {
            decode_errors_.fetch_add(1);
            decoder_resync_required_.store(true);
            if (!decoder_ || !decoder_->uses_hardware_frames())
                decoder_reset_requested_.store(true);
            keyframe_needed_.store(true);
        }
        if (unit.idr && decoded >= 0)
        {
            decoder_resync_required_.store(false);
            keyframe_needed_.store(false);
        }

        std::lock_guard<std::mutex> lock(decoder_queue_mutex_);
        recycle_decode_buffer_locked(std::move(unit.data));
    }
#endif
}

void LunaStreamSession::recycle_decode_buffer_locked(std::vector<uint8_t>&& buffer)
{
    constexpr size_t kPoolLimit = 8;
    constexpr size_t kMaximumReusableCapacity = 2 * 1024 * 1024;
    if (decoder_buffer_pool_.size() >= kPoolLimit || buffer.capacity() > kMaximumReusableCapacity)
        return;
    buffer.clear();
    decoder_buffer_pool_.push_back(std::move(buffer));
}

#ifdef __ANDROID__
uint64_t LunaStreamSession::observe_decoder_queue_age_locked()
{
    if (decoder_queue_.empty())
        return 0;
    const auto age_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - decoder_queue_.front().enqueued_at).count());
    peak_decoder_queue_age_ms_ = std::max(peak_decoder_queue_age_ms_, age_ms);
    return age_ms;
}
#endif

void LunaStreamSession::discard_decode_prefix_locked(size_t count)
{
#ifdef __ANDROID__
    observe_decoder_queue_age_locked();
#endif
    for (size_t i = 0; i < count; ++i)
    {
        recycle_decode_buffer_locked(std::move(decoder_queue_.front().data));
        decoder_queue_.pop_front();
    }
}

void LunaStreamSession::clear_decoder_queue_locked()
{
    discard_decode_prefix_locked(decoder_queue_.size());
}

void LunaStreamSession::enqueue_decode_unit(const uint8_t* data, size_t size, uint32_t rtp_timestamp, bool idr)
{
    if (!data || size == 0 || !decoder_running_.load(std::memory_order_acquire))
        return;

    std::lock_guard<std::mutex> lock(decoder_queue_mutex_);
#ifdef __ANDROID__
    if (!video_surface_attached_.load())
        return;
#endif
    const bool waiting_for_idr = decoder_resync_required_.load();
    if (waiting_for_idr && !idr)
    {
#ifdef __ANDROID__
        ++waiting_for_keyframe_drops_;
#endif
        decoder_queue_drops_++;
        keyframe_needed_.store(true, std::memory_order_release);
        return;
    }

#ifdef __ANDROID__
    if (waiting_for_idr)
    {
        decoder_queue_drops_ += static_cast<int>(decoder_queue_.size());
        clear_decoder_queue_locked();
    }
    // A recovered RTP burst can deliver several valid reference pictures at
    // once. Keep up to ~200 ms of encoded pictures, and drop stale decoded output.
    const auto queue_capacity = static_cast<size_t>((std::max(1, settings_.fps) + 4) / 5);
    if (decoder_queue_.size() >= queue_capacity)
    {
        ++queue_recoveries_;
        observe_decoder_queue_age_locked();
        const auto skip = idr ? decoder_queue_.size() : video::PicturesBeforeNewestKeyframe(decoder_queue_);
        if (!idr && (skip == 0 || skip == decoder_queue_.size()))
        {
            // Continue decoding the intact prefix while the next IDR is in
            // flight. Subsequent pictures depend on the one we just rejected.
            ++decoder_queue_drops_;
            ++waiting_for_keyframe_drops_;
            decoder_resync_required_.store(true);
            keyframe_needed_.store(true);
            return;
        }
        decoder_queue_drops_ += static_cast<int>(skip);
        discard_decode_prefix_locked(skip);
    }
#else
    const auto queue_capacity = video::MaximumQueuedAccessUnits(settings_.fps);
    if (decoder_queue_.size() >= queue_capacity)
    {
        decoder_queue_drops_ += static_cast<int>(decoder_queue_.size());
        clear_decoder_queue_locked();
        decoder_resync_required_.store(true);
        if (!decoder_ || !decoder_->uses_hardware_frames())
            decoder_reset_requested_.store(true);
        keyframe_needed_.store(true);
        if (!idr)
            return;
    }
#endif

    std::vector<uint8_t> buffer;
    if (!decoder_buffer_pool_.empty())
    {
        buffer = std::move(decoder_buffer_pool_.front());
        decoder_buffer_pool_.pop_front();
    }
    buffer.assign(data, data + size);
    decoder_queue_.push_back({std::move(buffer), idr, rtp_timestamp, std::chrono::steady_clock::now()});
#ifdef __ANDROID__
    if (idr)
    {
        if (first_pending_pli_at_us_ != 0)
        {
            AppendStreamLog("KEYFRAME accepted_idr elapsed_since_first_pli_ms=" +
                            std::to_string((NowUs() - first_pending_pli_at_us_) / 1000));
            first_pending_pli_at_us_ = 0;
        }
        decoder_resync_required_.store(false);
        keyframe_needed_.store(false);
    }
#endif
    decoder_queue_cv_.notify_one();
}

void LunaStreamSession::request_keyframe(const char* reason)
{
    const auto now = std::chrono::steady_clock::now();
    auto minimum_interval = std::chrono::milliseconds(650);
    if (!last_keyframe_request_sent_)
        minimum_interval = std::chrono::milliseconds(100);
    else if (decoder_resync_required_.load() || frames_decoded_.load() == 0)
        minimum_interval = std::chrono::milliseconds(350);
    if (last_keyframe_request_at_.time_since_epoch().count() != 0 &&
        now - last_keyframe_request_at_ < minimum_interval)
    {
#ifdef __ANDROID__
        keyframe_needed_.store(true);
#else
        if (decoder_resync_required_.load())
            keyframe_needed_.store(true);
#endif
        return;
    }
    last_keyframe_request_at_ = now;
    ++keyframe_request_attempts_;
    const int result = pc_ ? peer_connection_request_video_keyframe(pc_) : -1;
    last_keyframe_request_sent_ = result > 0;
#ifdef __ANDROID__
    if (last_keyframe_request_sent_ && decoder_resync_required_.load() && first_pending_pli_at_us_ == 0)
        first_pending_pli_at_us_ = NowUs();
#endif
    AppendStreamLog(std::string("KEYFRAME reason=") + reason + " pli=" + std::to_string(result));
    if (decoder_resync_required_.load()
#ifdef __ANDROID__
        || !last_keyframe_request_sent_
#endif
    )
        keyframe_needed_.store(true);
}

void LunaStreamSession::maybe_recover_decode_stall()
{
    if (!pc_)
        return;
#ifdef __ANDROID__
    if (!decoder_ || !video_surface_attached_.load())
        return;

    using Recovery = video::AndroidDecodeRecovery;
    const auto live_since = std::max(last_decoded_frame_at_us_.load(std::memory_order_acquire),
                                    video_run_start_us_);
    if (live_since == 0 || NowUs() - live_since < Recovery::kNoOutputUs)
        return;

    Recovery::Action action;
    {
        std::lock_guard lock(decoder_queue_mutex_);
        if (!video_surface_attached_.load())
            return;
        const auto last_output = last_decoded_frame_at_us_.load(std::memory_order_acquire);
        const auto now_us = NowUs();
        const auto signals = decoder_->signals();
        if (signals.output || signals.error || decoder_reset_requested_.load())
            return;

        const auto queued_since_us = decoder_queue_.empty() ? 0 : static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                decoder_queue_.front().enqueued_at.time_since_epoch()).count());
        action = codec_recovery_.poll(now_us, {
            last_output, video_run_start_us_, last_video_arrival_us_, last_codec_input_us_,
            last_codec_idr_input_us_, queued_since_us, signals.input,
        });
        if (action == Recovery::Action::RestartCodec)
        {
            decoder_reset_requested_.store(true);
            decoder_queue_cv_.notify_one();
        }
    }
    if (action == Recovery::Action::RequestKeyframe)
        request_keyframe("decode_soft_recovery");
#else
    const uint64_t last_decoded = last_decoded_frame_at_us_.load(std::memory_order_acquire);
    const uint64_t now_us = NowUs();
    if (last_decoded == 0 || now_us < last_decoded || now_us - last_decoded < 1'500'000)
        return;
    const auto now = std::chrono::steady_clock::now();
    if (last_decode_stall_request_at_.time_since_epoch().count() != 0 &&
        now - last_decode_stall_request_at_ < std::chrono::milliseconds(1200))
        return;
    last_decode_stall_request_at_ = now;
    {
        std::lock_guard<std::mutex> lock(decoder_queue_mutex_);
        clear_decoder_queue_locked();
    }
    decoder_resync_required_.store(true);
    if (!decoder_ || !decoder_->uses_hardware_frames())
        decoder_reset_requested_.store(true);
    request_keyframe("decode_stall");
#endif
}

void LunaStreamSession::maybe_recover_rtp_damage(bool incoming_idr)
{
    PeerVideoRtpStats stats {};
    if (!pc_ || peer_connection_get_video_rtp_stats(pc_, &stats) != 0)
        return;
    const uint32_t previous = last_rtp_access_units_dropped_;
    if (stats.access_units_dropped <= previous)
        return;
    last_rtp_access_units_dropped_ = stats.access_units_dropped;
    if (incoming_idr)
        return;

#ifdef __ANDROID__
    // The decoder can still conceal missing references. Keep sending complete
    // pictures after a network burst while requesting a clean keyframe.
    if (frames_decoded_.load(std::memory_order_relaxed) != 0 &&
        !decoder_resync_required_.load())
    {
        request_keyframe("rtp_recovery");
        return;
    }
#endif
    if (!decoder_resync_required_.exchange(true))
        keyframe_needed_.store(true);
}

void LunaStreamSession::on_video_packet(const PeerVideoPacket& packet)
{
    received_media_bytes_.fetch_add(packet.size, std::memory_order_relaxed);
#ifdef __ANDROID__
    const auto arrival_us = NowUs();
    const auto arrival_gap = arrival_us - last_video_arrival_us_;
    if (!last_video_arrival_us_ || arrival_gap > 250'000 || video_ssrc_ != packet.ssrc)
    {
        video_run_start_us_ = arrival_us;
        arrival_jitter_us_ = 0;
    }
    else
    {
        const auto ticks = static_cast<int32_t>(packet.timestamp - last_video_rtp_timestamp_);
        if (ticks > 0)
        {
            const auto media_gap = static_cast<int64_t>(ticks) * 100 / 9;
            const auto delta = static_cast<int64_t>(arrival_gap) - media_gap;
            arrival_jitter_us_ += ((delta < 0 ? -delta : delta) - arrival_jitter_us_) / 16;
        }
    }
    ++video_received_;
    last_video_arrival_us_ = arrival_us;
    last_video_rtp_timestamp_ = packet.timestamp;
#endif
    const bool idr = ContainsH264Idr(packet.data, packet.size);
    maybe_recover_rtp_damage(idr);

    video_ssrc_ = packet.ssrc;
    for (const auto& report : sender_reports_)
    {
        if (report.ssrc == video_ssrc_)
        {
            video_sr_ntp_us_ = report.ntp_us;
            video_sr_rtp_timestamp_ = report.rtp_timestamp;
            have_video_sender_report_ = true;
            break;
        }
    }
    if (packets_received_.fetch_add(1) == 0)
        AppendStreamLog("VIDEO first_access_unit size=" + std::to_string(packet.size) +
                        " idr=" + std::to_string(idr ? 1 : 0));
    enqueue_decode_unit(packet.data, packet.size, packet.timestamp, idr);
}

void LunaStreamSession::on_audio_packet(const PeerAudioPacket& packet)
{
    received_media_bytes_.fetch_add(packet.size, std::memory_order_relaxed);
    const bool new_ssrc = audio_ssrc_ != packet.ssrc;
    audio_ssrc_ = packet.ssrc;
    if (!audio_)
        return;
    audio_->submit(packet);
    if (new_ssrc)
        for (const auto& report : sender_reports_)
            if (report.ssrc == audio_ssrc_)
                audio_->set_sender_report(report.ssrc, report.ntp_us, report.rtp_timestamp);
}

void LunaStreamSession::on_rtp_sender_report(uint32_t ssrc, uint64_t ntp_us, uint32_t rtp_timestamp)
{
    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    auto found = std::find_if(sender_reports_.begin(), sender_reports_.end(),
                              [ssrc](const SenderReport& r) { return r.ssrc == ssrc; });
    if (found == sender_reports_.end())
        sender_reports_.push_back({ssrc, ntp_us, rtp_timestamp});
    else
        *found = {ssrc, ntp_us, rtp_timestamp};
    if (ssrc == audio_ssrc_ && audio_)
        audio_->set_sender_report(ssrc, ntp_us, rtp_timestamp);
    if (ssrc == video_ssrc_)
    {
        video_sr_ntp_us_ = ntp_us;
        video_sr_rtp_timestamp_ = rtp_timestamp;
        have_video_sender_report_ = true;
    }
}

#ifndef __ANDROID__
int64_t LunaStreamSession::video_target_rtp_timestamp() const
{
    std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
    if (!audio_ || !have_video_sender_report_)
        return AV_NOPTS_VALUE;
    const int64_t audio_ntp = audio_->playback_ntp_us();
    if (audio_ntp < 0)
        return AV_NOPTS_VALUE;
    return audio::RtpTimestampAtNtp(video_sr_rtp_timestamp_, static_cast<int64_t>(video_sr_ntp_us_),
                                    audio_ntp, 90000);
}

void LunaStreamSession::draw(NVGcontext* vg, int width, int height, AVFrame* frame, uint64_t generation)
{
    if (!renderer_ || !frame)
        return;
    rendered_width_.store(frame->width, std::memory_order_relaxed);
    rendered_height_.store(frame->height, std::memory_order_relaxed);
    renderer_->drawLatest(vg, width, height, frame, VIDEO_FORMAT_H264, generation);
    if (!have_presented_frame_ || generation != last_presented_generation_)
    {
        have_presented_frame_ = true;
        last_presented_generation_ = generation;
        presented_frames_.fetch_add(1, std::memory_order_relaxed);
    }
}

#endif

StreamCounters LunaStreamSession::stream_counters() const
{
    return {received_media_bytes_.load(std::memory_order_relaxed), presented_frames_.load(std::memory_order_relaxed)};
}

#ifdef __ANDROID__
bool LunaStreamSession::set_microphone_enabled(bool enabled)
{
    if (!enabled)
    {
        microphone_.stop();
        return true;
    }
    if (phase() != Phase::Streaming || stop_requested_.load())
        return false;
    return microphone_.start([this](const uint8_t* data, size_t size) {
        std::lock_guard<std::recursive_mutex> lock(peer_mutex_);
        if (pc_ && !stop_requested_.load())
            peer_connection_send_audio(pc_, data, size);
    });
}

bool LunaStreamSession::set_android_surface(ANativeWindow* window)
{
    if (!decoder_ || !decoder_running_.load())
        return !window;
    video_surface_attached_.store(false);
    const auto generation = ++video_surface_generation_;
    {
        std::lock_guard<std::mutex> lock(decoder_queue_mutex_);
        codec_recovery_.reset();
        last_codec_input_us_ = 0;
        last_codec_idr_input_us_ = 0;
        clear_decoder_queue_locked();
    }
    const bool ready = decoder_->set_output_window(window, generation);
    if (window && ready)
    {
        decoder_reset_requested_.store(false);
        decoder_resync_required_.store(true);
        last_decoded_frame_at_us_.store(NowUs(), std::memory_order_release);
        keyframe_needed_.store(true);
        std::lock_guard lock(decoder_queue_mutex_);
        video_surface_attached_.store(true);
        decoder_queue_cv_.notify_one();
    }
    return ready;
}

LunaStreamSession::AndroidVideoStats LunaStreamSession::android_video_stats()
{
    std::lock_guard<std::recursive_mutex> peer_lock(peer_mutex_);
    PeerVideoRtpStats rtp {};
    PeerNetworkRttStats rtt {};
    rtt.current_ms = -1;
    rtt.min_ms = -1;
    rtt.p95_ms = -1;
    rtt.jitter_ms = -1;
    if (pc_)
    {
        peer_connection_get_video_rtp_stats(pc_, &rtp);
        peer_connection_get_rtt_stats(pc_, &rtt);
    }
    std::lock_guard<std::mutex> queue_lock(decoder_queue_mutex_);
    const uint64_t queue_ms = observe_decoder_queue_age_locked();
    return {video_received_, static_cast<uint64_t>(frames_decoded_.load()),
            static_cast<uint64_t>(rtp.access_units_dropped), decoder_resets_.load(),
            codec_resets_.load(), queue_recoveries_.load(), queue_ms,
            static_cast<uint64_t>(arrival_jitter_us_ / 1000),
            decoder_ ? decoder_->codec_pending() : 0,
            decoder_ ? decoder_->peak_codec_delay_us() / 1000 : 0,
            rtp.non_picture_access_units, waiting_for_keyframe_drops_,
            rtp.reorder_gap_recoveries, rtp.reorder_gap_expirations,
            rendered_width_.load(std::memory_order_relaxed),
            rendered_height_.load(std::memory_order_relaxed),
            rtt.current_ms, rtt.min_ms, rtt.p95_ms, rtt.jitter_ms,
            rtp.packets_received, rtp.sequence_gaps, rtp.reordered_packets,
            rtp.late_packets_dropped, rtp.forced_sequence_skips, rtp.reorder_burst_fast_forwards,
            rtp.nack_requests, rtp.nack_packets_requested,
            rtp.rtx_packets_received, rtp.rtx_packets_recovered,
            decoder_ ? decoder_->submit_retries() : 0,
            decoder_ ? decoder_->stale_frames_dropped() : 0,
            static_cast<uint64_t>(keyframe_request_attempts_), peak_decoder_queue_age_ms_,
            codec_watchdog_resets_.load(),
            decoder_ ? decoder_->slow_codec_outputs() : 0,
            decoder_ ? decoder_->peak_playout_reserve_us() / 1000 : 0,
            decoder_ ? decoder_->deferred_frames_presented() : 0,
            rtp.nack_rtt_ms, rtp.rtx_response_ms};
}
#endif

#ifdef __ANDROID__
LunaStreamSession::LaunchQueueStatus LunaStreamSession::launch_queue_status() const
{
    std::lock_guard<std::mutex> lock(status_mutex_);
    return launch_queue_status_;
}
#endif

std::string LunaStreamSession::status_text() const
{
    std::lock_guard<std::mutex> lock(status_mutex_);
    return status_;
}

std::string LunaStreamSession::debug_text() const
{
    std::string region;
    {
        std::lock_guard<std::mutex> lock(status_mutex_);
        region = server_region_;
    }
    return TrFormat("Région {} · décodeur {} · {}x{} · paquets {} · images {} · erreurs {} · manette {}",
                    {region.empty() ? std::string("?") : region, video_backend_name_,
                     std::to_string(rendered_width_.load()), std::to_string(rendered_height_.load()),
                     std::to_string(packets_received_.load()), std::to_string(frames_decoded_.load()),
                     std::to_string(decode_errors_.load()), Tr(input_sid_ >= 0 ? "prête" : "en attente")});
}

} // namespace lunanx

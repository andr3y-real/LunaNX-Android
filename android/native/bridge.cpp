#include <android/native_window_jni.h>
#include <curl/curl.h>
#include <jni.h>
#include <qrcodegen.h>

#include "android_rumble.hpp"
#include "core/app_paths.hpp"
#include "core/i18n.hpp"
#include "luna/amazon_auth.hpp"
#include "luna/luna_client.hpp"
#include "luna/recent_games.hpp"
#include "luna/search_match.hpp"
#include "ui/image_url_policy.hpp"
#include "webrtc/luna_stream_session.hpp"

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <utility>

using namespace lunanx;

namespace
{
std::shared_ptr<luna::LunaClient> client;
std::vector<luna::Game> catalog;
std::vector<luna::Game> results;
std::vector<luna::Game> top_games;
std::vector<luna::Shelf> recent_shelves;
std::optional<luna::Game> playing;
std::unique_ptr<LunaStreamSession> session;
lunanx::input::GamepadState previous_input;
bool sent_input = false;
int64_t last_report_ms = 0;

std::string from_java(JNIEnv* env, jstring value)
{
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars)
        throw std::runtime_error("Não foi possível ler o texto.");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_java(JNIEnv* env, const std::string& value)
{
    auto bytes = env->NewByteArray(value.size());
    env->SetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<const jbyte*>(value.data()));
    auto type = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(type, "<init>", "([BLjava/lang/String;)V");
    auto charset = env->NewStringUTF("UTF-8");
    auto string = static_cast<jstring>(env->NewObject(type, constructor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(bytes);
    return string;
}

jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values)
{
    auto type = env->FindClass("java/lang/String");
    auto array = env->NewObjectArray(values.size(), type, nullptr);
    env->DeleteLocalRef(type);
    for (size_t i = 0; i < values.size(); ++i)
    {
        auto text = to_java(env, values[i]);
        env->SetObjectArrayElement(array, i, text);
        env->DeleteLocalRef(text);
    }
    return array;
}

void report_error(JNIEnv* env, const char* message)
{
    if (env->ExceptionCheck())
        return;
    auto type = env->FindClass("java/lang/IllegalStateException");
    auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;)V");
    auto text = to_java(env, message);
    env->Throw(static_cast<jthrowable>(env->NewObject(type, constructor, text)));
}

void append_shelves(std::vector<luna::Game>& games, std::set<std::string>& known,
                    const std::vector<luna::Shelf>& shelves)
{
    for (const auto& shelf : shelves)
        for (const auto& game : shelf.games)
            if (luna::InsertGameKeys(known, game))
                games.push_back(game);
}

jobjectArray game_rows(JNIEnv* env)
{
    std::vector<std::string> rows;
    rows.reserve(results.size() * 3);
    for (const auto& game : results)
    {
        rows.push_back(game.title);
        rows.push_back(ResizeAmazonImageUrl(
            game.image_portrait.empty() ? game.image_landscape : game.image_portrait, 384));
        rows.push_back(game.offer_tag);
    }
    return strings(env, rows);
}

jobjectArray connect(JNIEnv* env, const luna::DeviceRegistration& registration)
{
    luna::CookieJar cookies;
    cookies.SetCookies(registration.cookies);
    auto connected = std::make_shared<luna::LunaClient>(std::move(cookies), registration);
    connected->SignIn();
    connected->SetContentLanguage("pt");
    std::vector<luna::Shelf> shelves;
    try
    {
        shelves = connected->FetchShelves();
    }
    catch (const luna::LunaError&)
    {
        connected->SetContentLanguage("");
        shelves = connected->FetchShelves();
    }
    std::set<std::string> known;
    std::vector<luna::Game> games;
    append_shelves(games, known, shelves);
    std::vector<luna::Game> ranking;
    for (const auto& shelf : shelves)
    {
        if (shelf.row_type != "RANKINGS_ROW")
            continue;
        std::set<std::string> ranked;
        append_shelves(ranking, ranked, {shelf});
        if (ranking.size() > 10)
            ranking.resize(10);
        break;
    }
    std::vector<luna::Shelf> recent;
    try
    {
        recent = connected->FetchRecentShelves();
    }
    catch (const luna::LunaError&)
    {
    }
    top_games = std::move(ranking);
    recent_shelves = recent.empty() ? std::move(shelves) : std::move(recent);
    client = std::move(connected);
    catalog = std::move(games);
    results = catalog;
    return game_rows(env);
}

jbyteArray qr_code(JNIEnv* env, const std::string& url)
{
    uint8_t scratch[qrcodegen_BUFFER_LEN_MAX];
    uint8_t code[qrcodegen_BUFFER_LEN_MAX];
    if (!qrcodegen_encodeText(url.c_str(), scratch, code, qrcodegen_Ecc_MEDIUM,
                             qrcodegen_VERSION_MIN, qrcodegen_VERSION_MAX,
                             qrcodegen_Mask_AUTO, true))
        return nullptr;
    const int size = qrcodegen_getSize(code);
    std::vector<jbyte> pixels(size * size);
    for (int y = 0; y < size; ++y)
        for (int x = 0; x < size; ++x)
            pixels[y * size + x] = qrcodegen_getModule(code, x, y);
    auto result = env->NewByteArray(pixels.size());
    env->SetByteArrayRegion(result, 0, pixels.size(), pixels.data());
    return result;
}
}

#define JNI_METHOD(name) Java_org_lunanx_android_MainActivity_##name

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeInit)(JNIEnv* env, jobject, jstring path)
{
    try
    {
        static const bool initialized = curl_global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK;
        if (!initialized)
            throw std::runtime_error("Não foi possível iniciar a rede.");
        SetAndroidAppHome(from_java(env, path));
        SetUiLanguage("pt");
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeMarkets)(JNIEnv* env, jobject)
{
    std::vector<std::string> values;
    for (const auto& market : luna::AmazonMarkets())
    {
        values.push_back(market.code);
        values.push_back(market.native_name);
    }
    return strings(env, values);
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeLoad)(JNIEnv* env, jobject)
{
    try
    {
        luna::DeviceRegistration registration;
        if (luna::AmazonAuth::Load(registration))
            return connect(env, registration);
    }
    catch (const luna::SignInRequired&)
    {
        return nullptr;
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeSignIn)(
    JNIEnv* env, jobject activity, jstring market, jlong operation)
{
    try
    {
        auto type = env->GetObjectClass(activity);
        auto callback = env->GetMethodID(type, "onChallenge",
                                         "(JLjava/lang/String;Ljava/lang/String;[B)V");
        auto cancelled = env->GetMethodID(type, "isCancelled", "(J)Z");
        const auto is_cancelled = [&] {
            return env->ExceptionCheck() || env->CallBooleanMethod(activity, cancelled, operation);
        };
        auto registration = luna::AmazonAuth::SignInWithCode(from_java(env, market),
            [&](const luna::DeviceCodeChallenge& challenge) {
                auto code = to_java(env, challenge.user_code);
                auto url = to_java(env, challenge.verification_url);
                auto qr = qr_code(env, challenge.verification_url);
                env->CallVoidMethod(activity, callback, operation, code, url, qr);
                env->DeleteLocalRef(code);
                env->DeleteLocalRef(url);
                env->DeleteLocalRef(qr);
            }, is_cancelled);
        if (is_cancelled())
            return nullptr;
        if (!luna::AmazonAuth::Save(registration))
            throw std::runtime_error("Não foi possível salvar a sessão.");
        return connect(env, registration);
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeSignOut)(JNIEnv* env, jobject)
{
    client.reset();
    catalog.clear();
    results.clear();
    top_games.clear();
    recent_shelves.clear();
    try
    {
        luna::DeviceRegistration registration;
        const bool registered = luna::AmazonAuth::Load(registration);
        if (!luna::AmazonAuth::EraseAccountData())
            throw std::runtime_error("Não foi possível apagar a sessão local.");
        if (registered)
            luna::AmazonAuth::Deregister(registration);
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeSearch)(JNIEnv* env, jobject, jstring text)
{
    try
    {
        auto query = from_java(env, text);
        if (!client)
            throw std::runtime_error("Entre na conta Amazon primeiro.");
        if (query.empty())
            results = catalog;
        else
        {
            std::vector<luna::Game> matches;
            std::string server_error;
            try
            {
                matches = client->SearchGames(query);
            }
            catch (const luna::LunaError& error)
            {
                server_error = error.what();
            }
            std::set<std::string> seen;
            std::vector<luna::Game> combined;
            for (const auto& game : matches)
                if (luna::InsertGameKeys(seen, game))
                    combined.push_back(game);
            std::vector<std::pair<int, const luna::Game*>> local;
            for (const auto& game : catalog)
            {
                const int score = luna::GameTitleMatchScore(game.title, query);
                if (score > 0)
                    local.emplace_back(score, &game);
            }
            std::stable_sort(local.begin(), local.end(), [](const auto& first, const auto& second) {
                return first.first > second.first;
            });
            for (const auto& match : local)
                if (luna::InsertGameKeys(seen, *match.second))
                    combined.push_back(*match.second);
            if (combined.empty() && !server_error.empty())
                throw luna::LunaError(server_error);
            results = std::move(combined);
        }
        return game_rows(env);
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeSection)(JNIEnv* env, jobject, jint section)
{
    try
    {
        switch (section)
        {
            case 1: results = top_games; break;
            case 2: results = luna::RecentGamesForShelves(recent_shelves, luna::LoadRecentGames(), catalog); break;
            default: results = catalog; break;
        }
        return game_rows(env);
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeExplore)(
    JNIEnv* env, jobject activity, jlong operation)
{
    try
    {
        if (!client)
            throw std::runtime_error("Entre na conta Amazon primeiro.");
        auto type = env->GetObjectClass(activity);
        auto cancelled = env->GetMethodID(type, "isCancelled", "(J)Z");
        std::set<std::string> known;
        for (const auto& game : catalog)
            luna::InsertGameKeys(known, game);
        client->ExploreCatalog(std::move(known), [&](std::vector<luna::Game> games) {
            catalog.insert(catalog.end(), std::make_move_iterator(games.begin()),
                           std::make_move_iterator(games.end()));
        }, [&] { return !env->CallBooleanMethod(activity, cancelled, operation); });
        results = catalog;
        return game_rows(env);
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeStart)(
    JNIEnv* env, jobject, jint index, jobject surface, jboolean prefer_pt_br)
{
    try
    {
        if (!client || index < 0 || static_cast<size_t>(index) >= results.size())
            throw std::runtime_error("O jogo selecionado não está mais disponível.");
        if (session)
            throw std::runtime_error("Ainda há uma sessão em andamento.");

        std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)> window(
            ANativeWindow_fromSurface(env, surface), ANativeWindow_release);
        if (!window)
            throw std::runtime_error("A tela do jogo não está disponível.");
        auto next = std::make_unique<LunaStreamSession>(client, results[index], window.get(), prefer_pt_br == JNI_TRUE);
        next->start();
        playing = results[index];
        session = std::move(next);
        sent_input = false;
    }
    catch (const std::exception& error)
    {
        report_error(env, error.what());
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(nativeTick)(
    JNIEnv*, jobject, jint buttons, jfloat lt, jfloat rt, jfloat x, jfloat y, jfloat z, jfloat rz)
{
    if (!session)
        return 0;
    session->poll();
    const input::GamepadState current {
        static_cast<uint16_t>(buttons),
        static_cast<uint8_t>(lt * 255),
        static_cast<uint8_t>(rt * 255),
        input::AxisToXInput(x),
        input::AxisToXInput(y),
        input::AxisToXInput(z),
        input::AxisToXInput(rz)
    };
    auto now = input::WallClockMilliseconds();
    if (!sent_input || current != previous_input || now - last_report_ms >= 1000)
    {
        sent_input = session->send_gamepad(current);
        if (sent_input)
        {
            previous_input = current;
            last_report_ms = now;
        }
    }
    return static_cast<jint>(input::AndroidRumbleState());
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeStatus)(JNIEnv* env, jobject)
{
    if (!session)
        return nullptr;
    if (playing && session->phase() == LunaStreamSession::Phase::Streaming)
    {
        luna::RecordRecentGame(*playing);
        playing.reset();
    }
    const auto counters = session->stream_counters();
    const auto video = session->android_video_stats();
    return strings(env, {
        std::to_string(static_cast<int>(session->phase())),
        session->status_text(),
        std::to_string(counters.received_bytes),
        std::to_string(counters.presented_frames),
        std::to_string(video.received),
        std::to_string(video.decoded),
        std::to_string(video.discarded),
        std::to_string(video.resets),
        std::to_string(video.queue_ms),
        std::to_string(video.arrival_jitter_ms),
        std::to_string(video.codec_resets),
        std::to_string(video.queue_recoveries)
    });
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeSetVideoSurface)(
    JNIEnv* env, jobject, jobject surface)
{
    if (!session)
        return JNI_FALSE;
    std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)> window(
        surface ? ANativeWindow_fromSurface(env, surface) : nullptr, ANativeWindow_release);
    if (surface && !window)
        return JNI_FALSE;
    return session->set_android_surface(window.get()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeStop)(JNIEnv*, jobject)
{
    if (session && sent_input)
        session->send_gamepad({});
    session.reset();
    playing.reset();
}

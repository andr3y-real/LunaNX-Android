#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <thread>

struct AAudioStreamStruct;
struct OpusEncoder;

class AndroidMicrophone
{
public:
    ~AndroidMicrophone();
    bool start(std::function<void(const uint8_t*, size_t)> send);
    void stop();
    bool active() const { return running_.load(); }

private:
    void capture();
    AAudioStreamStruct* stream_ = nullptr;
    OpusEncoder* encoder_ = nullptr;
    std::atomic<bool> running_{false};
    std::thread worker_;
    std::function<void(const uint8_t*, size_t)> send_;
};

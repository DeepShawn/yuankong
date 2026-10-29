#pragma once
// Bounded, hardware-independent audio timeline and camera composition.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <vector>

namespace recording {
constexpr int sampleRate = 48000;
struct Timeline {
    static constexpr size_t capacity = sampleRate * 4;
    std::vector<float> samples = std::vector<float>(capacity * 2);
    std::vector<int64_t> stamps = std::vector<int64_t>(capacity, -1);
    void put(int64_t first, const float* stereo, size_t frames, int64_t consumed) {
        for (size_t i = 0; i < frames; ++i) {
            int64_t at = first + int64_t(i);
            if (at < consumed || at >= consumed + int64_t(capacity)) continue;
            size_t slot = size_t(at) % capacity;
            stamps[slot] = at;
            for (int c = 0; c < 2; ++c) {
                float value = stereo[i * 2 + c];
                samples[slot * 2 + c] = std::isfinite(value) ? value : 0.f;
            }
        }
    }
    float get(int64_t at, int channel) const {
        size_t slot = size_t(at) % capacity;
        return stamps[slot] == at ? samples[slot * 2 + channel] : 0.f;
    }
};
struct Mixer {
    Timeline system, microphone;
    int64_t cursor = 0;
    int systemGain, microphoneGain;
    Mixer(int systemPercent, int microphonePercent) : systemGain(systemPercent), microphoneGain(microphonePercent) {
        if (systemPercent < 0 || systemPercent > 200 || microphonePercent < 0 || microphonePercent > 200) throw std::invalid_argument("gain");
    }
    std::vector<int16_t> render(size_t frames) {
        if (frames > size_t(sampleRate)) throw std::invalid_argument("audio block");
        std::vector<int16_t> result(frames * 2);
        for (size_t i = 0; i < frames; ++i) for (int c = 0; c < 2; ++c) {
            float value = (system.get(cursor + int64_t(i), c) * systemGain + microphone.get(cursor + int64_t(i), c) * microphoneGain) / 100.f;
            value = std::max(-1.f, std::min(1.f, value));
            result[i * 2 + c] = int16_t(std::lround(value * 32767.f));
        }
        cursor += int64_t(frames);
        return result;
    }
};
inline std::vector<float> mono16(const uint8_t* data, size_t bytes) {
    std::vector<float> stereo((bytes / 2) * 2);
    for (size_t i = 0; i < bytes / 2; ++i) {
        int16_t value; std::memcpy(&value, data + i * 2, 2);
        stereo[i * 2] = stereo[i * 2 + 1] = value / 32768.f;
    }
    return stereo;
}
// Packet boundaries use absolute device positions, so 44.1/96k conversion never
// accumulates a per-packet rounding error. Native float/PCM packets are downmixed.
inline std::vector<float> resample(const std::vector<float>& stereo, uint64_t deviceFrame, unsigned rate) {
    if (rate < 8000 || rate > 384000 || stereo.size() % 2) throw std::invalid_argument("sample rate");
    uint64_t frames = stereo.size() / 2;
    uint64_t first = (deviceFrame * sampleRate + rate - 1) / rate;
    uint64_t end = ((deviceFrame + frames) * sampleRate + rate - 1) / rate;
    std::vector<float> out(size_t(end - first) * 2);
    for (uint64_t i = first; i < end; ++i) {
        double source = double(i) * rate / sampleRate - double(deviceFrame);
        size_t a = std::min(size_t(std::max(0., source)), size_t(frames - 1));
        size_t b = std::min(a + 1, size_t(frames - 1));
        float fraction = float(std::max(0., std::min(1., source - a)));
        for (int c = 0; c < 2; ++c) out[size_t(i - first) * 2 + c] = stereo[a * 2 + c] * (1.f - fraction) + stereo[b * 2 + c] * fraction;
    }
    return out;
}
struct PictureBox { unsigned x, y, width, height; };
inline PictureBox pictureBox(unsigned w, unsigned h, unsigned cw, unsigned ch, int corner) {
    if (w < 8 || h < 8 || !cw || !ch || corner < 0 || corner > 3) throw std::invalid_argument("picture dimensions");
    unsigned margin = std::min(8u, std::min(w, h) / 8);
    double scale = std::min(double(std::max(2u,w / 4)) / cw, double(std::max(2u,h / 3)) / ch);
    unsigned width = std::max(1u,unsigned(cw * scale)), height = std::max(1u,unsigned(ch * scale));
    return {corner == 1 || corner == 3 ? margin : w - margin - width,
            corner >= 2 ? margin : h - margin - height, width, height};
}
inline void overlay(std::vector<uint8_t>& desktop, unsigned w, unsigned h, const std::vector<uint8_t>& camera, unsigned cw, unsigned ch, int corner) {
    if (desktop.size() != size_t(w) * h * 4 || camera.size() != size_t(cw) * ch * 4) throw std::invalid_argument("picture buffer");
    auto box = pictureBox(w,h,cw,ch,corner);
    for (unsigned y = 0; y < box.height; ++y) for (unsigned x = 0; x < box.width; ++x) {
        auto* dest = desktop.data() + (size_t(y + box.y) * w + x + box.x) * 4;
        const auto* source = camera.data() + (size_t(y * ch / box.height) * cw + x * cw / box.width) * 4;
        std::memcpy(dest, source, 4);
        if (!x || !y || x == box.width - 1 || y == box.height - 1) { dest[0]=dest[1]=dest[2]=230; dest[3]=255; }
    }
}
}

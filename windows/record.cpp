// Native, explicitly started recordings. Windows privacy permissions and device
// indicators are left to Windows. stdin EOF finalizes a recording on agent exit.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <mmsystem.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <mmreg.h>
#include "record_mix.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <algorithm>
#include <atomic>
#include <thread>
#include <mutex>
#include <vector>
#include <string>
#include <memory>

template<class T> struct Ref {
    T* p = nullptr;
    ~Ref() { if (p) p->Release(); }
    T* operator->() const { return p; }
    T** put() { if (p) p->Release(); p = nullptr; return &p; }
};
static void check(HRESULT hr) { if (FAILED(hr)) throw hr; }
static std::atomic<bool> stopping{false};
static const char* recordingSource = "initialization";
static LONGLONG qpcTime() { LARGE_INTEGER value,frequency; QueryPerformanceCounter(&value); QueryPerformanceFrequency(&frequency); return LONGLONG((long double)value.QuadPart * 10000000 / frequency.QuadPart); }
static std::string quoted(const wchar_t* text) {
    int n = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
    std::vector<char> data(std::max(n, 1));
    WideCharToMultiByte(CP_UTF8, 0, text, -1, data.data(), n, nullptr, nullptr);
    std::string s = "\"";
    for (int i = 0; i < n - 1; ++i) {
        unsigned char c = (unsigned char)data[i];
        if (c == '\\' || c == '"') s += '\\';
        if (c < 32) { char b[8]; std::snprintf(b, sizeof(b), "\\u%04x", c); s += b; }
        else s += char(c);
    }
    return s + "\"";
}
struct Cameras {
    IMFActivate** devices = nullptr; UINT32 count = 0;
    Cameras() {
        Ref<IMFAttributes> a; check(MFCreateAttributes(a.put(), 1));
        check(a->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID));
        check(MFEnumDeviceSources(a.p, &devices, &count));
    }
    ~Cameras() { for (UINT32 i = 0; i < count; ++i) devices[i]->Release(); CoTaskMemFree(devices); }
};
static void devices() {
    std::printf("{\"microphones\":["); bool comma = false;
    for (UINT i = 0; i < waveInGetNumDevs(); ++i) {
        WAVEINCAPSW caps = {};
        if (waveInGetDevCapsW(i, &caps, sizeof(caps)) != MMSYSERR_NOERROR) continue;
        std::printf("%s{\"id\":%u,\"name\":%s}", comma ? "," : "", i, quoted(caps.szPname).c_str()); comma = true;
    }
    std::printf("],\"cameras\":[");
    try {
        Cameras list;
        for (UINT i = 0; i < list.count; ++i) {
            wchar_t* name = nullptr; UINT32 length = 0;
            check(list.devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &length));
            std::printf("%s{\"id\":%u,\"name\":%s}", i ? "," : "", i, quoted(name).c_str()); CoTaskMemFree(name);
        }
    } catch (HRESULT) { /* A machine without a camera can still record audio. */ }
    std::printf("],\"protocol\":2,\"features\":[\"system_audio\",\"panorama\"]}\n");
}
static void fit(UINT sourceW, UINT sourceH, UINT& w, UINT& h) {
    if (!sourceW || !sourceH || sourceW > 32768 || sourceH > 32768) throw E_INVALIDARG;
    double s = std::min(1.0, std::min(1280.0 / sourceW, 720.0 / sourceH));
    w = std::max(2u, UINT(sourceW * s) & ~1u); h = std::max(2u, UINT(sourceH * s) & ~1u);
}
struct Screen {
    HDC dc = nullptr, target = nullptr; HBITMAP bitmap = nullptr; HGDIOBJ previous = nullptr;
    BYTE* pixels = nullptr; UINT w, h; int x, y, sourceW, sourceH;
    Screen() {
        SetProcessDPIAware(); x = GetSystemMetrics(SM_XVIRTUALSCREEN); y = GetSystemMetrics(SM_YVIRTUALSCREEN);
        sourceW = GetSystemMetrics(SM_CXVIRTUALSCREEN); sourceH = GetSystemMetrics(SM_CYVIRTUALSCREEN);
        fit(UINT(sourceW), UINT(sourceH), w, h);
        dc = GetDC(nullptr); target = CreateCompatibleDC(dc);
        BITMAPINFO info = {}; info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        info.bmiHeader.biWidth = w; info.bmiHeader.biHeight = -LONG(h); info.bmiHeader.biPlanes = 1; info.bmiHeader.biBitCount = 32;
        bitmap = CreateDIBSection(dc, &info, DIB_RGB_COLORS, (void**)&pixels, nullptr, 0);
        if (!dc || !target || !bitmap) { cleanup(); throw E_FAIL; }
        previous = SelectObject(target, bitmap); SetStretchBltMode(target, HALFTONE);
    }
    void cleanup() { if (previous) SelectObject(target, previous); if (bitmap) DeleteObject(bitmap); if (target) DeleteDC(target); if (dc) ReleaseDC(nullptr, dc); }
    ~Screen() { cleanup(); }
    std::vector<BYTE> frame() {
        if (!StretchBlt(target, 0, 0, w, h, dc, x, y, sourceW, sourceH, SRCCOPY | CAPTUREBLT)) throw E_FAIL;
        CURSORINFO cursor = {}; cursor.cbSize = sizeof(cursor);
        if (GetCursorInfo(&cursor) && (cursor.flags & CURSOR_SHOWING)) {
            ICONINFO icon = {};
            if (GetIconInfo(cursor.hCursor, &icon)) {
                DrawIconEx(target, int((cursor.ptScreenPos.x - x - int(icon.xHotspot)) * double(w) / sourceW),
                    int((cursor.ptScreenPos.y - y - int(icon.yHotspot)) * double(h) / sourceH), cursor.hCursor,
                    std::max(1, int(GetSystemMetrics(SM_CXCURSOR) * double(w) / sourceW)),
                    std::max(1, int(GetSystemMetrics(SM_CYCURSOR) * double(h) / sourceH)), 0, nullptr, DI_NORMAL);
                if (icon.hbmMask) DeleteObject(icon.hbmMask); if (icon.hbmColor) DeleteObject(icon.hbmColor);
            }
        }
        GdiFlush(); return std::vector<BYTE>(pixels, pixels + size_t(w) * h * 4);
    }
};
struct Camera {
    Ref<IMFMediaSource> source; Ref<IMFSourceReader> reader;
    UINT w = 0, h = 0, sourceW = 0, sourceH = 0; LONG stride = 0;
    std::thread worker; std::mutex lock; std::vector<BYTE> latest;
    std::atomic<bool> done{false}; HRESULT error = S_OK;
    explicit Camera(UINT index) {
        Cameras list; if (index >= list.count) throw E_INVALIDARG;
        check(list.devices[index]->ActivateObject(IID_IMFMediaSource, (void**)source.put()));
        try {
            Ref<IMFAttributes> a; check(MFCreateAttributes(a.put(), 2));
            check(a->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE));
            check(a->SetUINT32(MF_LOW_LATENCY, TRUE));
            check(MFCreateSourceReaderFromMediaSource(source.p, a.p, reader.put()));
            check(reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE));
            check(reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE));
            // Prefer a bounded native format before asking the video processor for RGB.
            Ref<IMFMediaType> native; int best = -1; UINT64 bestScore = 0;
            for (DWORD i = 0; i < 256; ++i) {
                if (FAILED(reader->GetNativeMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, i, native.put()))) break;
                UINT32 nw = 0, nh = 0, n = 0, d = 0;
                if (FAILED(MFGetAttributeSize(native.p, MF_MT_FRAME_SIZE, &nw, &nh))) continue;
                MFGetAttributeRatio(native.p, MF_MT_FRAME_RATE, &n, &d);
                if (nw > 1920 || nh > 1080 || (d && n / d < 12)) continue;
                UINT64 score = UINT64(std::min(nw, 1280u)) * std::min(nh, 720u);
                if (score > bestScore) { bestScore = score; best = int(i); }
            }
            if (best >= 0) {
                check(reader->GetNativeMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, DWORD(best), native.put()));
                check(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, native.p));
            }
            Ref<IMFMediaType> type; check(MFCreateMediaType(type.put()));
            check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)); check(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32));
            check(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type.p));
            check(reader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, type.put()));
            check(MFGetAttributeSize(type.p, MF_MT_FRAME_SIZE, &sourceW, &sourceH)); fit(sourceW, sourceH, w, h);
            UINT32 encoded = 0; stride = -LONG(sourceW * 4);
            if (SUCCEEDED(type->GetUINT32(MF_MT_DEFAULT_STRIDE, &encoded))) stride = LONG(encoded);
            worker = std::thread([this] { read(); });
        } catch (...) { source->Shutdown(); throw; }
    }
    void read() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        try {
            while (!done) {
                Ref<IMFSample> sample; DWORD actual = 0, flags = 0; LONGLONG pts = 0;
                check(reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, &actual, &flags, &pts, sample.put()));
                if (flags & (MF_SOURCE_READERF_ERROR | MF_SOURCE_READERF_ENDOFSTREAM | MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED)) throw E_FAIL;
                if (!sample.p) continue;
                Ref<IMFMediaBuffer> buffer; check(sample->ConvertToContiguousBuffer(buffer.put()));
                Ref<IMF2DBuffer> surface; BYTE* data = nullptr; LONG pitch = stride; DWORD size = 0, capacity = 0;
                bool twoD = SUCCEEDED(buffer->QueryInterface(IID_IMF2DBuffer, (void**)surface.put()));
                if (twoD) check(surface->Lock2D(&data, &pitch)); else check(buffer->Lock(&data, &capacity, &size));
                try {
                    if (std::abs(pitch) < LONG(sourceW * 4) || (!twoD && size_t(std::abs(pitch)) * sourceH > size)) throw E_FAIL;
                    BYTE* top = (!twoD && pitch < 0) ? data + size_t(sourceH - 1) * std::abs(pitch) : data;
                    std::vector<BYTE> image(size_t(w) * h * 4);
                    for (UINT y = 0; y < h; ++y) for (UINT x = 0; x < w; ++x)
                        std::memcpy(image.data() + (size_t(y) * w + x) * 4, top + ptrdiff_t(y * sourceH / h) * pitch + (x * sourceW / w) * 4, 4);
                    std::lock_guard<std::mutex> hold(lock); latest.swap(image);
                } catch (...) { if (twoD) surface->Unlock2D(); else buffer->Unlock(); throw; }
                if (twoD) surface->Unlock2D(); else buffer->Unlock();
            }
        } catch (HRESULT hr) { std::lock_guard<std::mutex> hold(lock); error = hr; }
          catch (...) { std::lock_guard<std::mutex> hold(lock); error = E_FAIL; }
        CoUninitialize();
    }
    std::vector<BYTE> frame() { std::lock_guard<std::mutex> hold(lock); check(error); return latest; }
    ~Camera() { done = true; if (source.p) source->Shutdown(); if (worker.joinable()) worker.join(); }
};
struct MicrophoneCapture {
    HWAVEIN input = nullptr; WAVEFORMATEX format = {}; WAVEHDR headers[6] = {};
    std::vector<BYTE> buffers[6]; UINT64 samples = 0;
    explicit MicrophoneCapture(int device) {
        format.wFormatTag = WAVE_FORMAT_PCM; format.nChannels = 1; format.nSamplesPerSec = 48000;
        format.wBitsPerSample = 16; format.nBlockAlign = 2; format.nAvgBytesPerSec = 96000;
        if (waveInOpen(&input, device < 0 ? WAVE_MAPPER : UINT(device), &format, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR)
            throw HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED);
        try {
            for (int i = 0; i < 6; ++i) {
                buffers[i].resize(9600); headers[i].lpData = (LPSTR)buffers[i].data(); headers[i].dwBufferLength = DWORD(buffers[i].size());
                if (waveInPrepareHeader(input, &headers[i], sizeof(WAVEHDR)) || waveInAddBuffer(input, &headers[i], sizeof(WAVEHDR))) throw E_FAIL;
            }
        } catch (...) { cleanup(); throw; }
    }
    void start() { if (waveInStart(input)) throw E_FAIL; }
    void cleanup() {
        if (!input) return; waveInReset(input);
        for (auto& header : headers) if (header.dwFlags & WHDR_PREPARED) waveInUnprepareHeader(input, &header, sizeof(WAVEHDR));
        waveInClose(input); input = nullptr;
    }
    ~MicrophoneCapture() { cleanup(); }
    template<class F> void drain(F consume, bool recycle) {
        // Headers complete in queue order. Always consume from the oldest one.
        for (int attempts = 0; attempts < 6; ++attempts) {
            WAVEHDR& header = headers[next]; if (!(header.dwFlags & WHDR_DONE)) break;
            if (header.dwBytesRecorded) { consume((BYTE*)header.lpData, header.dwBytesRecorded, samples); samples += header.dwBytesRecorded / 2; }
            if (recycle) { header.dwBytesRecorded = 0; if (waveInAddBuffer(input, &header, sizeof(header))) throw E_FAIL; }
            next = (next + 1) % 6;
        }
    }
    int next = 0;
};
struct Loopback {
    Ref<IMMDeviceEnumerator> enumerator; Ref<IMMDevice> device;
    Ref<IAudioClient> client; Ref<IAudioCaptureClient> capture;
    WAVEFORMATEX* format = nullptr; bool floating = false, started = false;
    std::wstring selectedDevice; ULONGLONG nextDeviceCheck = 0;
    Loopback() {
        try {
            check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**)enumerator.put()));
            check(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, device.put()));
            LPWSTR endpoint=nullptr;check(device->GetId(&endpoint));selectedDevice=endpoint;CoTaskMemFree(endpoint);
            check(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)client.put()));
            check(client->GetMixFormat(&format));
            if (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
                if (format->cbSize < sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX)) throw E_INVALIDARG;
                auto* ext = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(format);
                const GUID pcm = {1,0,0x10,{0x80,0,0,0xaa,0,0x38,0x9b,0x71}};
                const GUID ieee = {3,0,0x10,{0x80,0,0,0xaa,0,0x38,0x9b,0x71}};
                if (IsEqualGUID(ext->SubFormat, ieee)) floating = true;
                else if (!IsEqualGUID(ext->SubFormat, pcm)) throw AUDCLNT_E_UNSUPPORTED_FORMAT;
            } else if (format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) floating = true;
            else if (format->wFormatTag != WAVE_FORMAT_PCM) throw AUDCLNT_E_UNSUPPORTED_FORMAT;
            if (!format->nChannels || format->nChannels > 32 || format->nSamplesPerSec < 8000 || format->nSamplesPerSec > 384000 ||
                (floating ? format->wBitsPerSample != 32 : (format->wBitsPerSample != 8 && format->wBitsPerSample != 16 && format->wBitsPerSample != 24 && format->wBitsPerSample != 32)) ||
                format->nBlockAlign < format->nChannels * (format->wBitsPerSample / 8)) throw AUDCLNT_E_UNSUPPORTED_FORMAT;
            check(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 2000000, 0, format, nullptr));
            check(client->GetService(__uuidof(IAudioCaptureClient), (void**)capture.put()));
        } catch (...) { cleanup(); throw; }
    }
    void start() { check(client->Start()); started = true; }
    void cleanup() {
        if (started) { client->Stop(); started = false; }
        if (format) { CoTaskMemFree(format); format = nullptr; }
    }
    ~Loopback() { cleanup(); }
    float value(const BYTE* p) const {
        if (floating) { float x; std::memcpy(&x,p,4); return std::isfinite(x) ? x : 0.f; }
        if (format->wBitsPerSample == 8) return (int(*p) - 128) / 128.f;
        if (format->wBitsPerSample == 16) { int16_t x; std::memcpy(&x,p,2); return x / 32768.f; }
        if (format->wBitsPerSample == 24) { int32_t x = int32_t(p[0]) | (int32_t(p[1])<<8) | (int32_t(p[2])<<16); if (x & 0x800000) x |= ~0xffffff; return x / 8388608.f; }
        int32_t x; std::memcpy(&x,p,4); return float(x / 2147483648.);
    }
    void drain(recording::Mixer& mixer, LONGLONG start) {
        if (GetTickCount64()>=nextDeviceCheck) {
            Ref<IMMDevice> current;check(enumerator->GetDefaultAudioEndpoint(eRender,eConsole,current.put()));
            LPWSTR endpoint=nullptr;check(current->GetId(&endpoint));bool same=selectedDevice==endpoint;CoTaskMemFree(endpoint);
            if (!same)throw AUDCLNT_E_DEVICE_INVALIDATED;
            nextDeviceCheck=GetTickCount64()+1000;
        }
        UINT32 available = 0; check(capture->GetNextPacketSize(&available));
        for (int packets = 0; available && packets < 128; ++packets) {
            BYTE* data = nullptr; UINT32 frames = 0; DWORD flags = 0; UINT64 deviceFrame = 0, timestamp = 0;
            check(capture->GetBuffer(&data,&frames,&flags,&deviceFrame,&timestamp));
            try {
                if (frames > format->nSamplesPerSec * 4u) throw E_FAIL;
                std::vector<float> stereo(size_t(frames) * 2,0.f);
                if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
                    if (!data && frames) throw E_POINTER;
                    unsigned bytes = format->wBitsPerSample / 8;
                    for (UINT32 i = 0; i < frames; ++i) {
                        const BYTE* frame = data + size_t(i) * format->nBlockAlign;
                        float left = value(frame), right = format->nChannels > 1 ? value(frame + bytes) : left;
                        for (unsigned c = 2; c < format->nChannels; ++c) { float extra = value(frame + c * bytes) * .5f; left += extra; right += extra; }
                        float scale = 1.f + .5f * std::max(0,int(format->nChannels)-2);
                        stereo[size_t(i)*2] = left / scale; stereo[size_t(i)*2+1] = right / scale;
                    }
                }
                auto converted = recording::resample(stereo,deviceFrame,format->nSamplesPerSec);
                if ((flags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR) || !timestamp) timestamp = UINT64(qpcTime() - LONGLONG(frames) * 10000000 / format->nSamplesPerSec);
                double phase = std::ceil(double(deviceFrame) * 48000 / format->nSamplesPerSec) - double(deviceFrame) * 48000 / format->nSamplesPerSec;
                int64_t first = int64_t(std::llround(double(LONGLONG(timestamp)-start) * 48000 / 10000000 + phase));
                mixer.system.put(first,converted.data(),converted.size()/2,mixer.cursor);
            } catch (...) { capture->ReleaseBuffer(frames); throw; }
            check(capture->ReleaseBuffer(frames)); check(capture->GetNextPacketSize(&available));
        }
    }
};

static BYTE clip(int v) { return BYTE(std::max(0, std::min(255, v))); }
static std::vector<BYTE> nv12(const std::vector<BYTE>& rgb, UINT w, UINT h) {
    std::vector<BYTE> out(size_t(w) * h * 3 / 2); BYTE* uv = out.data() + size_t(w) * h;
    for (UINT y = 0; y < h; ++y) for (UINT x = 0; x < w; ++x) {
        const BYTE* p = rgb.data() + (size_t(y) * w + x) * 4;
        out[size_t(y) * w + x] = clip(((66 * p[2] + 129 * p[1] + 25 * p[0] + 128) >> 8) + 16);
        if (!(x & 1) && !(y & 1)) {
            int b = 0, g = 0, r = 0;
            for (UINT dy = 0; dy < 2; ++dy) for (UINT dx = 0; dx < 2; ++dx) {
                const BYTE* q = rgb.data() + (size_t(y + dy) * w + x + dx) * 4; b += q[0]; g += q[1]; r += q[2];
            }
            b /= 4; g /= 4; r /= 4;
            uv[size_t(y / 2) * w + x] = clip(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            uv[size_t(y / 2) * w + x + 1] = clip(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
    }
    return out;
}
static void writeSample(IMFSinkWriter* writer, DWORD stream, const BYTE* data, DWORD size, LONGLONG pts, LONGLONG duration) {
    Ref<IMFMediaBuffer> buffer; Ref<IMFSample> sample; check(MFCreateMemoryBuffer(size, buffer.put()));
    BYTE* dest = nullptr; check(buffer->Lock(&dest, nullptr, nullptr)); std::memcpy(dest, data, size); check(buffer->Unlock());
    check(buffer->SetCurrentLength(size)); check(MFCreateSample(sample.put())); check(sample->AddBuffer(buffer.p));
    check(sample->SetSampleTime(pts)); check(sample->SetSampleDuration(duration)); check(writer->WriteSample(stream, sample.p));
}
struct Video {
    Ref<IMFSinkWriter> writer; DWORD video = 0, audio = 0;
    Video(const wchar_t* path, UINT w, UINT h, bool mic, UINT channels = 1) {
        Ref<IMFAttributes> a; check(MFCreateAttributes(a.put(), 1)); check(a->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, TRUE));
        check(MFCreateSinkWriterFromURL(path, nullptr, a.p, writer.put()));
        Ref<IMFMediaType> output; check(MFCreateMediaType(output.put()));
        check(output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)); check(output->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264));
        check(output->SetUINT32(MF_MT_AVG_BITRATE, 3000000)); check(output->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
        check(MFSetAttributeSize(output.p, MF_MT_FRAME_SIZE, w, h)); check(MFSetAttributeRatio(output.p, MF_MT_FRAME_RATE, 12, 1));
        check(MFSetAttributeRatio(output.p, MF_MT_PIXEL_ASPECT_RATIO, 1, 1)); check(writer->AddStream(output.p, &video));
        Ref<IMFMediaType> input; check(MFCreateMediaType(input.put())); check(output->CopyAllItems(input.p));
        check(input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12)); check(input->SetUINT32(MF_MT_DEFAULT_STRIDE, w));
        check(writer->SetInputMediaType(video, input.p, nullptr));
        if (mic) {
            check(MFCreateMediaType(output.put())); check(output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio));
            check(output->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC)); check(output->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels));
            check(output->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, 48000)); check(output->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16));
            check(output->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, channels == 2 ? 24000 : 12000)); check(output->SetUINT32(MF_MT_AAC_PAYLOAD_TYPE, 0));
            check(writer->AddStream(output.p, &audio));
            check(MFCreateMediaType(input.put())); check(input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio));
            check(input->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM)); check(input->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels));
            check(input->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, 48000)); check(input->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16));
            check(input->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, channels * 2)); check(input->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 48000 * channels * 2));
            check(writer->SetInputMediaType(audio, input.p, nullptr));
        }
        check(writer->BeginWriting());
    }
};
struct Wav {
    FILE* file = nullptr; DWORD size = 0;
    explicit Wav(const wchar_t* path) { file = _wfopen(path, L"wb"); if (!file) throw E_ACCESSDENIED; BYTE empty[44] = {}; if (std::fwrite(empty, 1, 44, file) != 44) { std::fclose(file); file = nullptr; throw E_FAIL; } }
    void append(const BYTE* data, DWORD n) { if (std::fwrite(data, 1, n, file) != n) throw HRESULT_FROM_WIN32(ERROR_DISK_FULL); size += n; }
    void finish() {
        BYTE header[44] = {}; std::memcpy(header, "RIFF", 4); DWORD riff = size + 36; std::memcpy(header + 4, &riff, 4);
        std::memcpy(header + 8, "WAVEfmt ", 8); DWORD fmt = 16; WORD pcm = 1, channels = 1, align = 2, bits = 16;
        DWORD rate = 48000, bytes = 96000;
        std::memcpy(header + 16, &fmt, 4); std::memcpy(header + 20, &pcm, 2); std::memcpy(header + 22, &channels, 2);
        std::memcpy(header + 24, &rate, 4); std::memcpy(header + 28, &bytes, 4); std::memcpy(header + 32, &align, 2);
        std::memcpy(header + 34, &bits, 2); std::memcpy(header + 36, "data", 4); std::memcpy(header + 40, &size, 4);
        if (_fseeki64(file, 0, SEEK_SET) || std::fwrite(header, 1, 44, file) != 44 || std::fflush(file)) throw E_FAIL;
    }
    ~Wav() { if (file) std::fclose(file); }
};
static int number(const wchar_t* s, int low, int high) { wchar_t* end = nullptr; long v = std::wcstol(s, &end, 10); if (!*s || *end || v < low || v > high) throw E_INVALIDARG; return int(v); }
static void panoramaSelfTest(const wchar_t* directory) {
    std::wstring path=std::wstring(directory)+L"\\panorama.mp4";
    {
        Video video(path.c_str(),640,360,true,2);recording::Mixer mixer(100,100);
        std::vector<BYTE> desktop(640*360*4),camera(320*240*4);
        for(size_t i=0;i<desktop.size();i+=4){desktop[i]=30;desktop[i+1]=80;desktop[i+2]=20;desktop[i+3]=255;}
        for(size_t i=0;i<camera.size();i+=4){camera[i]=20;camera[i+1]=30;camera[i+2]=220;camera[i+3]=255;}
        for(int frame=0;frame<24;++frame) {
            auto picture=desktop;recording::overlay(picture,640,360,camera,320,240,0);auto image=nv12(picture,640,360);
            writeSample(video.writer.p,video.video,image.data(),DWORD(image.size()),frame*10000000LL/12,10000000/12);
            std::vector<float> system(8000),mic(8000);
            for(int i=0;i<4000;++i) {
                int64_t at=int64_t(frame)*4000+i;
                system[i*2]=(at/48)%2?.18f:-.18f;system[i*2+1]=(at/24)%2?.15f:-.15f;
                mic[i*2]=mic[i*2+1]=(at/96)%2?.12f:-.12f;
            }
            mixer.system.put(int64_t(frame)*4000,system.data(),4000,mixer.cursor);
            mixer.microphone.put(int64_t(frame)*4000,mic.data(),4000,mixer.cursor);
            auto pcm=mixer.render(4000);
            writeSample(video.writer.p,video.audio,reinterpret_cast<const BYTE*>(pcm.data()),DWORD(pcm.size()*2),frame*10000000LL/12,10000000/12);
        }
        check(video.writer->Finalize());
    }
    // Decode the actual H.264 picture and AAC samples, checking the camera inset,
    // background, stereo channels, two independent frequencies and duration.
    Ref<IMFSourceReader> reader;check(MFCreateSourceReaderFromURL(path.c_str(),nullptr,reader.put()));
    check(reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS,FALSE));
    check(reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM,TRUE));
    Ref<IMFMediaType> type;check(MFCreateMediaType(type.put()));
    check(type->SetGUID(MF_MT_MAJOR_TYPE,MFMediaType_Video));check(type->SetGUID(MF_MT_SUBTYPE,MFVideoFormat_NV12));
    check(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM,nullptr,type.p));
    Ref<IMFSample> sample;DWORD actual=0,flags=0;LONGLONG pts=0;
    for(int i=0;i<50 && !sample.p;++i)check(reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM,0,&actual,&flags,&pts,sample.put()));
    if(!sample.p)throw E_FAIL;
    Ref<IMFMediaBuffer> buffer;check(sample->ConvertToContiguousBuffer(buffer.put()));BYTE* bytes=nullptr;DWORD size=0;
    check(buffer->Lock(&bytes,nullptr,&size));
    bool composed=size>=640*360*3/2 && bytes[180*640+320]>45 && bytes[180*640+320]<80 && bytes[290*640+550]>75;
    buffer->Unlock();if(!composed)throw E_FAIL;
    reader.p->Release();reader.p=nullptr;
    check(MFCreateSourceReaderFromURL(path.c_str(),nullptr,reader.put()));
    check(reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS,FALSE));check(reader->SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM,TRUE));
    check(MFCreateMediaType(type.put()));check(type->SetGUID(MF_MT_MAJOR_TYPE,MFMediaType_Audio));check(type->SetGUID(MF_MT_SUBTYPE,MFAudioFormat_PCM));
    check(type->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE,16));check(type->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS,2));check(type->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND,48000));
    check(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM,nullptr,type.p));
    UINT64 count=0;long double leftEnergy=0,rightEnergy=0,difference=0;double mr=0,mi=0,lr=0,li=0,rr=0,ri=0;LONGLONG previous=std::numeric_limits<LONGLONG>::min(),end=0;
    for(int i=0;i<400;++i) {
        check(reader->ReadSample(MF_SOURCE_READER_FIRST_AUDIO_STREAM,0,&actual,&flags,&pts,sample.put()));
        if(flags & MF_SOURCE_READERF_ERROR)throw E_FAIL;
        if(sample.p) {
            if(pts<previous)throw E_FAIL;previous=pts;LONGLONG duration=0;check(sample->GetSampleDuration(&duration));end=pts+duration;
            check(sample->ConvertToContiguousBuffer(buffer.put()));check(buffer->Lock(&bytes,nullptr,&size));
            for(DWORD n=0;n+3<size;n+=4) {
                int16_t l,r;std::memcpy(&l,bytes+n,2);std::memcpy(&r,bytes+n+2,2);
                leftEnergy+=double(l)*l;rightEnergy+=double(r)*r;difference+=double(l-r)*(l-r);
                double angle=6.283185307179586*double(count)/48000;
                mr+=(l+r)*.5*std::cos(angle*250);mi+=(l+r)*.5*std::sin(angle*250);
                lr+=l*std::cos(angle*500);li+=l*std::sin(angle*500);rr+=r*std::cos(angle*1000);ri+=r*std::sin(angle*1000);++count;
            }
            buffer->Unlock();
        }
        if(flags & MF_SOURCE_READERF_ENDOFSTREAM)break;
    }
    if(count<90000 || count>100000 || end<19000000 || end>21500000 || leftEnergy/count<1000000 || rightEnergy/count<1000000 || difference/count<1000000 || (mr*mr+mi*mi)/(count*count)<1000000 || (lr*lr+li*li)/(count*count)<2000000 || (rr*rr+ri*ri)/(count*count)<2000000)throw E_FAIL;
}

// Exercises the real Windows encoders and decoder without accessing hardware.
static void selfTest(const wchar_t* directory) {
    std::wstring base(directory), mp4 = base + L"\\test.mp4", wave = base + L"\\test.wav";
    {
        Video video(mp4.c_str(), 320, 240, true); Wav wav(wave.c_str());
        std::vector<BYTE> rgb(320 * 240 * 4), pcm(8000);
        for (UINT i = 0; i < 24; ++i) {
            for (UINT y = 0; y < 240; ++y) for (UINT x = 0; x < 320; ++x) {
                BYTE* p = rgb.data() + (y * 320 + x) * 4; p[0] = BYTE(x + i); p[1] = BYTE(y); p[2] = BYTE(i * 10); p[3] = 255;
            }
            auto frame = nv12(rgb, 320, 240);
            writeSample(video.writer.p, video.video, frame.data(), DWORD(frame.size()), i * 10000000LL / 12, 10000000 / 12);
            // A square wave makes a non-silent, deterministic audio fixture.
            for (UINT s = 0; s < 4000; ++s) { short value = (s / 24) % 2 ? 2000 : -2000; std::memcpy(pcm.data() + s * 2, &value, 2); }
            wav.append(pcm.data(), DWORD(pcm.size()));
            writeSample(video.writer.p, video.audio, pcm.data(), DWORD(pcm.size()), i * 10000000LL / 12, 10000000 / 12);
        }
        wav.finish(); check(video.writer->Finalize());
    }
    Ref<IMFSourceReader> reader; check(MFCreateSourceReaderFromURL(mp4.c_str(), nullptr, reader.put()));
    for (DWORD stream : { DWORD(MF_SOURCE_READER_FIRST_VIDEO_STREAM), DWORD(MF_SOURCE_READER_FIRST_AUDIO_STREAM) }) {
        Ref<IMFMediaType> type; check(MFCreateMediaType(type.put()));
        bool video = stream == DWORD(MF_SOURCE_READER_FIRST_VIDEO_STREAM);
        check(type->SetGUID(MF_MT_MAJOR_TYPE, video ? MFMediaType_Video : MFMediaType_Audio));
        check(type->SetGUID(MF_MT_SUBTYPE, video ? MFVideoFormat_NV12 : MFAudioFormat_PCM));
        check(reader->SetCurrentMediaType(stream, nullptr, type.p));
        bool found = false;
        for (int attempt = 0; attempt < 50; ++attempt) {
            Ref<IMFSample> sample; DWORD actual = 0, flags = 0; LONGLONG pts = 0;
            check(reader->ReadSample(stream, 0, &actual, &flags, &pts, sample.put()));
            if (flags & (MF_SOURCE_READERF_ERROR | MF_SOURCE_READERF_ENDOFSTREAM)) throw E_FAIL;
            if (sample.p) { DWORD size = 0; check(sample->GetTotalLength(&size)); if (!size) throw E_FAIL; found = true; break; }
        }
        if (!found) throw E_FAIL;
    }
    panoramaSelfTest(directory);
    std::printf("{\"self_test\":true,\"panorama_decode\":true,\"stereo_mix\":true}\n");
}
static void record(const wchar_t* mode, const wchar_t* path, int micIndex, int cameraIndex, int seconds) {
    bool audioOnly = std::wcscmp(mode, L"audio") == 0, desktop = std::wcscmp(mode, L"screen") == 0;
    if (!audioOnly && !desktop && std::wcscmp(mode, L"camera") != 0) throw E_INVALIDARG;
    if (audioOnly && micIndex == -2) throw E_INVALIDARG;
    if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) throw HRESULT_FROM_WIN32(ERROR_FILE_EXISTS);
    std::unique_ptr<MicrophoneCapture> mic; if (micIndex != -2) mic.reset(new MicrophoneCapture(micIndex));
    std::unique_ptr<Screen> screen; std::unique_ptr<Camera> camera; std::unique_ptr<Video> video; std::unique_ptr<Wav> wav;
    UINT w = 0, h = 0;
    if (audioOnly) wav.reset(new Wav(path));
    else {
        if (desktop) { screen.reset(new Screen()); w = screen->w; h = screen->h; }
        else {
            camera.reset(new Camera(UINT(cameraIndex))); w = camera->w; h = camera->h;
            ULONGLONG deadline = GetTickCount64() + 10000;
            while (camera->frame().empty()) { if (stopping || GetTickCount64() > deadline) throw HRESULT_FROM_WIN32(ERROR_TIMEOUT); Sleep(10); }
        }
        video.reset(new Video(path, w, h, mic != nullptr));
    }
    ULONGLONG start = GetTickCount64(), nextFrame = start, nextCheck = start; const char* reason = "duration";
    if (mic) mic->start();
    std::printf("{\"ready\":true,\"width\":%u,\"height\":%u}\n", w, h); std::fflush(stdout);
    auto audio = [&](const BYTE* data, DWORD n, UINT64 samples) {
        if (wav) wav->append(data, n);
        else writeSample(video->writer.p, video->audio, data, n, LONGLONG(samples * 10000000 / 48000), LONGLONG((n / 2) * 10000000LL / 48000));
    };
    while (!stopping && GetTickCount64() - start < ULONGLONG(seconds) * 1000) {
        ULONGLONG now = GetTickCount64();
        if (mic) mic->drain(audio, true);
        if (video && now >= nextFrame) {
            auto rgb = screen ? screen->frame() : camera->frame();
            if (!rgb.empty()) { auto data = nv12(rgb, w, h); writeSample(video->writer.p, video->video, data.data(), DWORD(data.size()), LONGLONG(now - start) * 10000, 10000000 / 12); }
            nextFrame = now + 83;
        }
        if (now >= nextCheck) {
            WIN32_FILE_ATTRIBUTE_DATA info = {};
            if (GetFileAttributesExW(path, GetFileExInfoStandard, &info) && (info.nFileSizeHigh || info.nFileSizeLow >= 1024u * 1024 * 1024)) { reason = "size_limit"; break; }
            std::wstring parent(path); auto slash = parent.find_last_of(L"\\/");
            ULARGE_INTEGER free = {}; if (slash != std::wstring::npos && GetDiskFreeSpaceExW(parent.substr(0, slash + 1).c_str(), &free, nullptr, nullptr) && free.QuadPart < 100 * 1024 * 1024) { reason = "disk_space"; break; }
            nextCheck = now + 1000;
        }
        Sleep(5);
    }
    if (stopping) reason = "stopped";
    if (mic) { waveInReset(mic->input); mic->drain(audio, false); }
    if (wav) wav->finish(); if (video) check(video->writer->Finalize());
    std::printf("{\"finished\":true,\"reason\":\"%s\"}\n", reason); std::fflush(stdout);
}
static void recordCombined(const wchar_t* mode, const wchar_t* path, int micIndex, int cameraIndex, int seconds,
                           bool systemAudio, int systemGain, int microphoneGain, int corner) {
    bool panorama = std::wcscmp(mode,L"panorama") == 0;
    bool desktop = panorama || std::wcscmp(mode,L"screen") == 0;
    if (!desktop && std::wcscmp(mode,L"camera") != 0) throw E_INVALIDARG;
    if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) throw HRESULT_FROM_WIN32(ERROR_FILE_EXISTS);
    recordingSource = "microphone";
    std::unique_ptr<MicrophoneCapture> mic; if (micIndex != -2) mic.reset(new MicrophoneCapture(micIndex));
    recordingSource = "system_audio";
    std::unique_ptr<Loopback> loopback; if (systemAudio) loopback.reset(new Loopback());
    recordingSource = "screen";
    std::unique_ptr<Screen> screen; if (desktop) screen.reset(new Screen());
    recordingSource = "camera";
    std::unique_ptr<Camera> camera;
    if (panorama || !desktop) {
        camera.reset(new Camera(UINT(cameraIndex)));
        ULONGLONG deadline = GetTickCount64() + 10000;
        while (camera->frame().empty()) { if (stopping || GetTickCount64() > deadline) throw HRESULT_FROM_WIN32(ERROR_TIMEOUT); Sleep(10); }
    }
    UINT w = desktop ? screen->w : camera->w, h = desktop ? screen->h : camera->h;
    bool hasAudio = mic != nullptr || systemAudio;
    recordingSource = "encoder";
    Video video(path,w,h,hasAudio,2);
    recording::Mixer mixer(systemGain,microphoneGain);
    auto picture = [&] {
        recordingSource = desktop ? "screen" : "camera";
        auto rgb = desktop ? screen->frame() : camera->frame();
        if (panorama) { recordingSource = "camera"; auto frame = camera->frame(); recording::overlay(rgb,w,h,frame,camera->w,camera->h,corner); }
        return nv12(rgb,w,h);
    };
    auto frame = picture();
    recordingSource = "encoder";
    writeSample(video.writer.p,video.video,frame.data(),DWORD(frame.size()),0,10000000/12);
    LONGLONG start = qpcTime(), lastVideo = 0, nextVideo = 10000000/12, nextCheck = 0;
    const char* reason = "duration"; HRESULT failure = S_OK; const char* failedSource = nullptr;
    auto writeAudio = [&](size_t frames) {
        int64_t first = mixer.cursor; auto pcm = mixer.render(frames);
        recordingSource = "encoder";
        writeSample(video.writer.p,video.audio,reinterpret_cast<const BYTE*>(pcm.data()),DWORD(pcm.size()*2),
                    first*10000000/48000,LONGLONG(frames)*10000000/48000);
    };
    auto drainMic = [&](bool recycle) {
        if (!mic) return;
        recordingSource = "microphone";
        mic->drain([&](const BYTE* data,DWORD n,UINT64 samples) {
            auto pcm = recording::mono16(data,n); mixer.microphone.put(int64_t(samples),pcm.data(),pcm.size()/2,mixer.cursor);
        },recycle);
    };
    try {
        recordingSource = "microphone"; if (mic) mic->start();
        recordingSource = "system_audio"; if (loopback) loopback->start();
        std::printf("{\"ready\":true,\"width\":%u,\"height\":%u,\"system_audio\":%s,\"channels\":%d}\n",w,h,systemAudio?"true":"false",hasAudio?2:0); std::fflush(stdout);
        while (!stopping && qpcTime()-start < LONGLONG(seconds)*10000000) {
            LONGLONG elapsed = qpcTime()-start;
            drainMic(true);
            recordingSource = "system_audio"; if (loopback) loopback->drain(mixer,start);
            // Let capture buffers arrive before committing silence for a missing
            // source; output remains on the same QPC timeline as the video.
            int64_t available = std::max<int64_t>(0,elapsed*48000/10000000-9600);
            while (hasAudio && mixer.cursor+960<=available) writeAudio(960);
            if (elapsed >= nextVideo) {
                auto rgb = picture(); recordingSource = "encoder";
                writeSample(video.writer.p,video.video,rgb.data(),DWORD(rgb.size()),elapsed,10000000/12);
                lastVideo=elapsed;nextVideo=elapsed+10000000/12;
            }
            if (elapsed >= nextCheck) {
                WIN32_FILE_ATTRIBUTE_DATA info = {};
                if (GetFileAttributesExW(path,GetFileExInfoStandard,&info) && (info.nFileSizeHigh || info.nFileSizeLow>=1024u*1024*1024)) { reason="size_limit";break; }
                std::wstring parent(path); auto slash=parent.find_last_of(L"\\/"); ULARGE_INTEGER free={};
                if (slash!=std::wstring::npos && GetDiskFreeSpaceExW(parent.substr(0,slash+1).c_str(),&free,nullptr,nullptr) && free.QuadPart<100*1024*1024) { reason="disk_space";break; }
                nextCheck=elapsed+10000000;
            }
            Sleep(5);
        }
        if (stopping) reason="stopped";
    } catch (HRESULT hr) { failure=hr; failedSource=recordingSource; reason="source_error"; }
      catch (...) { failure=E_FAIL;failedSource=recordingSource;reason="source_error"; }
    try {
        if (mic) { waveInReset(mic->input);drainMic(false); }
        recordingSource="system_audio"; if (loopback) { if (SUCCEEDED(failure))loopback->drain(mixer,start);loopback->cleanup(); }
        int64_t target=std::max<int64_t>(4000,std::min<int64_t>(LONGLONG(seconds)*48000,(lastVideo+10000000/12)*48000/10000000));
        while (hasAudio && mixer.cursor<target) writeAudio(size_t(std::min<int64_t>(960,target-mixer.cursor)));
        recordingSource="encoder"; check(video.writer->Finalize());
    } catch (HRESULT hr) { if (SUCCEEDED(failure)) {failure=hr;failedSource=recordingSource;} }
      catch (...) { if (SUCCEEDED(failure)) {failure=E_FAIL;failedSource=recordingSource;} }
    if (FAILED(failure)) { recordingSource=failedSource;throw failure; }
    std::printf("{\"finished\":true,\"reason\":\"%s\"}\n",reason);std::fflush(stdout);
}

int wmain(int argc, wchar_t** argv) {
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED); if (FAILED(hr)) return 1;
    bool media = false; int code = 0;
    try {
        check(MFStartup(MF_VERSION, MFSTARTUP_FULL)); media = true;
        if (argc == 2 && std::wcscmp(argv[1], L"devices") == 0) devices();
        else if (argc == 3 && std::wcscmp(argv[1], L"self-test") == 0) selfTest(argv[2]);
        else if (argc == 2 && std::wcscmp(argv[1],L"loopback-probe") == 0) {
            recordingSource="system_audio";Loopback endpoint;
            std::printf("{\"loopback_probe\":true,\"capture_started\":false,\"rate\":%u,\"channels\":%u}\n",unsigned(endpoint.format->nSamplesPerSec),unsigned(endpoint.format->nChannels));
        }
        else if ((argc == 8 || argc == 12) && std::wcscmp(argv[1], L"record") == 0) {
            int mic = number(argv[4], -2, 1023), camera = number(argv[5], 0, 31), seconds = number(argv[6], 1, 3600);
            // The final token is a protocol version, not a shell command.
            if ((argc==8 && std::wcscmp(argv[7],L"1")) || (argc==12 && std::wcscmp(argv[7],L"2"))) throw E_INVALIDARG;
            std::thread([] { HANDLE pipe = GetStdHandle(STD_INPUT_HANDLE); char c; DWORD n; while (ReadFile(pipe, &c, 1, &n, nullptr) && n) { if (c == '\n') break; } stopping = true; }).detach();
            if (argc==8)record(argv[2],argv[3],mic,camera,seconds);
            else recordCombined(argv[2],argv[3],mic,camera,seconds,number(argv[8],0,1)!=0,number(argv[9],0,200),number(argv[10],0,200),number(argv[11],0,3));
        } else throw E_INVALIDARG;
    } catch (HRESULT e) { std::printf("{\"source\":\"%s\",\"error\":\"Recording failed (0x%08lx). Check Windows privacy permissions, device availability and Media Feature Pack.\"}\n",recordingSource,(unsigned long)e); std::fflush(stdout); code = 1; }
      catch (...) { std::printf("{\"error\":\"Recording failed: insufficient resources.\"}\n"); std::fflush(stdout); code = 1; }
    if (media) MFShutdown(); CoUninitialize(); return code;
}

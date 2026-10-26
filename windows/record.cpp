// Native, explicitly started recordings. Windows privacy permissions and device
// indicators are left to Windows. stdin EOF finalizes a recording on agent exit.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <mmsystem.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
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
    std::printf("]}\n");
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
struct Microphone {
    HWAVEIN input = nullptr; WAVEFORMATEX format = {}; WAVEHDR headers[6] = {};
    std::vector<BYTE> buffers[6]; UINT64 samples = 0;
    explicit Microphone(int device) {
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
    ~Microphone() { cleanup(); }
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
    Video(const wchar_t* path, UINT w, UINT h, bool mic) {
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
            check(output->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC)); check(output->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, 1));
            check(output->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, 48000)); check(output->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16));
            check(output->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 12000)); check(output->SetUINT32(MF_MT_AAC_PAYLOAD_TYPE, 0));
            check(writer->AddStream(output.p, &audio));
            check(MFCreateMediaType(input.put())); check(input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio));
            check(input->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM)); check(input->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, 1));
            check(input->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, 48000)); check(input->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16));
            check(input->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, 2)); check(input->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 96000));
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
    std::printf("{\"self_test\":true}\n");
}
static void record(const wchar_t* mode, const wchar_t* path, int micIndex, int cameraIndex, int seconds) {
    bool audioOnly = std::wcscmp(mode, L"audio") == 0, desktop = std::wcscmp(mode, L"screen") == 0;
    if (!audioOnly && !desktop && std::wcscmp(mode, L"camera") != 0) throw E_INVALIDARG;
    if (audioOnly && micIndex == -2) throw E_INVALIDARG;
    if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) throw HRESULT_FROM_WIN32(ERROR_FILE_EXISTS);
    std::unique_ptr<Microphone> mic; if (micIndex != -2) mic.reset(new Microphone(micIndex));
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
int wmain(int argc, wchar_t** argv) {
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED); if (FAILED(hr)) return 1;
    bool media = false; int code = 0;
    try {
        check(MFStartup(MF_VERSION, MFSTARTUP_FULL)); media = true;
        if (argc == 2 && std::wcscmp(argv[1], L"devices") == 0) devices();
        else if (argc == 3 && std::wcscmp(argv[1], L"self-test") == 0) selfTest(argv[2]);
        else if (argc == 8 && std::wcscmp(argv[1], L"record") == 0) {
            int mic = number(argv[4], -2, 1023), camera = number(argv[5], 0, 31), seconds = number(argv[6], 1, 3600);
            // The final token is a protocol version, not a shell command.
            if (std::wcscmp(argv[7], L"1")) throw E_INVALIDARG;
            std::thread([] { HANDLE pipe = GetStdHandle(STD_INPUT_HANDLE); char c; DWORD n; while (ReadFile(pipe, &c, 1, &n, nullptr) && n) { if (c == '\n') break; } stopping = true; }).detach();
            record(argv[2], argv[3], mic, camera, seconds);
        } else throw E_INVALIDARG;
    } catch (HRESULT e) { std::printf("{\"error\":\"Recording failed (0x%08lx). Check Windows privacy permissions, device availability and Media Feature Pack.\"}\n", (unsigned long)e); std::fflush(stdout); code = 1; }
      catch (...) { std::printf("{\"error\":\"Recording failed: insufficient resources.\"}\n"); std::fflush(stdout); code = 1; }
    if (media) MFShutdown(); CoUninitialize(); return code;
}

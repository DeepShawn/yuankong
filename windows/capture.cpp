// One JPEG frame per invocation. Consent and the visible stop control live in the agent.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wincodec.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <vector>
#include <string>
#include <io.h>
#include <fcntl.h>

template<class T> class Com {
    T *p = nullptr;
public:
    Com() = default;
    Com(const Com&) = delete;
    Com& operator=(const Com&) = delete;
    ~Com() { if (p) p->Release(); }
    T *get() const { return p; }
    T *operator->() const { return p; }
    T **put() { if (p) p->Release(); p = nullptr; return &p; }
};
static void check(HRESULT hr) { if (FAILED(hr)) throw hr; }
struct Pixels { UINT w, h, stride; std::vector<BYTE> bytes; };

static Pixels resize_rgb32(const BYTE *top, LONG pitch, UINT w, UINT h, UINT limit) {
    if (!w || !h || w > 8192 || h > 8192 || std::abs(pitch) < LONG(w * 4)) throw E_INVALIDARG;
    double scale = std::min(1.0, std::min(double(limit) / w, 960.0 / h));
    Pixels out;
    out.w = std::max(1u, UINT(w * scale)); out.h = std::max(1u, UINT(h * scale));
    out.stride = (out.w * 3 + 3) & ~3u;
    out.bytes.resize(size_t(out.stride) * out.h);
    for (UINT y = 0; y < out.h; ++y) {
        const BYTE *row = top + ptrdiff_t(y * h / out.h) * pitch;
        BYTE *dst = out.bytes.data() + size_t(y) * out.stride;
        for (UINT x = 0; x < out.w; ++x) std::memcpy(dst + x * 3, row + (x * w / out.w) * 4, 3);
    }
    return out;
}

static void jpeg(const Pixels &pixels) {
    Com<IWICImagingFactory> factory;
    Com<IStream> stream;
    Com<IWICBitmapEncoder> encoder;
    Com<IWICBitmapFrameEncode> frame;
    Com<IPropertyBag2> options;
    check(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                           IID_IWICImagingFactory, reinterpret_cast<void **>(factory.put())));
    check(CreateStreamOnHGlobal(nullptr, TRUE, stream.put()));
    check(factory->CreateEncoder(GUID_ContainerFormatJpeg, nullptr, encoder.put()));
    check(encoder->Initialize(stream.get(), WICBitmapEncoderNoCache));
    check(encoder->CreateNewFrame(frame.put(), options.put()));
    PROPBAG2 prop = {}; prop.pstrName = const_cast<LPOLESTR>(L"ImageQuality");
    VARIANT quality = {}; quality.vt = VT_R4; quality.fltVal = 0.72f;
    check(options->Write(1, &prop, &quality));
    check(frame->Initialize(options.get()));
    check(frame->SetSize(pixels.w, pixels.h));
    WICPixelFormatGUID format = GUID_WICPixelFormat24bppBGR;
    check(frame->SetPixelFormat(&format));
    if (!IsEqualGUID(format, GUID_WICPixelFormat24bppBGR)) throw E_FAIL;
    check(frame->WritePixels(pixels.h, pixels.stride, UINT(pixels.bytes.size()),
                             const_cast<BYTE *>(pixels.bytes.data())));
    check(frame->Commit()); check(encoder->Commit());
    STATSTG info = {}; check(stream->Stat(&info, STATFLAG_NONAME));
    if (!info.cbSize.QuadPart || info.cbSize.QuadPart > 4 * 1024 * 1024) throw E_FAIL;
    HGLOBAL memory = nullptr; check(GetHGlobalFromStream(stream.get(), &memory));
    void *data = GlobalLock(memory);
    if (!data) throw E_OUTOFMEMORY;
    size_t written = std::fwrite(data, 1, size_t(info.cbSize.QuadPart), stdout);
    GlobalUnlock(memory);
    if (written != size_t(info.cbSize.QuadPart)) throw E_FAIL;
}

static Pixels screen(int sx=0,int sy=0,int width=0,int height=0) {
    SetProcessDPIAware();
    int x = GetSystemMetrics(SM_XVIRTUALSCREEN), y = GetSystemMetrics(SM_YVIRTUALSCREEN);
    int w = GetSystemMetrics(SM_CXVIRTUALSCREEN), h = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    if (w <= 0 || h <= 0 || w > 32768 || h > 32768) throw E_FAIL;
    if (width || height) {
        if (width<=0 || height<=0 || sx<x || sy<y || static_cast<long long>(sx)+width>x+w || static_cast<long long>(sy)+height>y+h) throw E_INVALIDARG;
        x=sx; y=sy; w=width; h=height;
    }
    double factor = std::min(1.0, std::min(1280.0 / w, 960.0 / h));
    int sw = std::max(1, int(w * factor)), sh = std::max(1, int(h * factor));
    HDC desktop = GetDC(nullptr), target = CreateCompatibleDC(desktop);
    HBITMAP bitmap = CreateCompatibleBitmap(desktop, sw, sh);
    if (!desktop || !target || !bitmap) {
        if (bitmap) DeleteObject(bitmap); if (target) DeleteDC(target);
        if (desktop) ReleaseDC(nullptr, desktop); throw E_FAIL;
    }
    HGDIOBJ previous = SelectObject(target, bitmap);
    SetStretchBltMode(target, HALFTONE);
    BOOL copied = StretchBlt(target, 0, 0, sw, sh, desktop, x, y, w, h, SRCCOPY | CAPTUREBLT);
    SelectObject(target, previous);
    BITMAPINFO info = {};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = sw; info.bmiHeader.biHeight = -sh;
    info.bmiHeader.biPlanes = 1; info.bmiHeader.biBitCount = 32;
    std::vector<BYTE> raw(size_t(sw) * sh * 4);
    int rows = copied ? GetDIBits(desktop, bitmap, 0, sh, raw.data(), &info, DIB_RGB_COLORS) : 0;
    DeleteObject(bitmap); DeleteDC(target); ReleaseDC(nullptr, desktop);
    if (rows != sh) throw E_FAIL;
    return resize_rgb32(raw.data(), sw * 4, sw, sh, 1280);
}

struct Cameras {
    IMFActivate **devices = nullptr; UINT32 count = 0;
    Cameras() {
        Com<IMFAttributes> attributes;
        check(MFCreateAttributes(attributes.put(), 1));
        check(attributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
                                  MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID));
        check(MFEnumDeviceSources(attributes.get(), &devices, &count));
    }
    ~Cameras() {
        for (UINT32 i = 0; i < count; ++i) devices[i]->Release();
        CoTaskMemFree(devices);
    }
};

static std::string quoted(const wchar_t *text) {
    int size = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
    std::vector<char> utf8(size);
    WideCharToMultiByte(CP_UTF8, 0, text, -1, utf8.data(), size, nullptr, nullptr);
    std::string result = "\"";
    for (int i = 0; i < size - 1; ++i) {
        unsigned char c = static_cast<unsigned char>(utf8[i]);
        if (c == '\\' || c == '"') result += '\\';
        if (c < 32) { char escaped[8]; std::snprintf(escaped, sizeof(escaped), "\\u%04x", c); result += escaped; }
        else result += char(c);
    }
    return result + "\"";
}

static Pixels camera(UINT index) {
    Cameras cameras;
    if (index >= cameras.count) throw HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
    Com<IMFMediaSource> source;
    check(cameras.devices[index]->ActivateObject(IID_IMFMediaSource, reinterpret_cast<void **>(source.put())));
    try {
        Com<IMFAttributes> attributes;
        Com<IMFSourceReader> reader;
        Com<IMFMediaType> type;
        check(MFCreateAttributes(attributes.put(), 1));
        check(attributes->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE));
        check(MFCreateSourceReaderFromMediaSource(source.get(), attributes.get(), reader.put()));
        check(reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE));
        check(reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE));
        check(MFCreateMediaType(type.put()));
        check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
        check(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32));
        check(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type.get()));
        const ULONGLONG started = GetTickCount64();
        for (int attempt = 0; attempt < 60; ++attempt) {
            Com<IMFSample> sample;
            DWORD stream = 0, flags = 0; LONGLONG timestamp = 0;
            check(reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, &stream, &flags, &timestamp, sample.put()));
            if (flags & (MF_SOURCE_READERF_ERROR | MF_SOURCE_READERF_ENDOFSTREAM)) throw E_FAIL;
            if (!sample.get()) continue;
            // Allow the device a short exposure warm-up instead of repeatedly returning its first black frame.
            if (attempt < 3 || GetTickCount64() - started < 400) continue;
            check(reader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, type.put()));
            UINT32 w = 0, h = 0;
            check(MFGetAttributeSize(type.get(), MF_MT_FRAME_SIZE, &w, &h));
            Com<IMFMediaBuffer> buffer;
            check(sample->ConvertToContiguousBuffer(buffer.put()));
            Com<IMF2DBuffer> two_d;
            BYTE *data = nullptr; LONG pitch = 0;
            Pixels result;
            if (SUCCEEDED(buffer->QueryInterface(IID_IMF2DBuffer, reinterpret_cast<void **>(two_d.put())))) {
                check(two_d->Lock2D(&data, &pitch));
                try { result = resize_rgb32(data, pitch, w, h, 960); }
                catch (...) { two_d->Unlock2D(); throw; }
                check(two_d->Unlock2D());
            } else {
                DWORD capacity = 0, length = 0;
                check(buffer->Lock(&data, &capacity, &length));
                try {
                    if (!w || !h || w > 8192 || h > 8192 || length < size_t(w) * h * 4) throw E_FAIL;
                    LONG stride = -LONG(w * 4);  // RGB DIB fallback is bottom up.
                    UINT32 encoded_stride = 0;
                    if (SUCCEEDED(type->GetUINT32(MF_MT_DEFAULT_STRIDE, &encoded_stride))) stride = LONG(encoded_stride);
                    if (std::abs(stride) < LONG(w * 4) || size_t(std::abs(stride)) * h > length) throw E_FAIL;
                    BYTE *top = stride < 0 ? data + size_t(h - 1) * std::abs(stride) : data;
                    result = resize_rgb32(top, stride, w, h, 960);
                } catch (...) { buffer->Unlock(); throw; }
                check(buffer->Unlock());
            }
            source->Shutdown();
            return result;
        }
        throw E_FAIL;
    } catch (...) { source->Shutdown(); throw; }
}

int main(int argc, char **argv) {
    _setmode(_fileno(stdout), _O_BINARY);
    HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(initialized)) return 1;
    bool media = false;
    int result = 0;
    try {
        if (argc == 2 && std::strcmp(argv[1], "screen") == 0) jpeg(screen());
        else if (argc == 6 && std::strcmp(argv[1], "screen") == 0) {
            int values[4];
            for (int i=0;i<4;++i) {
                char *end=nullptr;long number=std::strtol(argv[i+2],&end,10);
                if (!end || end==argv[i+2] || *end || number< -32768 || number>32768) throw E_INVALIDARG;
                values[i]=static_cast<int>(number);
            }
            if (values[2]<=0 || values[3]<=0) throw E_INVALIDARG;
            jpeg(screen(values[0],values[1],values[2],values[3]));
        }
        else {
            check(MFStartup(MF_VERSION, MFSTARTUP_FULL)); media = true;
            if (argc == 2 && std::strcmp(argv[1], "list") == 0) {
                Cameras cameras; std::printf("[");
                for (UINT32 i = 0; i < cameras.count; ++i) {
                    WCHAR *name = nullptr; UINT32 size = 0;
                    check(cameras.devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &size));
                    std::printf("%s{\"id\":%u,\"name\":%s}", i ? "," : "", i, quoted(name).c_str());
                    CoTaskMemFree(name);
                }
                std::printf("]\n");
            } else if (argc == 3 && std::strcmp(argv[1], "camera") == 0) {
                char *end = nullptr; unsigned long index = std::strtoul(argv[2], &end, 10);
                if (!end || *end || index > 31) throw E_INVALIDARG;
                jpeg(camera(UINT(index)));
            } else throw E_INVALIDARG;
        }
    } catch (HRESULT error) {
        std::fprintf(stderr, "Capture failed (0x%08lx). Check camera privacy permissions, device availability and Windows Media Feature Pack.\n", static_cast<unsigned long>(error));
        result = 1;
    } catch (...) { std::fprintf(stderr, "Capture failed: insufficient resources.\n"); result = 1; }
    if (media) MFShutdown();
    CoUninitialize();
    return result;
}

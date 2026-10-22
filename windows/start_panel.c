/*
 * Start.exe — LanCmd 启动面板
 *
 * 双击就有窗口，能看出到底跑没跑起来，一键启停。
 * 接收端本体（LanCmdAgent.exe）依然是无窗口的，这里只是给它套个可见入口。
 *
 * 编译：x86_64-w64-mingw32-gcc -O2 -s -municode -mwindows -o Start.exe start_panel.c res.o -ladvapi32 -lws2_32 -lshell32 -static
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdio.h>
#include <wchar.h>

#define ID_STATUS   2001
#define ID_IPINFO   2002
#define ID_START    2003
#define ID_STOP     2004
#define ID_AUTORUN  2005
#define ID_OPEN_DIR 2006
#define ID_QUIT     2007
#define TIMER_REFRESH 1

static HWND g_status = NULL;
static HWND g_ipinfo = NULL;
static HWND g_btn_start = NULL;
static HWND g_btn_stop = NULL;
static HWND g_btn_auto = NULL;
static HFONT g_font = NULL;
static HFONT g_font_bold = NULL;
static wchar_t g_dir[MAX_PATH];
static int g_lastRunning = -1;

static void app_dir(wchar_t *out, size_t cap)
{
    wchar_t self[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, self, MAX_PATH);
    if (n == 0) { out[0] = 0; return; }
    wcsncpy(out, self, cap - 1);
    out[cap - 1] = 0;
    wchar_t *slash = wcsrchr(out, L'\\');
    if (slash) *slash = 0;
}

static void join(wchar_t *out, size_t cap, const wchar_t *a, const wchar_t *b)
{
    size_t n = wcslen(a);
    int sep = (n > 0 && a[n-1] != L'\\');
    _snwprintf(out, cap - 1, L"%s%s%s", a, sep ? L"\\" : L"", b);
    out[cap - 1] = 0;
}

static int pid_path(wchar_t *out, size_t cap)
{
    wchar_t *appdata = _wgetenv(L"APPDATA");
    if (!appdata || !*appdata) return 0;
    _snwprintf(out, cap - 1, L"%s\\LanCmd\\agent.pid", appdata);
    out[cap - 1] = 0;
    return 1;
}

static int read_pid(void)
{
    wchar_t path[MAX_PATH];
    if (!pid_path(path, MAX_PATH)) return 0;
    FILE *f = _wfopen(path, L"r");
    if (!f) return 0;
    int pid = 0;
    if (fwscanf(f, L"%d", &pid) != 1) pid = 0;
    fclose(f);
    return pid;
}

static int alive(int pid)
{
    if (pid <= 0) return 0;
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, (DWORD)pid);
    if (!h) return 0;
    DWORD code = 0;
    int ok = GetExitCodeProcess(h, &code) && code == STILL_ACTIVE;
    CloseHandle(h);
    return ok;
}

static int running_pid(void)
{
    int pid = read_pid();
    if (pid > 0 && alive(pid)) return pid;
    return 0;
}

static void auto_start_enabled(wchar_t *cmd, size_t cap)
{
    cmd[0] = 0;
    HKEY key;
    if (RegOpenKeyExW(HKEY_CURRENT_USER,
            L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            0, KEY_QUERY_VALUE, &key) != ERROR_SUCCESS) return;
    DWORD type = 0, size = (DWORD)(cap * sizeof(wchar_t));
    RegQueryValueExW(key, L"LanCmdAgent", NULL, &type, (LPBYTE)cmd, &size);
    RegCloseKey(key);
    cmd[cap - 1] = 0;
}

static int set_autostart(int enable, wchar_t *detail, size_t cap)
{
    HKEY key;
    if (RegOpenKeyExW(HKEY_CURRENT_USER,
            L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            0, KEY_SET_VALUE, &key) != ERROR_SUCCESS) {
        _snwprintf(detail, cap - 1, L"无法打开注册表");
        detail[cap - 1] = 0;
        return 0;
    }
    LONG rc;
    if (enable) {
        wchar_t exe[MAX_PATH], value[MAX_PATH + 8];
        join(exe, MAX_PATH, g_dir, L"LanCmdAgent.exe");
        _snwprintf(value, MAX_PATH + 7, L"\"%s\"", exe);
        value[MAX_PATH + 7] = 0;
        rc = RegSetValueExW(key, L"LanCmdAgent", 0, REG_SZ,
                            (const BYTE *)value, (DWORD)((wcslen(value) + 1) * sizeof(wchar_t)));
        _snwprintf(detail, cap - 1, rc == ERROR_SUCCESS ? L"已加入开机自启" : L"写入注册表失败");
    } else {
        rc = RegDeleteValueW(key, L"LanCmdAgent");
        _snwprintf(detail, cap - 1,
                   (rc == ERROR_SUCCESS || rc == ERROR_FILE_NOT_FOUND)
                       ? L"已取消开机自启" : L"删除注册表项失败");
    }
    detail[cap - 1] = 0;
    RegCloseKey(key);
    return rc == ERROR_SUCCESS || rc == ERROR_FILE_NOT_FOUND;
}

static void local_ips(wchar_t *out, size_t cap)
{
    out[0] = 0;
    wchar_t host[256];
    if (gethostname((char *)host, sizeof(host) / sizeof(wchar_t)) != 0) return;
    struct addrinfo hints, *res = NULL;
    ZeroMemory(&hints, sizeof(hints));
    hints.ai_family = AF_INET;
    if (getaddrinfo((const char *)host, NULL, &hints, &res) != 0 || !res) return;
    for (struct addrinfo *p = res; p; p = p->ai_next) {
        struct sockaddr_in *sa = (struct sockaddr_in *)p->ai_addr;
        if (!sa) continue;
        unsigned char *b = (unsigned char *)&sa->sin_addr;
        if (b[0] == 127 || (b[0] == 169 && b[1] == 254)) continue;
        wchar_t item[32];
        _snwprintf(item, 31, L"%u.%u.%u.%u", b[0], b[1], b[2], b[3]);
        item[31] = 0;
        if (out[0]) wcsncat(out, L"  ", cap - wcslen(out) - 1);
        wcsncat(out, item, cap - wcslen(out) - 1);
    }
    freeaddrinfo(res);
}

static void refresh(void)
{
    int pid = running_pid();
    wchar_t text[512];
    if (pid > 0) {
        _snwprintf(text, 511, L"● 接收端运行中    PID %d", pid);
    } else {
        _snwprintf(text, 511, L"○ 接收端未运行");
    }
    text[511] = 0;
    SetWindowTextW(g_status, text);
    EnableWindow(g_btn_start, pid <= 0);
    EnableWindow(g_btn_stop, pid > 0);

    if (pid > 0 && g_lastRunning != 1) {
        wchar_t ips[512];
        local_ips(ips, 512);
        wchar_t line[640];
        _snwprintf(line, 639, L"本机地址  %s\n手机 App 会自动发现；找不到就手动填这个地址",
                   ips[0] ? ips : L"(获取失败)");
        line[639] = 0;
        SetWindowTextW(g_ipinfo, line);
    }
    if (pid <= 0) {
        SetWindowTextW(g_ipinfo, L"点下面「启动接收端」开始，之后手机打开 App 即可");
    }
    g_lastRunning = pid > 0 ? 1 : 0;
}

static int launch_agent(void)
{
    wchar_t exe[MAX_PATH], cmd[MAX_PATH + 4];
    join(exe, MAX_PATH, g_dir, L"LanCmdAgent.exe");
    if (GetFileAttributesW(exe) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(NULL, L"找不到 LanCmdAgent.exe。\n\n请保持整个文件夹完整，不要只拷单个 exe。",
                    L"LanCmd", MB_ICONERROR);
        return 0;
    }
    _snwprintf(cmd, MAX_PATH + 3, L"\"%s\"", exe);
    cmd[MAX_PATH + 3] = 0;
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessW(exe, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, g_dir, &si, &pi)) {
        wchar_t msg[256];
        _snwprintf(msg, 255, L"启动失败，错误码 %lu", GetLastError());
        MessageBoxW(NULL, msg, L"LanCmd", MB_ICONERROR);
        return 0;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 1;
}

static void do_stop(void)
{
    int pid = running_pid();
    if (pid <= 0) { refresh(); return; }
    if (MessageBoxW(NULL, L"确定停止接收端？\n停止后手机将无法连接。",
                    L"LanCmd", MB_YESNO | MB_ICONQUESTION) != IDYES) return;

    HANDLE h = OpenProcess(PROCESS_TERMINATE, FALSE, (DWORD)pid);
    if (h) {
        TerminateProcess(h, 0);
        CloseHandle(h);
    }
    // 不残留 pid 文件，下次启动才不会误判
    wchar_t path[MAX_PATH];
    if (pid_path(path, MAX_PATH)) DeleteFileW(path);
    Sleep(300);
    refresh();
}

static LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_CREATE: {
        g_font = CreateFontW(-14, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                             OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                             DEFAULT_PITCH, L"Microsoft YaHei UI");
        g_font_bold = CreateFontW(-19, 0, 0, 0, FW_SEMIBOLD, 0, 0, 0, DEFAULT_CHARSET,
                                  OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                                  DEFAULT_PITCH, L"Microsoft YaHei UI");

        HWND title = CreateWindowW(L"STATIC", L"LanCmd  局域网命令接收端",
                                   WS_CHILD | WS_VISIBLE, 18, 14, 330, 24,
                                   hwnd, NULL, NULL, NULL);
        SendMessageW(title, WM_SETFONT, (WPARAM)g_font_bold, TRUE);

        g_status = CreateWindowW(L"STATIC", L"正在检测…", WS_CHILD | WS_VISIBLE,
                                 18, 46, 330, 22, hwnd, (HMENU)ID_STATUS, NULL, NULL);
        SendMessageW(g_status, WM_SETFONT, (WPARAM)g_font_bold, TRUE);

        g_ipinfo = CreateWindowW(L"STATIC", L"", WS_CHILD | WS_VISIBLE,
                                 18, 72, 330, 40, hwnd, (HMENU)ID_IPINFO, NULL, NULL);
        SendMessageW(g_ipinfo, WM_SETFONT, (WPARAM)g_font, TRUE);

        g_btn_start = CreateWindowW(L"BUTTON", L"启动接收端",
                                    WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_DEFPUSHBUTTON,
                                    18, 124, 118, 36, hwnd, (HMENU)ID_START, NULL, NULL);
        g_btn_stop = CreateWindowW(L"BUTTON", L"停止",
                                   WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                   146, 124, 90, 36, hwnd, (HMENU)ID_STOP, NULL, NULL);
        g_btn_auto = CreateWindowW(L"BUTTON", L"开机自启",
                                   WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                   246, 124, 102, 36, hwnd, (HMENU)ID_AUTORUN, NULL, NULL);
        HWND btn_open = CreateWindowW(L"BUTTON", L"打开配置目录",
                                      WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                      18, 172, 130, 32, hwnd, (HMENU)ID_OPEN_DIR, NULL, NULL);
        HWND btn_quit = CreateWindowW(L"BUTTON", L"关闭",
                                      WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                      262, 172, 86, 32, hwnd, (HMENU)ID_QUIT, NULL, NULL);

        HWND all[] = {g_btn_start, g_btn_stop, g_btn_auto, btn_open, btn_quit};
        for (int i = 0; i < 5; ++i)
            SendMessageW(all[i], WM_SETFONT, (WPARAM)g_font, TRUE);

        SetTimer(hwnd, TIMER_REFRESH, 1000, NULL);
        refresh();
        return 0;
    }
    case WM_TIMER:
        if (wp == TIMER_REFRESH) {
            int pid = running_pid();
            int now = pid > 0 ? 1 : 0;
            if (now != g_lastRunning) refresh();
        }
        return 0;
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case ID_START:
            launch_agent();
            Sleep(400);
            refresh();
            return 0;
        case ID_STOP:
            do_stop();
            return 0;
        case ID_AUTORUN: {
            wchar_t cur[MAX_PATH], detail[256];
            auto_start_enabled(cur, MAX_PATH);
            set_autostart(cur[0] ? 0 : 1, detail, 256);
            MessageBoxW(hwnd, detail, L"LanCmd", MB_ICONINFORMATION);
            return 0;
        }
        case ID_OPEN_DIR: {
            wchar_t *appdata = _wgetenv(L"APPDATA");
            if (appdata && *appdata) {
                wchar_t path[MAX_PATH];
                _snwprintf(path, MAX_PATH - 1, L"%s\\LanCmd", appdata);
                path[MAX_PATH - 1] = 0;
                CreateDirectoryW(path, NULL);
                ShellExecuteW(NULL, L"open", path, NULL, NULL, SW_SHOWNORMAL);
            }
            return 0;
        }
        case ID_QUIT:
            DestroyWindow(hwnd);
            return 0;
        }
        return 0;
    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        KillTimer(hwnd, TIMER_REFRESH);
        if (g_font) DeleteObject(g_font);
        if (g_font_bold) DeleteObject(g_font_bold);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmdline, int show)
{
    (void)hPrev; (void)cmdline;
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    app_dir(g_dir, MAX_PATH);
    InitCommonControls();

    WNDCLASSEXW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = wnd_proc;
    wc.hInstance = hInst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.lpszClassName = L"LanCmdPanel";
    wc.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(1));
    wc.hIconSm = wc.hIcon;
    if (!RegisterClassExW(&wc)) return 1;

    int w = 386, h = 258;
    int x = (GetSystemMetrics(SM_CXSCREEN) - w) / 2;
    int y = (GetSystemMetrics(SM_CYSCREEN) - h) / 2;

    HWND hwnd = CreateWindowExW(0, L"LanCmdPanel", L"LanCmd",
                                WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                                x, y, w, h, NULL, NULL, hInst, NULL);
    if (!hwnd) return 1;

    ShowWindow(hwnd, show);
    UpdateWindow(hwnd);

    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
        if (!IsDialogMessageW(hwnd, &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    WSACleanup();
    return 0;
}

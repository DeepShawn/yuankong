/*
 * Start.exe — LanCmd 启动面板
 *
 * 双击就有窗口，能看出到底跑没跑起来，一键启停。
 * 接收端本体（LanCmdAgent.exe）依然是无窗口的，这里只是给它套个可见入口。
 *
 * 编译：x86_64-w64-mingw32-gcc -O2 -s -municode -mwindows -o Start.exe start_panel.c res.o -ladvapi32 -lws2_32 -lshell32 -static
 */

#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define ID_STATUS   2001
#define ID_IPINFO   2002
#define ID_START    2003
#define ID_STOP     2004
#define ID_AUTORUN  2005
#define ID_OPEN_DIR 2006
#define ID_QUIT     2007
#define ID_FIREWALL 2008
#define ID_ADMIN    2009
#define TIMER_REFRESH 1
#define WM_APP_FWCHECK  (WM_APP + 1)
#define WM_APP_ADMINSTART (WM_APP + 2)

static HWND g_status = NULL;
static HWND g_ipinfo = NULL;
static HWND g_btn_start = NULL;
static HWND g_btn_stop = NULL;
static HWND g_btn_auto = NULL;
static HWND g_btn_admin = NULL;
static HFONT g_font = NULL;
static HFONT g_font_bold = NULL;
static wchar_t g_dir[MAX_PATH];
static HWND g_fw = NULL;
static int g_fw_prompted = 0;
static int g_lastRunning = -1;
static int g_lastPid = 0;

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

/* Query the receiver's token, not the panel's token. -1 means unknown. */
static int process_elevated(int pid)
{
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, (DWORD)pid);
    if (!process) return -1;
    HANDLE token = NULL;
    if (!OpenProcessToken(process, TOKEN_QUERY, &token)) { CloseHandle(process); return -1; }
    TOKEN_ELEVATION elevation;
    DWORD size = 0;
    int result = GetTokenInformation(token, TokenElevation, &elevation, sizeof(elevation), &size)
        ? (elevation.TokenIsElevated ? 1 : 0) : -1;
    CloseHandle(token);
    CloseHandle(process);
    return result;
}

/* Never remove the PID file after a failed stop, or remove a new instance's file. */
static int stop_receiver_process(int pid)
{
    if (pid <= 4 || (DWORD)pid == GetCurrentProcessId()) {
        SetLastError(ERROR_INVALID_PARAMETER);
        return 0;
    }
    HANDLE process = OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, (DWORD)pid);
    if (!process) return 0;
    if (!TerminateProcess(process, 0)) {
        DWORD error = GetLastError(); CloseHandle(process); SetLastError(error); return 0;
    }
    DWORD wait = WaitForSingleObject(process, 5000);
    if (wait != WAIT_OBJECT_0) {
        DWORD error = wait == WAIT_TIMEOUT ? ERROR_TIMEOUT : GetLastError();
        CloseHandle(process); SetLastError(error); return 0;
    }
    if (read_pid() == pid) {
        wchar_t path[MAX_PATH];
        if (pid_path(path, MAX_PATH)) DeleteFileW(path);
    }
    CloseHandle(process);
    return 1;
}

/* The old receiver is left untouched until Windows has approved runas and the
 * elevated panel has started. Cancelling UAC therefore preserves the connection. */
static int open_admin_panel(HWND hwnd)
{
    wchar_t self[MAX_PATH];
    DWORD length = GetModuleFileNameW(NULL, self, MAX_PATH);
    if (!length || length >= MAX_PATH) return 0;
    SHELLEXECUTEINFOW request;
    ZeroMemory(&request, sizeof(request));
    request.cbSize = sizeof(request);
    request.fMask = SEE_MASK_NOCLOSEPROCESS;
    request.hwnd = hwnd;
    request.lpVerb = L"runas";
    request.lpFile = self;
    request.lpParameters = L"--restart-admin";
    request.lpDirectory = g_dir;
    request.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&request)) {
        DWORD error = GetLastError();
        MessageBoxW(hwnd, error == ERROR_CANCELLED
            ? L"已取消管理员授权，原接收端继续运行，权限没有改变。"
            : L"无法打开管理员面板。原接收端没有被停止，请检查 Windows 权限设置。",
            L"LanCmd", MB_ICONWARNING);
        return 0;
    }
    if (request.hProcess) CloseHandle(request.hProcess);
    DestroyWindow(hwnd);
    return 1;
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

/* 用 netsh 查规则是否存在。只读，不需要管理员。 */
static int firewall_ok(void)
{
    static const wchar_t *names[] = {L"LanCmd-TCP", L"LanCmd-UDP"};
    int ok = 1;
    for (int i = 0; i < 2; ++i) {
        wchar_t cmd[256];
        _snwprintf(cmd, 255, L"netsh advfirewall firewall show rule name=%s", names[i]);
        cmd[255] = 0;

        SECURITY_ATTRIBUTES sa;
        ZeroMemory(&sa, sizeof(sa));
        sa.nLength = sizeof(sa);
        sa.bInheritHandle = TRUE;

        HANDLE rd = NULL, wr = NULL;
        if (!CreatePipe(&rd, &wr, &sa, 0)) { ok = 0; continue; }
        SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);

        STARTUPINFOW si;
        PROCESS_INFORMATION pi;
        ZeroMemory(&si, sizeof(si));
        si.cb = sizeof(si);
        si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
        si.hStdOutput = wr;
        si.hStdError = wr;
        si.hStdInput = NULL;
        si.wShowWindow = SW_HIDE;
        ZeroMemory(&pi, sizeof(pi));

        wchar_t full[512];
        _snwprintf(full, 511, L"cmd.exe /c %s", cmd);
        full[511] = 0;

        if (!CreateProcessW(NULL, full, NULL, NULL, TRUE, CREATE_NO_WINDOW,
                            NULL, NULL, &si, &pi)) {
            CloseHandle(rd);
            CloseHandle(wr);
            ok = 0;
            continue;
        }
        CloseHandle(wr);

        char buf[4096];
        DWORD got = 0;
        if (ReadFile(rd, buf, sizeof(buf) - 1, &got, NULL) && got > 0) {
            buf[got] = 0;
            if (!strstr(buf, "4782")) ok = 0;
        } else {
            ok = 0;
        }
        CloseHandle(rd);
        WaitForSingleObject(pi.hProcess, 8000);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    }
    return ok;
}

/*
 * 防火墙检测要起 cmd.exe + netsh，单次可能几百毫秒。
 * 绝不能放在 WM_CREATE 里同步跑，否则窗口要等检测完才显示出来，
 * 双击后像“没反应”。这里丢到后台线程，查完再 PostMessage 回来。
 */
static DWORD WINAPI fw_thread(LPVOID param)
{
    HWND hwnd = (HWND)param;
    int ok = firewall_ok();
    PostMessageW(hwnd, WM_APP_FWCHECK, (WPARAM)ok, 0);
    return 0;
}

static void refresh(void)
{
    int pid = running_pid();
    wchar_t text[512];
    if (pid > 0) {
        int admin = process_elevated(pid);
        _snwprintf(text, 511, L"● 运行中 PID %d · %s", pid,
                   admin == 1 ? L"管理员" : admin == 0 ? L"标准用户" : L"权限未知");
    } else {
        _snwprintf(text, 511, L"○ 接收端未运行");
    }
    text[511] = 0;
    SetWindowTextW(g_status, text);
    EnableWindow(g_btn_start, pid <= 0);
    EnableWindow(g_btn_stop, pid > 0);
    int receiver_admin = pid > 0 ? process_elevated(pid) : -1;
    int panel_admin = process_elevated((int)GetCurrentProcessId());
    SetWindowTextW(g_btn_admin, receiver_admin == 1
        ? (panel_admin == 1 ? L"接收端已使用管理员权限" : L"打开管理员面板")
        : L"管理员启动 / 重启");
    EnableWindow(g_btn_admin, receiver_admin != 1 || panel_admin != 1);

    if (pid > 0 && (g_lastRunning != 1 || pid != g_lastPid)) {
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
    g_lastPid = pid;
}

/* 读 launcher.log 尾部，启动失败时给用户看 */
static void panel_log_tail(wchar_t *out, size_t cap)
{
    out[0] = 0;
    wchar_t *appdata = _wgetenv(L"APPDATA");
    if (!appdata || !*appdata) return;
    wchar_t path[MAX_PATH];
    _snwprintf(path, MAX_PATH - 1, L"%s\\LanCmd\\launcher.log", appdata);
    path[MAX_PATH - 1] = 0;

    HANDLE fh = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (fh == INVALID_HANDLE_VALUE) return;
    char buf[1600];
    DWORD got = 0;
    ReadFile(fh, buf, sizeof(buf) - 1, &got, NULL);
    buf[got] = 0;
    CloseHandle(fh);
    MultiByteToWideChar(CP_UTF8, 0, buf, -1, out, (int)cap - 1);
    out[cap - 1] = 0;
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

/* 兜底启动：直接用自带的 pythonw 跑脚本，不经过 LanCmdAgent.exe。
   万一启动器的参数组装在某个环境上有问题，这条路仍然能起来。 */
static int launch_via_pythonw(void)
{
    wchar_t pyw[MAX_PATH], script[MAX_PATH], cmd[MAX_PATH * 2 + 16];
    join(pyw, MAX_PATH, g_dir, L"python\\pythonw.exe");
    join(script, MAX_PATH, g_dir, L"lan_command_agent.py");
    if (GetFileAttributesW(pyw) == INVALID_FILE_ATTRIBUTES) return 0;
    if (GetFileAttributesW(script) == INVALID_FILE_ATTRIBUTES) return 0;

    _snwprintf(cmd, MAX_PATH * 2 + 15, L"\"%s\" \"%s\"", pyw, script);
    cmd[MAX_PATH * 2 + 15] = 0;

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessW(pyw, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW,
                        NULL, g_dir, &si, &pi)) {
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

    if (!stop_receiver_process(pid)) {
        MessageBoxW(NULL, L"接收端未能停止，运行状态和 PID 文件已保留。\n请以管理员身份打开 Start.exe 后重试。",
                    L"LanCmd", MB_ICONWARNING);
    }
    refresh();
}

static void start_receiver(HWND hwnd)
{
    launch_agent();
    int waited = 0;
    while (waited < 10000 && running_pid() <= 0) { Sleep(300); waited += 300; }
    if (running_pid() <= 0 && launch_via_pythonw()) {
        waited = 0;
        while (waited < 10000 && running_pid() <= 0) { Sleep(300); waited += 300; }
    }
    refresh();
    if (running_pid() <= 0) {
        wchar_t tail[1600], message[2200];
        panel_log_tail(tail, 1600);
        _snwprintf(message, 2199,
            L"接收端没能启动。\n\n--- 启动日志 ---\n%s\n\n请运行文件夹中的「诊断.bat」检查。",
            tail[0] ? tail : L"(没有日志)");
        message[2199] = 0;
        MessageBoxW(hwnd, message, L"LanCmd", MB_ICONERROR | MB_OK);
    }
}

static void start_as_admin(HWND hwnd)
{
    if (process_elevated((int)GetCurrentProcessId()) != 1) {
        MessageBoxW(hwnd, L"尚未获得 Windows 管理员授权。请点击「管理员启动 / 重启」并允许 UAC。",
                    L"LanCmd", MB_ICONWARNING);
        return;
    }
    int pid = running_pid();
    if (pid > 0) {
        int admin = process_elevated(pid);
        if (admin == 1) { refresh(); return; }
        if (admin < 0 || !stop_receiver_process(pid)) {
            MessageBoxW(hwnd, L"无法安全停止现有接收端，未执行重启。请检查权限和 PID，原 PID 文件已保留。",
                        L"LanCmd", MB_ICONERROR);
            return;
        }
    }
    /* CreateProcess inherits the UAC-approved token of this visible panel. */
    start_receiver(hwnd);
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
        g_btn_admin = CreateWindowW(L"BUTTON", L"管理员启动 / 重启",
                                    WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                    18, 170, 350, 36, hwnd, (HMENU)ID_ADMIN, NULL, NULL);
        SendMessageW(g_btn_admin, BCM_SETSHIELD, 0, TRUE);
        HWND btn_fw = CreateWindowW(L"BUTTON", L"修复防火墙",
                                    WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                    18, 218, 130, 32, hwnd, (HMENU)ID_FIREWALL, NULL, NULL);
        HWND btn_open = CreateWindowW(L"BUTTON", L"打开配置目录",
                                      WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                      152, 218, 130, 32, hwnd, (HMENU)ID_OPEN_DIR, NULL, NULL);
        HWND btn_quit = CreateWindowW(L"BUTTON", L"关闭",
                                      WS_CHILD | WS_VISIBLE | WS_TABSTOP,
                                      286, 218, 82, 32, hwnd, (HMENU)ID_QUIT, NULL, NULL);
        g_fw = btn_fw;

        HWND all[] = {g_btn_start, g_btn_stop, g_btn_auto, btn_open, btn_quit, btn_fw, g_btn_admin};
        for (int i = 0; i < 7; ++i)
            SendMessageW(all[i], WM_SETFONT, (WPARAM)g_font, TRUE);

        // 防火墙状态异步查，不阻塞窗口显示
        SetWindowTextW(g_fw, L"检查防火墙…");
        HANDLE t = CreateThread(NULL, 0, fw_thread, (LPVOID)hwnd, 0, NULL);
        if (t) CloseHandle(t);

        SetTimer(hwnd, TIMER_REFRESH, 1000, NULL);
        refresh();
        return 0;
    }
    case WM_APP_FWCHECK:
        if (g_fw) {
            SetWindowTextW(g_fw, wp ? L"防火墙已放行" : L"修复防火墙 !");
        }
        /* 没放行就主动问一次：开热点给手机连时，这是连不上的头号原因，
           用户往往不知道要点这个按钮。 */
        if (!wp && !g_fw_prompted) {
            g_fw_prompted = 1;
            if (MessageBoxW(hwnd,
                    L"检测到防火墙还没有放行 47823 / 47824 端口。\n\n"
                    L"手机连不上电脑，最常见的原因就是这个——\n"
                    L"尤其是「电脑开热点、手机连热点」的时候，\n"
                    L"这个网络会被 Windows 当成「公用网络」，入站连接默认被拦。\n\n"
                    L"现在就去放行吗？（会请求管理员权限）",
                    L"LanCmd 提示", MB_YESNO | MB_ICONWARNING) == IDYES) {
                SendMessageW(hwnd, WM_COMMAND, MAKEWPARAM(ID_FIREWALL, BN_CLICKED),
                             (LPARAM)g_fw);
            }
        }
        return 0;
    case WM_TIMER:
        if (wp == TIMER_REFRESH) {
            refresh();
        }
        return 0;
    case WM_APP_ADMINSTART:
        start_as_admin(hwnd);
        return 0;
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case ID_START:
            start_receiver(hwnd);
            return 0;
        case ID_ADMIN:
            if (process_elevated((int)GetCurrentProcessId()) == 1)
                PostMessageW(hwnd, WM_APP_ADMINSTART, 0, 0);
            else
                open_admin_panel(hwnd);
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
        case ID_FIREWALL: {
            wchar_t ctl[MAX_PATH];
            join(ctl, MAX_PATH, g_dir, L"LanCmdCtl.exe");
            if (GetFileAttributesW(ctl) == INVALID_FILE_ATTRIBUTES) {
                MessageBoxW(hwnd, L"找不到 LanCmdCtl.exe，无法自动修复。\n"
                                 L"请以管理员身份手动执行：\n"
                                 L"netsh advfirewall firewall add rule name=LanCmd-TCP "
                                 L"dir=in action=allow protocol=TCP localport=47823",
                            L"LanCmd", MB_ICONWARNING);
                return 0;
            }
            if (MessageBoxW(hwnd,
                    L"将请求管理员权限，放行 TCP 47823 和 UDP 47824。\n\n"
                    L"开热点给手机连时必须放行，否则手机连不上。\n继续？",
                    L"修复防火墙", MB_YESNO | MB_ICONQUESTION) != IDYES) {
                return 0;
            }
            HINSTANCE r = ShellExecuteW(hwnd, L"runas", ctl, L"--fix-firewall",
                                        g_dir, SW_SHOWNORMAL);
            if ((INT_PTR)r <= 32) {
                MessageBoxW(hwnd, L"提权被取消或失败，未能修改防火墙。",
                            L"LanCmd", MB_ICONWARNING);
            } else {
                Sleep(2500);
                if (firewall_ok()) {
                    SetWindowTextW(g_fw, L"防火墙已放行");
                    MessageBoxW(hwnd, L"防火墙已放行 47823/47824，手机现在可以连接了。",
                                L"LanCmd", MB_ICONINFORMATION);
                }
            }
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
    int restart_admin = 0, argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv) {
        for (int i = 1; i < argc; ++i)
            if (wcscmp(argv[i], L"--restart-admin") == 0) restart_admin = 1;
        LocalFree(argv);
    }
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

    int w = 386, h = 306;
    int x = (GetSystemMetrics(SM_CXSCREEN) - w) / 2;
    int y = (GetSystemMetrics(SM_CYSCREEN) - h) / 2;

    HWND hwnd = CreateWindowExW(0, L"LanCmdPanel", L"LanCmd",
                                WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                                x, y, w, h, NULL, NULL, hInst, NULL);
    if (!hwnd) return 1;

    ShowWindow(hwnd, show);
    UpdateWindow(hwnd);
    if (restart_admin) PostMessageW(hwnd, WM_APP_ADMINSTART, 0, 0);

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

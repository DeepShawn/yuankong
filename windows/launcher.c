/*
 * LanCmd Windows 启动器
 *
 * 由 mingw-w64 交叉编译，无需目标机安装 Python：
 *   LanCmdAgent.exe  GUI 子系统，无控制台窗口，启动后立即返回
 *   LanCmdCtl.exe    控制台子系统，等待脚本结束并返回其退出码
 *
 * 它只做四件事：定位同目录的 python 运行时与脚本、设置 LANCMD_EXE、
 * 以不弹窗的方式启动、把命令行参数原样透传。
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

#ifdef LANCMD_CONSOLE
#define ENTRY_NAME L"LanCmdCtl"
#else
#define ENTRY_NAME L"LanCmdAgent"
#endif

static const wchar_t *SCRIPT_NAME = L"lan_command_agent.py";
static const wchar_t *PY_DIR = L"python";

static void join_path(wchar_t *out, size_t cap, const wchar_t *a, const wchar_t *b)
{
    size_t n = wcslen(a);
    int need_sep = (n > 0 && a[n - 1] != L'\\' && a[n - 1] != L'/');
    _snwprintf(out, cap - 1, L"%s%s%s", a, need_sep ? L"\\" : L"", b);
    out[cap - 1] = L'\0';
}

static int file_exists(const wchar_t *path)
{
    DWORD attr = GetFileAttributesW(path);
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

static void dir_of(const wchar_t *path, wchar_t *out, size_t cap)
{
    wcsncpy(out, path, cap - 1);
    out[cap - 1] = L'\0';
    wchar_t *slash = wcsrchr(out, L'\\');
    wchar_t *fwd = wcsrchr(out, L'/');
    if (fwd > slash) slash = fwd;
    if (slash) *slash = L'\0';
}

/* 命令行拼接：含空格或引号的参数用双引号包起来并把内部引号翻倍 */
static void append_arg(wchar_t *out, size_t cap, const wchar_t *arg)
{
    size_t used = wcslen(out);
    if (used + 4 >= cap) return;
    int need_quote = (wcschr(arg, L' ') || wcschr(arg, L'\t') || !*arg);

    if (used + 1 < cap) { out[used++] = L' '; out[used] = L'\0'; }
    if (need_quote && used + 1 < cap) { out[used++] = L'"'; out[used] = L'\0'; }

    for (const wchar_t *p = arg; *p && used + 2 < cap; ++p) {
        if (*p == L'"') {
            if (used + 3 < cap) { out[used++] = L'\\'; }
        }
        out[used++] = *p;
        out[used] = L'\0';
    }
    if (need_quote && used + 1 < cap) { out[used++] = L'"'; out[used] = L'\0'; }
}

/* ---------------- 启动日志 ----------------
 * 接收端是 GUI 子系统、无控制台，一旦 Python 起不来，
 * 用户什么提示都看不到。这里把子进程的输出重定向进日志，
 * 并在启动失败时把日志尾部落到弹窗里，避免"双击没反应"。
 */
static wchar_t g_log_path[MAX_PATH];

static void log_path_init(void)
{
    wchar_t *appdata = _wgetenv(L"APPDATA");
    if (!appdata || !*appdata) { g_log_path[0] = 0; return; }
    wchar_t dir[MAX_PATH];
    _snwprintf(dir, MAX_PATH - 1, L"%s\\LanCmd", appdata);
    dir[MAX_PATH - 1] = 0;
    CreateDirectoryW(dir, NULL);
    _snwprintf(g_log_path, MAX_PATH - 1, L"%s\\launcher.log", dir);
    g_log_path[MAX_PATH - 1] = 0;
}

typedef struct { HANDLE rd; } PUMP;

static DWORD WINAPI pump_proc(LPVOID param)
{
    HANDLE rd = ((PUMP *)param)->rd;
    HANDLE fh = INVALID_HANDLE_VALUE;
    if (g_log_path[0]) {
        fh = CreateFileW(g_log_path, FILE_APPEND_DATA, FILE_SHARE_READ, NULL,
                         OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (fh != INVALID_HANDLE_VALUE) {
            SYSTEMTIME st;
            GetLocalTime(&st);
            char head[128];
            int n = _snprintf(head, sizeof(head) - 1,
                              "\r\n===== %04d-%02d-%02d %02d:%02d:%02d =====\r\n",
                              st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
            if (n > 0) { DWORD w; WriteFile(fh, head, (DWORD)n, &w, NULL); }
        }
    }
    char buf[4096];
    DWORD got = 0;
    while (ReadFile(rd, buf, sizeof(buf), &got, NULL) && got > 0) {
        if (fh != INVALID_HANDLE_VALUE) { DWORD w; WriteFile(fh, buf, got, &w, NULL); }
    }
    if (fh != INVALID_HANDLE_VALUE) CloseHandle(fh);
    CloseHandle(rd);
    return 0;
}

/* 把一行文本追加进 launcher.log，用于记录实际执行的命令行 */
static void log_line(const wchar_t *text)
{
    if (!g_log_path[0]) return;
    HANDLE fh = CreateFileW(g_log_path, FILE_APPEND_DATA, FILE_SHARE_READ, NULL,
                            OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (fh == INVALID_HANDLE_VALUE) return;
    char utf8[4096];
    int n = WideCharToMultiByte(CP_UTF8, 0, text, -1, utf8, sizeof(utf8) - 1, NULL, NULL);
    if (n > 1) {
        DWORD w;
        WriteFile(fh, utf8, (DWORD)(n - 1), &w, NULL);
        WriteFile(fh, "\r\n", 2, &w, NULL);
    }
    CloseHandle(fh);
}

/* 读日志尾部，给弹窗用 */
static void log_tail(wchar_t *out, size_t cap)
{
    out[0] = 0;
    if (!g_log_path[0]) return;
    HANDLE fh = CreateFileW(g_log_path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (fh == INVALID_HANDLE_VALUE) return;
    LARGE_INTEGER size;
    if (!GetFileSizeEx(fh, &size) || size.QuadPart == 0) { CloseHandle(fh); return; }
    long long want = 1800;
    long long start = (size.QuadPart > want) ? size.QuadPart - want : 0;
    LARGE_INTEGER pos;
    pos.QuadPart = start;
    SetFilePointerEx(fh, pos, NULL, FILE_BEGIN);

    char tmp[2048];
    DWORD got = 0;
    ReadFile(fh, tmp, sizeof(tmp) - 1, &got, NULL);
    tmp[got] = 0;
    CloseHandle(fh);

    /* UTF-8 -> 宽字符 */
    int wn = MultiByteToWideChar(CP_UTF8, 0, tmp, -1, out, (int)cap - 1);
    if (wn <= 0) {
        MultiByteToWideChar(CP_ACP, 0, tmp, -1, out, (int)cap - 1);
    }
    out[cap - 1] = 0;
}

static void report(const wchar_t *detail)
{
    wchar_t msg[2048];
    _snwprintf(msg, 2047,
        L"LanCmd 启动失败。\n\n%s\n\n"
        L"请确认以下文件与 LanCmdAgent.exe 在同一目录：\n"
        L"  lan_command_agent.py\n"
        L"  python\\pythonw.exe\n\n"
        L"如果解压时用的是“仅解压部分文件”，请整个文件夹完整解压。",
        detail);
    msg[2047] = L'\0';
#ifdef LANCMD_CONSOLE
    fwprintf(stderr, L"%s\n", msg);
#else
    MessageBoxW(NULL, msg, L"LanCmd", MB_ICONERROR | MB_OK | MB_TOPMOST);
#endif
}

static int run(void)
{
    wchar_t self[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, self, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) {
        report(L"无法获取自身路径（路径过长）。");
        return 2;
    }

    wchar_t dir[MAX_PATH];
    dir_of(self, dir, MAX_PATH);
    log_path_init();

    /* 脚本：优先同目录 */
    wchar_t script[MAX_PATH];
    join_path(script, MAX_PATH, dir, SCRIPT_NAME);
    if (!file_exists(script)) {
        wchar_t detail[MAX_PATH + 64];
        _snwprintf(detail, MAX_PATH + 63, L"找不到脚本：%s", script);
        report(detail);
        return 2;
    }

    /* 解释器：GUI 版用 pythonw（无窗口），控制台版用 python */
    wchar_t py_home[MAX_PATH], exe[MAX_PATH];
    join_path(py_home, MAX_PATH, dir, PY_DIR);

#ifdef LANCMD_CONSOLE
    join_path(exe, MAX_PATH, py_home, L"python.exe");
    if (!file_exists(exe)) join_path(exe, MAX_PATH, dir, L"python.exe");
#else
    join_path(exe, MAX_PATH, py_home, L"pythonw.exe");
    if (!file_exists(exe)) join_path(exe, MAX_PATH, dir, L"pythonw.exe");
#endif

    if (!file_exists(exe)) {
        wchar_t detail[MAX_PATH + 64];
        _snwprintf(detail, MAX_PATH + 63, L"找不到 Python 运行时：%s", exe);
        report(detail);
        return 2;
    }

    /* 命令行 = "解释器" "脚本" <透传参数> */
    static wchar_t cmd[32768];
    cmd[0] = L'\0';
    /* 注意：按 Windows 命令行规则，argv0 必须是解释器自身，
       argv1 才是脚本。这里显式加引号，避免路径含空格时被拆开。 */
    append_arg(cmd, 32768, exe);
    append_arg(cmd, 32768, script);

    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv) {
        for (int i = 1; i < argc; ++i) append_arg(cmd, 32768, argv[i]);
        LocalFree(argv);
    }

    /* 脚本据此得知“我被打包成了 exe”，注册自启时指向本启动器 */
    SetEnvironmentVariableW(L"LANCMD_EXE", self);
    SetEnvironmentVariableW(L"LANCMD_LAUNCHER", ENTRY_NAME);
    SetEnvironmentVariableW(L"PYTHONUTF8", L"1");
    SetEnvironmentVariableW(L"PYTHONIOENCODING", L"utf-8");

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    HANDLE pipe_rd = NULL, pipe_wr = NULL;
    BOOL inherit = FALSE;

    DWORD flags = CREATE_NO_WINDOW;
#ifdef LANCMD_CONSOLE
    /* 控制台版继承当前窗口，方便直接看到输出 */
    flags = 0;
#else
    /* GUI 版：把输出接进日志，失败时才查得到原因 */
    SECURITY_ATTRIBUTES sa;
    ZeroMemory(&sa, sizeof(sa));
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;
    if (CreatePipe(&pipe_rd, &pipe_wr, &sa, 0)) {
        SetHandleInformation(pipe_rd, HANDLE_FLAG_INHERIT, 0);
        si.dwFlags |= STARTF_USESTDHANDLES;
        si.hStdOutput = pipe_wr;
        si.hStdError = pipe_wr;
        /* 不能用 NULL：STARTF_USESTDHANDLES 会把 NULL 当句柄传给子进程，
           pythonw 拿到无效 stdin 会引发各种诡异行为。给它 NUL 设备。 */
        si.hStdInput = CreateFileW(L"NUL", GENERIC_READ | GENERIC_WRITE,
                                   FILE_SHARE_READ | FILE_SHARE_WRITE, &sa,
                                   OPEN_EXISTING, 0, NULL);
        if (si.hStdInput == INVALID_HANDLE_VALUE) si.hStdInput = NULL;
        inherit = TRUE;
    }
#endif

    {
        wchar_t dbg[4096];
        _snwprintf(dbg, 4095, L"[launcher] cwd=%s", dir);
        dbg[4095] = 0; log_line(dbg);
        _snwprintf(dbg, 4095, L"[launcher] exe=%s", exe);
        dbg[4095] = 0; log_line(dbg);
        _snwprintf(dbg, 4095, L"[launcher] script=%s", script);
        dbg[4095] = 0; log_line(dbg);
        _snwprintf(dbg, 4095, L"[launcher] cmd=%s", cmd);
        dbg[4095] = 0; log_line(dbg);
    }

    if (!CreateProcessW(exe, cmd, NULL, NULL, inherit, flags, NULL, dir, &si, &pi)) {
        DWORD err = GetLastError();
        if (pipe_wr) CloseHandle(pipe_wr);
        if (pipe_rd) CloseHandle(pipe_rd);
        wchar_t detail[MAX_PATH + 96];
        _snwprintf(detail, MAX_PATH + 95, L"无法启动 %s（错误码 %lu）", exe, err);
        report(detail);
        return 3;
    }

    if (pipe_wr) CloseHandle(pipe_wr);   /* 父进程留着会永远读不到 EOF */

#ifdef LANCMD_CONSOLE
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return (int)code;
#else
    if (pipe_rd) {
        PUMP *pp = (PUMP *)LocalAlloc(LPTR, sizeof(PUMP));
        if (pp) {
            pp->rd = pipe_rd;
            HANDLE th = CreateThread(NULL, 0, pump_proc, pp, 0, NULL);
            if (th) CloseHandle(th);
        } else {
            CloseHandle(pipe_rd);
        }
    }

    /* 等一小会儿看它是不是活着。
       pythonw 起不来时会立刻退出，这时要把原因告诉用户，
       否则就是"双击没反应 / 显示未运行"。 */
    DWORD wait = WaitForSingleObject(pi.hProcess, 2000);
    if (wait == WAIT_OBJECT_0) {
        DWORD code = 0;
        GetExitCodeProcess(pi.hProcess, &code);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        Sleep(200);   /* 给泵线程一点时间把日志刷完 */

        wchar_t tail[1800];
        log_tail(tail, 1800);
        wchar_t msg[2600];
        _snwprintf(msg, 2599,
            L"接收端启动失败（退出码 %lu）。\n\n"
            L"--- 输出 ---\n%s\n\n"
            L"日志：%%APPDATA%%\\LanCmd\\launcher.log",
            code, tail[0] ? tail : L"(没有输出)");
        msg[2599] = 0;
        MessageBoxW(NULL, msg, L"LanCmd", MB_ICONERROR | MB_OK);
        return 4;
    }

    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
#endif
}

#ifdef LANCMD_CONSOLE
int main(void)
{
    return run();
}
#else
int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev, LPSTR lpCmdLine, int nCmdShow)
{
    (void)hInst; (void)hPrev; (void)lpCmdLine; (void)nCmdShow;
    run();
    return 0;
}
#endif

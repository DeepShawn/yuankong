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

    DWORD flags = CREATE_NO_WINDOW;
#ifdef LANCMD_CONSOLE
    /* 控制台版继承当前窗口，方便直接看到输出 */
    flags = 0;
#endif

    if (!CreateProcessW(exe, cmd, NULL, NULL, FALSE, flags, NULL, dir, &si, &pi)) {
        wchar_t detail[MAX_PATH + 96];
        _snwprintf(detail, MAX_PATH + 95, L"无法启动 %s（错误码 %lu）", exe, GetLastError());
        report(detail);
        return 3;
    }

#ifdef LANCMD_CONSOLE
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return (int)code;
#else
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

/* Exercise the panel's native token/stop helpers without opening UAC or a GUI. */
#define _WIN32_WINNT 0x0601
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <stdlib.h>
#include <wchar.h>

static int shell_succeeds = 0, shell_calls = 0, destroyed = 0;
static wchar_t requested_verb[32], requested_args[2048];
static BOOL WINAPI fake_shell(SHELLEXECUTEINFOW *info)
{
    ++shell_calls;
    wcsncpy(requested_verb, info->lpVerb, 31);
    wcsncpy(requested_args, info->lpParameters, 2047);
    info->hProcess = NULL;
    if (!shell_succeeds) SetLastError(ERROR_CANCELLED);
    return shell_succeeds;
}
static int WINAPI fake_message(HWND hwnd, LPCWSTR text, LPCWSTR title, UINT type)
{
    (void)hwnd; (void)text; (void)title; (void)type;
    return IDOK;
}
static BOOL WINAPI fake_destroy(HWND hwnd)
{
    (void)hwnd;
    ++destroyed;
    return TRUE;
}
#define ShellExecuteExW fake_shell
#define MessageBoxW fake_message
#define DestroyWindow fake_destroy
#define wWinMain panel_unused_entry
#include "../windows/start_panel.c"
#undef ShellExecuteExW
#undef MessageBoxW
#undef DestroyWindow

__declspec(dllexport) int panel_elevated(int pid) { return process_elevated(pid); }
__declspec(dllexport) int panel_stop(int pid) { return stop_receiver_process(pid); }
__declspec(dllexport) int panel_appdata(const wchar_t *path) { return _wputenv_s(L"APPDATA", path); }
__declspec(dllexport) int panel_request_admin(int approve)
{
    shell_succeeds = approve; shell_calls = 0; destroyed = 0;
    requested_verb[0] = 0; requested_args[0] = 0;
    int result = open_admin_panel(NULL);
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(requested_args, &argc);
    int valid = argv && argc == 3 && wcscmp(argv[0], L"--restart-admin") == 0 &&
        wcscmp(argv[1], L"--profile-appdata") == 0 && wcscmp(argv[2], _wgetenv(L"APPDATA")) == 0;
    if (argv) LocalFree(argv);
    return result == approve && shell_calls == 1 && destroyed == approve && valid && wcscmp(requested_verb, L"runas") == 0;
}
